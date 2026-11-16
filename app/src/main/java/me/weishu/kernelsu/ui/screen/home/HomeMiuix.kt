package me.weishu.kernelsu.ui.screen.home

// RootSentinel adaptation note (v2.22.60): this file is KernelSU's
// HomeMiuix.kt with its screen scaffolding, card layouts and InfoText rows
// preserved; the state bindings read RootSentinel's HomeUiState seam (scan
// verdict + device info) instead of KernelSU's kernel facts, and the
// kernel-only cards (LKM/GKI/install) are replaced by RootSentinel's own
// warning/update cards in the same WarningCard form.

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.WarningLevel
import me.weishu.kernelsu.ui.component.miuix.WarningCard
import me.weishu.kernelsu.ui.component.rebootlistpopup.RebootListPopupMiuix
import me.weishu.kernelsu.ui.theme.LocalEnableBlur
import me.weishu.kernelsu.ui.theme.isInDarkTheme
import me.weishu.kernelsu.ui.util.BlurredBar
import me.weishu.kernelsu.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.isDynamicColor
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun HomePagerMiuix(
    state: HomeUiState,
    actions: HomeActions,
    bottomInnerPadding: Dp,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive || state.sukisuStyle) Color.Transparent else colorScheme.surface
    Scaffold(
        topBar = {
            TopBar(
                scrollBehavior = scrollBehavior,
                backdrop = backdrop,
                barColor = barColor,
            )
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            if (state.sukisuStyle) {
                HomeBackgroundImage(state.homeBgPath)
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(horizontal = 12.dp),
                contentPadding = innerPadding,
                overscrollEffect = null,
            ) {
                item {
                    Column(
                        modifier = Modifier.padding(top = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
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
                        InfoCard(
                            systemInfo = state.systemInfo,
                            modifier = Modifier.fillMaxWidth(),
                            sukisuStyle = state.sukisuStyle,
                        )
                        SupportLinks(
                            onOpenUrl = actions.onOpenUrl,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(
                            Modifier.height(
                                bottomInnerPadding + if (!Natives.isFullFeatured())
                                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() else 0.dp
                            )
                        )
                    }
                }
            }
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
    scrollBehavior: ScrollBehavior,
    backdrop: LayerBackdrop?,
    barColor: Color,
) {
    BlurredBar(backdrop) {
        TopAppBar(
            color = barColor,
            title = stringResource(R.string.app_name),
            actions = {
                RebootListPopupMiuix()
            },
            scrollBehavior = scrollBehavior
        )
    }
}

@Composable
private fun StatusCard(
    state: HomeUiState,
    actions: HomeActions,
) {
    // v2.22.70：SukiSU 沉浸模式下状态卡半透明（SukiSU 本体取 surface 0.88 alpha）
    val cardColors = if (state.sukisuStyle) {
        CardDefaults.defaultColors(color = colorScheme.surfaceContainer.copy(alpha = 0.88f))
    } else {
        CardDefaults.defaultColors()
    }
    Column {
        when {
            state.scanning -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = cardColors,
                ) {
                    BasicComponent(
                        title = "正在扫描",
                        summary = "${state.scanModuleName} · 已检 ${state.scanPercent} 项",
                        startAction = {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .size(24.dp),
                            )
                        },
                    )
                }
            }

            !state.hasResult -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = cardColors,
                    onClick = actions.onScanClick,
                    pressFeedbackType = PressFeedbackType.Tilt,
                ) {
                    BasicComponent(
                        title = "尚未检测",
                        summary = "点击开始检测",
                        startAction = {
                            Icon(
                                Icons.Rounded.Search,
                                "尚未检测",
                                modifier = Modifier.padding(end = 6.dp),
                                tint = colorScheme.onBackground,
                            )
                        },
                    )
                }
            }

            state.gradeKind == 4 -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = cardColors,
                    onClick = actions.onReportClick,
                    pressFeedbackType = PressFeedbackType.Tilt,
                ) {
                    BasicComponent(
                        title = state.gradeWord,
                        summary = state.gradeSummary,
                        startAction = {
                            Icon(
                                Icons.Rounded.ErrorOutline,
                                state.gradeWord,
                                modifier = Modifier.padding(end = 6.dp),
                                tint = colorScheme.onBackground,
                            )
                        },
                    )
                }
            }

            else -> {
                // KernelSU「正在工作」状态卡的版式：整卡语义色 + 右下大水印图标 +
                // 左上结论大字与副行。颜色按 RootSentinel 结论分档。
                val dark = isInDarkTheme()
                val cardColor = when (state.gradeKind) {
                    1 -> when {
                        isDynamicColor -> colorScheme.secondaryContainer
                        dark -> Color(0xFF1A3825)
                        else -> Color(0xFFDFFAE4)
                    }
                    2 -> when {
                        isDynamicColor -> colorScheme.tertiaryContainer
                        dark -> Color(0xFF3A2E12)
                        else -> Color(0xFFFFF3C4)
                    }
                    else -> when {
                        isDynamicColor -> colorScheme.errorContainer
                        dark -> Color(0xFF3B1D1D)
                        else -> Color(0xFFF9DEDC)
                    }
                }
                val watermark = when (state.gradeKind) {
                    1 -> Icons.Rounded.CheckCircleOutline
                    2 -> Icons.Rounded.WarningAmber
                    else -> Icons.Rounded.GppBad
                }
                val watermarkTint = when (state.gradeKind) {
                    1 -> if (isDynamicColor) colorScheme.primary.copy(alpha = 0.8f) else Color(0xFF36D167)
                    2 -> Color(0xFFFF9F0A).copy(alpha = 0.8f)
                    else -> if (isDynamicColor) colorScheme.error.copy(alpha = 0.8f) else Color(0xFFD93025)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.defaultColors(color = if (state.sukisuStyle) cardColor.copy(alpha = 0.88f) else cardColor),
                        onClick = actions.onReportClick,
                        pressFeedbackType = PressFeedbackType.Tilt,
                    ) {
                        Box {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .offset(27.dp, 31.dp),
                                contentAlignment = Alignment.BottomEnd
                            ) {
                                Icon(
                                    modifier = Modifier.size(110.dp),
                                    imageVector = watermark,
                                    tint = watermarkTint,
                                    contentDescription = null
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp, 14.dp),
                                contentAlignment = Alignment.TopStart,
                            ) {
                                Column {
                                    Text(
                                        text = state.gradeWord,
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Spacer(Modifier.height(1.dp))
                                    Text(
                                        text = state.gradeSummary,
                                        fontSize = 15.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SupportLinks(
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        ArrowPreference(
            title = "项目仓库",
            summary = "GitHub · AURORA-0711/RootSentinel",
            startAction = {
                Icon(
                    imageVector = Icons.Filled.Code,
                    contentDescription = "项目仓库",
                    modifier = Modifier.padding(end = 6.dp),
                    tint = colorScheme.onBackground,
                )
            },
            onClick = { onOpenUrl("https://github.com/AURORA-0711/RootSentinel") },
        )
        ArrowPreference(
            title = "Gitee 镜像",
            summary = "国内访问更快",
            startAction = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.MenuBook,
                    contentDescription = "Gitee 镜像",
                    modifier = Modifier.padding(end = 6.dp),
                    tint = colorScheme.onBackground,
                )
            },
            onClick = { onOpenUrl("https://gitee.com/AURORAqw/RootSentinel") },
        )
    }
}

@Composable
private fun InfoCard(
    systemInfo: SystemInfo,
    modifier: Modifier = Modifier,
    sukisuStyle: Boolean = false,
) {
    @Composable
    fun InfoText(
        icon: ImageVector,
        title: String,
        content: String,
        bottomPadding: Dp = 24.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = bottomPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(24.dp),
                tint = colorScheme.onSurface,
            )
            Column {
                Text(
                    text = title,
                    fontSize = MiuixTheme.textStyles.headline1.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurface,
                )
                Text(
                    text = content,
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }

    val selinuxDisplay = when (systemInfo.selinuxStatus) {
        "Enforcing" -> stringResource(R.string.selinux_status_enforcing)
        "Permissive" -> stringResource(R.string.selinux_status_permissive)
        "Disabled" -> stringResource(R.string.selinux_status_disabled)
        else -> stringResource(R.string.selinux_status_unknown)
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = if (sukisuStyle) {
                CardDefaults.defaultColors(color = colorScheme.surfaceContainer.copy(alpha = 0.88f))
            } else {
                CardDefaults.defaultColors()
            },
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                InfoText(
                    icon = Icons.Filled.Tag,
                    title = stringResource(R.string.home_manager_version),
                    content = systemInfo.appVersion,
                )
                InfoText(
                    icon = Icons.Filled.DeveloperBoard,
                    title = stringResource(R.string.home_kernel),
                    content = systemInfo.kernelVersion,
                )
                InfoText(
                    icon = Icons.Filled.Smartphone,
                    title = stringResource(R.string.home_device_model),
                    content = systemInfo.deviceModel,
                )
                InfoText(
                    icon = Icons.Filled.Fingerprint,
                    title = stringResource(R.string.home_fingerprint),
                    content = systemInfo.fingerprint,
                    bottomPadding = 0.dp,
                )
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = if (sukisuStyle) {
                CardDefaults.defaultColors(color = colorScheme.surfaceContainer.copy(alpha = 0.88f))
            } else {
                CardDefaults.defaultColors()
            },
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                InfoText(
                    icon = Icons.Filled.Security,
                    title = stringResource(R.string.home_selinux_status),
                    content = selinuxDisplay,
                    bottomPadding = 0.dp,
                )
            }
        }
    }
}
