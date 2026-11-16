package com.sukisu.nova.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rootsentinel.data.SkinBridge

/**
 * RootSentinel seam (v2.22.71): the skin switch back to the KernelSU
 * shell. Upstream's settings have no such concept — RootSentinel's
 * uiSkin lives in its own SettingsStore, bridged through SkinBridge.
 * Appended at the end of both settings pagers.
 */
@Composable
fun RsSkinCardMiuix() {
    val current = SkinBridge.uiSkin
    top.yukonga.miuix.kmp.basic.Card(
        modifier = Modifier
            .padding(vertical = 12.dp)
            .fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            top.yukonga.miuix.kmp.basic.Text(
                text = "界面皮肤（RootSentinel）",
                fontWeight = FontWeight.SemiBold,
            )
            RsSkinRowMiuix("KernelSU 风格", current == "ksu") { SkinBridge.uiSkin = "ksu" }
            RsSkinRowMiuix("SukiSU Ultra 风格", current == "sukisu") { SkinBridge.uiSkin = "sukisu" }
        }
    }
}

@Composable
private fun RsSkinRowMiuix(label: String, active: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        top.yukonga.miuix.kmp.basic.Text(
            text = if (active) "$label（当前）" else label,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun RsSkinCardMaterial() {
    val current = SkinBridge.uiSkin
    androidx.compose.material3.Card(
        modifier = Modifier
            .padding(vertical = 12.dp)
            .fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            androidx.compose.material3.Text(
                text = "界面皮肤（RootSentinel）",
                fontWeight = FontWeight.SemiBold,
            )
            RsSkinRowMaterial("KernelSU 风格", current == "ksu") { SkinBridge.uiSkin = "ksu" }
            RsSkinRowMaterial("SukiSU Ultra 风格", current == "sukisu") { SkinBridge.uiSkin = "sukisu" }
        }
    }
}

@Composable
private fun RsSkinRowMaterial(label: String, active: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        androidx.compose.material3.Text(
            text = if (active) "$label（当前）" else label,
            modifier = Modifier.weight(1f),
        )
    }
}
