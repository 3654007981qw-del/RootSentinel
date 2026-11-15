package com.rootsentinel

import com.rootsentinel.util.Updater
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
}
