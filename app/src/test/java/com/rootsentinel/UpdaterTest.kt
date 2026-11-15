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

    // ---- v2.22.58 Content-Range 严格解析（第九轮审计 P2-02） ----

    @Test
    fun contentRangeParsesValid() {
        assertEquals(Triple(0L, 99L, 1000L), Updater.parseContentRange("bytes 0-99/1000"))
        assertEquals(Triple(500L, 999L, 1000L), Updater.parseContentRange("bytes 500-999/1000"))
        assertEquals(Triple(0L, 0L, 1L), Updater.parseContentRange("bytes 0-0/1"))
        // total 未知（*）→ -1
        assertEquals(Triple(10L, 20L, -1L), Updater.parseContentRange("bytes 10-20/*"))
        // 前后空白容忍
        assertEquals(Triple(0L, 99L, 1000L), Updater.parseContentRange("  bytes 0-99/1000 "))
    }

    @Test
    fun contentRangeRejectsMalformed() {
        assertEquals(null, Updater.parseContentRange(null))
        assertEquals(null, Updater.parseContentRange(""))
        assertEquals(null, Updater.parseContentRange("bytes 99-0/1000")) // end < start
        assertEquals(null, Updater.parseContentRange("bytes 0-1000/1000")) // end >= total
        assertEquals(null, Updater.parseContentRange("items 0-99/1000")) // 单位不对
        assertEquals(null, Updater.parseContentRange("bytes 0-99")) // 缺 total
        assertEquals(null, Updater.parseContentRange("bytes -99/1000")) // 缺 start
        assertEquals(null, Updater.parseContentRange("bytes 0-99/1000 extra")) // 尾部垃圾
    }

    // ---- v2.22.55 测速选源与速度显示 ----

    @Test
    fun speedMeterNeedsHalfSecondSpan() {
        val m = Updater.SpeedMeter()
        assertEquals(0L, m.add(0, 0))
        // 跨度不足 500ms：读数无意义，返回 0
        assertEquals(0L, m.add(200, 100_000))
    }

    @Test
    fun speedMeterReportsWindowRate() {
        val m = Updater.SpeedMeter(windowMs = 3_000)
        m.add(0, 0)
        // 2 秒下了 2 MB → 约 1 MB/s
        val v = m.add(2_000, 2_000_000)
        assertTrue(v in 900_000..1_100_000)
    }

    @Test
    fun speedMeterWindowSlides() {
        val m = Updater.SpeedMeter(windowMs = 3_000)
        m.add(0, 0)
        m.add(1_000, 3_000_000) // 前 1 秒很快
        // 之后停滞到 5 秒：窗口只剩近 3 秒，速度应明显低于全程平均
        val v = m.add(5_000, 3_100_000)
        assertTrue(v < 200_000)
    }

    @Test
    fun rankBySpeedOrdersFastestFirst() {
        val urls = listOf("gh", "m1", "m2", "gt")
        val ranked = Updater.rankBySpeed(
            urls,
            mapOf("m2" to 5_000_000L, "gh" to 10_000L, "gt" to 2_000_000L),
        )
        // 实测最快在前；没测到的 m1 沉底但不消失
        assertEquals(listOf("m2", "gt", "gh", "m1"), ranked)
    }

    @Test
    fun rankBySpeedStableForTiesAndMissing() {
        val urls = listOf("a", "b", "c")
        assertEquals(urls, Updater.rankBySpeed(urls, emptyMap()))
        assertEquals(
            urls,
            Updater.rankBySpeed(urls, mapOf("a" to 7L, "b" to 7L, "c" to 7L)),
        )
    }

    @Test
    fun giteeFirstCandidatesOrder() {
        val gh = "https://github.com/AURORA-0711/RootSentinel/releases/download/v1/x.apk"
        val gt = "https://gitee.com/AURORAqw/RootSentinel/releases/download/v1/x.apk"
        val c = Updater.giteeFirstCandidates(gh, gt)
        // Gitee 第一，其后是 GitHub 直连 + 3 镜像，共 5 个
        assertEquals(gt, c.first())
        assertEquals(gh, c[1])
        assertEquals(5, c.size)
        assertEquals(listOf(gt), Updater.giteeFirstCandidates(null, gt))
        assertEquals(4, Updater.giteeFirstCandidates(gh, null).size)
    }

    @Test
    fun formatSpeedUnits() {
        assertEquals("—", Updater.formatSpeed(0))
        assertEquals("512 B/s", Updater.formatSpeed(512))
        assertEquals("256 KB/s", Updater.formatSpeed(256 * 1024))
        assertEquals("1.5 MB/s", Updater.formatSpeed(1536 * 1024))
    }
}
