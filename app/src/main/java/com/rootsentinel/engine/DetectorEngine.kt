package com.rootsentinel.engine

import android.content.Context
import com.rootsentinel.NativeBridge
import com.rootsentinel.RootSentinelVersion
import com.rootsentinel.model.Category
import com.rootsentinel.model.Confidence
import com.rootsentinel.model.DeviceProfile
import com.rootsentinel.model.Evidence
import com.rootsentinel.model.EvidenceState
import com.rootsentinel.model.Grade
import com.rootsentinel.model.ScoreResult
import com.rootsentinel.model.ScoringModel
import com.rootsentinel.model.Severity
import com.rootsentinel.model.Verdict
import com.rootsentinel.util.CrossProcessProbe
import com.rootsentinel.util.NativeReportParser

/**
 * 检测调度。
 *
 * 关键设计（v2 包第五节第 5 条）：
 *  - 每次运行的检查顺序随机，避免「固定顺序」被针对性 patch
 *  - 每项独立 try-catch，单项失败不影响整体
 *  - 结论不放在单一 isRooted() 咽喉点，而是返回完整证据列表
 */
class DetectorEngine(private val context: Context) {

    data class Result(
        val items: List<Evidence>,
        val profile: DeviceProfile,
        val score: ScoreResult,
        val nativeError: String?,
        val elapsedMs: Long,
        /** native 侧自报版本，用于核对 Java / Native 是否同步 */
        val nativeVersion: String = "",
        /** 交叉验证矛盾明细（v2.22.43）：供 JSON 导出复算 hookScore */
        val crosses: List<com.rootsentinel.model.CrossCheck> = emptyList(),
    ) {
        val grade: Grade get() = score.grade
    }

    /**
     * @param isCancelled 每进入一个阶段前询问一次；返回 true 就尽快收工。
     *                    native 扫描本身不可中断（C++ 侧无检查点），
     *                    但 Java 12 个阶段和跨进程探针都可以跳过。
     */
    fun run(
        onModule: ((String) -> Unit)? = null,
        onItem: ((String, String, String, String, String) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null,
    ): Result {
        val start = System.currentTimeMillis()
        val items = mutableListOf<Evidence>()

        // ---------------------------------------------------------- native
        var profile = DeviceProfile()
        var nativeError: String? = null
        var nativeJson: String? = null
        // native 输出的跨视角矛盾，必须交给评分器
        val nativeCrosses = mutableListOf<com.rootsentinel.model.CrossCheck>()
        var nativeVersion = ""
        var nativeProtocolError = false

        try {
            val cb = if (onModule != null || onItem != null) {
                object : NativeBridge.ProgressCallback {
                    override fun onModule(name: String) {
                        onModule?.invoke(name)
                    }

                    override fun onItem(
                        id: String,
                        title: String,
                        severity: String,
                        verdict: String,
                        category: String,
                    ) {
                        onItem?.invoke(id, title, severity, verdict, category)
                    }
                }
            } else null

            val json = NativeBridge.scan(cb)
            nativeJson = json
            val parsed = NativeReportParser.parse(json)
            profile = parsed.profile
            nativeError = parsed.error
            items += parsed.items
            nativeCrosses += parsed.crosses
            nativeVersion = parsed.version
            nativeProtocolError = parsed.protocolError
        } catch (t: Throwable) {
            nativeError = "${t.javaClass.simpleName}: ${t.message}"
        }

        // native 整个失败时，必须留一条显式证据。
        // 否则 38 个 native 模块全都没跑，剩下只有 Java 检测，
        // 有可能算出低分 → 显示 CLEAN —— 那是谎报安全。
        if (nativeError != null) {
            items += Evidence(
                id = "Z.NATIVE_FAIL",
                category = Category.CROSSCHECK,
                severity = Severity.CRITICAL,
                confidence = Confidence.HIGH,
                verdict = Verdict.UNKNOWN,
                // v2.4.1：标记为 ERROR 而不是普通 UNKNOWN。
                // 评分器据此拒绝给出 Clean 结论。
                state = EvidenceState.ERROR,
                title = "native 扫描失败，本次结果不完整",
                detail = nativeError,
                fpNote = "所有 native 检测（38 个模块）均未执行，"
                    + "本次结论不能代表设备真实状态",
            )
            onItem?.invoke(
                "Z.NATIVE_FAIL", "native 扫描失败，本次结果不完整",
                "CRITICAL", "UNKNOWN", "X",
            )
        }

        // v2.22.42：版本与协议完整性（P1-02 / P2-04）。
        // jniLibs 是预编译产物：.so 与 Java 不是同一版构建时，
        // 证据语义可能已经变了，结果不可信；协议里出现未知类别
        // 同理。两者各落一条 SKIPPED 证据，由评分器强制
        // INCOMPLETE —— 绝不允许拿错位的结果给 CLEAN。
        if (nativeError == null && nativeVersion != RootSentinelVersion.NAME) {
            items += Evidence(
                id = "Z.VERSION",
                category = Category.CROSSCHECK,
                severity = Severity.HIGH,
                confidence = Confidence.HIGH,
                verdict = Verdict.UNKNOWN,
                state = EvidenceState.SKIPPED,
                title = "native 版本与应用版本不一致",
                detail = "native=$nativeVersion, app=${RootSentinelVersion.NAME}",
                fpNote = "打包的 .so 与源码版本脱节时出现；本次结论不可信",
            )
        }
        if (nativeError == null && nativeProtocolError) {
            items += Evidence(
                id = "Z.PROTOCOL",
                category = Category.CROSSCHECK,
                severity = Severity.HIGH,
                confidence = Confidence.HIGH,
                verdict = Verdict.UNKNOWN,
                state = EvidenceState.SKIPPED,
                title = "native 协议异常（未知证据类别）",
                detail = "至少一条证据的类别无法识别，已降级为 UNKNOWN；" +
                    "native 与 Java 的协议版本可能不匹配",
            )
        }

        // ---------------------------------------------------------- Java 侧
        // 顺序随机：避免固定调用序列被 hook 针对
        // 每个 stage 都通过 onModule / onItem 推给 UI，保证进度条与实际一致
        //
        // 注意：这里**不再**用 runCatching 静默吞掉异常。
        // 2.2.1 及之前，一个 stage 抛异常 → 返回空列表 → 该阶段的所有检测项
        // 从报告里彻底消失，既不算 PASS 也不算 UNKNOWN，评分完全看不到它。
        // 现在由外层统一 catch，并落一条 Z.STAGE_FAIL (UNKNOWN) 证据。
        val javaStages: List<Pair<String, () -> List<Evidence>>> = listOf(
            "Java · ART 深度" to { ArtDeepDetector(context).collect() },
            "Java · 系统服务" to { ServiceDetector(context).collect() },
            "Java · 自保护" to { SelfGuardDetector(context).collect() },
            "Java · 行为痕迹" to { JavaDetectors(context).collect() },
            "Java · ClassLoader" to { ClassLoaderDetector(context).collect() },
            "Java · 证明链" to { AttestationDetector(context).collect() },
            "Java · 自校验" to { IntegrityChecker(context).collect() },
            "Java · 完整性深度" to { IntegrityDeepDetector(context).collect() },
            "Java · 硬件指纹" to { HardwareDetector(context).collect() },
            "Java · 网络层" to { NetworkDetector(context).collect() },
            "Java · ART hook" to { ArtHookDetector(context).collect() },
                "Java · 安全环境" to { SecureEnvDetector(context).collect() },
            "Java · 安全模型" to { SafetyNetDetector(context).collect() },
        ).shuffled()

        var cancelled = false
        var stageFailCount = 0
        val executedStages = mutableListOf<String>()
        for ((name, fn) in javaStages) {
            // 被取消就不再继续跑后续阶段
            if (isCancelled?.invoke() == true) {
                cancelled = true
                break
            }

            onModule?.invoke(name)
            executedStages += name

            val res: List<Evidence> = try {
                fn()
            } catch (t: Throwable) {
                stageFailCount++
                listOf(
                    Evidence(
                        // v2.22.41：每个失败阶段用独立 ID。
                        // 之前所有阶段共用 "Z.STAGE_FAIL"，评分器按 ID
                        // 去重后只剩一条，失败阶段数被系统性低估。
                        id = if (stageFailCount == 1) "Z.STAGE_FAIL"
                        else "Z.STAGE_FAIL.$stageFailCount",
                        category = Category.CROSSCHECK,
                        severity = Severity.MEDIUM,
                        confidence = Confidence.HIGH,
                        verdict = Verdict.UNKNOWN,
                        // v2.4.1：阶段异常 = 该检测面整块缺失，标 ERROR
                        state = EvidenceState.ERROR,
                        title = "检测阶段异常：$name",
                        detail = "${t.javaClass.simpleName}: ${t.message}",
                        fpNote = "该阶段的检测项全部未执行，"
                            + "不能据此认为这些方向是干净的",
                    ),
                )
            }

            for (e in res) {
                items += e
                // 每一项的结论都落日志：排查“哪一项命中/哪一项没跑”时不用再猜
                android.util.Log.i(
                    "RootSentinel",
                    "ev ${e.id} ${e.verdict.name} ${e.severity.name} ${e.title.take(70)}",
                )
                onItem?.invoke(
                    e.id,
                    e.title,
                    e.severity.name,
                    e.verdict.name,
                    e.category.code,
                )
            }
        }

        // ---------------------------------------------------------- 取消补全
        // 用户中途取消时，剩下的 Java 阶段根本没执行。
        // 之前这些阶段在报告里「不存在」—— 既不算 PASS 也不算 UNKNOWN，
        // 覆盖率分母也跟着变小，看起来反而更干净。
        // 现在为每个未执行阶段落一条 SKIPPED，明确计入不可用。
        //
        // v2.22.41 修正：之前只落一条 Z.CANCELLED 代表全部未跑阶段，
        // 与本节注释承诺的「每个未执行阶段一条」不符；评分器按 ID
        // 去重后只剩一条，缺失阶段数被低估，且定级不看 SKIPPED，
        // 取消照样可能得到 CLEAN。现在逐阶段落账（首条保留
        // Z.CANCELLED 原 ID 兼容历史，后续用 Z.CANCELLED.<n>），
        // 评分器据此强制 INCOMPLETE（见 Report.score）。
        if (cancelled) {
            val remaining = javaStages.map { it.first }.filter { it !in executedStages }
            remaining.forEachIndexed { idx, stageName ->
                val skippedStage = Evidence(
                    id = if (idx == 0) "Z.CANCELLED" else "Z.CANCELLED.$idx",
                    category = Category.CROSSCHECK,
                    severity = Severity.MEDIUM,
                    confidence = Confidence.HIGH,
                    verdict = Verdict.UNKNOWN,
                    state = EvidenceState.SKIPPED,
                    title = "扫描被用户取消，阶段未执行：$stageName",
                    detail = "已完成的检测项有效，但未执行的部分不能视为干净",
                    fpNote = "取消是主动行为，不代表设备状态异常",
                )
                items += skippedStage
                onItem?.invoke(
                    skippedStage.id, skippedStage.title,
                    skippedStage.severity.name, skippedStage.verdict.name,
                    skippedStage.category.code,
                )
            }
        }

        // ---------------------------------------------------------- 跨进程交叉验证
        // 在 :probe 独立进程里再跑一次 native 扫描，逐项对比 verdict。
        // 这是抓「只 hook 主进程」的手段：Zygisk 注入通常不会同时 hook 两个进程。
        if (nativeJson != null && isCancelled?.invoke() != true) {
            onModule?.invoke("跨进程 · 主进程 vs 探针")

            val pr = CrossProcessProbe.probe(context)
            val probeJson = pr.json

            val ev: Evidence = if (probeJson == null || probeJson.isEmpty()) {
                Evidence(
                    id = "G.CROSS_PROC",
                    category = Category.CROSSCHECK,
                    severity = Severity.LOW,
                    confidence = Confidence.LOW,
                    verdict = Verdict.UNKNOWN,
                    title = "跨进程探针不可用",
                    detail = pr.error ?: "探针进程没有返回结果",
                    fpNote = "部分系统限制同时运行多进程，此时本项无法判定",
                )
            } else {
                val diff = CrossProcessProbe.compare(nativeJson, probeJson)
                when {
                    // ============================================================
                    // 判定顺序（v2.22.39 修正）
                    // ============================================================
                    //
                    // v2.22.38 及之前的错误：把「有强差异」放在第一分支，
                    // 只要 mismatched 非空就直接 CRITICAL —— 哪怕只比较了 1 项。
                    //
                    // 这意味着：两个进程只成功对齐 1 个检测项，
                    // 而这一项恰好 HIT↔PASS 反转，就会得到 CRITICAL。
                    // 但 CrossProcessProbe 明确要求 compared >= 40 才算完整。
                    //
                    // 现在的顺序：
                    //   ① 覆盖不足          → UNKNOWN（不下结论）
                    //   ② 覆盖充分 + 强差异 → HIT / CRITICAL
                    //   ③ 覆盖充分 + 弱差异 → PASS / 低置信度
                    //   ④ 完整且一致        → PASS / 高置信度

                    // ① 覆盖不足：先卡这道门槛，任何差异都不算数
                    !diff.complete -> Evidence(
                        id = "G.CROSS_PROC",
                        category = Category.CROSSCHECK,
                        severity = Severity.MEDIUM,
                        confidence = Confidence.LOW,
                        verdict = Verdict.UNKNOWN,
                        title = "跨进程对比覆盖不足，结论不可用",
                        vectors = listOf(
                            "probe_pid=${pr.pid}",
                            "compared=${diff.compared}",
                            "min_required=${diff.minCompared}",
                            "whitelist=${diff.expectedCompared}",
                            "onlyMain=${diff.onlyInMain.size}",
                            "onlyProbe=${diff.onlyInProbe.size}",
                            "mismatched=${diff.mismatched.size}",
                        ) + diff.onlyInMain.take(3).map { "main-only: $it" }
                            + diff.onlyInProbe.take(3).map { "probe-only: $it" },
                        detail = "只成功对齐 ${diff.compared} 项" +
                            "（白名单 ${diff.expectedCompared} 项，要求 ≥${diff.minCompared}），"
                            + "两进程的检测项集合不对齐。"
                            + if (diff.mismatched.isNotEmpty())
                            "已发现 ${diff.mismatched.size} 项差异，但因样本量不足，"
                                + "不能据此判定 hook —— 可能是进程启动时序造成的偶发差异。"
                            else "本次对比不能作为「两进程一致」的依据。",
                        fpNote = "进程启动时序不同、条件分支差异都会造成缺项；"
                            + "覆盖不足时必须报「测不了」，不能报「没问题」也不能报「有问题」",
                    )

                    // ② 覆盖充分 + 强差异 → 这才是真正的 hook 证据
                    diff.mismatched.isNotEmpty() -> Evidence(
                        id = "G.CROSS_PROC",
                        category = Category.CROSSCHECK,
                        severity = Severity.CRITICAL,
                        confidence = Confidence.HIGH,
                        verdict = Verdict.HIT,
                        title = "两进程结论不一致（${diff.mismatched.size} 项，样本 ${diff.compared}）",
                        vectors = listOf(
                            "probe_pid=${pr.pid}",
                            "compared=${diff.compared}",
                        ) + diff.mismatched.take(8),
                        detail = "在 ${diff.compared} 项可比检测中，"
                            + "${diff.mismatched.size} 项出现 HIT↔PASS 反转 → "
                            + "至少有一个进程的 syscall 被拦截或改写。"
                            + "样本量已达门槛，结论可信。",
                        fpNote = "fd 数量、线程数等运行时指标本身会波动，"
                            + "只有 HIT↔PASS 的反转才是强信号；"
                            + "本项为辅助证据，不能替代独立观测源",
                    )

                    // ③ 覆盖充分 + 仅弱差异 → PASS 但降置信度
                    diff.weak.isNotEmpty() -> Evidence(
                        id = "G.CROSS_PROC",
                        category = Category.CROSSCHECK,
                        severity = Severity.INFO,
                        confidence = Confidence.LOW,
                        verdict = Verdict.PASS,
                        title = "两进程基本一致（${diff.weak.size} 项弱差异）",
                        vectors = listOf(
                            "probe_pid=${pr.pid}",
                            "compared=${diff.compared}",
                            "weak=${diff.weak.size}",
                        ) + diff.weak.take(5),
                        detail = "在 ${diff.compared} 项可比检测中无 HIT↔PASS 反转；"
                            + "存在 ${diff.weak.size} 项 UNKNOWN↔PASS 的弱差异，"
                            + "多由 SELinux 时序或进程启动状态不同造成。",
                    )

                    // ④ 覆盖充分且完全一致
                    else -> Evidence(
                        id = "G.CROSS_PROC",
                        category = Category.CROSSCHECK,
                        severity = Severity.INFO,
                        confidence = Confidence.MEDIUM,
                        verdict = Verdict.PASS,
                        title = "两进程结论一致（对比 ${diff.compared} 项）",
                        vectors = listOf(
                            "probe_pid=${pr.pid}",
                            "compared=${diff.compared}",
                            "onlyMain=${diff.onlyInMain.size}",
                            "onlyProbe=${diff.onlyInProbe.size}",
                        ),
                        detail = "主进程与 :probe 进程在 ${diff.compared} 项可比检测上完全相同。"
                            + "注意：两进程跑的是同一份 native 代码，"
                            + "一致只能排除「只 hook 单个进程」的情形，"
                            + "不能证明未被 hook —— 若攻击者同时影响两个进程，"
                            + "两边会得到一致的错误结果。本项为辅助证据。",
                    )
                }
            }

            items += ev
            onItem?.invoke(ev.id, ev.title, ev.severity.name, ev.verdict.name, ev.category.code)
        }

        // ---------------------------------------------------------- 收尾取消检查
        // v2.22.43：用户在最后一个 Java 阶段跑完之后（阶段循环已退出、
        // cancelled 仍为 false）才按下取消时，跨进程探针会被 isCancelled
        // 跳过，却不落任何 Z.CANCELLED 证据，评分器无从知道扫描被取消，
        // 仍可能给出 CLEAN。这里补一条 SKIPPED 证据，保证 cancelledScan
        // 在评分器里成立。（取消发生在阶段循环内时走前面的逐阶段补全，
        // 两条路径由 cancelled 标志互斥，不会重复。）
        if (!cancelled && isCancelled?.invoke() == true) {
            cancelled = true
            val cancelEv = Evidence(
                id = "Z.CANCELLED",
                category = Category.CROSSCHECK,
                severity = Severity.MEDIUM,
                confidence = Confidence.HIGH,
                verdict = Verdict.UNKNOWN,
                state = EvidenceState.SKIPPED,
                title = "扫描被用户取消（收尾阶段）",
                detail = "Java 阶段已全部执行，取消发生在跨进程探针/收尾阶段",
                fpNote = "取消是主动行为，不代表设备状态异常",
            )
            items += cancelEv
            onItem?.invoke(
                cancelEv.id, cancelEv.title, cancelEv.severity.name,
                cancelEv.verdict.name, cancelEv.category.code,
            )
        }

        // ---------------------------------------------------------- 评分
        // ★ crosses 必须传进去：
        //   之前固定传 emptyList()，导致 CROSSCHECK_BONUS 在真实路径里从未生效，
        //   native 输出的跨视角矛盾被整体丢弃。
        val score = ScoringModel.score(items, nativeCrosses, profile)

        return Result(
            items = items,
            profile = profile,
            score = score,
            nativeError = nativeError,
            elapsedMs = System.currentTimeMillis() - start,
            nativeVersion = nativeVersion,
            crosses = nativeCrosses.toList(),
        )
    }

    /** 只取命中项，按严重度降序（UI 展示用） */
    fun hits(result: Result): List<Evidence> =
        result.items
            .filter { it.verdict == Verdict.HIT }
            .sortedWith(
                compareByDescending<Evidence> { it.severity.ordinal }
                    .thenByDescending { it.confidence.ordinal },
            )
}