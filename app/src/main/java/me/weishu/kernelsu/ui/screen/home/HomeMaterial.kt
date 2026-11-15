package me.weishu.kernelsu.ui.screen.home

// RootSentinel adaptation note (v2.22.60): KernelSU's HomeMaterial.kt with
// its ExpressiveScaffold / Surface+ListItem status card / SegmentedColumn
// info rows preserved; bindings read RootSentinel's HomeUiState seam.

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.WarningLevel
import me.weishu.kernelsu.ui.component.material.ExpressiveScaffold
import me.weishu.kernelsu.ui.component.material.SegmentedColumn
import me.weishu.kernelsu.ui.component.material.SegmentedListItem
import me.weishu.kernelsu.ui.component.material.TonalCard
import me.weishu.kernelsu.ui.component.material.expressiveTopAppBarColors
import me.weishu.kernelsu.ui.component.rebootlistpopup.RebootListPopup

@Composable
fun HomePagerMaterial(
    state: HomeUiState,
    actions: HomeActions,
    bottomInnerPadding: Dp,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    ExpressiveScaffold(
        topBar = { TopBar(scrollBehavior = scrollBehavior) },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            if (state.checkUpdateEnabled) {
                UpdateCard(state = state, actions = actions)
            }
            if (state.showIncompleteWarning) {
                WarningCard(
                    "上次检测未能完成，结论可信度有限，建议重新检测。",
                    level = WarningLevel.Notice,
                )
            }
            if (state.showHidingWarning) {
                WarningCard("检测到隐藏的 Root 痕迹：提权存在且被主动隐藏。")
            }
            StatusCard(
                state = state,
                actions = actions,
            )
            InfoCard(systemInfo = state.systemInfo)
            SupportLinks(onOpenUrl = actions.onOpenUrl)
            Spacer(
                Modifier.height(
                    bottomInnerPadding + if (!Natives.isFullFeatured())
                        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() else 0.dp
                )
            )
        }
    }
}

@Composable
private fun UpdateCard(
    state: HomeUiState,
    actions: HomeActions,
) {
    AnimatedVisibility(
        visible = state.hasUpdate,
        enter = fadeIn() + expandVertically(),
        exit = shrinkVertically() + fadeOut()
    ) {
        WarningCard(
            message = stringResource(id = R.string.new_version_available, state.latestVersionName),
            level = WarningLevel.Notice,
            onClick = actions.onUpdateClick,
        )
    }
}

@Composable
private fun TopBar(
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
    LargeFlexibleTopAppBar(
        title = { Text(stringResource(R.string.app_name)) },
        actions = { RebootListPopup() },
        colors = expressiveTopAppBarColors(),
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        scrollBehavior = scrollBehavior
    )
}

@Composable
private fun StatusCard(
    state: HomeUiState,
    actions: HomeActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
        val containerColor = when {
            state.scanning -> MaterialTheme.colorScheme.surfaceContainer
            !state.hasResult -> MaterialTheme.colorScheme.surfaceContainer
            state.gradeKind == 1 -> MaterialTheme.colorScheme.secondaryContainer
            state.gradeKind == 2 -> MaterialTheme.colorScheme.tertiaryContainer
            state.gradeKind == 4 -> MaterialTheme.colorScheme.surfaceContainer
            else -> MaterialTheme.colorScheme.errorContainer
        }
        val contentColor = MaterialTheme.colorScheme.contentColorFor(containerColor)

        val statusIcon = when {
            state.scanning -> null
            !state.hasResult -> Icons.Rounded.Search
            state.gradeKind == 1 -> Icons.Rounded.CheckCircle
            state.gradeKind == 2 -> Icons.Rounded.Warning
            state.gradeKind == 4 -> Icons.Rounded.Block
            else -> Icons.Rounded.GppBad
        }
        val statusTitle = when {
            state.scanning -> "正在扫描"
            !state.hasResult -> "尚未检测"
            else -> state.gradeWord
        }
        val statusSummary = when {
            state.scanning -> "${state.scanModuleName} · 已检 ${state.scanPercent} 项"
            !state.hasResult -> "点击开始检测"
            else -> state.gradeSummary
        }
        val onClick: (() -> Unit)? = when {
            state.scanning -> null
            !state.hasResult -> actions.onScanClick
            else -> actions.onReportClick
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = containerColor,
            contentColor = contentColor,
            shape = MaterialTheme.shapes.large,
            onClick = onClick ?: {},
        ) {
            ListItem(
                modifier = Modifier,
                leadingContent = {
                    if (statusIcon != null) {
                        Icon(statusIcon, contentDescription = statusTitle)
                    } else {
                        CircularProgressIndicator()
                    }
                },
                trailingContent = null,
                overlineContent = null,
                supportingContent = {
                    Text(
                        text = statusSummary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                verticalAlignment = Alignment.CenterVertically,
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                    contentColor = contentColor,
                    leadingContentColor = contentColor,
                    trailingContentColor = contentColor,
                    supportingContentColor = contentColor.copy(alpha = 0.7f)
                ),
                elevation = ListItemDefaults.elevation(),
                content = {
                    Text(
                        text = statusTitle,
                        style = MaterialTheme.typography.titleMediumEmphasized
                    )
                },
            )
        }
    }
}

@Composable
private fun WarningCard(
    message: String,
    level: WarningLevel = WarningLevel.Error,
    onClick: (() -> Unit)? = null
) {
    val containerColor = when (level) {
        WarningLevel.Error -> MaterialTheme.colorScheme.errorContainer
        WarningLevel.Notice -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val content = @Composable {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.contentColorFor(containerColor)
            )
        }
    }
    if (onClick != null) {
        TonalCard(containerColor = containerColor, onClick = onClick, content = content)
    } else {
        TonalCard(containerColor = containerColor, content = content)
    }
}

@Composable
private fun SupportLinks(
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SegmentedColumn(modifier = modifier.fillMaxWidth()) {
        item {
            SegmentedListItem(
                onClick = { onOpenUrl("https://github.com/AURORA-0711/RootSentinel") },
                headlineContent = { Text("项目仓库") },
                supportingContent = { Text("GitHub · AURORA-0711/RootSentinel") },
                leadingContent = {
                    Icon(Icons.Filled.Code, "项目仓库")
                },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
            )
        }
        item {
            SegmentedListItem(
                onClick = { onOpenUrl("https://gitee.com/AURORAqw/RootSentinel") },
                headlineContent = { Text("Gitee 镜像") },
                supportingContent = { Text("国内访问更快") },
                leadingContent = {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, "Gitee 镜像")
                },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
            )
        }
    }
}

@Composable
private fun InfoCard(
    systemInfo: SystemInfo,
    modifier: Modifier = Modifier,
) {
    @Composable
    fun InfoCardItem(
        icon: ImageVector,
        label: String,
        content: String,
        modifier: Modifier = Modifier,
    ) {
        SegmentedListItem(
            modifier = modifier,
            headlineContent = { Text(text = label, style = MaterialTheme.typography.bodyLarge) },
            leadingContent = { Icon(imageVector = icon, contentDescription = label) },
            supportingContent = {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
    }

    val selinuxDisplay = when (systemInfo.selinuxStatus) {
        "Enforcing" -> stringResource(R.string.selinux_status_enforcing)
        "Permissive" -> stringResource(R.string.selinux_status_permissive)
        "Disabled" -> stringResource(R.string.selinux_status_disabled)
        else -> stringResource(R.string.selinux_status_unknown)
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        SegmentedColumn(modifier = Modifier.fillMaxWidth()) {
            item {
                InfoCardItem(
                    icon = Icons.Filled.Tag,
                    label = stringResource(R.string.home_manager_version),
                    content = systemInfo.appVersion,
                )
            }
            item {
                InfoCardItem(
                    icon = Icons.Filled.DeveloperBoard,
                    label = stringResource(R.string.home_kernel),
                    content = systemInfo.kernelVersion,
                )
            }
            item {
                InfoCardItem(
                    icon = Icons.Filled.Smartphone,
                    label = stringResource(R.string.home_device_model),
                    content = systemInfo.deviceModel,
                )
            }
            item {
                InfoCardItem(
                    icon = Icons.Filled.Fingerprint,
                    label = stringResource(R.string.home_fingerprint),
                    content = systemInfo.fingerprint,
                )
            }
        }
        SegmentedColumn(modifier = Modifier.fillMaxWidth()) {
            item {
                InfoCardItem(
                    icon = Icons.Filled.Security,
                    label = stringResource(R.string.home_selinux_status),
                    content = selinuxDisplay,
                )
            }
        }
    }
}
