package com.rootsentinel.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * v2.22.76 scan-entry bridge for the SukiSU shell's home page.
 * Upstream's home has no scan concept (its big card installs a
 * kernel), so RootSentinel's engine entry is an RS card appended
 * under the status card; it reads this bridge. RootApp owns the
 * real ScanState and syncs the bridge every recomposition, the
 * same pattern as SkinBridge.
 */
object ScanBridge {
    var scanning by mutableStateOf(false)
    var statusText by mutableStateOf("")
    /** v2.22.77: big status word for the home status card
     *  （待检测 / 检测中 / 最近一次评级词）. */
    var headline by mutableStateOf("待检测")
    var onRequestScan: (() -> Unit)? = null
}
