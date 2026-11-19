package com.rootsentinel.util

import com.rootsentinel.RootSentinelVersion
import com.rootsentinel.engine.DetectorEngine
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 诊断摘要上传（v2.22.46，用户要求加入）。
 *
 * ## 同意模型
 * 首次启动弹声明（见 DeclarationDialog）：继续使用即视为同意；
 * 「设置 → 隐私与数据 → 上传诊断摘要」可随时关闭，关闭立即生效。
 * 未确认声明（declarationSeen=false）时**不上传**——调用点双重把关。
 *
 * ## 字段最小化（与声明文案严格一致，改字段先改文案）
 * 传：App 版本、设备品牌/型号/厂商、Android SDK、评分汇总
 * （等级/四维分数/置信度/覆盖率/命中与未知计数/耗时）、逐项的
 * 编号/类别/严重度/置信度/判定/启发式标记。
 * **不传**：设备指纹（连 SHA-256 哈希也不传）、序列号/IMEI 等一切
 * 设备标识、文件路径（detail / vectors 因此整体排除）、证据标题
 * 与详情、已安装应用列表、报告全文。
 *
 * ## 失败语义
 * 发后即忘：无重试队列、不落盘缓存。失败（无网/超时/非 2xx）静默
 * 丢弃这一份，不影响扫描与界面。
 *
 * ## 传输保护（v2.22.48）
 * 载荷不再发明文 JSON：经 [ReportCrypto] 加密+签名封成 v2 信封
 * （HMAC 密钥流 XOR + EtM 签名、时间戳与 nonce 防重放），接收端
 * 验签通过才落日志。字段口径不变，最小化原则不变。
 */
object Telemetry {

    /**
     * 接收端地址（HTTPS）。v2.22.47 起填入正式地址；v2.22.54 起
     * 迁至 Cloudflare Workers（rootsentinel-report），2026-11-15
     * 部署并实测通过（合法载荷 200、重放/篡改/明文 401、报告落库）。
     * 协议与密钥不变。置空即回到待命状态、实际不发送。
     */
    const val UPLOAD_ENDPOINT: String =
        "https://rootsentinel-report.3654007981qw.workers.dev"

    /** 是否已配置接收端。未配置时上传与开关都只是待命状态。 */
    val configured: Boolean get() = UPLOAD_ENDPOINT.isNotBlank()

    /** 组装上传载荷。字段口径见类注释，与声明文案一致。 */
    fun buildPayload(r: DetectorEngine.Result): JSONObject {
        val items = JSONArray()
        for (e in r.items) {
            items.put(JSONObject().apply {
                put("id", e.id)
                put("cat", e.category.name)
                put("sev", e.severity.name)
                put("conf", e.confidence.name)
                put("verdict", e.verdict.name)
                put("heur", e.heuristic)
            })
        }
        return JSONObject().apply {
            put("app", JSONObject().apply {
                put("version", RootSentinelVersion.NAME)
                put("code", RootSentinelVersion.CODE)
            })
            put("device", JSONObject().apply {
                put("brand", r.profile.brand)
                put("model", r.profile.model)
                put("manufacturer", r.profile.manufacturer)
                put("sdk", r.profile.sdk)
            })
            put("report", JSONObject().apply {
                put("grade", r.score.grade.name)
                // v2.23.0 双轨过渡：未折减口径的等级一并上报，
                // 服务端据此监控同源折减转正后的评级漂移。
                put("gradeLegacy", r.score.legacyGrade.name)
                put("root", r.score.rootScore)
                put("integ", r.score.integrityScore)
                put("hook", r.score.hookScore)
                put("env", r.score.environmentScore)
                put("confidence", r.score.confidence.name)
                put("coverage", r.score.coverage)
                put("hits", r.score.hitCount)
                put("unknown", r.score.unknownCount)
                put("items", r.items.size)
                put("elapsedMs", r.elapsedMs)
            })
            put("results", items)
            put("ts", System.currentTimeMillis())
        }
    }

    /**
     * 上传一份诊断摘要。必须在 IO 线程调用（内部是阻塞网络 IO）。
     * 未配置接收端时直接返回。任何异常都吞掉——见类注释的失败语义。
     */
    fun upload(context: android.content.Context, r: DetectorEngine.Result) {
        if (!configured) return
        runCatching {
            // v2.23.0 起封包走 Uplink：优先协议 v3（设备密钥
            // 签名），身份不可用时回退 v2 加密信封（ReportCrypto）。
            val sealed = Uplink.seal(buildPayload(r).toString()) ?: return
            val body = sealed.toByteArray(Charsets.UTF_8)
            val conn = URL(UPLOAD_ENDPOINT).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 5_000
                conn.readTimeout = 5_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
                // 读一次响应码即完成；内容不关心，不落盘不重试。
                conn.responseCode
            } finally {
                conn.disconnect()
            }
        }
    }
}
