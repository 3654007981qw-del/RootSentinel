package com.sukisu.nova.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.sukisu.nova.ui.component.bottombar.MainPagerState

/**
 * RootSentinel seam: upstream defines this local inside its MainActivity
 * (which RootSentinel does not vendor — the RS shell provides the value).
 * Definition copied verbatim.
 */
val LocalMainPagerState = staticCompositionLocalOf<MainPagerState> { error("LocalMainPagerState not provided") }
