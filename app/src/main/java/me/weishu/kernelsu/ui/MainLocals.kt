package me.weishu.kernelsu.ui

import androidx.compose.runtime.staticCompositionLocalOf
import me.weishu.kernelsu.ui.component.bottombar.MainPagerState

// Copied from KernelSU MainActivity.kt (the local itself; the pager host is
// RootSentinel's MainActivity).
val LocalMainPagerState = staticCompositionLocalOf<MainPagerState> { error("LocalMainPagerState not provided") }
