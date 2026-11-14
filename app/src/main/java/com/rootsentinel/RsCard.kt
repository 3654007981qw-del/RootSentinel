package com.rootsentinel

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults

/**
 * 带内边距的 Miuix Card。
 *
 * ## 为什么必须有它
 *
 * Miuix 的 `CardDefaults.InsideMargin` 是 `PaddingValues(0.dp)`（Card.kt:182），
 * 而 `BasicCard` 内部是 `Box(...).clip(RoundedCornerShape(16.dp))`（Card.kt:161）。
 *
 * 两者相加 = **内容紧贴卡片边缘，然后被 16dp 圆角裁掉**。
 *
 * v2.5.6 及之前，所有页面都直接写了 `Card { ... }`，
 * 于是贴着边缘的文字被切掉一块：截图里「检测项」「风险项」「UNKNOWN」底部被切平、
 * 「SELinux 与内核」左边被切，**全是这一个原因** ——
 * 跟液态玻璃无关，跟 `includeFontPadding` 也无关。
 *
 * ## 用法
 * - 普通卡片：`RsCard { ... }`（默认 16dp / 14dp）
 * - 行式卡片（内容自带 `Row(height = 48.dp)`）：`RsCard(insideMargin = RowCardMargin)`
 * - 数字小格子：`RsCard(insideMargin = DenseCardMargin)`
 */
@Composable
fun RsCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    insideMargin: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    colors: CardColors = CardDefaults.defaultColors(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        cornerRadius = cornerRadius,
        insideMargin = insideMargin,
        colors = colors,
        content = content,
    )
}

/** 行式卡片：内容自己控制垂直留白（`Row(height = 48.dp)`），只需左右缩进 */
val RowCardMargin = PaddingValues(horizontal = 16.dp, vertical = 0.dp)

/** 紧凑卡片：数字 + 标签这类小格子 */
val DenseCardMargin = PaddingValues(horizontal = 14.dp, vertical = 13.dp)