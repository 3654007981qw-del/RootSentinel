package com.rootsentinel.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C1 回归（v2.22.86，联合审查）：崩溃堆栈脱敏。
 *
 * 此前 buildPayload 只对 message 字段脱敏，stack 却是
 * printStackTrace 原文——首行与 Caused by 行原样重复 message，
 * 路径/URL/邮箱照样随堆栈上传。断言：payload 的 stack 与
 * message 都不含植入的原文，且堆栈的定位信息（异常类名、
 * 帧）保留。
 */
class CrashReporterTest {

    private fun payloadOf(error: Throwable) =
        CrashReporter.buildPayload(Thread.currentThread(), error)
            .getJSONObject("crash")

    @Test
    fun stackAndMessageAreSanitized() {
        val error = IllegalStateException(
            "open /data/user/0/com.rootsentinel/files/x failed, see https://leak.example.com/a?b=1 or mail a.b@leak-example.com",
        )
        val crash = payloadOf(error)
        val stack = crash.getString("stack")
        val message = crash.getString("message")
        for (field in listOf(stack, message)) {
            assertFalse(field.contains("/data/user/0"))
            assertFalse(field.contains("leak.example.com"))
            assertFalse(field.contains("leak-example.com"))
        }
        // 定位信息保留：异常类名与堆栈帧仍在。
        assertTrue(stack.contains("java.lang.IllegalStateException"))
        assertTrue(stack.contains("\tat "))
    }

    @Test
    fun causedByLineIsSanitizedToo() {
        val cause = IllegalArgumentException("bad path /sdcard/Android/data/secret.txt")
        val error = RuntimeException("wrapper", cause)
        val stack = payloadOf(error).getString("stack")
        assertTrue(stack.contains("Caused by:"))
        assertFalse(stack.contains("/sdcard/Android/data"))
    }
}
