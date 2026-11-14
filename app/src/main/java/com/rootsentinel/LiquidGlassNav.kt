package com.rootsentinel

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 底部导航 · iOS 26 风格液态玻璃（v2.17）
 *
 * 结构（关键）：
 *   每一块玻璃 = **玻璃层 + 独立的着色层**，用兄弟节点叠起来。
 *   之前把着色画在 `.drawWithContent` 里，会被 drawBackdrop 的玻璃层整个盖掉，
 *   所以圆块看着和底栏一个颜色、怎么调都没反应。
 *
 * iOS 26 的观感来源（按重要性）：
 *   ① 边缘强折射（lens）+ 色散（chromaticAberration）→ 边缘把背后内容弯折/彩边
 *   ② 边缘亮线高光（highlight）+ 内阴影（innerShadow）→ 玻璃的"厚度"
 *   ③ 极淡的填充（tint 只有 6~18%）→ 主体是透明的，看得见后面内容
 *   ④ 模糊只是中等的（12dp 左右），不能糊成一团
 */
val BottomNavHeight = 112.dp

private val BarHeight = 58.dp
private val KnobSize = 58.dp
private val BarPadH = 20.dp
private val BarPadBottom = 24.dp
private val TabIconSize = 23.dp

@Composable
fun BottomNav(
    page: Page,
    backdrop: Backdrop,
    onSelect: (Page) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MiuixTheme.colorScheme
    val dark = c.background.luminance() < 0.5f

    // ============ iOS 26 通透档（数值取自 liquid-glass-demo 的 GlassParameters.mapping）============
    //   backgroundTransmission = 0.985   ← 背景几乎全保留
    //   materialOpacity        = 0.014   ← 白色材质只有 1.4%
    //   tintOpacity            = 0.004   ← 色调 0.4%
    // 三项合并后就是下面这个 alpha。之前写的是 0.30 / 0.52 —— 比官方高 20~37 倍，
    // 玻璃被一层白漆盖住，所以才"怎么调都不像玻璃"。
    // 深色下要略高一点，否则黑背景上完全区分不出玻璃。
    // iOS26 通透档（jtt0001/liquid-glass-demo 的 GlassParameters.mapping，t = 0.94）：
    //   materialOpacity = 0.014
    //   tintOpacity     = 0.004
    // 两者合并成一层非常薄的表面色。之前用的 0.4 是 skill 仓库那套“深色照片背景”
    // 的通用规范，放在我们的浅色纯底上就是一块白塑料 —— 这就是“不通透”的根因。
    // 通透档的 0.014 放在纯色页面上，底栏和背景几乎同色 —— 于是圆块凸出与否
    // 都没有参照物（你看到的“没溢出”就是这么来的）。抬到 0.05，
    // 底栏作为一条玻璃带能被看见，仍然是通透的。
    val containerColor = if (dark) Color(0xFF121212).copy(alpha = 0.05f)
    else Color(0xFFFAFAFA).copy(alpha = 0.05f)
    // 圆块要更通透：白填充再降一档，主要靠边缘的折射/高光成形
    // 圆块用同一档通透度（iOS26 的选中胶囊几乎只有边缘亮线）
    val knobColor = if (dark) Color(0xFF121212).copy(alpha = 0.018f)
    else Color(0xFFFAFAFA).copy(alpha = 0.018f)

    val accentColor = if (dark) Color.White else c.primary
    val idleColor = if (dark) Color.White.copy(alpha = 0.62f) else c.onBackgroundVariant

    val density = LocalDensity.current
    val tabsBackdrop = rememberLayerBackdrop()
    // 圆块的玻璃要同时采样「页面内容」和「底栏图标」：
    // 图标放在玻璃下面，玻璃折射它 —— 这才是 iOS 那种"滑过图标时图标被压弯"的效果。
    // 之前图标在玻璃**上面**（为了不被盖掉），自然完全不参与折射。
    val knobBackdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop)

    val tabs: List<Triple<String, Page, ImageVector>> = listOf(
        Triple("首页", Page.HOME, Icons.Filled.Home),
        Triple("扫描", Page.SCAN, Icons.Filled.Search),
        Triple("报告", Page.REPORT, Icons.Filled.List),
        Triple("历史", Page.HISTORY, Icons.Filled.DateRange),
        Triple("设置", Page.SETTINGS, Icons.Filled.Settings),
    )
    val lastIndex = (tabs.size - 1).toFloat()

    val targetIndex = tabs.indexOfFirst { (_, target, _) ->
        target == page ||
            (target == Page.REPORT && page == Page.DETAIL) ||
            (target == Page.SETTINGS && (page == Page.DEVICE || page == Page.ABOUT))
    }.coerceAtLeast(0)

    val settle = remember { Animatable(targetIndex.toFloat()) }
    // 每次手势结束都自增，强制把圆块重新对齐到 targetIndex。
    // 只靠 LaunchedEffect(targetIndex) 不够：targetIndex 没变时它不会重启，
    // 于是「圆块被拖走了、页面却没变」这种错位永远修不回来。
    var syncTick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var dragRaw by remember { mutableFloatStateOf(targetIndex.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    // 松手后让“鼓起”再保持 220ms：凸出只在按住时存在，一抬手就缩回，
    // 肉眼几乎抓不到。这段保持让抬手瞬间也能看见形状。
    var releaseHold by remember { mutableStateOf(false) }
    // 拖动结束时先把 settle 对齐到手指离开的位置，再让动画接管。
    // 否则：拖动中圆块由 dragRaw 驱动、settle 还停在拖动前那一格；
    // 松手后驱动源切回 settle，圆块就会先闪回上一格再弹到落点。
    var snapToDrag by remember { mutableStateOf(false) }
    LaunchedEffect(releaseHold) {
        if (releaseHold) {
            kotlinx.coroutines.delay(160)
            releaseHold = false
        }
    }
    val scope = rememberCoroutineScope()

    // ★ page 是唯一真相源：只要 targetIndex 变了，圆块就必须滑过去。
    // 之前加了 `if (!dragging)` 条件，而拖动结束时是「先 onSelect 再 dragging=false」，
    // 于是 LaunchedEffect 被跳过、只有 onDragEnd 里的 animateTo 在跑，
    // 两个驱动源一旦目标不一致，就会出现「圆块停在报告、页面却是首页」。
    LaunchedEffect(targetIndex, syncTick) {
        // 手指正在控制圆块时不要抢：这时候 settle 不是驱动源
        if (dragging) return@LaunchedEffect
        // 拖动刚结束：把 settle 直接搬到手指离开的位置（无跳变），再往落点动画
        if (snapToDrag) {
            settle.snapTo(dragRaw)
            snapToDrag = false
        }
        // 关键：把当前速度接续下去。
        // 快速连续切页时 targetIndex 会连着变，animateTo 被反复取消重启；
        // 每次重启速度都归零，表现就是圆块一颊一顿地"抽搐"。
        val v0 = settle.velocity
        settle.animateTo(
            targetValue = targetIndex.toFloat(),
            animationSpec = spring(
                dampingRatio = 0.88f,
                stiffness = 700f,
                visibilityThreshold = 0.001f,
            ),
            initialVelocity = v0,
        )
    }

    val press by animateFloatAsState(
        targetValue = if (dragging || releaseHold) 1f else 0f,
        // 响应要快：拖动开始时尺寸/亮度几乎立即跟上，否则会显得"黏"
        // 0.72/800 太快（~0.15s 就结束，几乎看不到凸出）；
        // 放成 0.62/420：鼓起更软、松手回落带一点回弹，约 0.5s
        // 下落要快：0.70/820（之前 0.62/420 太软，落回拖泥带水）
        animationSpec = spring(dampingRatio = 0.70f, stiffness = 820f),
        label = "pressProgress",
    )

    var barWidthPx by remember { mutableFloatStateOf(0f) }
    val tabWPx = (barWidthPx / tabs.size).coerceAtLeast(1f)

    val rawValue = if (dragging) dragRaw else settle.value
    val clampedValue = rawValue.coerceIn(0f, lastIndex)
    val overshoot = rawValue - clampedValue
    // 越界只显示 10%，并且**硬限制在 ±0.12 格**内：
    // 之前 0.28 且没有上限，一直往外拖会把圆块整个推出底栏（甚至推出屏幕）。
    val displayValue = (clampedValue + overshoot * 0.10f)
        .coerceIn(-0.12f, lastIndex + 0.12f)
    val wallSquash = (abs(overshoot) * 1.3f).coerceIn(0f, 0.18f)
    val offFromTab = displayValue - displayValue.roundToInt().toFloat()
    val stretch = (abs(offFromTab) * 2f).coerceIn(0f, 1f)

    // 拖动/按下：横向拉伸 + 上下各溢 2dp（按压鼓胀）
    // 原来横向 +22dp、纵向只有 press 时才高，结果拖动中是横椭圆。
    // 现在高度已经是 88dp，横向只需一点拉伸，保持“接近正圆”
    val knobW = KnobSize + 10.dp * press + 6.dp * stretch
    // 拖动时上下鼓起。底栏高也是 58dp（BarHeight == KnobSize），
    // 所以静止时上下各溢 0dp，这里多出来的部分上下均分：
    //   12dp → 上下各 6dp（太薄，加上玻璃几乎全透就完全看不出来）
    //   20dp → 上下各 10dp（能看出“压出底栏”的效果）
    // ★ 只有拖动时凸出底栏：
    //   静止  = KnobSize(58dp) = BarHeight，上下不凸，完全在底栏内
    //   拖动  = 58 + 30 = 88dp，上下各凸 15dp（压出底栏的鼓胀感）
    //   中间由 press 弹簧插值，抬手自动落回底栏内。
    // 拖动中 58 → 74dp，上下各凸 8dp（原来的 20dp 太高了）
    val knobH = KnobSize + 16.dp * press
    val knobPx = with(density) { knobW.toPx() }
    // 底栏跟着胶囊轻微左右移动。
    // 必须用「相对最近格」的偏移，不能用相对 targetIndex：
    // 快速切页时 targetIndex 会瞬间跳一格，而 displayValue 还在动画路上，
    // 两者相减会让底栏位移在一帧内跳变 ——这就是快速滑动时底栏抽搐的原因。
    val barShiftPx = (displayValue - displayValue.roundToInt()) * tabWPx * 0.18f

    /**
     * 指针处理：**一个**手势循环同时负责「点击切页」和「拖动圆块」。
     *
     * 之前 tap 和 drag 是两个独立 pointerInput：真实手指按下时总带几个像素抖动，
     * 两个手势会互相抢，结果就是"拖动好了点击就丢"。合并成一个循环后，
     * 由位移是否超过 touchSlop 来区分两者，不存在竞争。
     */
    val gestureModifier = Modifier.pointerInput(tabWPx, lastIndex) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val downX = down.position.x
            var moved = false
            // 必须用**累计**位移判断是否越过 touchSlop：
            // 真实手指（以及 input swipe）每帧只移动几像素，单帧永远达不到 slop，
            // 按单帧判断会把整次拖动误判成点击。
            var accDx = 0f

            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val dx = change.positionChange().x

                if (!moved) {
                    accDx += dx
                    if (abs(accDx) > viewConfiguration.touchSlop) {
                        moved = true
                        dragRaw = settle.value
                        dragging = true
                    }
                }

                if (moved && change.pressed) {
                    change.consume()
                    dragRaw += dx / tabWPx
                }

                if (!change.pressed) {
                    // 抬手：区分点击与拖动
                    if (!moved) {
                        val idx = (downX / tabWPx).toInt().coerceIn(0, lastIndex.toInt())
                        onSelect(tabs[idx].second)
                        syncTick++
                    } else {
                        val target = dragRaw.coerceIn(0f, lastIndex).roundToInt()
                        // 顺序很重要：先结束拖动、把游标对齐到落点，再切页。
                        // 切页之后统一由 LaunchedEffect(targetIndex) 把圆块滑到位，
                        // 避免两个协程抢 settle。
                        // 不在这里 snapTo（挂起函数，手势回调里不能直接调），
                        // 直接切页，settle 交给 LaunchedEffect 从当前位置滑到落点。
                        snapToDrag = true
                        releaseHold = true
                        dragging = false
                        onSelect(tabs[target].second)
                        syncTick++
                    }
                    break
                }
            }
        }
    }

    Row(
        modifier = modifier
            .padding(horizontal = BarPadH)
            .navigationBarsPadding()
            .padding(bottom = BarPadBottom)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ================= 底栏 =================
        Box(
            modifier = Modifier
                .weight(1f)
                .height(BarHeight)
                .onGloballyPositioned { barWidthPx = it.size.width.toFloat() },
        ) {
            // ① 玻璃层（拖动时整体轻微放大 + 跟着胶囊位移）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = barShiftPx
                        val g = 1f + press * 0.018f
                        scaleX = g
                        scaleY = 1f + press * 0.022f
                    }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        // ===== iOS26 通透档 =====
                        //   blurRadiusDp      = 0.8（通透端 transparentEnd）
                        //   refractionOffsetDp = 32（三档共用）
                        // refractionHeight 是「边缘折射带的宽度」，不是强度。
                        // 官方的 32dp 是在大面板上用的；底栏高 64dp，32dp 等于从上到下
                        // 没有一块平直区 → tab 文字全被拉变形。收到 10dp。
                        effects = {
                            vibrancy()
                            blur(0.8.dp.toPx())
                            lens(10.dp.toPx(), 12.dp.toPx())
                        },
                        //   edgeHighlightOpacity = 0.44（菲涅尔 fresnelStrength 1.32 的落地值）
                        highlight = { Highlight.Default.copy(alpha = 0.44f) },
                        //   iOS26 通透档没有外投影（只有 localDimming 0.02），更干净
                        shadow = null,
                        //   edgeShadowOpacity = 0.18，内侧倒角
                        // 上下暗边略强：底栏的上下边界看得见，圆块凸出才有参照
                        innerShadow = { InnerShadow(radius = 8.dp, alpha = 0.22f) },
                    )
                    .then(gestureModifier),
            )

            // ② 着色层（在玻璃**之上**，用 background 而不是 drawWithContent）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = barShiftPx
                        scaleX = 1f + press * 0.018f
                        scaleY = 1f + press * 0.022f
                    }
                    .background(containerColor, Capsule())
                    .then(gestureModifier),
            )

            // ③ tab 图标/文字
            // zIndex 2：必须画在圆块**之上**。
            // drawBackdrop 的玻璃是「替换」它覆盖区域的像素（它只采样页面内容图层，
            // 不包含下方兄弟节点），圆块在上层就会把图标整个吃掉。
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(1f)
                    .layerBackdrop(tabsBackdrop),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEach { (label, target, icon) ->
                    val active = page == target ||
                        (target == Page.REPORT && page == Page.DETAIL) ||
                        (target == Page.SETTINGS && (page == Page.DEVICE || page == Page.ABOUT))

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        // 点击由底部玻璃层的 tapModifier 统一处理（见上）
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = icon,
                            contentDescription = label,
                            tint = if (active) accentColor else idleColor,
                            modifier = Modifier.size(TabIconSize),
                        )
                        Text(
                            text = label,
                            fontSize = 11.sp,
                            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                            color = if (active) accentColor else idleColor,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }

            // ================= 圆形玻璃块（内部无任何图标/文字）=================
            if (barWidthPx > 0f) {
                Box(
                    modifier = Modifier
                        // 必须是 CenterStart，不能是 Center：
                        // offset 是相对「对齐基准点」的，用 Center 的话基准在屏幕正中，
                        // 于是 displayValue=0 时圆块也待在屏幕中间 —— 这就是
                        // 「胶囊在报告、页面却是首页」的真因（整体右移半屏 ≈ 2 格）。
                        // CenterStart = 垂直居中（上下溢出对称）+ 水平从左侧起算。
                        .align(Alignment.CenterStart)
                        .offset {
                            IntOffset(
                                x = (displayValue * tabWPx + (tabWPx - knobPx) / 2f).roundToInt(),
                                y = 0,
                            )
                        }
                        // ★ 必须用 requiredHeight：父容器是 Box(height(BarHeight)=58dp)，
                        // 它的 maxHeight 会传给子元素，普通 .height(98dp) 会被强制压回 58dp。
                        // 这就是“怎么调参数都不凸出、只会变宽”的真正原因。
                        .requiredHeight(knobH)
                        .requiredWidth(knobW)
                        .zIndex(2f)
                        .graphicsLayer {
                            // 撞墙：横向压扁、纵向略鼓
                            var sx = 1f - wallSquash
                            var sy = 1f + wallSquash * 0.35f
                            // 跨格拖动：横向再拉一点、纵向略微压扁（水袋式拉伸）
                            sx += stretch * 0.06f
                            sy -= stretch * 0.04f
                            scaleX = sx
                            scaleY = sy
                        }
                        .then(gestureModifier),
                ) {
                    // ① 玻璃层
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawBackdrop(
                                backdrop = knobBackdrop,
                                shape = { Capsule() },
                                // ===== iOS26 通透档 =====
                                // 色散关：通透档的 dispersionOffsetDp = 0 —— 彩边会把“干净”破掉
                                effects = {
                                    vibrancy()
                                    blur(0.8.dp.toPx())
                                    // 两个参数的分工：
                                    //   refractionHeight = 边缘折射带宽度（越大越“像水珠”）
                                    //   refractionAmount = 折射位移（= 色散强度，彩边就是它带出来的）
                                    // 32/32 在 59dp 的圆块上：折射带占满整个圆、位移 88px，
                                    // 图标必然被拉成一条条。改成 8/14：中间 43dp 平直，图标看得清，
                                    // 彩边只有原来的 3 成左右。
                                    lens(
                                        8.dp.toPx(),
                                        14.dp.toPx(),
                                        chromaticAberration = true,
                                    )
                                },
                                // highlight = Highlight.Default.copy(alpha = 0.15f + 0.35f * press)
                                // 菲涅尔 0.44，按压时再亮一点
                                highlight = {
                                    Highlight.Default.copy(
                                        alpha = 0.44f + 0.18f * press,
                                    )
                                },
                                // 通透档没有外投影，只留一丝保层次
                                shadow = {
                                    Shadow(alpha = 0.05f + 0.15f * press)
                                },
                                // 边缘内暗 0.18
                                innerShadow = {
                                    InnerShadow(
                                        radius = 8.dp,
                                        alpha = 0.18f + 0.12f * press,
                                    )
                                },
                            ),
                    )
                    // ② 着色层 + 描边（玻璃之上）
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(knobColor, Capsule())
                            .drawWithContent {
                                drawContent()
                                // 均匀描边：不依赖方向光，上下左右都有边界
                                drawRoundRect(
                                    // 这里只负责“有边界”，不负责“突出”。
                                    // 突出靠玻璃自身的折射 + 菲涅尔高光（0.44），描边一旦加粗加亮
                                    // 就变成一圈白边 —— 固定极细极淡，不跟随按压。
                                    color = if (dark) Color.White.copy(alpha = 0.12f)
                                    else Color.Black.copy(alpha = 0.08f),
                                    cornerRadius = CornerRadius(size.height / 2f),
                                    style = Stroke(width = 0.8.dp.toPx()),
                                )
                            },
                    )
                    // 内容留空：图标留在各自那一格里，圆只是滑过去的玻璃
                    Spacer(Modifier.fillMaxSize())
                }
            }
        }
    }
}

private fun Color.luminance(): Float {
    fun ch(v: Float): Float = if (v <= 0.03928f) v / 12.92f
    else Math.pow(((v + 0.055) / 1.055).toDouble(), 2.4).toFloat()
    return 0.2126f * ch(red) + 0.7152f * ch(green) + 0.0722f * ch(blue)
}