package com.rootsentinel.util

import org.json.JSONObject
import java.util.Base64
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 诊断摘要的传输加密（v2.22.48，协议 v2），与接收端
 * （阿里云 FC rootsentinel-report v3）严格对应：
 *
 *   encKey = HMAC-SHA256(master, "rs-enc")
 *   macKey = HMAC-SHA256(master, "rs-mac")
 *   密钥流 block_i = HMAC-SHA256(encKey, nonce || uint32BE(i))
 *   密文 = 明文 XOR 密钥流；签名 s = HMAC-SHA256(macKey, "2.{ts}.{n}.{d}")
 *   信封 = {"v":2,"ts":毫秒,"n":nonce hex,"d":base64 密文,"s":签名 hex}
 *
 * 在 HTTPS 之外再加一层：链路抓包看不到字段内容；没有密钥造不出
 * 合法报告（服务端验签 + 时间戳窗口 + nonce 单次使用防重放）。
 *
 * 密钥以「分片 XOR 掩码」形式存放在下面，运行时拼装，避免完整
 * 密钥以明文字符串出现在源码与字符串池里。要说明的是：客户端
 * 内嵌密钥对有耐心的逆向者不是绝对秘密——这层防护的定位是拦住
 * 随手伪造/灌水与被动抓包，不是把数据变成保险箱。载荷本身仍
 * 维持 2.22.46 起的字段最小化，里面没有设备标识。
 */
object ReportCrypto {

    private val F0 = intArrayOf(0xBF, 0x54, 0x25, 0xD4, 0x74, 0xF6, 0x57, 0xE2)
    private val M0 = intArrayOf(0x6C, 0xB4, 0x38, 0xF1, 0x73, 0x2D, 0x4A, 0x10)
    private val F1 = intArrayOf(0x70, 0x41, 0xAB, 0x52, 0xA0, 0x68, 0x73, 0x5D)
    private val M1 = intArrayOf(0x16, 0x46, 0xD1, 0x2F, 0x1F, 0xA8, 0x52, 0xD2)
    private val F2 = intArrayOf(0xD2, 0x93, 0x1A, 0x2D, 0x87, 0xD8, 0x75, 0xA9)
    private val M2 = intArrayOf(0x18, 0x2E, 0xFC, 0x1E, 0xD1, 0xBE, 0x88, 0x4A)
    private val F3 = intArrayOf(0x12, 0x4D, 0xB9, 0xE7, 0x06, 0xE0, 0x6D, 0xA8)
    private val M3 = intArrayOf(0xE2, 0x4B, 0x3A, 0x07, 0x9F, 0x4A, 0x06, 0x70)

    private fun masterKey(): ByteArray {
        val frags = arrayOf(F0, F1, F2, F3)
        val masks = arrayOf(M0, M1, M2, M3)
        val out = ByteArray(32)
        for (p in 0 until 4) {
            for (i in 0 until 8) {
                out[p * 8 + i] = (frags[p][i] xor masks[p][i]).toByte()
            }
        }
        return out
    }

    private fun hmac(key: ByteArray, msg: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(msg)
    }

    private fun xorStream(encKey: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        var pos = 0
        var counter = 0
        while (pos < data.size) {
            val ctr = byteArrayOf(
                (counter ushr 24).toByte(),
                (counter ushr 16).toByte(),
                (counter ushr 8).toByte(),
                counter.toByte(),
            )
            val block = hmac(encKey, nonce + ctr)
            val step = minOf(block.size, data.size - pos)
            for (i in 0 until step) {
                out[pos + i] = (data[pos + i].toInt() xor block[i].toInt()).toByte()
            }
            pos += step
            counter++
        }
        return out
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    /**
     * 把明文报告 JSON 封成 v2 信封。nonce/ts 可注入以便测试向量
     * 对拍；生产调用走默认随机 nonce + 当前时间。
     */
    fun seal(
        plain: String,
        ts: Long = System.currentTimeMillis(),
        nonce: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) },
    ): String {
        val master = masterKey()
        val encKey = hmac(master, "rs-enc".toByteArray(Charsets.UTF_8))
        val macKey = hmac(master, "rs-mac".toByteArray(Charsets.UTF_8))
        val cipher = xorStream(encKey, nonce, plain.toByteArray(Charsets.UTF_8))
        // java.util.Base64（API 26+ 可用）：标准字母表、带填充、无换行，
        // 与接收端 base64.b64decode(validate=True) 口径一致。
        val d = Base64.getEncoder().encodeToString(cipher)
        val n = nonce.hex()
        val s = hmac(macKey, "2.$ts.$n.$d".toByteArray(Charsets.UTF_8)).hex()
        return JSONObject()
            .put("v", 2)
            .put("ts", ts)
            .put("n", n)
            .put("d", d)
            .put("s", s)
            .toString()
    }
}
