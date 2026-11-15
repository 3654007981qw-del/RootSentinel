package com.rootsentinel

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 界面模式（v2.22.59）：结构照 KernelSU 管理器——同一套页面逻辑，
 * 两套视觉实现（Miuix / Material），设置里可切换，默认 Miuix。
 * 与 KernelSU 的 UiMode 同构（它的 LocalUiMode 也是 staticCompositionLocalOf）。
 */
enum class UiMode(val value: String) {
    Miuix("miuix"),
    Material("material");

    companion object {
        fun fromValue(value: String): UiMode =
            if (value == Material.value) Material else Miuix
    }
}

val LocalUiMode = staticCompositionLocalOf { UiMode.Miuix }

/**
 * 语义色板：页面只认语义色，不直接读某个主题的 colorScheme。
 * RootApp 按当前模式与深浅，从 MiuixTheme / MaterialTheme 的
 * colorScheme 映射出同一组语义色注入，页面因此只写一份。
 */
data class RsPalette(
    val background: Color,
    val card: Color,
    val onBackground: Color,
    val onVariant: Color,
    val primary: Color,
    val divider: Color,
)

val LocalRsPalette = compositionLocalOf {
    RsPalette(
        background = Color(0xFFF2F2F7),
        card = Color.White,
        onBackground = Color(0xFF1C1C1E),
        onVariant = Color(0xFF8A8A8E),
        primary = Color(0xFF0A84FF),
        divider = Color(0x1F000000),
    )
}

/** 状态语义色（与主题无关，两模式共用；沿用既有 gradeColor 的色值） */
object RsStatusColors {
    val Success = Color(0xFF34C759)
    val Danger = Color(0xFFFF3B30)
    val Warning = Color(0xFFFF9500)
    val Medium = Color(0xFFFFCC00)
}
