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
 */
object Telemetry {

    /**
     * 接收端地址（HTTPS）。**当前为空 = 功能待命、实际不发送。**
     * 作者部署好接收端后把地址填在这里即可生效，格式如
     * "https://example.com/rootsentinel/report"。
     */
    const val UPLOAD_ENDPOINT: String = ""

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
    fun upload(r: DetectorEngine.Result) {
        if (!configured) return
        runCatching {
            val body = buildPayload(r).toString().toByteArray(Charsets.UTF_8)
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
