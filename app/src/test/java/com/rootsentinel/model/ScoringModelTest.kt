package com.rootsentinel.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评分模型回归（v2.22.45，审查 A P1-14 / 审查 B P2-04、P2-08）。
 *
 * 核心断言：
 *  - 同 ID 证据只计一次（取最严重的一条），不因多模块重复产出而放大分数；
 *  - 结构化 CrossCheck 不一致时 CROSSCHECK_BONUS 真实进入 hookScore。
 */
class ScoringModelTest {

    private fun ev(
        id: String,
        verdict: Verdict,
        severity: Severity = Severity.HIGH,
        category: Category = Category.ARTIFACT,
    ) = Evidence(
        id = id,
        category = category,
        severity = severity,
        confidence = Confidence.HIGH,
        verdict = verdict,
        title = "t-$id-$verdict",
    )

    @Test
    fun duplicateIdsAreCountedOnceKeepingWorst() {
        val items = listOf(
            ev("A.X", Verdict.PASS),
            ev("A.X", Verdict.HIT),
        )
        val result = ScoringModel.score(items, emptyList(), DeviceProfile())
        assertEquals(1, result.hitCount)
        assertTrue(result.notes.any { it.contains("去重") })
    }

    @Test
    fun inconsistentCrossCheckAddsHookBonus() {
        val items = listOf(ev("A.X", Verdict.PASS))
        val crosses = listOf(
            CrossCheck(
                id = "X.T",
                subject = "两视角",
                observations = listOf("a" to "1", "b" to "2"),
                consistent = false,
            ),
        )
        val result = ScoringModel.score(items, crosses, DeviceProfile())
        assertTrue(result.hookScore >= 12.0)
    }

    @Test
    fun consistentCrossCheckAddsNothing() {
        val items = listOf(ev("A.X", Verdict.PASS))
        val crosses = listOf(
            CrossCheck(
                id = "X.T",
                subject = "两视角",
                observations = listOf("a" to "1"),
                consistent = true,
            ),
        )
        val result = ScoringModel.score(items, crosses, DeviceProfile())
        assertEquals(0.0, result.hookScore, 0.0001)
    }

    @Test
    fun kernelSuNodeHitRaisesRootScoreNotIntegrity() {
        // 第六轮审查 #8 回归：F.KSU_NODE 由 native 以 KERNEL 类产出，
        // 类别码 F 在 Kotlin 侧落 SELINUX 桶。此前 dimensionOf 默认
        // 把它归 INTEGRITY —— KernelSU 节点明确可见却只涨完整性分、
        // Root 分纹丝不动。现在必须经 ROOT_OVERRIDE_IDS 归 ROOT。
        val items = listOf(
            ev("F.KSU_NODE", Verdict.HIT, Severity.HIGH, Category.SELINUX),
        )
        val result = ScoringModel.score(items, emptyList(), DeviceProfile())
        assertTrue("rootScore 应 > 0，实际 ${result.rootScore}", result.rootScore > 0.0)
        assertEquals(0.0, result.integrityScore, 0.0001)
        assertEquals(1, result.hitCount)
    }

    @Test
    fun crossCheckDoesNotDoubleCountHit() {
        // 第六轮审查 #11 回归：三个生产者都同时产出 Evidence 本体与
        // CrossCheck，同一事实的命中只计一次（奖金作为额外权重保留）。
        val items = listOf(
            ev("C.MOUNT_MULTI", Verdict.HIT, Severity.HIGH, Category.MOUNT),
        )
        val crosses = listOf(
            CrossCheck(
                id = "C.MOUNT_MULTI",
                subject = "多源挂载视角",
                observations = listOf("a" to "1", "b" to "2"),
                consistent = false,
            ),
        )
        val result = ScoringModel.score(items, crosses, DeviceProfile())
        assertEquals(1, result.hitCount)
        assertTrue("交叉奖金应保留，hookScore=${result.hookScore}", result.hookScore >= 12.0)
    }
}
