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
}
