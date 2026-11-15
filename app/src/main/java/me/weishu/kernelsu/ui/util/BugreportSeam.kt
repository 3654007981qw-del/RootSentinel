package me.weishu.kernelsu.ui.util

import android.content.Context
import com.rootsentinel.data.LatestReportProvider
import java.io.File

/**
 * RootSentinel seam for KernelSU's getBugreportFile (ui/util/LogEvent.kt):
 * same signature; the produced file is RootSentinel's latest scan report
 * JSON (or a short placeholder note when no scan has run yet), written to
 * the app cache dir for the share/save flows that consume it.
 */
fun getBugreportFile(context: Context): File {
    val json = LatestReportProvider.latestReportJson?.invoke()
        ?: """{"app":"RootSentinel","note":"no scan report yet"}"""
    val file = File(context.cacheDir, "rootsentinel-report.json")
    file.writeText(json)
    return file
}
