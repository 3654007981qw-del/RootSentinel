package com.rootsentinel

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.sukisu.nova.ai.api.AiFeatureBridge
import com.sukisu.nova.ai.host.ManagerAiTarget
import com.sukisu.nova.ui.LocalMainPagerState
import com.sukisu.nova.ui.UiMode
import com.sukisu.nova.ui.component.bottombar.BottomBar
import com.sukisu.nova.ui.component.bottombar.MainPagerState
import com.sukisu.nova.ui.component.bottombar.NavigationBadgeState
import com.sukisu.nova.ui.component.bottombar.SideRail
import com.sukisu.nova.ui.component.bottombar.rememberMainPagerState
import com.sukisu.nova.ui.component.bottombar.useNavigationRail
import com.sukisu.nova.ui.navigation3.LocalNavigator
import com.sukisu.nova.ui.navigation3.Navigator
import com.sukisu.nova.ui.navigation3.Route
import com.sukisu.nova.ui.navigation3.rememberNavigator
import com.sukisu.nova.ui.screen.about.AboutScreen
import com.sukisu.nova.ui.screen.account.AccountScreen
import com.sukisu.nova.ui.screen.appicon.AppIconScreen
import com.sukisu.nova.ui.screen.appearanceoverlay.AppearanceOverlayScreen
import com.sukisu.nova.ui.screen.appprofile.AppProfileScreen
import com.sukisu.nova.ui.screen.backgroundcrop.BackgroundCropScreen
import com.sukisu.nova.ui.screen.builtinzygisk.BuiltinZygiskScreen
import com.sukisu.nova.ui.screen.colorpalette.AdaptiveThemeBuilderScreen
import com.sukisu.nova.ui.screen.colorpalette.ColorPaletteScreen
import com.sukisu.nova.ui.screen.customtext.CustomTextScreen
import com.sukisu.nova.ui.screen.dynamicmanagers.DynamicManagersScreen
import com.sukisu.nova.ui.screen.extensions.CpuSpoofScreen
import com.sukisu.nova.ui.screen.extensions.ExtensionsScreen
import com.sukisu.nova.ui.screen.extensions.VersionSpoofScreen
import com.sukisu.nova.ui.screen.flash.FlashScreen
import com.sukisu.nova.ui.screen.home.HomePager
import com.sukisu.nova.ui.screen.install.InstallScreen
import com.sukisu.nova.ui.screen.kernelmodules.KernelModulesScreen
import com.sukisu.nova.ui.screen.kpm.KpmScreen
import com.sukisu.nova.ui.screen.settings.SettingPager
import com.sukisu.nova.ui.screen.settings.tools.ToolsScreen
import com.sukisu.nova.ui.screen.susfs.SuSFSScreen
import com.sukisu.nova.ui.screen.template.AppProfileTemplateScreen
import com.sukisu.nova.ui.screen.templateeditor.TemplateEditorScreen
import com.sukisu.nova.ui.screen.themestore.ThemeStoreScreen
import com.sukisu.nova.ui.screen.themestore.ThemeStoreSourcesScreen
import com.sukisu.nova.ui.screen.umountmanager.UmountManagerScreen
import com.sukisu.nova.ui.theme.AiAssistantMaterialTheme
import com.sukisu.nova.ui.theme.KernelSUTheme
import com.sukisu.nova.ui.theme.LocalAppBackgroundBackdrop
import com.sukisu.nova.ui.theme.LocalBackgroundImagePath
import com.sukisu.nova.ui.theme.LocalAppSettings
import com.sukisu.nova.ui.theme.LocalCardBackgroundAlpha
import com.sukisu.nova.ui.theme.LocalColorMode
import com.sukisu.nova.ui.theme.LocalEnableBlur
import com.sukisu.nova.ui.theme.LocalEnableFloatingBottomBar
import com.sukisu.nova.ui.theme.LocalEnableFloatingBottomBarBlur
import com.sukisu.nova.ui.theme.LocalEnableNavigationBadge
import com.sukisu.nova.ui.theme.LocalEnableProgressiveBlur
import com.sukisu.nova.ui.theme.LocalModuleDescriptionMaxLines
import com.sukisu.nova.ui.theme.MaterialExpressivePalette
import com.sukisu.nova.ui.theme.appearanceImageEffects
import com.sukisu.nova.ui.util.ImageStorage
import com.sukisu.nova.ui.util.LocalCustomTexts
import com.sukisu.nova.ui.util.rememberBlurBackdrop
import com.sukisu.nova.ui.viewmodel.MainActivityViewModel
import com.sukisu.nova.ui.viewmodel.MainPagerConfig
import java.io.File
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PagerGestureNestedScrollConnection
import top.yukonga.miuix.kmp.utils.PagerInterceptionMode
import top.yukonga.miuix.kmp.utils.PagerNavigationSpringSpec
import top.yukonga.miuix.kmp.utils.pagerGestureOverride

/**
 * RootSentinel shell for the SukiSU-Nova UI (v2.22.71). When
 * SettingsStore.uiSkin == "sukisu", RootApp renders this instead of the
 * KernelSU shell. Structure follows SukiSU-Nova's own MainActivity +
 * MainScreen (their Activity class, zip-intent dispatch, splash and the
 * root-feature preload/badge machinery are not vendored); every screen
 * and component below is the vendored upstream one. Pager destinations
 * are RootSentinel's: SukiSU Home (seam-fed data), Report, History,
 * SukiSU Settings.
 */
@Composable
fun SukisuShell(
    rsPageScale: Float,
    reportState: ReportUiState,
    reportActions: ReportActions,
    historyEntries: List<com.rootsentinel.data.HistoryEntry>,
    onClearHistory: () -> Unit,
) {
    val mainViewModel = viewModel<MainActivityViewModel>()
    val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val selectedMainPage by mainViewModel.selectedMainPage.collectAsStateWithLifecycle()
    val appSettings = uiState.appSettings
    val uiMode = uiState.uiMode
    val navigator = rememberNavigator(Route.Main)

    // RootApp already scaled LocalDensity by RS's pageScale; undo that and
    // apply SukiSU's own pageScale instead.
    val outerDensity = LocalDensity.current
    val density = remember(outerDensity, uiState.pageScale, rsPageScale) {
        Density(
            outerDensity.density / rsPageScale * uiState.pageScale,
            outerDensity.fontScale,
        )
    }
    val themedAppSettings = remember(appSettings, uiState.keyColorOverride) {
        uiState.keyColorOverride?.let { appSettings.copy(keyColor = it) } ?: appSettings
    }

    CompositionLocalProvider(
        LocalNavigator provides navigator,
        LocalDensity provides density,
        LocalColorMode provides appSettings.colorMode.value,
        LocalAppSettings provides themedAppSettings,
        LocalEnableBlur provides uiState.enableBlur,
        LocalEnableProgressiveBlur provides uiState.enableProgressiveBlur,
        LocalEnableFloatingBottomBar provides uiState.enableFloatingBottomBar,
        LocalEnableFloatingBottomBarBlur provides uiState.enableFloatingBottomBarBlur,
        LocalEnableNavigationBadge provides uiState.enableNavigationBadge,
        LocalModuleDescriptionMaxLines provides uiState.moduleDescriptionMaxLines,
        LocalBackgroundImagePath provides uiState.customBackgroundUri,
        LocalAppBackgroundBackdrop provides null,
        LocalCardBackgroundAlpha provides (uiState.cardBackgroundAlphaPercent / 100f),
        com.sukisu.nova.ui.LocalUiMode provides uiMode,
        LocalCustomTexts provides uiState.customTexts,
    ) {
        KernelSUTheme(appSettings = themedAppSettings, uiMode = uiMode) {
            val swipeDismiss = if (uiState.enableSwipeDismiss) {
                if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
                    NavSwipeDirection.RightToLeft
                } else {
                    NavSwipeDirection.LeftToRight
                }
            } else {
                NavSwipeDirection.None
            }
            val flashRoute = navigator.current() as? Route.Flash
            var flashBackEnabled by remember(flashRoute) { mutableStateOf(false) }

            val mainScreenEntry: @Composable () -> Unit = {
                SukisuPager(
                    navigator = navigator,
                    initialPage = selectedMainPage,
                    onPageChanged = mainViewModel::setSelectedMainPage,
                    pagerInterceptionMode = uiState.pagerInterceptionMode,
                    reportState = reportState,
                    reportActions = reportActions,
                    historyEntries = historyEntries,
                    onClearHistory = onClearHistory,
                )
            }

            Box(Modifier.fillMaxSize()) {
                if (uiState.customBackgroundUri.isNotEmpty()) {
                    SukisuGlobalBackground(
                        path = uiState.customBackgroundUri,
                        updatedAt = uiState.customBackgroundUpdatedAt,
                        blurPercent = uiState.customBackgroundBlurPercent,
                        scrimPercent = uiState.customBackgroundScrimPercent,
                        backgroundEffects = themedAppSettings.appearance?.background,
                    )
                }
                val hasBg = uiState.customBackgroundUri.isNotEmpty()
                val navDisplay: @Composable () -> Unit = {
                    NavDisplay(
                        backStack = navigator.backStack,
                        onBack = {
                            when (val top = navigator.current()) {
                                is Route.TemplateEditor -> {
                                    if (!top.readOnly) {
                                        navigator.setResult("template_edit", true)
                                    } else {
                                        navigator.pop()
                                    }
                                }
                                else -> navigator.pop()
                            }
                        },
                    ) {
                        entry<Route.Main>(swipeDismiss = swipeDismiss) { mainScreenEntry() }
                        entry<Route.Home>(swipeDismiss = swipeDismiss) { mainScreenEntry() }
                        entry<Route.Settings>(swipeDismiss = swipeDismiss) { mainScreenEntry() }
                        entry<Route.About>(swipeDismiss = swipeDismiss) { AboutScreen() }
                        entry<Route.ColorPalette>(swipeDismiss = swipeDismiss) { key ->
                            ColorPaletteScreen(key.section, key.importUri)
                        }
                        entry<Route.AppearanceOverlay>(swipeDismiss = swipeDismiss) {
                            AppearanceOverlayScreen(onBack = { navigator.pop() })
                        }
                        entry<Route.CustomTexts>(swipeDismiss = swipeDismiss) { CustomTextScreen() }
                        entry<Route.ThemeStore>(swipeDismiss = swipeDismiss) { key ->
                            MaterialExpressivePalette {
                                ThemeStoreScreen(
                                    initialThemeId = key.themeId,
                                    initialSourceBaseUrl = key.sourceBaseUrl,
                                )
                            }
                        }
                        entry<Route.ThemeStoreAdaptiveUpload>(swipeDismiss = swipeDismiss) {
                            MaterialExpressivePalette { ThemeStoreScreen(startAdaptiveUpload = true) }
                        }
                        entry<Route.ThemeStoreCurrentUpload>(swipeDismiss = swipeDismiss) {
                            MaterialExpressivePalette { ThemeStoreScreen(startCurrentUpload = true) }
                        }
                        entry<Route.ThemeStoreSources>(swipeDismiss = swipeDismiss) {
                            MaterialExpressivePalette { ThemeStoreSourcesScreen() }
                        }
                        entry<Route.ThemeAccount>(swipeDismiss = swipeDismiss) {
                            MaterialExpressivePalette { AccountScreen() }
                        }
                        entry<Route.AdaptiveThemeBuilder>(swipeDismiss = swipeDismiss) {
                            MaterialExpressivePalette { AdaptiveThemeBuilderScreen() }
                        }
                        entry<Route.AppIcon>(swipeDismiss = swipeDismiss) { AppIconScreen() }
                        entry<Route.BackgroundCrop>(swipeDismiss = NavSwipeDirection.None) { key ->
                            BackgroundCropScreen(key.sourcePath)
                        }
                        entry<Route.AppProfileTemplate>(swipeDismiss = swipeDismiss) {
                            AppProfileTemplateScreen()
                        }
                        entry<Route.TemplateEditor>(swipeDismiss = swipeDismiss) { key ->
                            TemplateEditorScreen(key.template, key.readOnly)
                        }
                        entry<Route.AppProfile>(swipeDismiss = swipeDismiss) { key ->
                            AppProfileScreen(key.uid)
                        }
                        entry<Route.Install>(swipeDismiss = swipeDismiss) { key ->
                            InstallScreen(preselectedKernelUri = key.preselectedKernelUri)
                        }
                        entry<Route.Flash>(
                            swipeDismiss = if (flashBackEnabled) swipeDismiss else NavSwipeDirection.None,
                        ) { key ->
                            FlashScreen(
                                key.flashIt,
                                onBackEnabledChange = { enabled ->
                                    if (navigator.current() == key) flashBackEnabled = enabled
                                },
                            )
                        }
                        entry<Route.KernelFlash>(swipeDismiss = swipeDismiss) { key ->
                            com.sukisu.nova.ui.kernelFlash.KernelFlashScreen(
                                key.kernelUri,
                                key.selectedSlot,
                                key.kpmPatchEnabled,
                                key.kpmUndoPatch,
                            )
                        }
                        entry<Route.Kpm>(swipeDismiss = swipeDismiss) { KpmScreen() }
                        entry<Route.SuSFS>(swipeDismiss = swipeDismiss) { key -> SuSFSScreen(key.tab) }
                        entry<Route.Tool>(swipeDismiss = swipeDismiss) { ToolsScreen() }
                        entry<Route.UmountManager>(swipeDismiss = swipeDismiss) { UmountManagerScreen() }
                        entry<Route.KernelModules>(swipeDismiss = swipeDismiss) { KernelModulesScreen() }
                        entry<Route.DynamicManagers>(swipeDismiss = swipeDismiss) { DynamicManagersScreen() }
                        entry<Route.Extensions>(swipeDismiss = swipeDismiss) { ExtensionsScreen() }
                        entry<Route.CpuSpoof>(swipeDismiss = swipeDismiss) { CpuSpoofScreen() }
                        entry<Route.VersionSpoof>(swipeDismiss = swipeDismiss) { VersionSpoofScreen() }
                        entry<Route.BuiltinZygisk>(swipeDismiss = swipeDismiss) { BuiltinZygiskScreen() }
                        entry<Route.AiAssistant>(swipeDismiss = swipeDismiss) {
                            if (!AiFeatureBridge.isAvailable) {
                                LaunchedEffect(Unit) { navigator.pop() }
                            } else {
                                AiAssistantMaterialTheme {
                                    AiFeatureBridge.ChatScreen(
                                        onClose = {
                                            if (navigator.current() == Route.AiAssistant) navigator.pop()
                                        },
                                        onOpenSettings = { navigator.push(Route.AiSettings) },
                                        onNavigate = { target ->
                                            when (target) {
                                                is ManagerAiTarget.Tab -> {
                                                    mainViewModel.openMainPage(target.pageIndex, target.section)
                                                    navigator.popUntil { it is Route.Main }
                                                }
                                                is ManagerAiTarget.Push -> navigator.navigateTo(target.route)
                                                else -> Unit
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        entry<Route.AiSettings>(swipeDismiss = swipeDismiss) {
                            if (!AiFeatureBridge.isAvailable) {
                                LaunchedEffect(Unit) { navigator.pop() }
                            } else {
                                AiAssistantMaterialTheme {
                                    AiFeatureBridge.SettingsScreen(onBack = { navigator.pop() })
                                }
                            }
                        }
                    }
                }
                when (uiMode) {
                    UiMode.Material -> androidx.compose.material3.Scaffold(
                        containerColor = if (hasBg) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
                    ) { _ -> navDisplay() }
                    UiMode.Miuix -> Scaffold(
                        containerColor = if (hasBg) Color.Transparent else MiuixTheme.colorScheme.surface,
                    ) { _ -> navDisplay() }
                }
            }
        }
    }
}

/** Adapted from upstream MainActivity's global background layer. */
@Composable
private fun SukisuGlobalBackground(
    path: String,
    updatedAt: Long,
    blurPercent: Int,
    scrimPercent: Int,
    backgroundEffects: com.sukisu.nova.ui.util.ResolvedImageEffects?,
) {
    val blurRadius = (ImageStorage.BACKGROUND_BLUR_MAX_RADIUS_DP * blurPercent / 100f).dp
    Crossfade(
        targetState = path to updatedAt,
        label = "global_background",
    ) { (p, _) ->
        if (p.isNotEmpty()) {
            val context = LocalContext.current
            val stamp = File(p).lastModified()
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(File(p))
                    .crossfade(true)
                    .memoryCacheKey("$p@$stamp")
                    .diskCacheKey("$p@$stamp")
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (blurRadius > 0.dp) Modifier.blur(blurRadius) else Modifier)
                    .appearanceImageEffects(
                        backgroundEffects,
                        allowSharpen = blurRadius <= 0.dp,
                    ),
            )
            if (scrimPercent != 0) {
                val scrimColor = if (scrimPercent < 0) {
                    Color.Black.copy(alpha = -scrimPercent / 100f)
                } else {
                    Color.White.copy(alpha = scrimPercent / 100f)
                }
                Box(Modifier.fillMaxSize().background(scrimColor))
            }
        }
    }
}

@Composable
private fun SukisuPager(
    navigator: Navigator,
    initialPage: Int,
    onPageChanged: (Int) -> Unit,
    pagerInterceptionMode: Int,
    reportState: ReportUiState,
    reportActions: ReportActions,
    historyEntries: List<com.rootsentinel.data.HistoryEntry>,
    onClearHistory: () -> Unit,
) {
    val uiMode = com.sukisu.nova.ui.LocalUiMode.current
    val rsUiMode = me.weishu.kernelsu.ui.LocalUiMode.current
    val enableBlur = LocalEnableBlur.current
    val enableFloatingBottomBar = LocalEnableFloatingBottomBar.current
    val enableFloatingBottomBarBlur = LocalEnableFloatingBottomBarBlur.current
    val useRail = useNavigationRail(enableFloatingBottomBar)
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { MainPagerConfig.PAGE_COUNT },
    )
    val mainPagerState = rememberMainPagerState(
        pagerState = pagerState,
        animatePageChanges = !useRail,
    )
    LaunchedEffect(initialPage) {
        if (pagerState.currentPage != initialPage) {
            mainPagerState.animateToPage(initialPage)
        }
    }
    val pagerMode = PagerInterceptionMode.entries.getOrElse(pagerInterceptionMode) {
        PagerInterceptionMode.Native
    }
    val interceptPagerGestures = pagerMode == PagerInterceptionMode.CrossAxisInterceptor

    val settledPage = pagerState.settledPage
    LaunchedEffect(settledPage) { onPageChanged(settledPage) }
    val currentPage = pagerState.currentPage
    LaunchedEffect(currentPage) { mainPagerState.syncPage() }

    SukisuMainBackHandler(mainPagerState, navigator)

    val hasBg = LocalBackgroundImagePath.current.isNotEmpty()
    val cardAlpha = LocalCardBackgroundAlpha.current
    val surfaceColor = when (uiMode) {
        UiMode.Material -> MaterialTheme.colorScheme.surfaceContainer
        UiMode.Miuix -> MiuixTheme.colorScheme.surface
    }
    val needsStandardMiuixBarBackdrop = uiMode == UiMode.Miuix && !enableFloatingBottomBar && !useRail
    val blurBackdrop = rememberBlurBackdrop(
        enableBlur = enableBlur && needsStandardMiuixBarBackdrop,
        allowProgressive = needsStandardMiuixBarBackdrop,
    )
    val needsBottomBarBackdrop = !useRail &&
        (uiMode == UiMode.Material || enableFloatingBottomBar) &&
        (enableFloatingBottomBarBlur || LocalEnableProgressiveBlur.current)
    val backdrop = top.yukonga.miuix.kmp.blur.rememberLayerBackdrop {
        if (!hasBg) {
            drawRect(surfaceColor)
        } else {
            drawRect(surfaceColor.copy(alpha = cardAlpha))
        }
        drawContent()
    }

    CompositionLocalProvider(LocalMainPagerState provides mainPagerState) {
        val pagerContent: @Composable (Dp) -> Unit = { bottomInnerPadding ->
            Box(
                modifier = if (blurBackdrop != null) Modifier.layerBackdrop(blurBackdrop) else Modifier,
            ) {
                HorizontalPager(
                    modifier = Modifier
                        .pagerGestureOverride(
                            pagerState = mainPagerState.pagerState,
                            mode = pagerMode,
                            enabled = true,
                        )
                        .then(if (needsBottomBarBackdrop) Modifier.layerBackdrop(backdrop) else Modifier),
                    state = mainPagerState.pagerState,
                    beyondViewportPageCount = 3,
                    overscrollEffect = null,
                    userScrollEnabled = !interceptPagerGestures,
                    pageNestedScrollConnection = if (interceptPagerGestures) {
                        PagerGestureNestedScrollConnection
                    } else {
                        PagerDefaults.pageNestedScrollConnection(
                            state = mainPagerState.pagerState,
                            orientation = Orientation.Horizontal,
                        )
                    },
                    flingBehavior = PagerDefaults.flingBehavior(
                        state = mainPagerState.pagerState,
                        snapAnimationSpec = PagerNavigationSpringSpec,
                    ),
                ) { page ->
                    val isCurrentPage = page == settledPage
                    when (page) {
                        0 -> HomePager(navigator, bottomInnerPadding, isCurrentPage)
                        1 -> if (rsUiMode == me.weishu.kernelsu.ui.UiMode.Miuix) {
                            ReportPagerMiuix(reportState, reportActions, bottomInnerPadding)
                        } else {
                            ReportPagerMaterial(reportState, reportActions, bottomInnerPadding)
                        }
                        2 -> if (rsUiMode == me.weishu.kernelsu.ui.UiMode.Miuix) {
                            HistoryPagerMiuix(historyEntries, onClearHistory, bottomInnerPadding)
                        } else {
                            HistoryPagerMaterial(historyEntries, onClearHistory, bottomInnerPadding)
                        }
                        else -> SettingPager(navigator, bottomInnerPadding, isCurrentPage)
                    }
                }
            }
        }

        if (useRail) {
            val startInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
                .only(WindowInsetsSides.Start)
            val navBarBottomPadding = WindowInsets.systemBars.asPaddingValues()
                .calculateBottomPadding()
            when (uiMode) {
                UiMode.Material -> androidx.compose.material3.Scaffold(
                    containerColor = if (hasBg) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
                ) { _ ->
                    Row {
                        SideRail(NavigationBadgeState())
                        Box(Modifier.weight(1f).consumeWindowInsets(startInsets)) {
                            pagerContent(navBarBottomPadding)
                        }
                    }
                }
                UiMode.Miuix -> Scaffold(
                    containerColor = if (hasBg) Color.Transparent else MiuixTheme.colorScheme.surface,
                ) { _ ->
                    Row {
                        SideRail(NavigationBadgeState())
                        Box(Modifier.weight(1f).consumeWindowInsets(startInsets)) {
                            pagerContent(navBarBottomPadding)
                        }
                    }
                }
            }
        } else {
            val bottomBar: @Composable () -> Unit = {
                Box(Modifier.fillMaxWidth()) {
                    BottomBar(
                        blurBackdrop = blurBackdrop,
                        backdrop = backdrop,
                        navigationBadge = NavigationBadgeState(),
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
            when (uiMode) {
                UiMode.Material -> androidx.compose.material3.Scaffold(
                    bottomBar = bottomBar,
                    containerColor = if (hasBg) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
                ) { innerPadding ->
                    pagerContent(innerPadding.calculateBottomPadding())
                }
                UiMode.Miuix -> Scaffold(
                    bottomBar = bottomBar,
                    containerColor = if (hasBg) Color.Transparent else MiuixTheme.colorScheme.surface,
                ) { innerPadding ->
                    pagerContent(innerPadding.calculateBottomPadding())
                }
            }
        }
    }
}

/** Same back-to-home-tab behavior as upstream's MainScreenBackHandler. */
@Composable
private fun SukisuMainBackHandler(
    mainPagerState: MainPagerState,
    navigator: Navigator,
) {
    val isEnabled = remember {
        androidx.compose.runtime.derivedStateOf {
            navigator.current() is Route.Main && navigator.backStackSize() == 1 &&
                mainPagerState.selectedPage != 0
        }
    }
    val navEventState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = navEventState,
        isBackEnabled = isEnabled.value,
        onBackCompleted = { mainPagerState.animateToPage(0) },
    )
}
