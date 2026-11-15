package com.rootsentinel

import android.app.Activity
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.rootsentinel.data.HistoryEntry
import com.rootsentinel.data.HistoryStore
import com.rootsentinel.data.SettingsStore
import com.rootsentinel.engine.DetectorEngine
import com.rootsentinel.model.Evidence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.TextStyles

/**
 * 入口（v2.22.59 起界面按 KernelSU 管理器重构）：
 * 双模式（Miuix 默认 / Material）+ 状态卡首页 + 分组卡片列表 +
 * 主标签浮动底栏。检测引擎、评分、遥测与更新逻辑一概未动。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 窗口背景必须是实色：否则系统栏 / 窗口边缘会透出桌面
        // （v2.5.3 的截图里顶部漏出了桌面 dock，就是这个原因）
        val night = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        window.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(
                if (night) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(),
            ),
        )
        super.onCreate(savedInstanceState)
        setContent { RootApp() }
    }
}

/** 页面 */
enum class Page(val label: String) {
    HOME("首页"),
    SCAN("扫描"),
    REPORT("报告"),
    HISTORY("历史"),
    SETTINGS("设置"),
    DETAIL("详情"),
    DEVICE("设备"),
    ABOUT("关于"),
}

/** 扫描期间的实时状态（Compose snapshot state，可从后台线程写入） */
class ScanState {
    var scanning by mutableStateOf(false)
    /** v2.22.43：真实取消信号。之前 onCancel 直接把 scanning 置 false，
     *  引擎的取消检查点从未被接通（isCancelled 没人传），扫描在后台
     *  继续跑，而 UI 却允许立刻开新一轮。现在取消只置这个标志，
     *  scanning 保持 true 直到引擎真正返回，避免两轮扫描并发。 */
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

/**
 * Miuix 的默认文本样式只设了 fontSize，没有 lineHeight；
 * 而 Compose 默认 `includeFontPadding = false` ——
 * 中文字形的底横正好会被裁掉，表现就是「检测项」这类小字下缘被切平（v2.5.5 截图）。
 *
 * 这里统一修：
 *   · platformStyle.includeFontPadding = true  → 恢复字形上下留白
 *   · lineHeight = 1.35em                      → 相对字号，随 fontSize 自动缩放
 */
@Composable
private fun rsTextStyles(): TextStyles {
    val base = MiuixTheme.textStyles
    return remember(base) {
        val fixed = PlatformTextStyle(includeFontPadding = true)
        base.copy(
            main = base.main.copy(platformStyle = fixed, lineHeight = 1.35.em),
            body1 = base.body1.copy(platformStyle = fixed, lineHeight = 1.35.em),
            body2 = base.body2.copy(platformStyle = fixed, lineHeight = 1.35.em),
            footnote1 = base.footnote1.copy(platformStyle = fixed, lineHeight = 1.35.em),
            footnote2 = base.footnote2.copy(platformStyle = fixed, lineHeight = 1.35.em),
            subtitle = base.subtitle.copy(platformStyle = fixed, lineHeight = 1.35.em),
            button = base.button.copy(platformStyle = fixed, lineHeight = 1.35.em),
        )
    }
}

@Composable
fun RootApp() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsStore(ctx) }
    val history = remember { HistoryStore(ctx) }
    val engine = remember { DetectorEngine(ctx) }
    val scan = remember { ScanState() }
    // 应用内更新（v2.22.47）：声明确认后每次启动自动查一次新版，
    // 查到才弹窗；设置页有手动入口。检查走 GitHub/Gitee 发布接口。
    val updateController = remember { UpdateController() }
    LaunchedEffect(settings.declarationSeen) {
        if (settings.declarationSeen) updateController.checkAuto(scope)
    }

    var page by remember { mutableStateOf(Page.HOME) }
    var result by remember { mutableStateOf<DetectorEngine.Result?>(null) }
    var detail by remember { mutableStateOf<Evidence?>(null) }

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
        page = Page.SCAN

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
                    // 结果摘要只进 logcat（不落盘、不含设备标识），供自动化回归判断
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
                    // 用 runCatching 包住：写历史失败（存储满等）不能影响结果展示
                    if (settings.keepHistory) {
                        val ok = runCatching { history.append(HistoryEntry.from(r)) }.isSuccess
                        android.util.Log.i(
                            "RootSentinel",
                            "history append ok=$ok keepHistory=true total=${history.entries.size}",
                        )
                    } else {
                        android.util.Log.w(
                            "RootSentinel",
                            "history skipped: keepHistory=false",
                        )
                    }

                    // ---------------------------------------------------------- 自动导出
                    // settings.autoExport 之前**只有设置页在写、没有任何地方读** ——
                    // 开关存在但功能根本没实现，用户打开它什么也不会发生。
                    // 这里补上：扫描完成后按开关决定是否落盘 JSON。
                    // ---------------------------------------------------------- 诊断摘要上传
                    // v2.22.46：声明已确认 + 开关开启才传。未确认声明时
                    // 一律不传（顺序见 SettingsStore.declarationSeen）。
                    // 接收端未配置时 Telemetry.upload 内部直接返回。
                    if (settings.declarationSeen && settings.telemetryEnabled) {
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            com.rootsentinel.util.Telemetry.upload(r)
                        }
                    }

                    if (settings.autoExport) {
                        // v2.22.44（P2-08）：导出移出主线程。此前这段在
                        // withContext(Main) 块里同步跑，JSON 构建 + 写盘
                        // 都压在主线程上，大报告会卡界面。改走 IO 协程，
                        // 结果只落一行日志（导出是副作用，不阻塞出报告）。
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val f = runCatching {
                                com.rootsentinel.util.JsonExporter.export(ctx, r)
                            }.getOrNull()
                            android.util.Log.i(
                                "RootSentinel",
                                "autoExport path=${f?.absolutePath ?: "null"}",
                            )
                        }
                    }

                    page = Page.REPORT
                } else {
                    scan.failed = true
                }
            }
        }
    }

    // 调试/自动化：`am start ... --ez autoscan true` 时启动即扫描
    // v2.22.44（P2-06）：autoscan / navtest / snap 三个调试入口全部
    // 加 BuildConfig.DEBUG 门槛。此前 release 构建同样生效 —— 启动器
    // Activity 是 exported 的，任何应用都能用这些 extra 拉起扫描，
    // 叠加自动导出开关还能顺带落盘报告。现在 release 里恒不触发。
    val activity = ctx as? Activity
    LaunchedEffect(Unit) {
        if (BuildConfig.DEBUG &&
            activity?.intent?.getBooleanExtra("autoscan", false) == true) {
            startScan()
        }
    }

    // 调试/自动化：`am start ... --ez navtest true` 时自动走一遍所有 tab。
    LaunchedEffect(Unit) {
        if (BuildConfig.DEBUG &&
            activity?.intent?.getBooleanExtra("navtest", false) == true) {
            val seq = listOf(
                Page.SCAN, Page.REPORT, Page.HISTORY,
                Page.SETTINGS, Page.DEVICE, Page.HOME,
            )
            seq.forEachIndexed { i, p ->
                kotlinx.coroutines.delay(2500)
                android.util.Log.i("RootSentinel", "navtest[$i]=$p")
                page = p
            }
        }
    }

    // 调试/自动化：`am start ... --ez snap true` 时把界面画成 PNG 存到应用外部目录。
    val rootView = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(Unit) {
        if (BuildConfig.DEBUG &&
            activity?.intent?.getBooleanExtra("snap", false) == true) {
            kotlinx.coroutines.delay(3000)
            try {
                val w = rootView.width
                val h = rootView.height
                if (w > 0 && h > 0) {
                    val bmp = android.graphics.Bitmap.createBitmap(
                        w, h, android.graphics.Bitmap.Config.ARGB_8888,
                    )
                    rootView.draw(android.graphics.Canvas(bmp))
                    val dir = ctx.getExternalFilesDir(null)
                    val f = java.io.File(dir, "snap.png")
                    java.io.FileOutputStream(f).use {
                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    android.util.Log.i("RootSentinel", "snap=${f.absolutePath} ${w}x${h}")
                } else {
                    android.util.Log.w("RootSentinel", "snap skipped: view size ${w}x$h")
                }
            } catch (t: Throwable) {
                android.util.Log.w("RootSentinel", "snap failed: $t")
            }
        }
    }

    // ================= 界面模式与主题（KernelSU 式双模式） =================
    val uiMode = UiMode.fromValue(settings.uiMode)
    val sysDark = isSystemInDarkTheme()
    val dark = when (settings.colorMode) {
        1 -> false
        2 -> true
        else -> sysDark
    }
    val rsColors = if (dark) top.yukonga.miuix.kmp.theme.darkColorScheme()
    else top.yukonga.miuix.kmp.theme.lightColorScheme()
    val matColors = when {
        dark && Build.VERSION.SDK_INT >= 31 -> dynamicDarkColorScheme(ctx)
        !dark && Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    val palette = if (uiMode == UiMode.Miuix) {
        RsPalette(
            background = rsColors.background,
            card = rsColors.surface,
            onBackground = rsColors.onBackground,
            onVariant = rsColors.onBackgroundVariant,
            primary = rsColors.primary,
            divider = rsColors.onBackground.copy(alpha = 0.08f),
        )
    } else {
        RsPalette(
            background = matColors.background,
            card = matColors.surfaceContainer,
            onBackground = matColors.onBackground,
            onVariant = matColors.onSurfaceVariant,
            primary = matColors.primary,
            divider = matColors.outlineVariant,
        )
    }

    val mainTabs = listOf(Page.HOME, Page.REPORT, Page.HISTORY, Page.SETTINGS)
    val isMain = page in mainTabs
    val backTarget: Page? = when (page) {
        Page.DETAIL -> Page.REPORT
        Page.DEVICE, Page.ABOUT -> Page.SETTINGS
        else -> null
    }
    val tabs = listOf(
        RsTab(Page.HOME, "首页", Icons.Filled.Home),
        RsTab(Page.REPORT, "报告", Icons.Filled.List),
        RsTab(Page.HISTORY, "历史", Icons.Filled.DateRange),
        RsTab(Page.SETTINGS, "设置", Icons.Filled.Settings),
    )
    val title = when (page) {
        Page.HOME -> "RootSentinel"
        Page.SCAN -> "扫描"
        Page.REPORT -> "报告"
        Page.DETAIL -> "详情"
        Page.HISTORY -> "历史"
        Page.SETTINGS -> "设置"
        Page.DEVICE -> "设备信息"
        Page.ABOUT -> "关于"
    }

    // 预测性返回：子页左滑返回上一层
    PredictiveBackHandler(enabled = backTarget != null) { progress ->
        try {
            progress.collect { }
            backTarget?.let { page = it }
        } catch (e: CancellationException) {
            // 用户取消手势：留在当前页
        }
    }

    MiuixTheme(rsColors, rsTextStyles(), dark) {
        CompositionLocalProvider(
            LocalUiMode provides uiMode,
            LocalRsPalette provides palette,
            LocalBottomPad provides if (uiMode == UiMode.Miuix && isMain) 104.dp else 20.dp,
        ) {
            val themed: @Composable () -> Unit = {
                val content: @Composable (PaddingValues) -> Unit = { padding ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(palette.background)
                            .padding(padding),
                    ) {
                        AnimatedContent(
                            targetState = page,
                            transitionSpec = {
                                val fwd = pageRank(targetState) >= pageRank(initialState)
                                val spec = spring<IntOffset>(dampingRatio = 0.88f, stiffness = 640f)
                                (
                                    slideInHorizontally(spec) { w -> if (fwd) w else -w } +
                                        fadeIn(tween(90)) +
                                        scaleIn(
                                            initialScale = 0.97f,
                                            animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f),
                                        )
                                    ).togetherWith(
                                    slideOutHorizontally(spec) { w -> if (fwd) -w / 3 else w / 3 } +
                                        fadeOut(tween(120)) +
                                        scaleOut(
                                            targetScale = 1.02f,
                                            animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f),
                                        ),
                                )
                            },
                            label = "pageSlide",
                            modifier = Modifier.fillMaxSize(),
                        ) { current ->
                            when (current) {
                                Page.HOME -> HomePage(
                                    result = result,
                                    scanning = scan.scanning,
                                    updateVersion = if (
                                        updateController.phase == UpdateController.Phase.Available
                                    ) {
                                        updateController.info?.versionName
                                    } else {
                                        null
                                    },
                                    onScan = { startScan() },
                                    onDevice = { page = Page.DEVICE },
                                    onUpdateClick = { updateController.dialogVisible = true },
                                )

                                Page.SCAN -> ScanPage(
                                    scan = scan,
                                    onRescan = { startScan() },
                                    onCancel = { scan.cancelRequested = true },
                                )

                                Page.REPORT -> ReportPage(
                                    result = result,
                                    onRescan = { startScan() },
                                    onDetail = {
                                        detail = it
                                        page = Page.DETAIL
                                    },
                                )

                                Page.DETAIL -> DetailPage(
                                    item = detail,
                                    onBack = { page = Page.REPORT },
                                )

                                Page.HISTORY -> HistoryPage(
                                    history = history,
                                    onScan = { startScan() },
                                    onClear = { history.clear() },
                                )

                                Page.SETTINGS -> SettingsPage(
                                    settings = settings,
                                    onDevice = { page = Page.DEVICE },
                                    onAbout = { page = Page.ABOUT },
                                    onClearHistory = { history.clear() },
                                    onCheckUpdate = { updateController.checkManual(scope) },
                                )

                                Page.DEVICE -> DevicePage(
                                    result = result,
                                    onBack = { page = Page.SETTINGS },
                                )

                                Page.ABOUT -> AboutPage(
                                    result = result,
                                    onBack = { page = Page.SETTINGS },
                                )
                            }
                        }

                        // Miuix 模式的浮动底栏只在主标签页出现
                        if (uiMode == UiMode.Miuix && isMain) {
                            Box(Modifier.align(Alignment.BottomCenter)) {
                                RsBottomBar(
                                    tabs = tabs,
                                    current = page,
                                    onSelect = {
                                        // 导航埋点：供自动化回归判断切页是否生效
                                        android.util.Log.i("RootSentinel", "nav=$it")
                                        page = it
                                    },
                                )
                            }
                        }
                    }

                    // 首次使用声明（v2.22.46）：未确认过就弹，确认后不再出现。
                    if (!settings.declarationSeen) {
                        DeclarationDialog(
                            onConfirm = { settings.declarationSeen = true },
                        )
                    }

                    // 更新弹窗（v2.22.47）：发现新版 / 手动检查时出现。
                    if (updateController.dialogVisible) {
                        UpdateDialog(
                            controller = updateController,
                            context = ctx,
                            scope = scope,
                        )
                    }
                }

                when (uiMode) {
                    UiMode.Miuix -> top.yukonga.miuix.kmp.basic.Scaffold(
                        topBar = {
                            RsTopBar(
                                title = title,
                                large = isMain,
                                onBack = backTarget?.let { t -> { page = t } },
                            )
                        },
                        bottomBar = {},
                        containerColor = palette.background,
                        content = content,
                    )
                    UiMode.Material -> androidx.compose.material3.Scaffold(
                        topBar = {
                            RsTopBar(
                                title = title,
                                large = isMain,
                                onBack = backTarget?.let { t -> { page = t } },
                            )
                        },
                        bottomBar = {
                            if (isMain) {
                                RsBottomBar(
                                    tabs = tabs,
                                    current = page,
                                    onSelect = {
                                        android.util.Log.i("RootSentinel", "nav=$it")
                                        page = it
                                    },
                                )
                            }
                        },
                        containerColor = palette.background,
                        content = content,
                    )
                }
            }
            if (uiMode == UiMode.Material) {
                androidx.compose.material3.MaterialTheme(
                    colorScheme = matColors,
                    content = themed,
                )
            } else {
                themed()
            }
        }
    }
}

/**
 * 页面"深度"排序：用来决定切换动画的方向。
 * 详情/设备/关于属于更深一层（从右侧推进来）。
 */
private fun pageRank(p: Page): Float = when (p) {
    Page.HOME -> 0f
    Page.SCAN -> 1f
    Page.REPORT -> 2f
    Page.DETAIL -> 2.5f
    Page.HISTORY -> 3f
    Page.SETTINGS -> 4f
    Page.DEVICE -> 4.5f
    Page.ABOUT -> 4.6f
}
