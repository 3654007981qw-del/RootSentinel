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

    // ---------------------------------------------------------- E1 影子评分

    private fun gev(
        id: String,
        group: String,
        severity: Severity = Severity.HIGH,
    ) = Evidence(
        id = id,
        category = Category.ARTIFACT,
        severity = severity,
        confidence = Confidence.HIGH,
        verdict = Verdict.HIT,
        title = "t-$id",
        sourceGroup = group,
    )

    @Test
    fun shadowScoreDiscountsSameGroupHits() {
        // v2.22.82 E1：同组两条 HIGH HIT（单条 20 分）正式口径合计
        // 40；影子口径 = 最高 20 + 其余 20×0.25 = 25。覆盖率折扣
        // 同比例作用于两个总分，比值不受影响。
        val items = listOf(
            gev("A.DATA_ADB", "fs:/data/adb"),
            gev("A.MODULES_DIR", "fs:/data/adb"),
        )
        val result = ScoringModel.score(items, emptyList(), DeviceProfile())
        assertTrue("正式分应 > 0", result.score > 0.0)
        assertEquals(25.0 / 40.0, result.shadowScore / result.score, 0.01)
        assertTrue(result.notes.any { it.contains("影子评分") })
    }

    @Test
    fun shadowScoreEqualsOfficialForUngroupedHits() {
        // 无组证据各自独立成组，折减公式退化为原值：影子 == 正式。
        val items = listOf(
            ev("A.ONE", Verdict.HIT),
            ev("A.TWO", Verdict.HIT),
        )
        val result = ScoringModel.score(items, emptyList(), DeviceProfile())
        assertEquals(result.score, result.shadowScore, 0.001)
        assertEquals(result.grade, result.shadowGrade)
    }

    @Test
    fun shadowScoreDiscountsSelinuxfsGroupOnly() {
        // v2.22.83：selinuxfs 组内两条 HIGH HIT 折减（20 + 20×0.25 = 25/40）；
        // 跨组（selinuxfs vs fs:/data/adb）的 HIT 不互相折减，影子 == 正式。
        val sameGroup = listOf(
            gev("F.SELINUX_POLICY", "selinuxfs"),
            gev("F.SELINUX_FILTERED", "selinuxfs"),
        )
        val r1 = ScoringModel.score(sameGroup, emptyList(), DeviceProfile())
        assertEquals(25.0 / 40.0, r1.shadowScore / r1.score, 0.01)

        val crossGroup = listOf(
            gev("F.SELINUX_POLICY", "selinuxfs"),
            gev("A.DATA_ADB", "fs:/data/adb"),
        )
        val r2 = ScoringModel.score(crossGroup, emptyList(), DeviceProfile())
        assertEquals(r2.score, r2.shadowScore, 0.001)
    }

    @Test
    fun shadowScoreTreatsPathDerivedGroupsAsDistinctSources() {
        // v2.22.86 E1：来源组按命中路径派生后，SUSFS 的 /proc 证据
        // （procfs）与 ramdisk 证据（fs:/debug_ramdisk）是不同来源，
        // 不互相折减；两条同为 procfs 的证据仍按同源折减。
        val cross = listOf(
            gev("A.SUSFS_TRACE", "procfs"),
            gev("A.MAGISK_TRACE", "fs:/debug_ramdisk"),
        )
        val r1 = ScoringModel.score(cross, emptyList(), DeviceProfile())
        assertEquals(r1.score, r1.shadowScore, 0.001)

        val same = listOf(
            gev("A.SUSFS_TRACE", "procfs"),
            gev("A.KSU_TRACE", "procfs"),
        )
        val r2 = ScoringModel.score(same, emptyList(), DeviceProfile())
        assertEquals(25.0 / 40.0, r2.shadowScore / r2.score, 0.01)
    }

    @Test
    fun shadowScoreIgnoresGroupedPasses() {
        // 只有 HIT 进影子桶：同组一条 HIT + 一条 PASS，
        // 影子分应与正式分一致（PASS 本来就不计分）。
        val items = listOf(
            gev("A.DATA_ADB", "fs:/data/adb"),
            Evidence(
                id = "A.MODULES_DIR", category = Category.ARTIFACT,
                severity = Severity.HIGH, confidence = Confidence.HIGH,
                verdict = Verdict.PASS, title = "t",
                sourceGroup = "fs:/data/adb",
            ),
        )
        val result = ScoringModel.score(items, emptyList(), DeviceProfile())
        assertEquals(result.score, result.shadowScore, 0.001)
    }
}
