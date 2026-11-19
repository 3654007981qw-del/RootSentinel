package com.sukisu.nova.ui.screen.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rootsentinel.data.PrivacyBridge
import com.rootsentinel.util.Identity
import com.sukisu.nova.ui.component.material.SegmentedColumn
import com.sukisu.nova.ui.component.material.SegmentedListItem
import com.sukisu.nova.ui.component.material.SegmentedSwitchItem
import com.sukisu.nova.ui.component.miuix.AppCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/** v2.23.0: 设备身份摘要 + 重置（与经典外壳同一 Identity）。 */
@Composable
private fun rememberIdentitySummary(): Pair<String, () -> Unit> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var epoch by remember { mutableStateOf(0) }
    val summary = remember(epoch) {
        Identity.statusSummary(context) ?: "未生成（首次上报时自动生成）"
    }
    val reset = {
        scope.launch(Dispatchers.IO) {
            Identity.reset()
            Identity.ensureKey()
            epoch++
        }
        Unit
    }
    return summary to reset
}

/**
 * RootSentinel seam (v2.22.76): RootSentinel's own privacy toggles
 * inside the SukiSU settings. Upstream's settings have no such
 * concept — the toggles live in RS's SettingsStore, bridged
 * through PrivacyBridge. Appended next to the RS skin card in
 * both settings pagers.
 */
@Composable
fun RsPrivacyCardMiuix() {
    val (identitySummary, resetIdentity) = rememberIdentitySummary()
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
        ArrowPreference(
            title = "重置设备身份",
            summary = identitySummary + "；重置后以新身份登记，旧上报不再关联",
            onClick = resetIdentity,
        )
    }
}

@Composable
fun RsPrivacyCardMaterial() {
    val (identitySummary, resetIdentity) = rememberIdentitySummary()
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
            {
                SegmentedListItem(
                    onClick = resetIdentity,
                    headlineContent = { Text("重置设备身份") },
                    supportingContent = { Text(identitySummary + "；重置后以新身份登记，旧上报不再关联") },
                )
            },
        ),
    )
}
