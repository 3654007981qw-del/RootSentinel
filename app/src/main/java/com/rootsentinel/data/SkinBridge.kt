package com.rootsentinel.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * v2.22.71 cross-shell skin state. RootApp owns persistence
 * (SettingsStore.uiSkin) and syncs this bridge both ways; the vendored
 * SukiSU settings screen's RS skin card writes here so the user can
 * switch back to the KernelSU shell without a second SettingsStore
 * instance (whose Compose state RootApp would not observe).
 */
object SkinBridge {
    var uiSkin by mutableStateOf("ksu")

    /**
     * v2.22.73 one-launch rescue: when the previous launch crashed
     * (a pending crash file exists) and the persisted skin is the
     * SukiSU shell, RootApp renders the KernelSU shell for THIS launch
     * only — the persisted setting is untouched, the crash upload gets
     * a crash-free launch to complete in, and the crash loop is broken.
     * Set by MainActivity.onCreate before setContent.
     */
    var forceKsuOnce by mutableStateOf(false)
}
