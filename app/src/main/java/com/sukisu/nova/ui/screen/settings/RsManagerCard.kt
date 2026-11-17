package com.sukisu.nova.ui.screen.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rootsentinel.data.ManagerBridge
import com.sukisu.nova.ui.component.material.SegmentedColumn
import com.sukisu.nova.ui.component.material.SegmentedListItem
import com.sukisu.nova.ui.component.miuix.AppCard
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos

/**
 * RootSentinel seam (v2.22.80): "switch to the real manager"
 * entry inside the SukiSU settings. The SukiSU shell is a
 * seam (not a real manager); the bundled vivoksu manager is
 * one tap away through ManagerBridge — launched when
 * installed, installed from the bundled copy when not.
 * Appended next to the RS privacy card in both pagers.
 */
@Composable
fun RsManagerCardMiuix() {
    AppCard(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        BasicComponent(
            title = "切换到管理器",
            summary = "打开 vivo 版 KernelSU 管理器，未装时自动安装内置副本",
            onClick = { ManagerBridge.onSwitch?.invoke() },
            endActions = {
                MiuixIcon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
        )
    }
}

@Composable
fun RsManagerCardMaterial() {
    SegmentedColumn(
        modifier = Modifier.padding(top = 12.dp),
        content = listOf(
            {
                SegmentedListItem(
                    onClick = { ManagerBridge.onSwitch?.invoke() },
                    headlineContent = { Text("切换到管理器") },
                    supportingContent = { Text("打开 vivo 版 KernelSU 管理器，未装时自动安装内置副本") },
                    leadingContent = { Icon(Icons.Filled.AdminPanelSettings, null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                )
            },
        ),
    )
}
