package com.sukisu.nova.ui.screen.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rootsentinel.data.PrivacyBridge
import com.sukisu.nova.ui.component.material.SegmentedColumn
import com.sukisu.nova.ui.component.material.SegmentedSwitchItem
import com.sukisu.nova.ui.component.miuix.AppCard
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * RootSentinel seam (v2.22.76): RootSentinel's own privacy toggles
 * inside the SukiSU settings. Upstream's settings have no such
 * concept — the toggles live in RS's SettingsStore, bridged
 * through PrivacyBridge. Appended next to the RS skin card in
 * both settings pagers.
 */
@Composable
fun RsPrivacyCardMiuix() {
    AppCard(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        SwitchPreference(
            title = "上传诊断摘要",
            summary = "扫描完成后上传版本、设备型号与逐项结论（不含指纹与原始数据）",
            checked = PrivacyBridge.telemetryEnabled,
            onCheckedChange = { PrivacyBridge.telemetryEnabled = it },
        )
        SwitchPreference(
            title = "上传崩溃日志",
            summary = "应用崩溃后，下次启动时上传崩溃堆栈以便定位问题",
            checked = PrivacyBridge.crashUploadEnabled,
            onCheckedChange = { PrivacyBridge.crashUploadEnabled = it },
        )
    }
}

@Composable
fun RsPrivacyCardMaterial() {
    SegmentedColumn(
        modifier = Modifier.padding(top = 12.dp),
        title = "RootSentinel",
        content = listOf(
            {
                SegmentedSwitchItem(
                    title = "上传诊断摘要",
                    summary = "扫描完成后上传版本、设备型号与逐项结论（不含指纹与原始数据）",
                    checked = PrivacyBridge.telemetryEnabled,
                    onCheckedChange = { PrivacyBridge.telemetryEnabled = it },
                )
            },
            {
                SegmentedSwitchItem(
                    title = "上传崩溃日志",
                    summary = "应用崩溃后，下次启动时上传崩溃堆栈以便定位问题",
                    checked = PrivacyBridge.crashUploadEnabled,
                    onCheckedChange = { PrivacyBridge.crashUploadEnabled = it },
                )
            },
        ),
    )
}
