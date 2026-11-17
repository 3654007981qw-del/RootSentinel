package com.rootsentinel.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * v2.22.76 privacy-toggle bridge for the SukiSU shell's settings.
 * RootSentinel's own 上传诊断摘要 / 上传崩溃日志 toggles live in
 * its SettingsStore; the vendored SukiSU settings cannot reach
 * that instance, so RootApp two-way-syncs this bridge (the same
 * pattern as SkinBridge) and the RS privacy card in the SukiSU
 * settings reads/writes here.
 */
object PrivacyBridge {
    var telemetryEnabled by mutableStateOf(true)
    var crashUploadEnabled by mutableStateOf(true)
}
