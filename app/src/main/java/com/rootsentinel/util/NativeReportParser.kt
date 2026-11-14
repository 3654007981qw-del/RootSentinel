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
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items += Evidence(
                    id = o.optString("id"),
                    category = categoryOf(o.optString("category")),
                    severity = severityOf(o.optString("severity")),
                    confidence = confidenceOf(o.optString("confidence")),
                    verdict = verdictOf(o.optString("verdict")),
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
                    crosses += CrossCheck(
                        id = o.optString("id"),
                        subject = o.optString("subject"),
                        observations = obs,
                        consistent = o.optBoolean("consistent", true),
                        note = o.optString("note"),
                    )
                }
            }

            Parsed(items = items, profile = profile, crosses = crosses, version = version)
        } catch (t: Throwable) {
            Parsed(
                items = emptyList(),
                profile = DeviceProfile(),
                error = "parse: ${t.javaClass.simpleName}: ${t.message}",
            )
        }
    }

    private fun categoryOf(code: String): Category = when (code) {
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
        else -> Category.ARTIFACT
    }

    private fun severityOf(s: String): Severity = when (s) {
        "CRITICAL" -> Severity.CRITICAL
        "HIGH" -> Severity.HIGH
        "MEDIUM" -> Severity.MEDIUM
        "LOW" -> Severity.LOW
        else -> Severity.INFO
    }

    private fun confidenceOf(s: String): Confidence = when (s) {
        "VERIFIED" -> Confidence.VERIFIED
        "HIGH" -> Confidence.HIGH
        "MEDIUM" -> Confidence.MEDIUM
        else -> Confidence.LOW
    }

    private fun verdictOf(s: String): Verdict = when (s) {
        "HIT" -> Verdict.HIT
        "PASS" -> Verdict.PASS
        else -> Verdict.UNKNOWN
    }
}