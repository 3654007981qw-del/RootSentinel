package com.rootsentinel.data

/**
 * v2.22.77: report-export action for the SukiSU settings, where
 * upstream's "send log" (a ksud bugreport export) used to sit.
 * RootApp points this at the same export action the report page
 * uses (JsonExporter + toast); the vendored settings rows just
 * invoke it.
 */
object ExportBridge {
    var onExport: (() -> Unit)? = null
}
