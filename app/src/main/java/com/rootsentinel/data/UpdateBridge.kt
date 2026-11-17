package com.rootsentinel.data

/**
 * v2.22.79: manual update-check action for the SukiSU About
 * page. Upstream's manual entry is its home update card, which
 * never renders in RS, so the SukiSU shell had no way to check
 * on demand — only the launch-time auto check. RootApp points
 * this at UpdateController.checkManual (dialog + feedback for
 * every outcome, same as the KSU shell's settings entry).
 */
object UpdateBridge {
    var onCheckManual: (() -> Unit)? = null
}
