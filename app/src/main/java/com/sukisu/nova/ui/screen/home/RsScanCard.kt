package com.sukisu.nova.ui.screen.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rootsentinel.data.ScanBridge
import com.sukisu.nova.ui.component.miuix.AppCard
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon

/**
 * RootSentinel seam (v2.22.76): the scan entry on the SukiSU home
 * page. Upstream's home has no scan concept — its status card
 * installs a kernel — so RootSentinel's engine entry is this card,
 * appended under the status card in both home pagers. State and
 * the start action come through ScanBridge (RootApp owns them).
 */
@Composable
fun RsScanCardMiuix() {
    val scanning = ScanBridge.scanning
    AppCard(Modifier.fillMaxWidth()) {
        BasicComponent(
            title = if (scanning) "正在扫描…" else "开始检测",
            summary = ScanBridge.statusText,
            endActions = {
                Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null)
            },
            onClick = { ScanBridge.onRequestScan?.invoke() },
        )
    }
}

@Composable
fun RsScanCardMaterial() {
    val scanning = ScanBridge.scanning
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { ScanBridge.onRequestScan?.invoke() }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                androidx.compose.material3.Text(
                    text = if (scanning) "正在扫描…" else "开始检测",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
                androidx.compose.material3.Text(
                    text = ScanBridge.statusText,
                    fontSize = 13.sp,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
            )
        }
    }
}
