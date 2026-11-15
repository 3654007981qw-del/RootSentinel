package com.rootsentinel

import com.rootsentinel.util.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Updater.isNewer 的版本比较口径（v2.22.47 新增）。
 * 更新检查全靠它判新旧，判错一次就是漏更或假更新。
 */
class UpdaterTest {

    @Test
    fun patchBumpIsNewer() {
        assertTrue(Updater.isNewer("2.22.47", "2.22.46"))
    }

    @Test
    fun sameVersionIsNotNewer() {
        assertFalse(Updater.isNewer("2.22.46", "2.22.46"))
    }

    @Test
    fun olderIsNotNewer() {
        assertFalse(Updater.isNewer("2.22.45", "2.22.46"))
        assertFalse(Updater.isNewer("2.22.5", "2.22.46"))
    }

    @Test
    fun minorBumpBeatsPatch() {
        assertTrue(Updater.isNewer("2.23.0", "2.22.99"))
    }

    @Test
    fun majorBumpIsNewer() {
        assertTrue(Updater.isNewer("3.0.0", "2.22.46"))
    }

    @Test
    fun leadingVIsTolerated() {
        assertTrue(Updater.isNewer("v2.22.47", "2.22.46"))
    }

    @Test
    fun garbageIsNeverNewer() {
        assertFalse(Updater.isNewer("latest", "2.22.46"))
        assertFalse(Updater.isNewer("", "2.22.46"))
        assertFalse(Updater.isNewer("2.22.47", ""))
    }

    // ---- decide() 三态合并（v2.22.50，第六轮审查 #12 回归） ----

    private fun info() = Updater.UpdateInfo(
        versionName = "9.9.9",
        notes = "",
        apkUrl = "https://example.invalid/a.apk",
        apkSize = 1L,
        sha256 = null,
        source = "GitHub",
    )

    @Test
    fun decidePrefersFoundFromEitherSource() {
        val f = Updater.SourceOutcome.Found(info())
        assertTrue(
            Updater.decide(f, Updater.SourceOutcome.Failed)
                is Updater.CheckResult.Found,
        )
        assertTrue(
            Updater.decide(Updater.SourceOutcome.Failed, f)
                is Updater.CheckResult.Found,
        )
    }

    @Test
    fun decideUpToDateWhenAnySourceAnswered() {
        assertTrue(
            Updater.decide(Updater.SourceOutcome.NoUpdate, Updater.SourceOutcome.Failed)
                is Updater.CheckResult.UpToDate,
        )
        assertTrue(
            Updater.decide(Updater.SourceOutcome.Failed, Updater.SourceOutcome.NoUpdate)
                is Updater.CheckResult.UpToDate,
        )
    }

    @Test
    fun decideFailedWhenBothSourcesFailed() {
        // 双源都失败 = 检查失败，绝不冒充「已是最新」
        assertTrue(
            Updater.decide(Updater.SourceOutcome.Failed, Updater.SourceOutcome.Failed)
                is Updater.CheckResult.Failed,
        )
    }

    // ---- v2.22.52 多源候选与分片计划 ----

    @Test
    fun candidatesOrderGithubMirrorsGitee() {
        val gh = "https://github.com/AURORA-0711/RootSentinel/releases/download/v1/x.apk"
        val gt = "https://gitee.com/AURORAqw/RootSentinel/releases/download/v1/x.apk"
        val c = Updater.candidates(gh, gt)
        assertEquals(gh, c.first())
        assertEquals(gt, c.last())
        // 直连 + 3 个镜像 + Gitee，共 5 个，且镜像都是前缀拼接
        assertEquals(5, c.size)
        assertTrue(c.subList(1, 4).all { it.endsWith(gh) && it != gh })
    }

    @Test
    fun candidatesHandleNulls() {
        assertEquals(4, Updater.candidates("https://github.com/x", null).size)
        assertEquals(
            listOf("https://gitee.com/x"),
            Updater.candidates(null, "https://gitee.com/x"),
        )
        assertTrue(Updater.candidates(null, null).isEmpty())
    }

    @Test
    fun chunkPlanCoversExactly() {
        val plan = Updater.chunkPlan(10, 4)
        assertEquals(4, plan.size)
        assertEquals(0L, plan.first().first)
        assertEquals(9L, plan.last().last)
        // 连续不重叠，总长恰好 10
        var covered = 0L
        for (i in plan.indices) {
            if (i > 0) assertEquals(plan[i - 1].last + 1, plan[i].first)
            covered += plan[i].last - plan[i].first + 1
        }
        assertEquals(10L, covered)
    }

    @Test
    fun chunkPlanEdgeCases() {
        assertEquals(3, Updater.chunkPlan(3, 4).size)
        assertEquals(1, Updater.chunkPlan(1, 4).size)
        assertTrue(Updater.chunkPlan(0, 4).isEmpty())
        assertTrue(Updater.chunkPlan(-5, 4).isEmpty())
        val big = Updater.chunkPlan(16_233_586, 4)
        assertEquals(4, big.size)
        assertEquals(16_233_585L, big.last().last)
    }
}
