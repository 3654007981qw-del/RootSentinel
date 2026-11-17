package com.sukisu.nova.ui.screen.about

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sukisu.nova.Natives
import com.sukisu.nova.R
import com.sukisu.nova.ui.component.material.ExpressiveScaffold
import com.sukisu.nova.ui.component.material.SegmentedColumn
import com.sukisu.nova.ui.component.material.SegmentedListItem
import com.sukisu.nova.ui.component.material.SegmentedSwitchItem
import com.sukisu.nova.ui.component.material.TonalCard
import com.sukisu.nova.ui.component.material.TopBarBackButton
import com.sukisu.nova.ui.component.material.expressiveTopAppBarColors
import com.sukisu.nova.ui.screen.appicon.BuiltinAppIconPreview

@Composable
fun AboutScreenMaterial(
    state: AboutUiState,
    actions: AboutScreenActions,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val isKsuValid = remember { Natives.isManager }

    ExpressiveScaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(state.title) },
                navigationIcon = {
                    TopBarBackButton(onClick = actions.onBack)
                },
                colors = expressiveTopAppBarColors(),
                scrollBehavior = scrollBehavior
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
        ) {
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 240.dp)
                            .padding(24.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color.White)
                        ) {
                            BuiltinAppIconPreview(
                                icon = state.icon,
                                modifier = Modifier.fillMaxSize(),
                                foregroundOnly = true,
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = state.appName,
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Black,
                            )
                            Text(
                                text = state.versionName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
            item {
                SegmentedColumn(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    item {
                        SegmentedSwitchItem(
                            icon = Icons.Filled.SystemUpdate,
                            title = stringResource(id = R.string.sukisu_settings_check_update),
                            summary = stringResource(id = R.string.sukisu_settings_check_update_summary),
                            checked = state.checkUpdate,
                            onCheckedChange = actions.onSetCheckUpdate,
                        )
                    }
                    // RootSentinel seam (v2.22.78): upstream's
                    // "check CI builds" switch removed — RS has
                    // no CI-build update channel; its checker
                    // follows GitHub releases only.
                    item(visible = isKsuValid) {
                        SegmentedSwitchItem(
                            icon = Icons.Filled.SystemUpdateAlt,
                            title = stringResource(id = R.string.sukisu_settings_module_check_update),
                            summary = stringResource(id = R.string.sukisu_settings_check_update_summary),
                            checked = state.checkModuleUpdate,
                            onCheckedChange = actions.onSetCheckModuleUpdate,
                        )
                    }
                }
            }
            item {
                // RootSentinel seam (v2.22.79): manual update check.
                // Upstream's manual entry is its home update card,
                // which never renders in RS; without this row the
                // SukiSU shell could only auto-check at launch.
                SegmentedColumn(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    content = listOf(
                        {
                            SegmentedListItem(
                                onClick = { com.rootsentinel.data.UpdateBridge.onCheckManual?.invoke() },
                                headlineContent = { Text("检查更新") },
                                supportingContent = { Text("当前版本 ${state.versionName}，点按立即检查新版") },
                                leadingContent = { Icon(Icons.Filled.SystemUpdateAlt, null) },
                                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                            )
                        }
                    )
                )
            }
            item {
                SegmentedColumn(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    content = state.links.map { linkInfo ->
                        {
                            SegmentedListItem(
                                onClick = { actions.onOpenLink(linkInfo.url) },
                                headlineContent = { Text(linkInfo.fullText) }
                            )
                        }
                    }
                )
            }
            // RootSentinel seam (v2.22.78): upstream's contributor
            // wall + name wordmark (SukiSU's 22 contributors)
            // replaced with RS's own credits card.
            item {
                TonalCard(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "开发与维护",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "RootSentinel 由 AURORA 开发与维护，检测引擎与数据均属本项目。界面移植自 KernelSU 与 SukiSU-Nova，在此致谢。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                Spacer(
                    Modifier.height(
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                            WindowInsets.captionBar.asPaddingValues().calculateBottomPadding()
                    )
                )
            }
        }
    }
}
