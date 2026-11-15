package com.rootsentinel.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ReportCrypto 与接收端（Python 实现）的跨语言对拍（v2.22.48）。
 *
 * 期望值由接收端同算法的参考实现在固定明文/时间戳/nonce 下生成；
 * 两边任何一侧改了密钥派生、密钥流或签名串格式，这个测试就会红。
 */
class ReportCryptoTest {

    @Test
    fun sealMatchesReferenceVector() {
        val plain = """{"app":{"version":"2.22.48","code":22248},"report":{"grade":"CLEAN"}}"""
        val nonce = ByteArray(16) { it.toByte() } // 00 01 02 … 0f
        val sealed = ReportCrypto.seal(plain, ts = 1763178000000L, nonce = nonce)
        val env = JSONObject(sealed)
        assertEquals(2, env.getInt("v"))
        assertEquals(1763178000000L, env.getLong("ts"))
        assertEquals("000102030405060708090a0b0c0d0e0f", env.getString("n"))
        assertEquals(
            "x1gqxV1wIRyJQOeZVUrSxntCi+G/VMSiZkD4ce6rRAZzwPJSZj2Wn6nzq5Ea/9No84OxG0e/1pK32UxsiMBCjPY59zVX",
            env.getString("d"),
        )
        assertEquals(
            "4bf7ddc877d32bde7d15c8026c687d9c5c31c735b6b05fb5ff1b47c800664d31",
            env.getString("s"),
        )
    }

    @Test
    fun sealRandomizesNonce() {
        val a = JSONObject(ReportCrypto.seal("{}"))
        val b = JSONObject(ReportCrypto.seal("{}"))
        // 随机 nonce → 同一明文两次封包的 nonce 与密文都应不同
        assertEquals(32, a.getString("n").length)
        org.junit.Assert.assertNotEquals(a.getString("n"), b.getString("n"))
        org.junit.Assert.assertNotEquals(a.getString("d"), b.getString("d"))
        org.junit.Assert.assertNotEquals(a.getString("s"), b.getString("s"))
    }
}
