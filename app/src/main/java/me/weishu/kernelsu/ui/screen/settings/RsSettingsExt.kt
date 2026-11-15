package me.weishu.kernelsu.ui.screen.settings

import androidx.compose.runtime.Immutable

/**
 * RootSentinel extension for KernelSU's settings screen: RootSentinel's own
 * privacy/update/device rows, rendered as one extra card inside the copied
 * SettingPager (Miuix and Material variants) just above the send-log/about
 * card. The KernelSU SettingsUiState/SettingsScreenActions stay untouched.
 */
@Immutable
data class RsPrivacyState(
    val telemetryEnabled: Boolean,
    val crashUploadEnabled: Boolean,
    val autoExport: Boolean,
    val keepHistory: Boolean,
)

@Immutable
data class RsSettingsActions(
    val onSetTelemetryEnabled: (Boolean) -> Unit,
    val onSetCrashUploadEnabled: (Boolean) -> Unit,
    val onSetAutoExport: (Boolean) -> Unit,
    val onSetKeepHistory: (Boolean) -> Unit,
    val onCheckUpdateClick: () -> Unit,
    val onOpenDevice: () -> Unit,
)
