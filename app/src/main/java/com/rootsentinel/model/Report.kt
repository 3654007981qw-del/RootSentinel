package com.rootsentinel.model

/**
 * 三态结论。
 * UNKNOWN 不等于 PASS —— SELinux 拒绝、接口缺失都必须落在这里。
 */
enum class Verdict { PASS, HIT, UNKNOWN }

/**
 * 证据的执行状态（v2.4.1）。
 *
 * 之前只有三态 verdict，无法区分「检测跑了且通过」「检测根本没跑」
 * 「检测抛异常」「检测被跳过」「检测超时」。结果就是：
 *   - 阶段抛异常 → 该批证据根本不入库 → 评分完全看不到它
 *   - 超时中断 → 剩下的项不存在 → 覆盖率分母也跟着变小
 * 这两种情况都会让报告看起来比实际更「干净」。
 */
enum class EvidenceState {
    /** 检测执行完成，结论为通过 */
    PASS,
    /** 检测执行完成，发现风险 */
    HIT,
    /** 检测执行完成，但环境不允许得出结论 */
    UNKNOWN,
    /** 检测自身出错（抛异常、native 崩溃、协议错误） */
    ERROR,
    /** 检测被跳过（前置条件不满足、用户取消） */
    SKIPPED,
    /** 检测超时 */
    TIMEOUT,
    ;

    /** 是否代表「这一项没有得出有效结论」 */
    val isInconclusive: Boolean
        get() = this == UNKNOWN || this == ERROR || this == SKIPPED || this == TIMEOUT

    companion object {
        fun of(v: Verdict): EvidenceState = when (v) {
            Verdict.PASS -> PASS
            Verdict.HIT -> HIT
            Verdict.UNKNOWN -> UNKNOWN
        }
    }
}

/**
 * 严重度，weight 直接进入硬证据打分。
 * 这些数字是评分模型的唯一来源，改动会影响最终等级，需同步 README。
 */
enum class Severity(val weight: Int, val label: String) {
    INFO(0, "INFO"),
    LOW(3, "LOW"),
    MEDIUM(8, "MEDIUM"),
    HIGH(20, "HIGH"),
    CRITICAL(40, "CRITICAL"),
}

/**
 * 置信度乘数。
 * VERIFIED > 1.0 只用于 Key Attestation 这类密码学验证过的结论。
 */
enum class Confidence(val multiplier: Double, val label: String) {
    LOW(0.6, "LOW"),
    MEDIUM(0.85, "MEDIUM"),
    HIGH(1.0, "HIGH"),
    VERIFIED(1.3, "VERIFIED"),
}

enum class Category(val code: String, val label: String) {
    ARTIFACT("A", "经典痕迹"),
    PROPERTY("B", "属性与启动状态"),
    MOUNT("C", "挂载与命名空间"),
    MEMORY("D", "内存与注入"),
    HOOK("E", "Hook 框架"),
    SELINUX("F", "SELinux 与内核"),
    ATTESTATION("G", "硬件级证明"),
    HIDING("H", "隐藏与欺骗痕迹"),
    INTEGRITY("I", "自身完整性"),
    CROSSCHECK("X", "交叉验证矛盾"),
}

/**
 * 一条检测结论。
 *
 * @param heuristic true 表示软证据（启发式），打分时乘 0.35
 * @param vectors   多视角原始观测，形如 "openat=EACCES"
 * @param fpNote    误报说明：什么环境下会误判
 * @param verifyNote 需要真机确认的部分
 */
data class Evidence(
    val id: String,
    val category: Category,
    val severity: Severity,
    val confidence: Confidence,
    val verdict: Verdict,
    val heuristic: Boolean = false,
    val title: String,
    val detail: String = "",
    val vectors: List<String> = emptyList(),
    val fpNote: String = "",
    val verifyNote: String = "",
    /**
     * 显式执行状态（v2.4.1）。为 null 时由 [verdict] 推导，
     * 所以老代码不需要改；只有 ERROR / SKIPPED / TIMEOUT 才需要显式设置。
     */
    val state: EvidenceState? = null,
) {
    /** 用于评分与展示的最终状态 */
    val effectiveState: EvidenceState get() = state ?: EvidenceState.of(verdict)
}

/**
 * 同一事实的多视角观测。consistent == false 时额外产出 CONTRA 证据。
 */
data class CrossCheck(
    val id: String,
    val subject: String,
    val observations: List<Pair<String, String>>,
    val consistent: Boolean,
    val note: String = "",
)

data class DeviceProfile(
    val sdk: Int = 0,
    val fingerprint: String = "",
    val buildType: String = "",
    val buildTags: String = "",
    val brand: String = "",
    val model: String = "",
    val manufacturer: String = "",
    val isEmulator: Boolean = false,
    val isGsi: Boolean = false,
    val isUserdebug: Boolean = false,
    val isEng: Boolean = false,
    val isVendorRom: Boolean = false,
    val isDebuggable: Boolean = false,
    val developerOptions: Boolean = false,
    val socManufacturer: String = "",
)

enum class Grade(val label: String) {
    CLEAN("Clean"),
    SUSPICIOUS("Suspicious"),
    ROOTED("Rooted"),
    ROOTED_HIDING("Rooted + Hiding"),

    /**
     * 检测面不完整，不能给出结论。
     *
     * v2.4.0 新增。之前「所有项都 UNKNOWN」会算出 0 分 → CLEAN，
     * 等于把「测不出来」谎报成「设备干净」。
     * 现在只要 native 失败、有效项数不足、或可读率过低，一律落在这里。
     */
    INCOMPLETE("Incomplete"),
}

/**
 * 证据所属的安全维度。
 *
 * v2.4.0 引入。之前三类完全不同的风险被简单相加后统一叫「Rooted」：
 *   - 装了 Magisk 管理器   → 环境风险
 *   - 调试器已附加          → 环境风险
 *   - Bootloader 已解锁     → 系统完整性问题
 * 这三者都不是「设备已被提权」的证据，却都能把总分推过阈值。
 *
 * 现在只有 [ROOT] 维度的分数参与等级判定，另外两个维度单独展示。
 */
enum class Dimension(val label: String) {
    ROOT("Root 状态"),
    /** 注入、Hook、运行时篡改 —— 与「提权」是两件事 */
    HOOK("Hook 风险"),
    INTEGRITY("系统完整性"),
    ENVIRONMENT("运行环境"),
}

data class ScoreResult(
    val grade: Grade,
    val score: Double,
    val hardScore: Double,
    val softScore: Double,
    val confidence: Confidence,
    val hitCount: Int,
    val unknownCount: Int,
    val coverage: Double,
    val notes: List<String>,
    // ---- v2.4.0 三维拆分 ----
    /** 只由 ROOT 维度证据算出的分数；grade 只看它 */
    val rootScore: Double = score,
    /** 启动链 / 签名 / 证明链的完整性得分，不参与 Root 判定 */
    val integrityScore: Double = 0.0,
    /** 注入 / Hook / 运行时篡改，不参与 Root 判定 */
    val hookScore: Double = 0.0,
    /** 调试器、管理器、开发者选项等环境风险，不参与 Root 判定 */
    val environmentScore: Double = 0.0,
    /** 硬件证明确认启动链不可信（TEE 签名，无法伪造） */
    val integrityBreach: Boolean = false,
    val envRisk: Boolean = false,
)

/**
 * 评分模型。
 *
 * 单条得分 = severity.weight × confidence.multiplier × (heuristic ? 0.35 : 1.0)
 * 交叉验证矛盾（category == CROSSCHECK && verdict == HIT）额外固定 +12，不受 severity 影响。
 *
 * 环境降权（作用在总分上，逐条仍保留原始权重以便展示）：
 *   emulator        × 0.50
 *   userdebug/eng   × 0.80
 *   vendor ROM      × 0.90（仅作用于 MOUNT / PROPERTY 类，避免厂商魔改直接判 Rooted）
 *   test-keys       × 0.85（仅作用于 PROPERTY 类）
 *
 * 等级阈值：
 *   score <  10            → CLEAN
 *   10 ≤ score <  40       → SUSPICIOUS
 *   40 ≤ score <  85       → ROOTED
 *   score ≥ 85 或存在 HIDING 类 HIT 且总分 ≥ 40 → ROOTED_HIDING
 */
object ScoringModel {

    private const val HEURISTIC_FACTOR = 0.35
    private const val CROSSCHECK_BONUS = 12.0

    /**
     * 默认按 Category 推导维度，下面两张表是例外。
     *
     * 判定原则：
     *   - 「能做到某件事」= ROOT 能力（提权、注入、隐藏痕迹）
     *   - 「系统签名/启动链状态」= INTEGRITY
     *   - 「设备配置/形态/装了什么 App」= ENVIRONMENT
     *
     * 一句话：装了 Magisk 管理器 ≠ 有 root；开了 USB 调试 ≠ 有 root；
     * 无摄像头 ≠ 有 root。这三类都必须排除在 Root 判定之外。
     */
    private val ROOT_OVERRIDE_IDS = setOf(
        // I 类里这几项其实在测「提权能力」，不是自身完整性
        "I.CAP_EFF", "I.UID_MAP", "I.SETUID", "I.PERM_GRANTED", "I.SELF_SO_PATH",
    )

    private val ENV_IDS = setOf(
        // 模拟器：是环境特征，不是 root 证据
        "A.EMULATOR",
        // 硬件形态：无摄像头/传感器少在低端机、工业机、模拟器上是正常的
        "A.SENSOR_COUNT", "A.CAMERA", "A.INPUT_DEVICES", "A.DISPLAY", "A.SIM_STATE",
        // 管理器包「被看见」：装了管理器不代表设备已 root（Shizuku 可无线调试启动）
        "B.PKG_VISIBLE",
        // 调试器 / 无障碍 / 输入法 / 设备管理器：都是设备配置，不是 root
        "I.DEBUGGER", "I.ACCESSIBILITY", "I.INPUT_METHOD", "I.DEVICE_ADMIN",
        // 安装来源 / debuggable 标记 / 权限授予 / 签名方案：App 打包与配置问题
        "I.INSTALLER", "I.INSTALL_SOURCE", "I.SELF_DEBUGGABLE", "I.APP_FLAGS",
        // 开发者选项类：属于「设备安全配置」，不是「设备已被提权」
        "I.ADB_ENABLED", "I.DEV_OPTIONS", "I.OVERLAY_PERM", "I.UNKNOWN_SOURCES",
        "I.SECCOMP", "I.JIFFIES",
        // perf_event_paranoid=-1：MTK 等厂商内核默认值，是内核配置差异
        "G.PERF_PARANOID",
    )

    /** 一条证据属于哪个维度 */
    /**
     * 启动链 / 签名 / 系统镜像类：属于「系统完整性」，不是「已提权」。
     *
     * 之前这些全落进 ROOT 维度，导致「Bootloader 解锁」「userdebug 系统」
     * 「自编译内核」直接推高 Root 分数 —— 但这三者都不能证明设备拿到了 root。
     */
    private val INTEGRITY_IDS = setOf(
        // B 属性与启动状态：都是系统镜像/签名属性
        "B.TEST_KEYS", "B.DEBUGGABLE", "B.BUILD_TYPE", "B.BUILD_TAGS",
        "B.VB_STATE", "B.FLASH_LOCKED", "B.VB_META", "B.VERITY",
        "B.BOOTLOADER", "B.SECURE_BOOT", "B.OEM_UNLOCK",
        "B.RO_BUOT", "B.ROOT_PROPS",
        // F 内核层：内核标识 / taint / 启动链 / 安全子系统
        "F.KERNEL_DIRTY", "F.KERNEL_VERSION", "F.KERNEL_TAINT",
        "F.INIT_CHAIN", "F.KERNEL_MODULE", "F.KERNEL_DEV",
        "F.KALLSYMS", "F.KPTR_RESTRICT", "F.KERNEL_EXT",
        // I 自身完整性：签名、代码段、ELF
        "I.SIGNATURE", "I.SIG_CHECK", "I.TEXT_HASH", "I.ELF_SEGMENT",
        "I.APK_INTEGRITY", "I.DEX_HASH", "I.OWN_SO", "I.PROTECT",
        // G 硬件证明：TEE 签名的启动链事实
        "G.ATTESTATION", "G.ATTESTATION_EXT", "G.CHAIN", "G.ROOT_ANCHOR",
        "G.BOOT_STATE", "G.DEVICE_LOCKED", "G.BOOT_HASH", "G.VB_STATE",
        "G.KEY_DESC", "G.ATTEST_TRUST",
        // v2.22.37：维护状态类。补丁滞后、内核过保、SELinux 域伪装
        // 都不是「设备已提权」，属于系统完整性事实。
        "B.SEC_PATCH", "F.KERNEL_EOL", "F.SELINUX_XCHECK",
    )

    /** 调试器 / 跟踪器：属于「调试风险」，与注入并列归 HOOK 维度下的调试侧 */
    private val DEBUG_IDS = setOf(
        "E.TRACER", "E.PTRACE", "E.DEBUGGER_WAIT", "E.ANTIDEBUG",
        "I.DEBUGGER", "I.SELF_DEBUGGABLE",
    )

    /** 注入 / Hook / 运行时篡改：HOOK 维度 */
    private val HOOK_IDS = setOf(
        "E.HOOK_LIB", "E.SYMBOL_HOOK", "E.PLT_HOOK", "E.INLINE_HOOK",
        "E.ART_HOOK", "E.ART_METHOD", "E.FD_SO", "E.JNIENV_TABLE",
        "E.XPOSED", "E.LSPOSED", "E.ZYGISK", "E.SUBSTRATE",
        "E.RIZU", "E.SHADOWHOOK", "E.PINE", "E.SANDBOX",
        "D.ANON_EXEC", "D.MEMFD", "D.THREAD_NAME", "D.INJECT_LIB",
        "D.MEM_RESIDUE", "D.DEX_LOAD", "D.PROC_MAPS",
    )

    /** 一条证据属于哪个维度 */
    fun dimensionOf(e: Evidence): Dimension {
        // 显式覆盖优先级最高
        if (e.id in ROOT_OVERRIDE_IDS) return Dimension.ROOT
        if (e.id in ENV_IDS) return Dimension.ENVIRONMENT
        if (e.id in HOOK_IDS) return Dimension.HOOK
        if (e.id in DEBUG_IDS) return Dimension.HOOK
        if (e.id in INTEGRITY_IDS) return Dimension.INTEGRITY

        // 前缀兜底：ID 形如 "B.XXX" / "F.XXX" / "I.XXX" / "G.XXX" / "E.XXX"
        return when {
            e.category == Category.ATTESTATION -> Dimension.INTEGRITY
            e.category == Category.INTEGRITY -> Dimension.INTEGRITY
            e.category == Category.HOOK -> Dimension.HOOK
            // B 类剩余项、F 类剩余项默认归完整性（都是系统镜像/内核属性）
            e.category == Category.PROPERTY -> Dimension.INTEGRITY
            e.category == Category.SELINUX -> Dimension.INTEGRITY
            // D 类（内存注入）默认归 HOOK
            e.category == Category.MEMORY -> Dimension.HOOK
            // 交叉验证类（跨进程对比、native 失败、阶段异常）证明的是
            // 「观测通道被干扰 / 结论不可用」，属于 HOOK（观测完整性）维度。
            // v2.22.41 修正：之前没有这一分支，CROSSCHECK 直接掉进
            // else -> ROOT，单条 G.CROSS_PROC（CRITICAL=40 分）就能
            // 凑满 ROOTED 阈值，把 Hook 风险误判成 Root 风险。
            e.category == Category.CROSSCHECK -> Dimension.HOOK
            // H 类（隐藏与欺骗痕迹）证明的是「观测通道被干扰」，归 HOOK。
            // v2.22.43 修正：之前没有这一分支，H 类掉进 else -> ROOT，
            // analyze.cpp 启发式生成的 H.HIDING_SIGNAL（源自交叉验证矛盾 /
            // 过度隐藏）会直接污染 rootScore，甚至单独凑满 ROOTED_HIDING
            // 的 hasHiding 门槛之外的分数部分。现在与 CROSSCHECK 同路。
            e.category == Category.HIDING -> Dimension.HOOK
            // A / C 类默认归 ROOT（root 方案痕迹、挂载篡改）
            else -> Dimension.ROOT
        }
    }

    /**
     * 检测项基线（仅作**兜底**，不再直接当分母）。
     *
     * v2.22.39 之前覆盖率分母直接取这个固定值 200。
     * 问题是：Android 版本、内核配置、SELinux 策略、厂商 ROM 都会
     * 影响检测项的实际可用性 —— 用固定数字当分母，
     * 等于把「系统本身不支持」也算成「检测失败」。
     *
     * 现在改为**设备能力感知**：
     *   · 分母 = 本次设备上「适用」的检测项数（见 [applicabilityOf]）
     *   · 不适用（NOT_APPLICABLE）的项不计入分母，也不计入 PASS
     *   · 权限不足 / 异常 / 超时 仍计入分母，但算作「未得出结论」
     *   · 只有分母小到不可信时，才回退到这个兜底值
     */
    const val BASELINE_ITEMS = 200

    /**
     * 检测项对当前设备的适用性。
     *
     * 引入这个枚举的原因：
     *   之前所有检测项一视同仁 —— 设备上根本不存在的节点
     *   （比如没挂载的 debugfs、不支持的 StrongBox）也被算进分母，
     *   于是「覆盖率 0.6」既可能是「检测被掐断」，
     *   也可能是「这台机器本来就没这些东西」。两者含义完全不同。
     */
    enum class Applicability(val label: String) {
        /** 必须执行：核心检测项，任何 Android 设备都该有结果 */
        REQUIRED("必须执行"),

        /** 条件适用：依赖特定 Android 版本 / 内核 / 硬件 */
        CONDITIONAL("条件适用"),

        /** 系统不支持：本设备明确没有这个能力（不计入分母） */
        NOT_APPLICABLE("系统不支持"),

        /** 权限不足：节点存在但读不到（计入分母，算未得出结论） */
        PERMISSION_DENIED("权限不足"),
    }

    /**
     * 判定一条证据的适用性。
     *
     * 判据全部来自证据自身携带的信息，不需要额外探测：
     *   · verdict == UNKNOWN 且 detail 里含「不支持 / 不存在 / 未挂载」
     *     → NOT_APPLICABLE
     *   · verdict == UNKNOWN 且 detail 里含「拒绝 / EACCES / 权限」
     *     → PERMISSION_DENIED
     *   · 其余 UNKNOWN → CONDITIONAL（不确定，保守计入分母）
     *   · 有明确 PASS / HIT → REQUIRED（正常执行了）
     */
    fun applicabilityOf(e: Evidence): Applicability {
        if (e.effectiveState == EvidenceState.SKIPPED ||
            e.effectiveState == EvidenceState.ERROR ||
            e.effectiveState == EvidenceState.TIMEOUT
        ) {
            // 异常 / 跳过 / 超时：这是检测面缺失，必须计入分母
            return Applicability.REQUIRED
        }

        if (e.verdict != Verdict.UNKNOWN) {
            // 有明确结论 → 说明这一项在本设备上跑通了
            return Applicability.REQUIRED
        }

        // ---- UNKNOWN：细分原因
        // v2.22.43 修正：之前把 fpNote 也拼进匹配文本。fpNote 是
        // 「误报说明」，里面天然会出现「不存在 / 权限不足」等词，
        // 会把真实命中的证据误判成 NOT_APPLICABLE，缩小覆盖率分母、
        // 虚增覆盖率。现在只看标题与结论详情。
        val blob = (e.title + " " + e.detail).lowercase()

        // 系统明确不支持。
        // v2.22.44 收敛（第四份审查 P2-01）：删掉「不存在 / 未提供 /
        // 没有该 / 缺少」这组裸缺失词 —— 它们分不清「设备没这能力」
        // 和「数据缺口」：F.KERNEL_EOL「缺少该内核分支的维护周期数据」
        // （内置表没收录）和 G.ATTEST_CHAIN「缺少 bootState 字段」
        // （解析没取到）都被误判 NOT_APPLICABLE 移出分母，覆盖损失被
        // 二次掩盖。现在只认明确的能力表述；数据缺口型 UNKNOWN 落到
        // 下面的 CONDITIONAL，保守计入分母。结构化原因字段仍是
        // 后续方向（见 CHANGELOG 的延期项），本步先止住误判。
        val notSupported = listOf(
            "不支持", "未挂载", "not supported", "unsupported",
            "enoent", "设备不支持",
        )
        if (notSupported.any { blob.contains(it) }) {
            return Applicability.NOT_APPLICABLE
        }

        // 权限不足
        val denied = listOf(
            "eacces", "eperm", "拒绝", "权限不足", "不可读", "denied",
            "permission", "selinux 拒绝",
        )
        if (denied.any { blob.contains(it) }) {
            return Applicability.PERMISSION_DENIED
        }

        // 不确定 → 保守算作条件适用（计入分母）
        return Applicability.CONDITIONAL
    }

    /**
     * 按设备能力推算覆盖率分母。
     *
     * 规则：
     *   · NOT_APPLICABLE 的项**不计入**分母（系统本身没这个能力）
     *   · REQUIRED / CONDITIONAL / PERMISSION_DENIED 都计入
     *   · 分母过小（< 兜底值的 40%）时回退到兜底值 ——
     *     否则一个几乎什么都没跑的扫描会因为分母变小而覆盖率虚高
     */
    private fun applicableDenominator(
        items: List<Evidence>,
        crosses: Int,
        fallback: Int,
    ): Int {
        var applicable = crosses
        for (e in items) {
            if (applicabilityOf(e) != Applicability.NOT_APPLICABLE) {
                applicable++
            }
        }
        // 分母太小 → 说明大量项被判「系统不支持」，这本身可疑
        val minSane = (fallback * 0.4).toInt()
        return if (applicable < minSane) maxOf(applicable, minSane) else applicable
    }

    /**
     * @param expectedMin 期望的检测项下限。传 null 表示按 [dynamicExpectedMin] 自动推算。
     */
    fun score(
        items: List<Evidence>,
        crosses: List<CrossCheck>,
        profile: DeviceProfile,
        expectedMin: Int? = null,
    ): ScoreResult {
        val notes = mutableListOf<String>()

        // ---------------------------------------------------------- 证据去重
        // 同一个 ID 可能被多个模块产出（例如 D.THREAD_NAME 曾被
        // check_memory 与 check_threads 各报一次）。评分器之前逐条累加，
        // 导致「同一事实」被算两遍，单条就能越过 ROOTED 阈值。
        //
        // 现在按 ID 聚合：同 ID 取最严重的 verdict，权重只计一次。
        val deduped = mutableListOf<Evidence>()
        run {
            val best = LinkedHashMap<String, Evidence>()
            for (e in items) {
                val prev = best[e.id]
                if (prev == null) {
                    best[e.id] = e
                    continue
                }
                // 取更严重的一条，全序比较（顺序无关）：
                // 状态 > severity 权重 > confidence > 非启发式优先。
                // v2.22.43 修正：之前 rank 只含状态与 severity.ordinal，
                // 同 ID 同严重度时保留「先到的一条」，而 DetectorEngine 的
                // 阶段顺序是 shuffled 的 —— 同一台设备两次扫描可能选出
                // 不同证据、算出不同分数。现在同 ID 永远选同一条。
                val rank = { x: Evidence ->
                    val v = when (x.effectiveState) {
                        EvidenceState.HIT -> 3
                        EvidenceState.ERROR, EvidenceState.TIMEOUT -> 2
                        EvidenceState.SKIPPED, EvidenceState.UNKNOWN -> 1
                        EvidenceState.PASS -> 0
                    }
                    v * 100_000 + x.severity.weight * 100 +
                        (x.confidence.multiplier * 100).toInt() -
                        (if (x.heuristic) 50 else 0)
                }
                if (rank(e) > rank(prev)) best[e.id] = e
            }
            deduped += best.values
            val dropped = items.size - deduped.size
            if (dropped > 0) {
                notes += "证据去重：$dropped 条重复 ID 已合并（同 ID 只计一次）"
            }
        }

        var hard = 0.0
        var soft = 0.0
        var rootScore = 0.0
        var integrityScore = 0.0
        var hookScore = 0.0
        var envScore = 0.0
        var hit = 0
        var unknown = 0
        var errored = 0
        var skipped = 0

        for (e in deduped) {
            // v2.4.1：按显式状态分流，而不是只看三态 verdict。
            // ERROR / TIMEOUT 不是「检测通过了」，也不是「检测没读到」，
            // 它代表这一批检测根本没跑完 —— 必须计入不可用。
            when (e.effectiveState) {
                EvidenceState.UNKNOWN -> unknown++
                EvidenceState.SKIPPED -> { skipped++; unknown++ }
                EvidenceState.ERROR, EvidenceState.TIMEOUT -> { errored++; unknown++ }
                EvidenceState.PASS -> Unit
                EvidenceState.HIT -> {
                    hit++
                    var s = e.severity.weight * e.confidence.multiplier
                    if (e.heuristic) s *= HEURISTIC_FACTOR

                    // 环境降权只影响「易被厂商 ROM 干扰」的类别
                    if (profile.isEmulator) s *= 0.50
                    if (profile.isUserdebug || profile.isEng) s *= 0.80
                    if (profile.isVendorRom &&
                        (e.category == Category.MOUNT || e.category == Category.PROPERTY)
                    ) s *= 0.90
                    if (profile.buildTags.contains("test-keys") &&
                        e.category == Category.PROPERTY
                    ) s *= 0.85

                    // v2.4.0：按维度分流。只有 ROOT 维度进入 hard/soft/rootScore，
                    // 另外两个维度单独累计，不再把「解锁」「装了管理器」算成 root。
                    when (dimensionOf(e)) {
                        Dimension.ROOT -> {
                            rootScore += s
                            if (e.heuristic) soft += s else hard += s
                        }
                        Dimension.HOOK -> hookScore += s
                        Dimension.INTEGRITY -> integrityScore += s
                        Dimension.ENVIRONMENT -> envScore += s
                    }
                }
            }
        }

        // 交叉验证矛盾单独计。crosses 全部是「同一事实多视角对不上」，
        // 交叉验证矛盾单独计。crosses 全部是「同一事实多视角对不上」，
        // 证明的是观测通道被干扰 / 存在隐藏，属于 HOOK（观测完整性）维度，
        // 不是 Root 权限证据。v2.22.41 修正：之前计入 hard / rootScore，
        // 单条矛盾就给 Root 维度 +12；现在只计入 hookScore。
        for (c in crosses) {
            if (!c.consistent) {
                hookScore += CROSSCHECK_BONUS
                hit++
                notes += "交叉验证矛盾: ${c.subject}"
            }
        }

        var total = hard + soft
        val totalItems = deduped.size + crosses.size

        // ---------------------------------------------------------- 设备能力感知分母
        //
        // v2.22.39 之前：denom = max(实际项数, 固定 200)。
        // 问题：Android 版本 / 内核配置 / SELinux 策略 / 厂商 ROM
        // 都会让一部分检测项在本设备上根本不可用（例如 debugfs 未挂载、
        // 不支持 StrongBox、老内核没有 eBPF）。
        // 用固定 200 当分母，等于把「系统本身不支持」也算成「检测失败」。
        //
        // 现在：
        //   · NOT_APPLICABLE 的项不计入分母（系统没这个能力）
        //   · PERMISSION_DENIED / CONDITIONAL / REQUIRED 计入
        //   · 分母过小时回退到兜底值，防止「几乎没跑」反而覆盖率虚高
        val fallback = expectedMin ?: BASELINE_ITEMS
        val denom = applicableDenominator(deduped, crosses.size, fallback)

        // 统计各类适用性，写进 notes 便于排查
        var cntNotApplicable = 0
        var cntDenied = 0
        var cntConditional = 0
        for (e in deduped) {
            when (applicabilityOf(e)) {
                Applicability.NOT_APPLICABLE -> cntNotApplicable++
                Applicability.PERMISSION_DENIED -> cntDenied++
                Applicability.CONDITIONAL -> cntConditional++
                Applicability.REQUIRED -> Unit
            }
        }

        // 「得出结论」的项：非 UNKNOWN/ERROR/SKIPPED/TIMEOUT，且适用
        val concluded = deduped.count {
            !it.effectiveState.isInconclusive &&
                applicabilityOf(it) != Applicability.NOT_APPLICABLE
        } + crosses.size

        val coverage = if (denom == 0) 0.0
        else (concluded.toDouble() / denom).coerceIn(0.0, 1.0)

        if (cntNotApplicable > 0) {
            notes += "$cntNotApplicable 项在本设备上不适用（系统不支持 / 节点不存在），" +
                "已从覆盖率分母中排除"
        }
        if (cntDenied > 0) {
            notes += "$cntDenied 项因权限不足无法读取（计入分母，算未得出结论）"
        }
        if (cntConditional > 0) {
            notes += "$cntConditional 项为条件适用，已按保守口径计入分母"
        }
        if (unknown > 0) {
            notes += "$unknown 项无法读取（SELinux/接口缺失），已按 UNKNOWN 记录，未计入 PASS"
        }
        if (skipped > 0) {
            notes += "$skipped 项被跳过（前置条件不满足或用户取消）"
        }
        if (errored > 0) {
            notes += "$errored 项执行失败或超时，对应检测面缺失"
        }

        // ---------------------------------------------------------- 完整性门槛
        // 「测不出来」必须与「测了是干净的」区分开。
        val nativeFailed = deduped.any { it.id == "Z.NATIVE_FAIL" }
        val stageFailed = deduped.count {
            it.id == "Z.STAGE_FAIL" || it.id.startsWith("Z.STAGE_FAIL.")
        }
        // v2.22.41：用户取消 = 必需阶段没有跑完。
        // 之前 skipped 只写进备注、不参与定级：native 先跑完且覆盖率
        // 达标时，取消剩下的 Java 阶段照样能得到 CLEAN —— 取消不能
        // 被翻译成「没问题」，一律 INCOMPLETE。
        val cancelledScan = deduped.any {
            it.id == "Z.CANCELLED" || it.id.startsWith("Z.CANCELLED.")
        }
        // v2.22.42：版本/协议完整性（P1-02 / P2-04）。
        // .so 与应用版本错位、或协议出现未知类别时，证据语义
        // 不可信，与取消扫描同级处理：强制 INCOMPLETE。
        val versionMismatch = deduped.any { it.id == "Z.VERSION" }
        val protocolBroken = deduped.any { it.id == "Z.PROTOCOL" }
        // tooFew 不再比固定基线，而是比「本设备适用的项数」。
        // 一台精简 ROM 上适用项本来就少，不该被判成检测面不足。
        val applicableCount = deduped.size - cntNotApplicable
        val tooFew = applicableCount < (fallback * 0.85).toInt()

        if (nativeFailed) {
            notes += "native 扫描未完成：38 个 native 模块全部未执行，" +
                "本次只有 Java 侧结果，不能作为安全结论"
        }
        if (stageFailed > 0) {
            notes += "$stageFailed 个 Java 检测阶段抛异常，对应检测项缺失"
        }
        if (tooFew) {
            notes += "仅完成 $applicableCount 项适用检测" +
                "（本设备应有 ≥ ${(fallback * 0.85).toInt()} 项），检测面不足"
        }
        if (cancelledScan) {
            notes += "扫描被用户取消，部分必需阶段未执行，本次不下「干净」结论"
        }
        if (versionMismatch) {
            notes += "native 版本与应用版本不一致（打包的 .so 不是同一版构建），本次结果不可信"
        }
        if (protocolBroken) {
            notes += "native 协议出现未知证据类别，相关证据已降级，本次不下完整结论"
        }

        // ---------------------------------------------------------- 覆盖率降分
        // 2.2.0 及之前：覆盖率低只写一句 note，不降分 —— 结果可信度虚高。
        // 现在按覆盖率线性折扣：coverage=1.0 不打折，0.6 打 0.8，0.3 打 0.65。
        if (coverage < 0.6 && total > 0.0) {
            val factor = 0.5 + coverage * 0.5
            val before = total
            // v2.22.43 修正：之前只折扣 total，hard/soft 不变，
            // 于是 rootScore != hardScore + softScore，分项对不上总分。
            // 现在同比例折扣三者，保持 rootScore == hard + soft。
            hard *= factor
            soft *= factor
            total = hard + soft
            notes += "可读率仅 ${(coverage * 100).toInt()}%，总分已按 ×" +
                String.format("%.2f", factor) + " 折扣（" +
                String.format("%.1f", before) + " → " + String.format("%.1f", total) + "）"
        } else if (coverage < 0.6) {
            notes += "可读率仅 ${(coverage * 100).toInt()}%，整体置信度已下调"
        }
        if (profile.isEmulator) notes += "检测到模拟器环境，全部硬证据 ×0.5"
        if (profile.isUserdebug || profile.isEng) notes += "构建类型为 ${profile.buildType}，硬证据 ×0.8"
        if (profile.isVendorRom) notes += "厂商定制 ROM，MOUNT/PROPERTY 类证据 ×0.9"

        val hasHiding = deduped.any {
            it.category == Category.HIDING && it.verdict == Verdict.HIT
        }

        // 硬件证明确认「启动链不可信」。这是 INTEGRITY 维度的事实，
        // 不再直接等于「设备已 root」——解锁 bootloader 本身不等于提权。
        val integrityBreach = deduped.any {
            it.category == Category.ATTESTATION && it.verdict == Verdict.HIT &&
                it.confidence == Confidence.VERIFIED
        }

        val grade = when {
            // ① 已经抓到足够多 root 证据 → 直接下结论，
            //    哪怕同时有阶段失败也不影响（证据本身是有效的）。
            total >= 85.0 -> Grade.ROOTED_HIDING
            hasHiding && total >= 40.0 -> Grade.ROOTED_HIDING
            total >= 40.0 -> Grade.ROOTED
            total >= 10.0 -> Grade.SUSPICIOUS

            // ② 没有足够证据说「有问题」。这时候任何检测面缺失
            //    都不能翻译成「没问题」—— 一律 INCOMPLETE。
            //    v2.4.1：把 ERROR / TIMEOUT / 阶段异常也纳入门槛。
            nativeFailed || stageFailed > 0 || errored > 0 || tooFew || cancelledScan ||
                versionMismatch || protocolBroken ||
                (items.isEmpty() && crosses.isEmpty()) -> Grade.INCOMPLETE

            // ③ 检测跑完了，但可读率太低，同样不下「干净」结论
            coverage < 0.6 -> Grade.INCOMPLETE

            else -> Grade.CLEAN
        }

        if (integrityScore > 0.0) {
            notes += "系统完整性维度 ${String.format("%.1f", integrityScore)} 分" +
                (if (integrityBreach) "（TEE 证明确认启动链不可信）" else "") +
                " —— 独立维度，不计入 Root 判定"
        }
        if (hookScore > 0.0) {
            notes += "Hook 维度 ${String.format("%.1f", hookScore)} 分 " +
                "（注入/内联 hook/运行时篡改）—— 独立维度，不计入 Root 判定"
        }
        if (envScore > 0.0) {
            notes += "运行环境维度 ${String.format("%.1f", envScore)} 分 " +
                "（调试/管理器/配置类）—— 独立维度，不计入 Root 判定"
        }

        // 整体置信度：由「高分证据的密度」与「可读率」共同决定
        val strong = deduped.count {
            it.verdict == Verdict.HIT &&
                (it.confidence == Confidence.HIGH || it.confidence == Confidence.VERIFIED) &&
                it.severity.weight >= Severity.MEDIUM.weight
        }
        // v2.22.43 修正：之前只看强命中数量，可读率 0.3 也能给出
        // HIGH 置信度，与上方注释承诺的「与可读率共同决定」不符。
        // 现在低可读率直接封顶整体置信度。
        val conf = when {
            coverage < 0.6 -> Confidence.LOW
            strong >= 3 && coverage >= 0.8 -> Confidence.HIGH
            strong >= 1 -> Confidence.MEDIUM
            else -> Confidence.LOW
        }

        return ScoreResult(
            grade = grade,
            score = total,
            hardScore = hard,
            softScore = soft,
            confidence = conf,
            hitCount = hit,
            unknownCount = unknown,
            coverage = coverage,
            notes = notes,
            rootScore = total,
            hookScore = hookScore,
            integrityScore = integrityScore,
            environmentScore = envScore,
            integrityBreach = integrityBreach,
            envRisk = envScore >= 10.0,
        )
    }
}