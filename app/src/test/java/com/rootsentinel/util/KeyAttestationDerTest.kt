package com.rootsentinel.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DER 读取器测试向量（v2.22.45，审查 B P2-06 / P2-08）。
 *
 * 核心断言：声明长度超过剩余数据必须失败，绝不静默截断 —— 旧实现
 * 用 minOf 截断后仍返回「看起来有效」的 TLV，截断/伪造的证明数据
 * 也能解出字段。
 */
class KeyAttestationDerTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun validSequenceParses() {
        // SEQUENCE { INTEGER 5 }
        val der = KeyAttestation.Der(bytes(0x30, 0x03, 0x02, 0x01, 0x05))
        val outer = der.readTlv()
        assertTrue(outer != null)
        assertEquals(0x30, outer!!.first and 0xFF)
        assertArrayEquals(bytes(0x02, 0x01, 0x05), outer.second)
        assertTrue(der.eof())

        val inner = KeyAttestation.Der(outer.second)
        val intTlv = inner.readTlv()
        assertTrue(intTlv != null)
        assertEquals(0x02, intTlv!!.first and 0xFF)
        assertArrayEquals(bytes(0x05), intTlv.second)
    }

    @Test
    fun truncatedDeclaredLengthIsRejected() {
        // SEQUENCE 声明 5 字节内容，实际只有 3 字节
        val der = KeyAttestation.Der(bytes(0x30, 0x05, 0x02, 0x01, 0x05))
        assertNull(der.readTlv())
    }

    @Test
    fun longFormLengthParses() {
        // INTEGER, 长格式长度 0x81 0x03, 内容 01 02 03
        val der = KeyAttestation.Der(bytes(0x02, 0x81, 0x03, 0x01, 0x02, 0x03))
        val tlv = der.readTlv()
        assertTrue(tlv != null)
        assertArrayEquals(bytes(0x01, 0x02, 0x03), tlv!!.second)
    }

    @Test
    fun truncatedLengthBytesAreRejected() {
        // 0x82 表示后面有 2 字节长度，但只给了 1 字节
        val der = KeyAttestation.Der(bytes(0x02, 0x82, 0x01))
        assertNull(der.readTlv())
    }

    @Test
    fun oversizedDeclaredLengthIsRejected() {
        // INTEGER 声明 127 字节，实际 1 字节
        val der = KeyAttestation.Der(bytes(0x02, 0x7F, 0x01))
        assertNull(der.readTlv())
    }
}
