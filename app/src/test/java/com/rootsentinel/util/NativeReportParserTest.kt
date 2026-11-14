package com.rootsentinel.util

import com.rootsentinel.model.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议严格性回归（v2.22.45，审查 B P2-03 / P2-08）。
 *
 * 核心断言：未知枚举绝不静默降级。协议错位时必须置 protocolError，
 * 且涉事证据的判定被强制为 UNKNOWN，而不是按原样计分。
 */
class NativeReportParserTest {

    private fun report(itemsJson: String, crossesJson: String = "[]"): String =
        """{"version":"2.22.45","items":[$itemsJson],"crosses":$crossesJson}"""

    private fun item(
        severity: String = "HIGH",
        confidence: String = "HIGH",
        verdict: String = "HIT",
        category: String = "A",
    ): String =
        """{"id":"A.TEST","category":"$category","severity":"$severity",""" +
            """"confidence":"$confidence","verdict":"$verdict","title":"t"}"""

    @Test
    fun validHitParsesClean() {
        val parsed = NativeReportParser.parse(report(item()))
        assertFalse(parsed.protocolError)
        assertEquals(1, parsed.items.size)
        assertEquals(Verdict.HIT, parsed.items[0].verdict)
    }

    @Test
    fun unknownSeverityFlagsProtocolErrorAndForcesUnknown() {
        val parsed = NativeReportParser.parse(report(item(severity = "MEGA")))
        assertTrue(parsed.protocolError)
        assertEquals(Verdict.UNKNOWN, parsed.items[0].verdict)
    }

    @Test
    fun unknownConfidenceFlagsProtocolError() {
        val parsed = NativeReportParser.parse(report(item(confidence = "SURE")))
        assertTrue(parsed.protocolError)
        assertEquals(Verdict.UNKNOWN, parsed.items[0].verdict)
    }

    @Test
    fun unknownVerdictFlagsProtocolError() {
        val parsed = NativeReportParser.parse(report(item(verdict = "MAYBE")))
        assertTrue(parsed.protocolError)
        assertEquals(Verdict.UNKNOWN, parsed.items[0].verdict)
    }

    @Test
    fun unknownCategoryFlagsProtocolError() {
        val parsed = NativeReportParser.parse(report(item(category = "Q")))
        assertTrue(parsed.protocolError)
        assertEquals(Verdict.UNKNOWN, parsed.items[0].verdict)
    }

    @Test
    fun missingConsistentIsProtocolErrorAndCountsAsInconsistent() {
        val crosses = """[{"id":"X.T","subject":"s","observations":[]}]"""
        val parsed = NativeReportParser.parse(report(item(), crosses))
        assertTrue(parsed.protocolError)
        assertEquals(1, parsed.crosses.size)
        assertFalse(parsed.crosses[0].consistent)
    }

    @Test
    fun missingItemsIsErrorNotEmptySuccess() {
        val parsed = NativeReportParser.parse("""{"version":"2.22.45"}""")
        assertTrue(parsed.items.isEmpty())
        assertTrue(parsed.error != null)
    }
}
