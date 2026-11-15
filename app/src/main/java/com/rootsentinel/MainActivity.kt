package com.rootsentinel

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.rootsentinel.data.HistoryEntry
import com.rootsentinel.data.HistoryStore
import com.rootsentinel.data.LatestReportProvider
import com.rootsentinel.data.SettingsStore
import com.rootsentinel.engine.DetectorEngine
import com.rootsentinel.model.Evidence
import com.rootsentinel.model.Grade
import com.rootsentinel.model.Verdict
import com.rootsentinel.util.JsonExporter
import com.rootsentinel.util.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.data.repository.SettingsRepositoryImpl
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.UiMode
import me.weishu.kernelsu.ui.component.bottombar.BottomBar
import me.weishu.kernelsu.ui.component.bottombar.MainPagerState
import me.weishu.kernelsu.ui.component.bottombar.NavigationBadgeState
import me.weishu.kernelsu.ui.component.bottombar.SideRail
import me.weishu.kernelsu.ui.component.bottombar.rememberMainPagerState
import me.weishu.kernelsu.ui.component.bottombar.useNavigationRail
import me.weishu.kernelsu.ui.screen.colorpalette.ColorPaletteScreenActions
import me.weishu.kernelsu.ui.screen.colorpalette.ColorPaletteScreenMaterial
import me.weishu.kernelsu.ui.screen.colorpalette.ColorPaletteScreenMiuix
import me.weishu.kernelsu.ui.screen.colorpalette.ColorPaletteUiState
import me.weishu.kernelsu.ui.screen.home.HomeActions
import me.weishu.kernelsu.ui.screen.home.HomePagerMaterial
import me.weishu.kernelsu.ui.screen.home.HomePagerMiuix
import me.weishu.kernelsu.ui.screen.home.HomeUiState
import me.weishu.kernelsu.ui.screen.home.SystemInfo
import me.weishu.kernelsu.ui.screen.settings.RsPrivacyState
import me.weishu.kernelsu.ui.screen.settings.RsSettingsActions
import me.weishu.kernelsu.ui.screen.settings.SettingPagerMaterial
import me.weishu.kernelsu.ui.screen.settings.SettingPagerMiuix
import me.weishu.kernelsu.ui.screen.settings.SettingsScreenActions
import me.weishu.kernelsu.ui.screen.settings.SettingsUiState
import me.weishu.kernelsu.ui.theme.KernelSUTheme
import me.weishu.kernelsu.ui.theme.LocalColorMode
import me.weishu.kernelsu.ui.theme.LocalEnableBlur
import me.weishu.kernelsu.ui.theme.LocalEnableFloatingBottomBar
import me.weishu.kernelsu.ui.theme.LocalEnableFloatingBottomBarBlur
import me.weishu.kernelsu.ui.theme.LocalEnableNavigationBadge
import me.weishu.kernelsu.ui.theme.LocalModuleDescriptionMaxLines
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.theme.ThemeController
import me.weishu.kernelsu.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PagerGestureNestedScrollConnection
import top.yukonga.miuix.kmp.utils.PagerInterceptionMode
import top.yukonga.miuix.kmp.utils.PagerNavigationSpringSpec
import top.yukonga.miuix.kmp.utils.pagerGestureOverride

/**
 * RootSentinel 主入口（v2.22.60）。
 *
 * 界面骨架照 KernelSU 的 MainActivity / MainScreen 组织：主题系统、本地
 *  CompositionLocal、HorizontalPager 四页、FloatingBottomBar 液态玻璃
 * 底栏与导航栏（SideRail）全部用 KSU 原样组件；RootSentinel 只提供
 * 数据接缝：扫描状态、报告/历史、设置存储与更新控制器。
 */

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ksuApp = application
        // v2.22.64：尽早装崩溃捕获（只落盘，下次启动再按开关上传）
        com.rootsentinel.util.CrashReporter.install(application)
        enableEdgeToEdge()
        val auto = intent?.getStringExtra("auto")
        val tab = intent?.getIntExtra("tab", 0) ?: 0
        val sub = intent?.getStringExtra("sub")
        setContent {
            RootApp(auto = auto, initialTab = tab, initialSub = sub)
        }
    }
}

/** 扫描期间的实时状态（Compose snapshot state，可从后台线程写入） */
class ScanState {
    var scanning by mutableStateOf(false)
    /** 真实取消信号：取消只置此标志，scanning 保持 true 直到引擎真正
     *  返回，避免两轮扫描并发（v2.22.43 起的行为，保持不变） */
    var cancelRequested by mutableStateOf(false)
    var moduleName by mutableStateOf("准备中…")
    var moduleIdx by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var hit by mutableIntStateOf(0)
    var startedAt by mutableLongStateOf(0L)
    var failed by mutableStateOf(false)
    var timedOut by mutableStateOf(false)
    val live = mutableStateListOf<LiveItem>()
}

data class LiveItem(val id: String, val title: String, val severity: String)

private enum class SubPage { NONE, SCAN, DETAIL, DEVICE, ABOUT, THEME }

@Composable
fun RootApp(auto: String?, initialTab: Int, initialSub: String?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember {
        SettingsStore(ctx).also { SettingsRepositoryImpl.store = it }
    }
    val history = remember { HistoryStore(ctx) }
    val engine = remember { DetectorEngine(ctx) }
    val scan = remember { ScanState() }
    val updateController = remember { UpdateController() }

    var result by remember { mutableStateOf<DetectorEngine.Result?>(null) }
    var detail by remember { mutableStateOf<Evidence?>(null) }
    var subPage by remember {
        mutableStateOf(
            when (initialSub) {
                "device" -> SubPage.DEVICE
                "about" -> SubPage.ABOUT
                "theme" -> SubPage.THEME
                else -> SubPage.NONE
            },
        )
    }

    val pagerState = rememberPagerState(
        initialPage = initialTab.coerceIn(0, 3),
        pageCount = { 4 },
    )
    val useRail = useNavigationRail(settings.enableFloatingBottomBar)
    val mainPagerState = rememberMainPagerState(
        pagerState = pagerState,
        animatePageChanges = !useRail,
    )

    fun openUrl(url: String) {
        runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            Toast.makeText(ctx, "打开链接失败", Toast.LENGTH_SHORT).show()
        }
    }

    fun startScan() {
        if (scan.scanning) return
        scan.scanning = true
        scan.cancelRequested = false
        scan.failed = false
        scan.timedOut = false
        scan.total = 0
        scan.hit = 0
        scan.moduleIdx = 0
        scan.moduleName = "准备中…"
        scan.startedAt = System.currentTimeMillis()
        scan.live.clear()
        subPage = SubPage.SCAN

        scope.launch(Dispatchers.Default) {
            val r = try {
                engine.run(
                    onModule = { name ->
                        scan.moduleName = name
                        scan.moduleIdx += 1
                    },
                    onItem = { id, title, sev, verdict, _ ->
                        scan.total += 1
                        if (verdict == "HIT") {
                            scan.hit += 1
                            if (scan.live.size < 40) {
                                scan.live.add(0, LiveItem(id, title, sev))
                            }
                        }
                    },
                    isCancelled = { scan.cancelRequested },
                )
            } catch (t: Throwable) {
                null
            }
            withContext(Dispatchers.Main) {
                scan.scanning = false
                if (r != null) {
                    android.util.Log.i(
                        "RootSentinel",
                        "grade=${r.score.grade}" +
                            " root=${String.format("%.1f", r.score.rootScore)}" +
                            " integ=${String.format("%.1f", r.score.integrityScore)}" +
                            " hook=${String.format("%.1f", r.score.hookScore)}" +
                            " env=${String.format("%.1f", r.score.environmentScore)}" +
                            " hits=${r.score.hitCount} unknown=${r.score.unknownCount}" +
                            " coverage=${String.format("%.2f", r.score.coverage)}" +
                            " breach=${r.score.integrityBreach}" +
                            " items=${r.items.size} elapsed=${r.elapsedMs}ms",
                    )
                    result = r
                    if (settings.keepHistory) {
                        runCatching { history.append(HistoryEntry.from(r)) }
                    }
                    // 诊断摘要上传：声明已确认 + 开关开启才传（v2.22.46 起的约定）
                    if (settings.declarationSeen && settings.telemetryEnabled) {
                        scope.launch(Dispatchers.IO) { Telemetry.upload(r) }
                    }
                    if (settings.autoExport) {
                        scope.launch(Dispatchers.IO) {
                            runCatching { JsonExporter.export(ctx, r) }
                        }
                    }
                    subPage = SubPage.NONE
                    mainPagerState.animateToPage(1)
                } else {
                    scan.failed = true
                    subPage = SubPage.NONE
                }
            }
        }
    }

    // 启动自动检查更新：声明已确认且设置开启时才查（查到新版才弹窗）
    LaunchedEffect(settings.declarationSeen, settings.checkUpdate) {
        if (settings.declarationSeen && settings.checkUpdate) {
            updateController.checkAuto(scope)
        }
    }
    // DEBUG 自动化入口：auto=scan 直接开扫
    // v2.22.64：上次崩溃的堆栈在本次启动补传（声明已确认且开关开时）
    LaunchedEffect(settings.declarationSeen, settings.crashUploadEnabled) {
        if (settings.declarationSeen && settings.crashUploadEnabled) {
            scope.launch(Dispatchers.IO) { com.rootsentinel.util.CrashReporter.uploadPending(ksuApp) }
        }
    }
    LaunchedEffect(Unit) {
        if (auto == "scan" && settings.declarationSeen) startScan()
    }
    // 发送日志/导出报告的数据源
    LaunchedEffect(result) {
        val snapshot = result
        LatestReportProvider.latestReportJson = {
            snapshot?.let { r -> runCatching { JsonExporter.toJsonString(r) }.getOrNull() }
        }
    }
    LaunchedEffect(pagerState.settledPage, subPage) {
        android.util.Log.i(
            "RootSentinel",
            "nav=page${pagerState.settledPage} sub=${subPage.name.lowercase()}",
        )
    }

    // ------------------------------------------------------------ 状态组装
    val uiMode = UiMode.entries.firstOrNull { it.value == settings.uiMode } ?: UiMode.Miuix
    val appSettings = ThemeController.getAppSettings()
    val settingsUiState = SettingsUiState(
        uiMode = settings.uiMode,
        checkUpdate = settings.checkUpdate,
        checkModuleUpdate = settings.checkModuleUpdate,
        themeMode = settings.themeMode,
        miuixMonet = settings.miuixMonet,
        keyColor = settings.keyColor,
        colorStyle = settings.colorStyle,
        colorSpec = settings.colorSpec,
        enablePredictiveBack = settings.enablePredictiveBack,
        enableSwipeDismiss = settings.enableSwipeDismiss,
        pagerInterceptionMode = settings.pagerInterceptionMode,
        enableBlur = settings.enableBlur,
        enableFloatingBottomBar = settings.enableFloatingBottomBar,
        enableFloatingBottomBarBlur = settings.enableFloatingBottomBarBlur,
        enableNavigationBadge = settings.enableNavigationBadge,
        pageScale = settings.pageScale,
        moduleDescriptionMaxLines = settings.moduleDescriptionMaxLines,
    )

    val uname = remember { runCatching { android.system.Os.uname() }.getOrNull() }
    val selinuxStatus = remember {
        runCatching { java.io.File("/sys/fs/selinux/enforce").readText().trim() }
            .getOrNull()
            ?.let { if (it == "1") "Enforcing" else if (it == "0") "Permissive" else "Unknown" }
            ?: "Unknown"
    }
    val homeState = HomeUiState(
        checkUpdateEnabled = settings.checkUpdate,
        hasUpdate = updateController.phase == UpdateController.Phase.Available && updateController.info != null,
        latestVersionName = updateController.info?.versionName ?: "",
        showIncompleteWarning = result?.score?.grade == Grade.INCOMPLETE,
        showHidingWarning = result?.score?.grade == Grade.ROOTED_HIDING,
        hasResult = result != null,
        scanning = scan.scanning,
        scanModuleName = scan.moduleName,
        scanPercent = scan.total,
        gradeWord = result?.let { gradeWord(it.score.grade) } ?: "",
        gradeSummary = result?.let {
            "Root ${it.score.rootScore.toInt()} · 完整性 ${it.score.integrityScore.toInt()} · " +
                "Hook ${it.score.hookScore.toInt()} · 环境 ${it.score.environmentScore.toInt()} · " +
                "覆盖 ${(it.score.coverage * 100).toInt()}% · ${it.score.hitCount} 命中 / ${it.score.unknownCount} 未知"
        } ?: "",
        gradeKind = result?.let { gradeKindOf(it.score.grade) } ?: 0,
        systemInfo = SystemInfo(
            appVersion = "v${RootSentinelVersion.NAME} (${RootSentinelVersion.CODE})",
            kernelVersion = uname?.release ?: "—",
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            fingerprint = Build.FINGERPRINT,
            selinuxStatus = selinuxStatus,
        ),
    )
    val homeActions = HomeActions(
        onScanClick = { if (!scan.scanning) startScan() },
        onReportClick = { mainPagerState.animateToPage(1) },
        onUpdateClick = {
            updateController.dialogVisible = true
            if (updateController.phase != UpdateController.Phase.Available) {
                updateController.checkManual(scope)
            }
        },
        onOpenUrl = { openUrl(it) },
        onDeviceClick = { subPage = SubPage.DEVICE },
    )

    val reportState = ReportUiState(
        hasResult = result != null,
        scanning = scan.scanning,
        gradeWord = result?.let { gradeWord(it.score.grade) } ?: "",
        gradeKind = result?.let { gradeKindOf(it.score.grade) } ?: 0,
        scoreLines = result?.let {
            listOf(
                "Root" to it.score.rootScore.toInt().toString(),
                "完整性" to it.score.integrityScore.toInt().toString(),
                "Hook" to it.score.hookScore.toInt().toString(),
                "环境" to it.score.environmentScore.toInt().toString(),
                "覆盖率" to "${(it.score.coverage * 100).toInt()}%",
                "置信度" to it.score.confidence.name,
            )
        } ?: emptyList(),
        notes = result?.score?.notes ?: emptyList(),
        hits = result?.items?.filter { it.verdict == Verdict.HIT } ?: emptyList(),
        unknowns = result?.items?.filter { it.verdict == Verdict.UNKNOWN } ?: emptyList(),
        all = result?.items ?: emptyList(),
        elapsedText = result?.let {
            if (it.elapsedMs >= 1000) String.format("%.1f s", it.elapsedMs / 1000.0) else "${it.elapsedMs} ms"
        } ?: "",
    )
    val reportActions = ReportActions(
        onRescan = { mainPagerState.animateToPage(0) },
        onExport = {
            val r = result
            if (r == null) {
                Toast.makeText(ctx, "暂无可导出的报告", Toast.LENGTH_SHORT).show()
            } else {
                scope.launch(Dispatchers.IO) {
                    val f = runCatching { JsonExporter.export(ctx, r) }.getOrNull()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            ctx,
                            if (f != null) "报告已保存" else "报告已导出到下载目录",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        },
        onOpenItem = { e -> detail = e; subPage = SubPage.DETAIL },
    )

    val settingsActions = SettingsScreenActions(
        onSetCheckUpdate = { settings.checkUpdate = it },
        onSetCheckModuleUpdate = { settings.checkModuleUpdate = it },
        onOpenTheme = { subPage = SubPage.THEME },
        onSetUiModeIndex = { idx ->
            settings.uiMode = UiMode.entries.getOrElse(idx) { UiMode.Miuix }.value
        },
        onOpenProfileTemplate = {},
        onSetSuCompatMode = {},
        onSetKernelUmountEnabled = {},
        onSetSelinuxHideEnabled = {},
        onSetSulogEnabled = {},
        onSetAdbRootEnabled = {},
        onSetShellSuAdbOnlyEnabled = {},
        onSetDefaultUmountModules = {},
        onSetEnableWebDebugging = {},
        onSetAutoJailbreak = {},
        onSetUseSoftReboot = {},
        onOpenAbout = { subPage = SubPage.ABOUT },
    )
    val rsPrivacy = RsPrivacyState(
        telemetryEnabled = settings.telemetryEnabled,
        crashUploadEnabled = settings.crashUploadEnabled,
        autoExport = settings.autoExport,
        keepHistory = settings.keepHistory,
    )
    val rsActions = RsSettingsActions(
        onSetTelemetryEnabled = { settings.telemetryEnabled = it },
        onSetCrashUploadEnabled = { settings.crashUploadEnabled = it },
        onSetAutoExport = { settings.autoExport = it },
        onSetKeepHistory = { settings.keepHistory = it },
        onCheckUpdateClick = {
            updateController.dialogVisible = true
            updateController.checkManual(scope)
        },
        onOpenDevice = { subPage = SubPage.DEVICE },
    )
    val paletteState = ColorPaletteUiState(
        uiState = settingsUiState,
        currentColorMode = appSettings.colorMode,
        currentPaletteStyle = runCatching { PaletteStyle.valueOf(settings.colorStyle) }
            .getOrElse { PaletteStyle.TonalSpot },
        currentColorSpec = runCatching { ColorSpec.SpecVersion.valueOf(settings.colorSpec) }
            .getOrElse { ColorSpec.SpecVersion.SPEC_2025 },
    )
    val paletteActions = ColorPaletteScreenActions(
        onBack = { subPage = SubPage.NONE },
        onSetThemeMode = { settings.themeMode = it },
        onSetMiuixMonet = { settings.miuixMonet = it },
        onSetKeyColor = { settings.keyColor = it },
        onSetColorMode = { settings.themeMode = it.value },
        onSetColorStyle = { settings.colorStyle = it },
        onSetColorSpec = { settings.colorSpec = it },
        onSetEnableBlur = { settings.enableBlur = it },
        onSetEnableFloatingBottomBar = { settings.enableFloatingBottomBar = it },
        onSetEnableFloatingBottomBarBlur = { settings.enableFloatingBottomBarBlur = it },
        onSetEnableNavigationBadge = { settings.enableNavigationBadge = it },
        onSetEnablePredictiveBack = { settings.enablePredictiveBack = it },
        onSetEnableSwipeDismiss = { settings.enableSwipeDismiss = it },
        onSetPagerInterceptionMode = { settings.pagerInterceptionMode = it },
        onSetPageScale = { settings.pageScale = it },
        onSetModuleDescriptionMaxLines = { settings.moduleDescriptionMaxLines = it },
    )

    val badge = NavigationBadgeState(
        moduleUpdatableCount = if (homeState.hasUpdate) 1 else 0,
    )

    // ------------------------------------------------------------ 返回处理
    // v2.22.63: predictive back must DRIVE the page visually. Previously
    // NavigationBackHandler only fired onBackCompleted at gesture end —
    // during the swipe nothing moved, so the feature looked dead even
    // when enabled. The gesture progress now offsets the sub-page
    // container in real time (and eases back to 0 on cancel).
    val navEventState = rememberNavigationEventState(NavigationEventInfo.None)
    val backProgress = remember { Animatable(0f) }
    if (settings.enablePredictiveBack) {
        LaunchedEffect(navEventState.transitionState) {
            val ts = navEventState.transitionState
            if (ts is NavigationEventTransitionState.InProgress) {
                val ev = ts.latestEvent
                val sign = if (ev.swipeEdge == NavigationEvent.EDGE_RIGHT) -1f else 1f
                backProgress.snapTo(ev.progress * sign)
            } else {
                backProgress.animateTo(0f, tween(180))
            }
        }
    }
    if (subPage != SubPage.NONE) {
        if (settings.enablePredictiveBack) {
            NavigationBackHandler(
                state = navEventState,
                isBackEnabled = true,
                onBackCompleted = { subPage = SubPage.NONE },
            )
        } else {
            BackHandler { subPage = SubPage.NONE }
        }
    } else if (pagerState.settledPage != 0) {
        BackHandler { mainPagerState.animateToPage(0) }
    }

    // ------------------------------------------------------------ 主题与骨架
    val systemDensity = LocalDensity.current
    val density = remember(systemDensity, settings.pageScale) {
        Density(systemDensity.density * settings.pageScale, systemDensity.fontScale)
    }
    CompositionLocalProvider(
        LocalUiMode provides uiMode,
        LocalColorMode provides appSettings.colorMode.value,
        LocalEnableBlur provides settings.enableBlur,
        LocalEnableFloatingBottomBar provides settings.enableFloatingBottomBar,
        LocalEnableFloatingBottomBarBlur provides settings.enableFloatingBottomBarBlur,
        LocalEnableNavigationBadge provides settings.enableNavigationBadge,
        LocalModuleDescriptionMaxLines provides settings.moduleDescriptionMaxLines,
        LocalDensity provides density,
    ) {
        KernelSUTheme(appSettings = appSettings, uiMode = uiMode) {
            val content: @Composable () -> Unit = {
                if (subPage != SubPage.NONE) {
                    var dragTotal by remember { mutableStateOf(0f) }
                    Box(
                        Modifier.graphicsLayer {
                            val p = backProgress.value
                            translationX = p * size.width * 0.3f
                            val sc = 1f - 0.05f * kotlin.math.abs(p)
                            scaleX = sc
                            scaleY = sc
                        }.then(
                            if (settings.enableSwipeDismiss) {
                                Modifier.pointerInput(Unit) {
                                    detectHorizontalDragGestures(
                                        onDragStart = { dragTotal = 0f },
                                        onHorizontalDrag = { _, amount -> dragTotal += amount },
                                        onDragEnd = {
                                            if (dragTotal > 96.dp.toPx()) subPage = SubPage.NONE
                                        },
                                    )
                                }
                            } else {
                                Modifier
                            },
                        ),
                    ) {
                        when (subPage) {
                            SubPage.SCAN -> if (uiMode == UiMode.Miuix) {
                                ScanScreenMiuix(scan) { scan.cancelRequested = true }
                            } else {
                                ScanScreenMaterial(scan) { scan.cancelRequested = true }
                            }
                            SubPage.DETAIL -> detail?.let { e ->
                                if (uiMode == UiMode.Miuix) {
                                    DetailScreenMiuix(e) { subPage = SubPage.NONE }
                                } else {
                                    DetailScreenMaterial(e) { subPage = SubPage.NONE }
                                }
                            }
                            SubPage.DEVICE -> if (uiMode == UiMode.Miuix) {
                                DeviceScreenMiuix { subPage = SubPage.NONE }
                            } else {
                                DeviceScreenMaterial { subPage = SubPage.NONE }
                            }
                            SubPage.ABOUT -> if (uiMode == UiMode.Miuix) {
                                AboutScreenMiuix({ subPage = SubPage.NONE }, ::openUrl)
                            } else {
                                AboutScreenMaterial({ subPage = SubPage.NONE }, ::openUrl)
                            }
                            SubPage.THEME -> if (uiMode == UiMode.Miuix) {
                                ColorPaletteScreenMiuix(paletteState, paletteActions)
                            } else {
                                ColorPaletteScreenMaterial(paletteState, paletteActions)
                            }
                            SubPage.NONE -> Unit
                        }
                    }
                } else {
                    MainScreen(
                        mainPagerState = mainPagerState,
                        pagerInterceptionMode = settings.pagerInterceptionMode,
                        navigationBadge = badge,
                    ) { page, bottomPad ->
                        when (page) {
                            0 -> if (uiMode == UiMode.Miuix) {
                                HomePagerMiuix(homeState, homeActions, bottomPad)
                            } else {
                                HomePagerMaterial(homeState, homeActions, bottomPad)
                            }
                            1 -> if (uiMode == UiMode.Miuix) {
                                ReportPagerMiuix(reportState, reportActions, bottomPad)
                            } else {
                                ReportPagerMaterial(reportState, reportActions, bottomPad)
                            }
                            2 -> if (uiMode == UiMode.Miuix) {
                                HistoryPagerMiuix(history.entries, { history.clear() }, bottomPad)
                            } else {
                                HistoryPagerMaterial(history.entries, { history.clear() }, bottomPad)
                            }
                            else -> if (uiMode == UiMode.Miuix) {
                                SettingPagerMiuix(settingsUiState, settingsActions, bottomPad, rsPrivacy, rsActions)
                            } else {
                                SettingPagerMaterial(settingsUiState, settingsActions, bottomPad, rsPrivacy, rsActions)
                            }
                        }
                    }
                }
            }
            when (uiMode) {
                UiMode.Material -> androidx.compose.material3.Scaffold(
                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer,
                ) { _ -> content() }
                UiMode.Miuix -> Scaffold { _ -> content() }
            }

            // 弹窗层
            if (!settings.declarationSeen) {
                DeclarationDialog { settings.declarationSeen = true }
            }
            if (updateController.dialogVisible) {
                UpdateDialog(updateController) { updateController.dialogVisible = false }
            }
        }
    }
}

/**
 * 照 KSU MainScreen 的骨架：HorizontalPager + 液态玻璃背板 +
 * FloatingBottomBar / SideRail 双形态。
 */
@Composable
private fun MainScreen(
    mainPagerState: MainPagerState,
    pagerInterceptionMode: Int,
    navigationBadge: NavigationBadgeState,
    pages: @Composable (page: Int, bottomInnerPadding: Dp) -> Unit,
) {
    val uiMode = LocalUiMode.current
    val enableBlur = LocalEnableBlur.current
    val enableFloatingBottomBar = LocalEnableFloatingBottomBar.current
    val enableFloatingBottomBarBlur = LocalEnableFloatingBottomBarBlur.current
    val useRail = useNavigationRail(enableFloatingBottomBar)
    val pagerMode = PagerInterceptionMode.entries.getOrElse(pagerInterceptionMode) {
        PagerInterceptionMode.Native
    }
    val interceptPagerGestures = pagerMode == PagerInterceptionMode.CrossAxisInterceptor
    val surfaceColor = when (uiMode) {
        UiMode.Material -> androidx.compose.material3.MaterialTheme.colorScheme.surface
        UiMode.Miuix -> MiuixTheme.colorScheme.surface
    }
    val blurBackdrop = rememberBlurBackdrop(enableBlur)
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    val currentPage = mainPagerState.pagerState.currentPage
    LaunchedEffect(currentPage) {
        mainPagerState.syncPage()
    }

    CompositionLocalProvider(
        me.weishu.kernelsu.ui.LocalMainPagerState provides mainPagerState,
    ) {
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
                        .then(
                            if (enableFloatingBottomBar && enableFloatingBottomBarBlur) {
                                Modifier.layerBackdrop(backdrop)
                            } else {
                                Modifier
                            },
                        ),
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
                    pages(page, bottomInnerPadding)
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
                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer,
                ) { _ ->
                    Row {
                        SideRail(navigationBadge)
                        Box(Modifier.weight(1f).consumeWindowInsets(startInsets)) {
                            pagerContent(navBarBottomPadding)
                        }
                    }
                }
                UiMode.Miuix -> Scaffold { _ ->
                    Row {
                        SideRail(navigationBadge)
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
                        navigationBadge = navigationBadge,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
            when (uiMode) {
                UiMode.Material -> androidx.compose.material3.Scaffold(
                    bottomBar = bottomBar,
                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer,
                ) { innerPadding ->
                    pagerContent(innerPadding.calculateBottomPadding())
                }
                UiMode.Miuix -> Scaffold(bottomBar = bottomBar) { innerPadding ->
                    pagerContent(innerPadding.calculateBottomPadding())
                }
            }
        }
    }
}
