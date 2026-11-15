package com.rootsentinel

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.material3.NavigationBarItem
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack

import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 模式感知组件层（v2.22.59）：版式与交互逻辑按 KernelSU 管理器——
 * Scaffold + TopAppBar + 卡片列表 + 状态卡/信息卡/警告卡 + 浮动底栏；
 * 每个组件按 LocalUiMode 渲染 Miuix 或 Material 两种外观，页面只写一份。
 * （KernelSU 本体是每页写 Miuix/Material 两个文件、共享 UiState；
 * 这里用组件层收敛，页面结构与它逐项对应。）
 */

/** 页面底部留白（浮动底栏悬浮在内容之上，主标签页需要让出它的高度） */
val LocalBottomPad = compositionLocalOf { 20.dp }

/** 页面滚动容器：横向 16dp 页边距，与 KernelSU 列表页一致的节奏 */
@Composable
fun PageScroll(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = LocalBottomPad.current),
        content = content,
    )
}

// ------------------------------------------------------------ 文本

enum class RsTextStyle { TitleL, TitleM, Body, BodyS, Label, Caption }

@Composable
fun RsText(
    text: String,
    style: RsTextStyle = RsTextStyle.Body,
    color: Color? = null,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
) {
    val p = LocalRsPalette.current
    val (size, defaultColor, defaultWeight) = when (style) {
        RsTextStyle.TitleL -> Triple(22.sp, p.onBackground, FontWeight.Medium)
        RsTextStyle.TitleM -> Triple(17.sp, p.onBackground, FontWeight.Medium)
        RsTextStyle.Body -> Triple(15.sp, p.onBackground, FontWeight.Normal)
        RsTextStyle.BodyS -> Triple(14.sp, p.onBackground, FontWeight.Normal)
        RsTextStyle.Label -> Triple(13.sp, p.onVariant, FontWeight.Normal)
        RsTextStyle.Caption -> Triple(12.sp, p.onVariant, FontWeight.Normal)
    }
    val finalColor = color ?: defaultColor
    val finalWeight = fontWeight ?: defaultWeight
    when (LocalUiMode.current) {
        UiMode.Miuix -> top.yukonga.miuix.kmp.basic.Text(
            text = text,
            fontSize = size,
            color = finalColor,
            fontWeight = finalWeight,
            maxLines = maxLines,
            overflow = overflow,
            softWrap = softWrap,
            modifier = modifier,
        )
        UiMode.Material -> androidx.compose.material3.Text(
            text = text,
            fontSize = size,
            color = finalColor,
            fontWeight = finalWeight,
            maxLines = maxLines,
            overflow = overflow,
            softWrap = softWrap,
            modifier = modifier,
        )
    }
}

// ------------------------------------------------------------ 卡片

/** 行式卡片的内容边距：行高由内容自己控制，只留左右 */
val RowCardMargin = PaddingValues(horizontal = 16.dp, vertical = 0.dp)

@Composable
fun RsCard(
    modifier: Modifier = Modifier,
    insideMargin: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    containerColor: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalRsPalette.current
    when (LocalUiMode.current) {
        UiMode.Miuix -> top.yukonga.miuix.kmp.basic.Card(
            modifier = if (onClick != null) modifier.clickable { onClick() } else modifier,
            cornerRadius = 16.dp,
            insideMargin = insideMargin,
            colors = if (containerColor != null) {
                CardDefaults.defaultColors(color = containerColor)
            } else {
                CardDefaults.defaultColors()
            },
            content = content,
        )
        UiMode.Material -> {
            val cardColors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = containerColor ?: p.card,
            )
            if (onClick != null) {
                androidx.compose.material3.Card(
                    modifier = modifier,
                    shape = RoundedCornerShape(20.dp),
                    colors = cardColors,
                    onClick = onClick,
                ) {
                    Column(Modifier.padding(insideMargin), content = content)
                }
            } else {
                androidx.compose.material3.Card(
                    modifier = modifier,
                    shape = RoundedCornerShape(20.dp),
                    colors = cardColors,
                ) {
                    Column(Modifier.padding(insideMargin), content = content)
                }
            }
        }
    }
}

/** 分组小标题（KernelSU 列表页的 SmallTitle 节奏） */
@Composable
fun RsSectionTitle(text: String) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> top.yukonga.miuix.kmp.basic.SmallTitle(text)
        UiMode.Material -> RsText(
            text,
            RsTextStyle.Label,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
        )
    }
}

/** 细分隔线 */
@Composable
fun RsDivider(indent: Boolean = false) {
    val p = LocalRsPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = if (indent) 16.dp else 0.dp)
            .height(1.dp)
            .background(p.divider),
    )
}

// ------------------------------------------------------------ 行

/** 「标签 — 值」行（设备/详情页用） */
@Composable
fun RowKV(name: String, value: String, valueColor: Color? = null) {
    val p = LocalRsPalette.current
    Row(
        Modifier.fillMaxWidth().height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RsText(name, RsTextStyle.Body, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        RsText(
            value, RsTextStyle.BodyS,
            color = valueColor ?: p.onVariant,
            maxLines = 1, softWrap = false,
        )
    }
}

/**
 * 信息行（KernelSU 首页 InfoCard 的 InfoText 版式）：
 * 左图标（主题色），右两行——小标签 + 值。
 */
@Composable
fun RsInfoRow(
    icon: ImageVector,
    label: String,
    value: String,
    valueColor: Color? = null,
) {
    val p = LocalRsPalette.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = null,
            tint = p.primary,
            modifier = Modifier.size(21.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            RsText(label, RsTextStyle.Caption)
            RsText(value, RsTextStyle.BodyS, color = valueColor ?: p.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 带图标的导航行（设置页/链接卡用，图标放在浅色圆角方块里，与旧 IconRow 同版式） */
@Composable
fun RsNavRow(
    icon: ImageVector,
    title: String,
    sub: String = "",
    value: String = "",
    tint: Color? = null,
    showChevron: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val p = LocalRsPalette.current
    val iconTint = tint ?: p.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .let { m -> if (onClick != null) m.clickable { onClick() } else m },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(iconTint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(17.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            RsText(title, RsTextStyle.Body)
            if (sub.isNotEmpty()) RsText(sub, RsTextStyle.Caption)
        }
        if (value.isNotEmpty()) {
            RsText(value, RsTextStyle.BodyS, color = p.onVariant)
        }
        if (showChevron) {
            RsText("›", RsTextStyle.TitleM, color = p.onVariant)
        }
    }
}

/** 开关行 */
@Composable
fun RsSwitchRow(
    title: String,
    sub: String = "",
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(if (sub.isEmpty()) 52.dp else 60.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            RsText(title, RsTextStyle.Body)
            if (sub.isNotEmpty()) RsText(sub, RsTextStyle.Caption)
        }
        when (LocalUiMode.current) {
            UiMode.Miuix -> top.yukonga.miuix.kmp.basic.Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
            UiMode.Material -> androidx.compose.material3.Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}

/** 单选行（外观设置里选模式/深浅用）：标题 + 说明 + 选中勾 */
@Composable
fun RsRadioRow(
    title: String,
    sub: String = "",
    selected: Boolean,
    onClick: () -> Unit,
) {
    val p = LocalRsPalette.current
    Row(
        Modifier.fillMaxWidth().height(if (sub.isEmpty()) 52.dp else 60.dp).clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            RsText(title, RsTextStyle.Body)
            if (sub.isNotEmpty()) RsText(sub, RsTextStyle.Caption)
        }
        if (selected) {
            RsText("✓", RsTextStyle.TitleM, color = p.primary)
        }
    }
}

// ------------------------------------------------------------ 标签 / 按钮 / 进度

/** 状态小标签（胶囊；KernelSU 的 StatusTag 版式） */
@Composable
fun RsTag(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(11.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        RsText(text, RsTextStyle.Caption, color = color, maxLines = 1, softWrap = false)
    }
}

/** 主按钮（整宽，50dp） */
@Composable
fun RsButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> top.yukonga.miuix.kmp.basic.Button(
            onClick = onClick,
            modifier = modifier.height(50.dp),
            enabled = enabled,
        ) {
            top.yukonga.miuix.kmp.basic.Text(text, fontSize = 16.sp)
        }
        UiMode.Material -> androidx.compose.material3.Button(
            onClick = onClick,
            modifier = modifier.height(50.dp),
            enabled = enabled,
            shape = RoundedCornerShape(25.dp),
        ) {
            androidx.compose.material3.Text(text, fontSize = 16.sp)
        }
    }
}

/** 细进度条（两模式共用自绘，与主题无关） */
@Composable
fun RsProgress(fraction: Float, color: Color, height: Dp = 8.dp) {
    val p = LocalRsPalette.current
    val anim by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 500),
        label = "bar",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(p.onBackground.copy(alpha = 0.10f)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .let { it }
                .padding(end = 0.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(anim)
                    .height(height)
                    .clip(RoundedCornerShape(height / 2))
                    .background(color),
            )
        }
    }
}

// ------------------------------------------------------------ 警告卡

enum class RsWarningLevel { Notice, Error }

/** 警告卡（KernelSU 首页 WarningCard 版式：淡色底 + 图标 + 文案） */
@Composable
fun RsWarningCard(text: String, level: RsWarningLevel = RsWarningLevel.Notice) {
    val color = when (level) {
        RsWarningLevel.Notice -> RsStatusColors.Warning
        RsWarningLevel.Error -> RsStatusColors.Danger
    }
    RsCard(containerColor = color.copy(alpha = 0.12f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(
                imageVector = if (level == RsWarningLevel.Error) Icons.Filled.Warning else Icons.Filled.Warning,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            RsText(text, RsTextStyle.BodyS, color = color)
        }
    }
}

// ------------------------------------------------------------ 顶栏

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun RsTopBar(
    title: String,
    large: Boolean = false,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val p = LocalRsPalette.current
    when (LocalUiMode.current) {
        UiMode.Miuix -> {
            val c = MiuixTheme.colorScheme
            top.yukonga.miuix.kmp.basic.TopAppBar(
                title = title,
                largeTitle = if (large) title else "",
                color = p.background,
                titleColor = c.onBackground,
                largeTitleColor = c.onBackground,
                navigationIcon = {
                    if (onBack != null) {
                        androidx.compose.material3.IconButton(onClick = onBack) {
                            androidx.compose.material3.Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                                tint = c.onBackground,
                            )
                        }
                    }
                },
                actions = actions,
            )
        }
        UiMode.Material -> {
            val colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                containerColor = p.background,
            )
            if (large) {
                androidx.compose.material3.LargeTopAppBar(
                    title = { androidx.compose.material3.Text(title) },
                    navigationIcon = {
                        if (onBack != null) {
                            androidx.compose.material3.IconButton(onClick = onBack) {
                                androidx.compose.material3.Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                            }
                        }
                    },
                    actions = actions,
                    colors = colors,
                )
            } else {
                androidx.compose.material3.TopAppBar(
                    title = { androidx.compose.material3.Text(title) },
                    navigationIcon = {
                        if (onBack != null) {
                            androidx.compose.material3.IconButton(onClick = onBack) {
                                androidx.compose.material3.Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                            }
                        }
                    },
                    actions = actions,
                    colors = colors,
                )
            }
        }
    }
}

// ------------------------------------------------------------ 底栏

data class RsTab(val page: Page, val label: String, val icon: ImageVector)

@Composable
fun RsBottomBar(tabs: List<RsTab>, current: Page, onSelect: (Page) -> Unit) {
    val p = LocalRsPalette.current
    when (LocalUiMode.current) {
        UiMode.Miuix -> {
            // KernelSU 的浮动底栏：悬浮胶囊，选中项主题色 + 浅色衬底
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 14.dp),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .shadow(10.dp, RoundedCornerShape(30.dp))
                        .clip(RoundedCornerShape(30.dp))
                        .background(p.card)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tabs.forEach { tab ->
                        val selected = tab.page == current
                        Column(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(22.dp))
                                .background(if (selected) p.primary.copy(alpha = 0.12f) else Color.Transparent)
                                .clickable { onSelect(tab.page) }
                                .padding(vertical = 7.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label,
                                tint = if (selected) p.primary else p.onVariant,
                                modifier = Modifier.size(22.dp),
                            )
                            RsText(
                                tab.label,
                                RsTextStyle.Caption,
                                color = if (selected) p.primary else p.onVariant,
                            )
                        }
                    }
                }
            }
        }
        UiMode.Material -> androidx.compose.material3.NavigationBar {
            tabs.forEach { tab ->
                NavigationBarItem(
                    selected = tab.page == current,
                    onClick = { onSelect(tab.page) },
                    icon = {
                        androidx.compose.material3.Icon(tab.icon, contentDescription = tab.label)
                    },
                    label = { androidx.compose.material3.Text(tab.label) },
                )
            }
        }
    }
}
