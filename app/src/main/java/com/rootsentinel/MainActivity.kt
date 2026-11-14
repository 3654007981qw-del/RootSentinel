package com.rootsentinel
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerEventPass
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.abs

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.abs
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.spring
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import kotlin.coroutines.cancellation.CancellationException
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.kyant.backdrop.backdrops.layerBackdrop
import com.rootsentinel.data.HistoryEntry
import com.rootsentinel.data.HistoryStore
import com.rootsentinel.data.SettingsStore
import com.rootsentinel.engine.DetectorEngine
import com.rootsentinel.model.Evidence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.TextStyles

/**
 * RootSentinel v2.5.0 —— UI 用 Miuix（HyperOS 风格 Compose 组件库）重写
 *
 * 构建体系已从手工链（aapt2 + kotlinc + d8）切换为 Gradle + AGP + Compose。
 * 检测引擎、评分模型、JNI 协议完全保留，只是换了 UI 层与构建方式。
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
    // 背景层：改用 backdrop 库的 LayerBackdrop（与 KSU 内部同一套实现）。
    // 之前用自己写的 GraphicsLayer 录制，绘制端也自己写；现在录制与绘制都用库。
    val backdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()

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
                    if (settings.autoExport) {
                        val f = runCatching {
                            com.rootsentinel.util.JsonExporter.export(ctx, r)
                        }.getOrNull()
                        android.util.Log.i(
                            "RootSentinel",
                            "autoExport path=${f?.absolutePath ?: "null"}",
                        )
                    }

                    page = Page.REPORT
                } else {
                    scan.failed = true
                }
            }
        }
    }

    // 调试/自动化：`am start ... --ez autoscan true` 时启动即扫描
    val activity = ctx as? Activity
    LaunchedEffect(Unit) {
        if (activity?.intent?.getBooleanExtra("autoscan", false) == true) {
            startScan()
        }
    }

    // 调试/自动化：`am start ... --ez navtest true` 时自动走一遍所有 tab。
    // 用途：在无法注入真实触摸（通知栏劫持焦点等）的环境下，
    // 仍然能验证「切页 + 指示器弹簧 + 三块玻璃渲染」不会崩溃 / ANR / 掉帧。
    LaunchedEffect(Unit) {
        if (activity?.intent?.getBooleanExtra("navtest", false) == true) {
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
    // 用途：Operit 会抢前台，screencap 抓不到本应用的界面；
    // 由应用自己把 View 画到位图上，才能看到底栏玻璃的真实效果。
    val rootView = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(Unit) {
        if (activity?.intent?.getBooleanExtra("snap", false) == true) {
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

    // 之前没传 colorScheme，等于永远用浅色 —— 这就是"没适配暗黑模式"
    val sysDark = androidx.compose.foundation.isSystemInDarkTheme()
    val rsColors = if (sysDark) top.yukonga.miuix.kmp.theme.darkColorScheme()
    else top.yukonga.miuix.kmp.theme.lightColorScheme()
    MiuixTheme(rsColors, rsTextStyles(), sysDark) {
        val colors = MiuixTheme.colorScheme
        Scaffold(
            topBar = {
                // 顶栏也是液态玻璃：页面内容会从它下面滚过，
                // 所以边缘同样能折射/模糊（不再是死白一块）
                Column(
                    Modifier
                        .fillMaxWidth()
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { RoundedCornerShape(0.dp) },
                            // 官方 LiquidTopBar：vibrancy + blur(14dp) + lens(20,22)+色散
                            // 但官方顶栏是**胶囊**；我们的是全宽矩形，lens 在直边上会把
                            // 内容拉成一道横条（之前踩过），所以这里只照搬它的模糊与光影数值：
                            //   blur 14dp / Highlight.Default(alpha 0.18) /
                            //   Shadow(radius 20dp, alpha 0.12) / InnerShadow(radius 5dp, alpha 0.07)
                            // iOS26 通透档：blur 0.8dp / 菲涅尔 0.44 / 边缘内暗 0.18 / 无外投影
                            effects = {
                                vibrancy()
                                blur(0.8.dp.toPx())
                            },
                            highlight = {
                                Highlight.Default.copy(alpha = 0.44f)
                            },
                            shadow = null,
                            innerShadow = {
                                InnerShadow(radius = 8.dp, alpha = 0.18f)
                            },
                        ),
                ) {
                    TopAppBar(
                        title = "RootSentinel",
                        largeTitle = "RootSentinel",
                        color = Color.Transparent,
                        titleColor = colors.onBackground,
                        largeTitleColor = colors.onBackground,
                    )
                }
            },
            // 底栏是浮在内容之上的液态玻璃，所以 Scaffold 不占位
            bottomBar = {},
            containerColor = colors.background,
        ) { padding ->
            Box(Modifier.fillMaxSize()) {
                // ================= 液态玻璃的“内容底” =================
                // 必须画在 Scaffold 的内容区里：Scaffold 自己会铺一层 surface 色，
                // 放在它外面会被整块盖掉（实测底栏区域变成中性灰 48）。

                // ---- 预测性返回：左滑时页面跟手缩放位移，松手才真正返回 ----
                var backProgress by remember { mutableFloatStateOf(0f) }
                val backTarget: Page? = when (page) {
                    Page.DETAIL -> Page.REPORT
                    Page.DEVICE, Page.ABOUT -> Page.SETTINGS
                    else -> null
                }
                PredictiveBackHandler(enabled = backTarget != null) { progress ->
                    try {
                        progress.collect { backProgress = it.progress }
                        backProgress = 0f
                        backTarget?.let { page = it }
                    } catch (e: CancellationException) {
                        // 用户取消手势：回弹
                        backProgress = 0f
                    }
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        // 顶栏/底栏都不吃 padding：内容铺满全屏，
                        // 上下两端都从玻璃后面滑过，玻璃才有东西可折射。
                        // 顶部那份留白由 PageScroll 通过 LocalTopInset 自己加。
                        .background(colors.background)
                        .layerBackdrop(backdrop)
                        .graphicsLayer {
                            val p = backProgress
                            if (p > 0f) {
                                translationX = p * size.width * 0.22f
                                val sc = 1f - p * 0.07f
                                scaleX = sc
                                scaleY = sc
                            }
                        },
                ) {
                // PaddingValues.calculateTopPadding() 本身就是 Dp，不需要再换算
                val topInsetDp = padding.calculateTopPadding()
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalTopInset provides topInsetDp,
                ) {
                // ---- 左右滑动切页：只作用于 5 个主页面，详情/设备/关于不参与 ----
                val mainSeq = listOf(
                    Page.HOME, Page.SCAN, Page.REPORT, Page.HISTORY, Page.SETTINGS,
                )
                // 页面跟手滑动用的水平位移（手指拖多少，页面移多少）
                var contentDragX by remember { mutableFloatStateOf(0f) }
                val bottomGuardPx = with(LocalDensity.current) { 132.dp.toPx() }

                // ★ 页面左右滑动暂时关闭。
                //   实测三版：只要页面容器上挂手势，按钮/开关的点击就会丢
                //   （Compose 的 scrollable 与页面手势都会消费 DOWN）。
                //   这一版先保证点击 100% 可用，滑动切页下一轮用
                //   HorizontalPager 重新接入（Pager 内部手势与点击天然兼容）。
                val pagerModifier = Modifier

                // 页面切换：跟着 tab 的方向横向滑入滑出（不是硬切）
                AnimatedContent(
                    targetState = page,
                    transitionSpec = {
                        val fwd = pageRank(targetState) >= pageRank(initialState)
                        val spec = spring<IntOffset>(dampingRatio = 0.88f, stiffness = 640f)
                        // 旧页同时淡出：只靠位移会让两页叠在一起
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
                    modifier = Modifier
                        .fillMaxSize()
                        .offset { IntOffset(contentDragX.roundToInt(), 0) }
                        .then(pagerModifier),
                ) { current ->
                when (current) {
                    Page.HOME -> HomePage(
                        result = result,
                        scanning = scan.scanning,
                        onScan = { startScan() },
                        onDevice = { page = Page.DEVICE },
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
                }
                }

                // 液态玻璃底栏：浮在内容之上，采样它背后的页面内容
                BottomNav(
                    page = page,
                    backdrop = backdrop,
                    onSelect = {
                        // 导航埋点：供自动化回归判断「拖动/点击是否真的切了页」
                        android.util.Log.i("RootSentinel", "nav=$it")
                        page = it
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

/**
 * 液态玻璃的内容底：一层柔和的冷暖渐变 + 两个大光斑。
 * 不需要任何权限（不读壁纸），深浅主题各一套。
 */
@Composable
private fun LgBackdrop(dark: Boolean) {
    val base = if (dark) {
        Brush.linearGradient(
            colors = listOf(
                Color(0xFF0B1020), Color(0xFF16213A),
                Color(0xFF241834), Color(0xFF0B1020),
            ),
            start = Offset.Zero,
            end = Offset(1100f, 2800f),
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                Color(0xFFF6F8FF), Color(0xFFE7EDFF),
                Color(0xFFF4E9FF), Color(0xFFFDFDFF),
            ),
            start = Offset.Zero,
            end = Offset(1100f, 2800f),
        )
    }
    Box(Modifier.fillMaxSize().background(base)) {
        // 两个大光斑：让玻璃滑过时边缘能折出颜色变化
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            if (dark) Color(0x552F6BFF) else Color(0x332F6BFF),
                            Color.Transparent,
                        ),
                        center = Offset(260f, 520f),
                        radius = 1000f,
                    ),
                ),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            if (dark) Color(0x44B14BFF) else Color(0x22C86BFF),
                            Color.Transparent,
                        ),
                        center = Offset(1000f, 1700f),
                        radius = 1200f,
                    ),
                ),
        )
        // 底部光斑：底栏正好压在这块上，玻璃才有颜色可折
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            if (dark) Color(0x551E7BFF) else Color(0x2A65A8FF),
                            Color.Transparent,
                        ),
                        center = Offset(320f, 2520f),
                        radius = 1100f,
                    ),
                ),
        )
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

/**
 * 把「页面跟手位移」平滑收回到 0。
 *
 * 为什么不能直接在 pointerInput 里调 animate()：
 * awaitEachGesture 所在的作用域只允许调用它的成员挂起函数，
 * 调顶层挂起函数会编译报 “Restricted suspending functions”。
 * 所以用组合层的 scope.launch 发起，返回 true 表示「已启动平滑收尾」。
 */
private fun startSmoothRelease(
    from: Float,
    durationMs: Int,
    scope: kotlinx.coroutines.CoroutineScope,
    set: (Float) -> Unit,
): Boolean {
    if (kotlin.math.abs(from) <= 1f) return false
    scope.launch {
        try {
            androidx.compose.animation.core.animate(
                initialValue = from,
                targetValue = 0f,
                animationSpec = androidx.compose.animation.core.tween(durationMs),
            ) { v, _ -> set(v) }
        } catch (_: Throwable) {
            set(0f)
        }
    }
    return true
}
