package com.rootsentinel

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** 轨道尺寸 */
private val TrackW = 52.dp
private val TrackH = 32.dp

/** 滑块直径 + 内边距 */
private val KnobD = 26.dp
private val TrackPad = 3.dp

/**
 * 液态玻璃风格的开关。
 *
 * 点击检测走 [glassTap]：在 `PointerEventPass.Initial` 抓 DOWN，
 * 用 `changedToDown()`，所以上层页面滑动消费了 DOWN 也不影响。
 * 不能用 `toggleable` / `clickable` —— 它们内部 `awaitFirstDown()`
 * 要求 DOWN 未被消费，正是「开关点不动」的原因。
 *
 * 语义上仍然是一个标准 Switch（`toggleableState`），无障碍和
 * `uiautomator` 都能读到开关状态。
 */
@Composable
fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val dark = isSystemInDarkTheme()
    var pressed by remember { mutableStateOf(false) }

    // ★ 关键：手势协程不会因为 checked 变化而重启，
    //   所以必须用 rememberUpdatedState 把最新值搬进来，
    //   否则 onClick 里读到的永远是第一次组合时的 checked。
    val latestChecked by rememberUpdatedState(checked)
    val latestOnChange by rememberUpdatedState(onCheckedChange)

    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 620f),
        label = "glassSwitch",
    )
    val pressAmt by animateFloatAsState(
        targetValue = if (pressed && enabled) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "glassSwitchPress",
    )

    val onColor = Color(0xFF0A84FF)
    val offColor = if (dark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.14f)
    val trackColor = lerp(offColor, onColor, progress).let {
        if (enabled) it else it.copy(alpha = it.alpha * 0.5f)
    }
    val edgeAlpha = 0.10f + 0.14f * progress + 0.10f * pressAmt

    Box(
        modifier = modifier
            .size(width = TrackW, height = TrackH)
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(percent = 50),
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.10f),
                spotColor = Color.Black.copy(alpha = 0.16f),
            )
            .clip(RoundedCornerShape(percent = 50))
            .background(trackColor, RoundedCornerShape(percent = 50))
            .border(
                width = 0.8.dp,
                brush = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.34f + edgeAlpha),
                    0.5f to Color.White.copy(alpha = 0.10f),
                    1f to Color.White.copy(alpha = 0.06f),
                ),
                shape = RoundedCornerShape(percent = 50),
            )
            .semantics {
                role = Role.Switch
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
            }
            .glassTap(
                enabled = enabled,
                onClick = { latestOnChange(!latestChecked) },
                onPressChange = { pressed = it },
            )
            .padding(TrackPad),
    ) {
        val maxOffset = TrackW - KnobD - TrackPad * 2
        val knobScale = 1f - 0.06f * pressAmt
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset((maxOffset * progress).toPx().roundToInt(), 0) }
                .size(KnobD * knobScale)
                .shadow(
                    elevation = 4.dp,
                    shape = CircleShape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.14f),
                    spotColor = Color.Black.copy(alpha = 0.20f),
                )
                .clip(CircleShape)
                .background(Color.White, CircleShape),
        )
    }
}