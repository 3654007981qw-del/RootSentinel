package com.rootsentinel.util

import com.rootsentinel.model.Category
import com.rootsentinel.model.Confidence
import com.rootsentinel.model.CrossCheck
import com.rootsentinel.model.DeviceProfile
import com.rootsentinel.model.Evidence
import com.rootsentinel.model.Severity
import com.rootsentinel.model.Verdict
import org.json.JSONObject

/**
 * 解析 native 返回的 JSON。
 *
 * 约定：native 只输出「观测」，不打分；评分在 [com.rootsentinel.model.ScoringModel] 里做，
 * 这样规则可以单独审阅、单独测试。
 */
object NativeReportParser {

    data class Parsed(
        val items: List<Evidence>,
        val profile: DeviceProfile,
        val crosses: List<CrossCheck> = emptyList(),
        val version: String = "",
        val error: String? = null,
        /** 协议出现未知证据类别等异常（v2.22.42，P2-04） */
        val protocolError: Boolean = false,
    )

    fun parse(json: String): Parsed {
        return try {
            val root = JSONObject(json)
            root.optString("error").takeIf { it.isNotEmpty() }?.let {
                return Parsed(
                    items = emptyList(),
                    profile = DeviceProfile(),
                    error = it,
                )
            }

            val profile = root.optJSONObject("profile")?.let { p ->
                DeviceProfile(
                    sdk = p.optInt("sdk"),
                    fingerprint = p.optString("fingerprint"),
                    buildType = p.optString("buildType"),
                    buildTags = p.optString("buildTags"),
                    brand = p.optString("brand"),
                    model = p.optString("model"),
                    manufacturer = p.optString("manufacturer"),
                    isEmulator = p.optBoolean("isEmulator"),
                    isGsi = p.optBoolean("isGsi"),
                    isUserdebug = p.optBoolean("isUserdebug"),
                    isEng = p.optBoolean("isEng"),
                    isVendorRom = p.optBoolean("isVendorRom"),
                    isDebuggable = p.optBoolean("isDebuggable"),
                    socManufacturer = p.optString("socManufacturer"),
                )
            } ?: DeviceProfile()

            val version = root.optString("version")

            val items = mutableListOf<Evidence>()
            // v2.4.0：协议校验。
            // 之前 items 缺失时静默返回空列表 + error=null，
            // 调度层会拿着空列表继续评分 → 0 分 → CLEAN。
            // 现在没有 items 就是协议错误，必须显式上报。
            val arr = root.optJSONArray("items")
                ?: return Parsed(
                    items = emptyList(),
                    profile = profile,
                    error = "协议错误：响应里没有 items 数组（native 输出结构异常）",
                )
            if (arr.length() == 0) {
                return Parsed(
                    items = emptyList(),
                    profile = profile,
                    error = "协议错误：items 是空数组（native 未产出任何检测结果）",
                )
            }
            var sawUnknownCategory = false
            var sawBlankId = false
            var sawUnknownEnum = false
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                // v2.22.44（P2-02）：id 缺失/为空是协议错误。空 ID 证据
                // 进入评分层后会按 ID 互相合并去重，悄悄吞掉证据。
                if (o.optString("id").isBlank()) { sawBlankId = true; continue }
                // v2.22.42（P2-04）：未知类别不再默认落 ARTIFACT。
                // ARTIFACT 属 ROOT 维度 —— native 新增类别或协议
                // 版本错位时，一条不认识的 HIT 会被当成 Root 证据。
                // 现在：类别码无法识别 → 证据降级为 UNKNOWN 并落到
                // CROSSCHECK，同时置 protocolError，由调度层把整次
                // 扫描标记为不完整。
                val cat = categoryOf(o.optString("category"))
                if (cat == null) sawUnknownCategory = true
                // v2.22.45（审查 B P2-03）：严重度 / 置信度 / 判定三个
                // 枚举以前对未知值静默降级（severity→INFO 权重 0、
                // confidence→LOW），协议错位时高危证据会被悄悄低估。
                // 现在任一枚举不可识别 → 置 protocolError，且该条
                // 证据的判定强制 UNKNOWN（与未知类别同口径）。
                val sev = severityOf(o.optString("severity"))
                val conf = confidenceOf(o.optString("confidence"))
                val verd = verdictOf(o.optString("verdict"))
                if (sev == null || conf == null || verd == null) {
                    sawUnknownEnum = true
                }
                items += Evidence(
                    id = o.optString("id"),
                    category = cat ?: Category.CROSSCHECK,
                    severity = sev ?: Severity.INFO,
                    confidence = conf ?: Confidence.LOW,
                    verdict = if (cat == null || sev == null ||
                        conf == null || verd == null
                    ) Verdict.UNKNOWN else verd,
                    heuristic = o.optBoolean("heuristic"),
                    title = o.optString("title"),
                    detail = o.optString("detail"),
                    vectors = o.optJSONArray("vectors")?.let { va ->
                        (0 until va.length()).map { va.getString(it) }
                    } ?: emptyList(),
                    fpNote = o.optString("fpNote"),
                    verifyNote = o.optString("verifyNote"),
                )
            }

            // ---------------------------------------------------------- crosses
            // native 侧一直有输出 crosses 数组（CrossCheck），
            // 但 Java 从未解析它 —— 于是 CROSSCHECK_BONUS 在真实调用路径里
            // 永远不会生效，跨视角矛盾这一最有力的证据被整体丢弃。
            val crosses = mutableListOf<CrossCheck>()
            var sawInconsistentMissing = false
            root.optJSONArray("crosses")?.let { ca ->
                for (i in 0 until ca.length()) {
                    val o = ca.optJSONObject(i) ?: continue
                    val obs = mutableListOf<Pair<String, String>>()
                    o.optJSONArray("observations")?.let { oa ->
                        for (k in 0 until oa.length()) {
                            val pair = oa.optJSONObject(k) ?: continue
                            obs += pair.optString("view") to pair.optString("value")
                        }
                    }
                    // 兼容另一种写法：observations 直接是 [{"a":"b"}] 形式的对象
                    if (obs.isEmpty()) {
                        o.optJSONObject("observations")?.let { oo ->
                            oo.keys().forEach { k -> obs += k to oo.optString(k) }
                        }
                    }
                    // v2.22.44（P2-02）：consistent 缺失不再默认 true。
                    // 「没说不一致」不等于「一致」—— 缺字段按不一致 +
                    // 协议问题处理，宁可少给交叉一致的加成。
                    val consistentDeclared = o.has("consistent")
                    if (!consistentDeclared) sawInconsistentMissing = true
                    crosses += CrossCheck(
                        id = o.optString("id"),
                        subject = o.optString("subject"),
                        observations = obs,
                        consistent = consistentDeclared &&
                            o.optBoolean("consistent", false),
                        note = o.optString("note"),
                    )
                }
            }

            Parsed(
                items = items, profile = profile, crosses = crosses,
                version = version,
                protocolError = sawUnknownCategory || sawBlankId ||
                    sawInconsistentMissing || sawUnknownEnum,
            )
        } catch (t: Throwable) {
            Parsed(
                items = emptyList(),
                profile = DeviceProfile(),
                error = "parse: ${t.javaClass.simpleName}: ${t.message}",
            )
        }
    }

    private fun categoryOf(code: String): Category? = when (code) {
        "A" -> Category.ARTIFACT
        "B" -> Category.PROPERTY
        "C" -> Category.MOUNT
        "D" -> Category.MEMORY
        "E" -> Category.HOOK
        "F" -> Category.SELINUX
        "G" -> Category.ATTESTATION
        "H" -> Category.HIDING
        "I" -> Category.INTEGRITY
        "X" -> Category.CROSSCHECK
        else -> null
    }

    private fun severityOf(s: String): Severity? = when (s) {
        "CRITICAL" -> Severity.CRITICAL
        "HIGH" -> Severity.HIGH
        "MEDIUM" -> Severity.MEDIUM
        "LOW" -> Severity.LOW
        "INFO" -> Severity.INFO
        else -> null
    }

    private fun confidenceOf(s: String): Confidence? = when (s) {
        "VERIFIED" -> Confidence.VERIFIED
        "HIGH" -> Confidence.HIGH
        "MEDIUM" -> Confidence.MEDIUM
        "LOW" -> Confidence.LOW
        else -> null
    }

    private fun verdictOf(s: String): Verdict? = when (s) {
        "HIT" -> Verdict.HIT
        "PASS" -> Verdict.PASS
        "UNKNOWN" -> Verdict.UNKNOWN
        else -> null
    }
}