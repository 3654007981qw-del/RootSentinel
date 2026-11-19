package com.rootsentinel.util

import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom

/**
 * 上报封包的统一入口（v2.23.0）：优先协议 v3（设备密钥签名，
 * 见 [Identity] 与服务端 v11-cf），身份不可用或签名失败时
 * 回退协议 v2（[ReportCrypto] 共享密钥信封）。Telemetry 与
 * CrashReporter 都只走这里，避免两条上报路径口径漂移。
 * 返回 null 表示两种封包都失败，调用方按各自失败语义处理。
 * 必须在 IO 线程调用（Keystore 操作可能阻塞）。
 */
object Uplink {

    fun seal(payload: String): String? {
        sealV3(payload)?.let { return it }
        return runCatching { ReportCrypto.seal(payload) }.getOrNull()
    }

    private fun sealV3(payload: String): String? = runCatching {
        if (!Identity.ensureKey()) return null
        val kid = Identity.kid() ?: return null
        val pub = Identity.pubB64() ?: return null
        val ts = System.currentTimeMillis()
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val n = nonce.joinToString("") { "%02x".format(it) }
        val sig = Identity.sign("3.$ts.$n.$kid.$payload".toByteArray(Charsets.UTF_8))
            ?: return null
        JSONObject().apply {
            put("v", 3)
            put("ts", ts)
            put("n", n)
            put("kid", kid)
            put("pub", pub)
            put("lvl", Identity.level())
            put("payload", payload)
            put("sig", Base64.encodeToString(sig, Base64.NO_WRAP))
        }.toString()
    }.getOrNull()
}
