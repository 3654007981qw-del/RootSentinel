package com.rootsentinel.util

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * 服务端签名策略（v2.23.0）：GET /policy 拉取 {minCode,
 * v2Sunset, scoreMode, expiresAt}，用内置的策略公钥验签后
 * 缓存。公钥不是秘密（源码公开无妨）；私钥只在服务端。
 *
 * 语义纪律：策略只做「提示与口径切换」，绝不锁应用——
 * minCode 只驱动升级横幅，scoreMode 只切换评分口径
 * （grouped / legacy 远程回退），v2Sunset 只影响上报通道
 * 选择。拉取失败或策略过期时用最后一份缓存（标记 stale），
 * 无缓存时用内置默认值（grouped、不提示升级）。
 * 必须在 IO 线程调用 refresh。
 */
object Policy {

    // 策略签名公钥（SPKI，EC P-256）。与服务端 secret
    // POLICY_KEY 配对，公钥公开无妨，写死在源码里正是
    // 「公钥不是秘密」的正常用法。
    private const val SPKI_B64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAECl+G+YXc5JX6yHRYhEg9RtjTCkklEt4G2AfzTFPA49IwfAII+Yn+CkMTwDE1dK2++X81pbhBi8qbywYahBTB9A=="

    private const val SP_NAME = "rs_policy"
    private const val KEY_TEXT = "policy_text"
    private const val KEY_FETCHED = "fetched_at"

    data class Current(
        val minCode: Int = 0,
        val v2Sunset: Long = Long.MAX_VALUE,
        val scoreMode: String = "grouped",
        val expiresAt: Long = 0,
        val stale: Boolean = true,
    )

    /** 拉取并验签策略，成功则写入缓存。失败静默（沿用旧缓存）。IO 线程调用。 */
    fun refresh(context: Context) {
        if (!Telemetry.configured) return
        runCatching {
            val conn = URL(Telemetry.UPLOAD_ENDPOINT + "/policy").openConnection() as HttpURLConnection
            val text = try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 5_000
                conn.readTimeout = 5_000
                if (conn.responseCode !in 200..299) return
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                conn.disconnect()
            }
            val obj = JSONObject(text)
            val policyText = obj.getString("policyText")
            val sigRaw = Base64.decode(obj.getString("sig"), Base64.DEFAULT)
            if (!verify(policyText, sigRaw)) return
            // 验签通过才解析并缓存原文（缓存的即是被签名的原文，
            // 之后读取无需再验签）。
            JSONObject(policyText)
            context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_TEXT, policyText)
                .putLong(KEY_FETCHED, System.currentTimeMillis())
                .apply()
        }
    }

    /** 当前生效策略（缓存优先，无缓存用默认值）。 */
    fun current(context: Context): Current {
        val sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        val text = sp.getString(KEY_TEXT, null) ?: return Current()
        return runCatching {
            val o = JSONObject(text)
            val exp = o.optLong("expiresAt", 0)
            Current(
                minCode = o.optInt("minCode", 0),
                v2Sunset = o.optLong("v2Sunset", Long.MAX_VALUE),
                scoreMode = if (o.optString("scoreMode") == "legacy") "legacy" else "grouped",
                expiresAt = exp,
                stale = System.currentTimeMillis() > exp,
            )
        }.getOrDefault(Current())
    }

    private fun verify(policyText: String, sigRaw: ByteArray): Boolean = runCatching {
        val spki = Base64.decode(SPKI_B64, Base64.DEFAULT)
        val pub = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(spki))
        val der = rawToDer(sigRaw) ?: return false
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(pub)
            update(policyText.toByteArray(Charsets.UTF_8))
            verify(der)
        }
    }.getOrDefault(false)

    /** WebCrypto 的签名是 raw r||s（各 32 字节），Java 验签要 DER。 */
    private fun rawToDer(raw: ByteArray): ByteArray? {
        if (raw.size != 64) return null
        fun part(b: ByteArray, off: Int): ByteArray {
            var start = off
            while (start < off + 31 && b[start] == 0.toByte()) start++
            val len = off + 32 - start
            val needPad = (b[start].toInt() and 0x80) != 0
            val out = ByteArray(2 + len + if (needPad) 1 else 0)
            out[0] = 0x02
            out[1] = (len + if (needPad) 1 else 0).toByte()
            var p = 2
            if (needPad) out[p++] = 0
            System.arraycopy(b, start, out, p, len)
            return out
        }
        val r = part(raw, 0)
        val s = part(raw, 32)
        val body = r + s
        if (body.size > 127) return null
        return byteArrayOf(0x30, body.size.toByte()) + body
    }
}
