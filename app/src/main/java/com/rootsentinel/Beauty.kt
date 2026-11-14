package com.rootsentinel

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 美化组件（v2.5.1）
 *
 * 目的：把「一列卡片 + 数字」升级成有层级的界面 —— 环形仪表、渐变 Hero、
 * 带动画的数字、带图标的列表行。全部基于 Miuix + Compose 原生 API。
 */

/** 渐变色数字（数值变化时平滑过渡） */
@Composable
fun AnimatedNumber(
    value: Double,
    fontSize: TextUnit = 34.sp,
    color: Color,
    decimals: Int = 0,
) {
    val anim by animateFloatAsState(
        targetValue = value.toFloat(),
        animationSpec = tween(durationMillis = 800),
        label = "num",
    )
    val txt = if (decimals == 0) String.format("%.0f", anim) else String.format("%.${decimals}f", anim)
    Text(
        text = txt,
        fontSize = fontSize,
        fontWeight = FontWeight.Medium,
        color = color,
    )
}

/**
 * 环形仪表（Miuix CircularProgressIndicator + 中心文字）
 *
 * 画两层：底层是弱色满环当作轨道，上层是进度环。
 */
@Composable
fun HeroGauge(
    fraction: Float,
    color: Color,
    size: Dp = 176.dp,
    strokeWidth: Dp = 9.dp,
    center: @Composable () -> Unit,
) {
    val anim by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 900),
        label = "gauge",
    )
    Box(
        modifier = Modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        // 注意：不要再给 CircularProgressIndicator 传 size 参数，
        // 它和 fillMaxSize 同时存在会重复约束，导致环被画得比盒子大、位置偏移。
        CircularProgressIndicator(
            progress = 1f,
            modifier = Modifier.fillMaxSize(),
            colors = ProgressIndicatorDefaults.progressIndicatorColors(
                foregroundColor = color.copy(alpha = 0.12f),
                backgroundColor = Color.Transparent,
            ),
            strokeWidth = strokeWidth,
        )
        CircularProgressIndicator(
            progress = anim,
            modifier = Modifier.fillMaxSize(),
            colors = ProgressIndicatorDefaults.progressIndicatorColors(
                foregroundColor = color,
                backgroundColor = Color.Transparent,
            ),
            strokeWidth = strokeWidth,
        )
        center()
    }
}

/** 进度条（用 Miuix 的实现，带背景色） */
@Composable
fun MiuixProgress(fraction: Float, color: Color, height: Dp = 8.dp) {
    val anim by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 500),
        label = "bar",
    )
    top.yukonga.miuix.kmp.basic.LinearProgressIndicator(
        progress = anim,
        modifier = Modifier.fillMaxWidth(),
        colors = ProgressIndicatorDefaults.progressIndicatorColors(
            foregroundColor = color,
            backgroundColor = color.copy(alpha = 0.12f),
        ),
        height = height,
    )
}

/** 带图标的列表行 */
@Composable
fun IconRow(
    icon: ImageVector,
    title: String,
    sub: String = "",
    value: String = "",
    tint: Color = MiuixTheme.colorScheme.primary,
    showChevron: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val c = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .let { m -> if (onClick != null) m.clickable { onClick() } else m },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 图标底：浅色圆角方块，Miuix 常用的列表图标容器
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(17.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(title, fontSize = 15.sp, color = c.onBackground)
            if (sub.isNotEmpty()) {
                Text(sub, fontSize = 12.sp, color = c.onBackgroundVariant)
            }
        }
        if (value.isNotEmpty()) {
            Text(value, fontSize = 14.sp, color = c.onBackgroundVariant)
        }
        if (showChevron) {
            Spacer(Modifier.width(6.dp))
            Text("›", fontSize = 18.sp, color = c.onBackgroundVariant)
        }
    }
}

/**
 * Hero 卡背景：从主色淡起，向右上淡出
 */
@Composable
fun HeroBackground(tint: Color, radius: Dp = 26.dp): Modifier {
    val c = MiuixTheme.colorScheme
    return Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(radius))
        .background(
            Brush.linearGradient(
                listOf(
                    tint.copy(alpha = 0.16f),
                    c.surface,
                    c.surface,
                ),
            ),
        )
}

/** 小标题 + 一行说明（用于分组顶部） */
@Composable
fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = MiuixTheme.colorScheme.onBackgroundVariant,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 6.dp),
    )
}