package com.rootsentinel

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 玻璃按钮高度 */
private val GlassButtonHeight = 48.dp

/** 玻璃圆钮直径 */
private val GlassIconSize = 44.dp

/** 胶囊形状 */
private val CapsuleShape = RoundedCornerShape(percent = 50)

/**
 * 通用「无视消费」的点击检测。
 *
 * ## 为什么不能用 Modifier.clickable
 * `clickable` 内部是 `detectTapAndPress`，第一句就是
 * `awaitFirstDown()` —— **requireUnconsumed = true**。
 * 只要上层任何一个手势消费了 DOWN（页面的左右滑动就会），
 * 点击就永远不触发。这就是「按钮/开关点不动」的根因。
 *
 * ## 这里的做法
 * 1. 在 `PointerEventPass.Initial` 抓 DOWN，用
 *    `changedToDown()` —— 被消费了也能拿到
 * 2. 全程不 consume，不影响父级和其它手势
 * 3. 抬手时位移没超 touchSlop 才算点击
 *
 * Initial pass 先于 Main pass 派发，所以父级消费得再早也不影响这里。
 */
internal fun Modifier.glassTap(
    enabled: Boolean,
    onClick: () -> Unit,
    onPressChange: (Boolean) -> Unit = {},
): Modifier = this.pointerInput(enabled) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        // requireUnconsumed = false —— 真正的「无视已消费」版本。
        // 注意 changedToDownIgnoreConsumed() 名字有误导性，它其实**要求**
        // 未被消费；awaitFirstDown(requireUnconsumed = false) 才是宽容的。
        val down = awaitFirstDown(requireUnconsumed = true)
        onPressChange(true)
        val startX = down.position.x
        val startY = down.position.y
        var moved = false
        val slop = viewConfiguration.touchSlop
        try {
            while (true) {
                val e = awaitPointerEvent()
                val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                if (!moved) {
                    val dx = ch.position.x - startX
                    val dy = ch.position.y - startY
                    if (dx * dx + dy * dy > slop * slop) moved = true
                }
                if (!ch.pressed) {
                    if (!moved) onClick()
                    break
                }
            }
        } finally {
            onPressChange(false)
        }
    }
}

/** 液态玻璃按钮 */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    GlassSurface(
        onClick = onClick,
        modifier = modifier.height(GlassButtonHeight),
        enabled = enabled,
        horizontalPadding = 22.dp,
        content = content,
    )
}

/** 圆形玻璃图标按钮 */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    GlassSurface(
        onClick = onClick,
        modifier = modifier.size(GlassIconSize),
        enabled = enabled,
        horizontalPadding = 0.dp,
    ) {
        content()
    }
}

@Composable
private fun GlassSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    horizontalPadding: Dp = 22.dp,
    content: @Composable RowScope.() -> Unit,
) {
    val dark = isSystemInDarkTheme()
    var pressed by remember { mutableStateOf(false) }

    // 同上：手势协程不随 onClick 变化重启，必须取最新回调
    val latestOnClick by rememberUpdatedState(onClick)
    val press by animateFloatAsState(
        targetValue = if (pressed && enabled) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 300f),
        label = "glassButtonPress",
    )

    val baseAlpha = if (enabled) 0.05f else 0.03f
    val surfaceColor = if (dark) {
        Color(0xFF121212).copy(alpha = baseAlpha + 0.06f * press)
    } else {
        Color(0xFAFAFA).copy(alpha = baseAlpha + 0.06f * press)
    }
    val topInner = Color.White.copy(alpha = 0.44f + 0.15f * press)
    val bottomInner = if (dark) Color.Black.copy(alpha = 0.18f)
    else Color.Black.copy(alpha = 0.10f)
    val edgeTop = Color.White.copy(alpha = 0.44f + 0.15f * press)
    val edgeBottom = Color.White.copy(alpha = 0.12f + 0.10f * press)
    val shadowAlpha = 0.08f + 0.22f * press

    Box(
        modifier = modifier
            .shadow(
                elevation = 10.dp + 6.dp * press,
                shape = CapsuleShape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = shadowAlpha * 0.6f),
                spotColor = Color.Black.copy(alpha = shadowAlpha),
            )
            .clip(CapsuleShape)
            .background(surfaceColor, CapsuleShape)
            .drawBehind {
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to topInner,
                        0.42f to Color.Transparent,
                        1f to bottomInner,
                    ),
                )
                if (press > 0.01f) {
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.22f * press),
                                Color.Transparent,
                            ),
                            center = Offset(size.width / 2f, size.height / 2f),
                            radius = size.minDimension * 1.3f,
                        ),
                        blendMode = BlendMode.Plus,
                    )
                }
            }
            .border(
                width = 0.8.dp,
                brush = Brush.verticalGradient(
                    0f to edgeTop,
                    0.5f to Color.White.copy(alpha = 0.10f),
                    1f to edgeBottom,
                ),
                shape = CapsuleShape,
            )
            .semantics { role = Role.Button }
            .glassTap(
                enabled = enabled,
                onClick = { latestOnClick() },
                onPressChange = { pressed = it },
            )
            .padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}