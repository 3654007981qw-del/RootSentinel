package com.rootsentinel

import android.app.ActivityManager
import android.content.Context
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp

/**
 * 真正的液态玻璃（v2.6.0）
 *
 * ## 数学来源
 * 折射公式取自 backdrop 的官方实现
 * （`com.kyant.backdrop.internal.Shaders.RoundedRectRefractionShaderString`，
 *  经 jtt0001/liquid-glass-demo 仓库内随附的 backdrop 源码逐行核对）：
 *
 * ```glsl
 * float circleMap(float x) { return 1.0 - sqrt(1.0 - x * x); }      // 球面透镜曲线
 * float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
 * float2 grad = normalize(gradSdRoundedRect(...) + depthEffect * normalize(centeredCoord));
 * float2 refractedCoord = coord + d * grad;
 * ```
 *
 * ## 与之前自己写的版本相比，修掉的五处错误
 *
 * 1. **折射曲线**：原来用 `h*h*h` 三次方，官方是 `circleMap = 1 - sqrt(1 - x²)`（圆方程）。
 *    三次方衰减太快，只有极边缘有一点位移；圆方程才是球面透镜的真实形状。
 * 2. **放大靠负号**：`refractionAmount` 传**负值**时，采样点沿梯度向内收，
 *    画面就被放大。之前额外加了一个 `magnify` 去模拟，方向重复且互相干扰。
 * 3. **深度感**：官方在梯度里混入 `depthEffect * normalize(centeredCoord)`（从中心指向外的方向），
 *    让中心也有轻微推移。之前完全没有这一项。
 * 4. **不做模糊**：官方 `content.eval(refractedCoord)` 直接采样，玻璃是**清晰**的，
 *    只是被折射和放大。之前用九点模糊把内容糊成一片，所以怎么调都不像参考图。
 * 5. **早退**：`if (-sd >= refractionHeight) return content.eval(coord);`
 *    离边缘超过折射带宽度的像素原样返回 —— 玻璃内部大面积像素几乎零成本。
 *    之前每个像素都跑九点模糊，白白多算 9 次采样。
 *
 * 色差也换成官方写法：`(x * y) / (halfW * halfH)`，让四个象限的色散方向不同，
 * 而不是整圈朝同一个方向偏。
 */

private const val GLASS_AGSL = """
// ============================================================
// v2.8.0：直接移植 KSU 管理器（me.inkdye.vivoksu）的液态玻璃 shader
//   来源：classes.dex 中 3149 字节的
//         "LiquidGlassLensDispersion" / "LiquidGlassLens"
//   注入点：qr1.N(sn0, refractionHeight, -refractionAmount, depthEffect, chromaticAberration)
//
//   与原实现的差别：
//     ① 色散从 3 点补采样 → **7 点（红橙黄绿青蓝紫）**，权重 1/3.5、1/3.0、1/7.0
//     ② 圆角改成四角独立（float4），为不同角半径的卡片留能力
//     ③ 梯度半径用 min(radius*1.5, minHalf)，避免角上梯度归一化后方向突变
//     ④ 色散路径不做模糊（KSU 里 LiquidGlassLensDispersion 就是纯折射）
// ============================================================
uniform shader child;
uniform float2 uSize;
uniform float2 uOffset;
uniform float uRadius;
uniform float uLighten;
uniform float uRefractHeight;
uniform float uRefractAmount;
uniform float uDepth;
uniform float uDispersion;
uniform float uBlur;

// 半径统一（KSU 的 radiusAt 支持四角独立，但 RuntimeShader 的
// float4 数组 uniform 在 AGSL 上会抛 "unable to find uniform"，
// 而我们的形状（胶囊/圆）四角同值，所以收回标量）
// 圆角矩形 SDF（KSU sdRoundedRect 原样）
float sdRoundBox(float2 p, float2 b, float r) {
    float2 q = abs(p) - (b - float2(r));
    float outside = length(max(q, 0.0)) - r;
    float inside = min(max(q.x, q.y), 0.0);
    return outside + inside;
}
// SDF 解析梯度（KSU gradSdRoundedRect 原样）
float2 gradSdRoundBox(float2 p, float2 b, float r) {
    float2 q = abs(p) - (b - float2(r));
    if (q.x >= 0.0 || q.y >= 0.0) {
        return sign(p) * normalize(max(q, float2(0.0001, 0.0001)));
    }
    float gx = step(q.y, q.x);
    return sign(p) * float2(gx, 1.0 - gx);
}
// 球面透镜曲线（KSU circleMap 原样）
float circleMap(float x) {
    return 1.0 - sqrt(max(1.0 - x * x, 0.0));
}
// 五点模糊：只在无色散时使用（底栏面板）
half4 evalBlur(float2 c, float r) {
    if (r < 0.5) {
        return child.eval(c);
    }
    half4 s = child.eval(c) * half(0.40);
    s += child.eval(c + float2(r, 0.0)) * half(0.15);
    s += child.eval(c - float2(r, 0.0)) * half(0.15);
    s += child.eval(c + float2(0.0, r)) * half(0.15);
    s += child.eval(c - float2(0.0, r)) * half(0.15);
    return s;
}
half4 main(float2 coord) {
    float2 halfSize = uSize * 0.5;
    float2 centered = (coord - uOffset) - halfSize;
    float radius = uRadius;
    float sd = sdRoundBox(centered, halfSize, radius);

    // 早退：离边缘超过折射高度，直接输出（KSU: if (-sd >= refractionHeight)）
    if (-sd >= uRefractHeight) {
        return evalBlur(coord, uBlur);
    }

    float sdIn = min(sd, 0.0);
    float d = circleMap(1.0 + sdIn / max(uRefractHeight, 0.001)) * uRefractAmount;

    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(
        gradSdRoundBox(centered, halfSize, gradRadius)
        + uDepth * normalize(centered + float2(0.0001, 0.0001))
    );
    float2 refracted = coord + d * grad;

    half4 c;
    if (uDispersion > 0.0001) {
        // ==== KSU 的 7 点色散，逐行照搬 ====
        float dispersionIntensity = uDispersion
            * ((centered.x * centered.y) / (halfSize.x * halfSize.y + 0.0001));
        float2 dc = d * grad * dispersionIntensity;
        half4 col = half4(0.0);
        half4 red = child.eval(refracted + dc);
        col.r += red.r / 3.5;
        col.a += red.a / 7.0;
        half4 orange = child.eval(refracted + dc * (2.0 / 3.0));
        col.r += orange.r / 3.5;
        col.g += orange.g / 7.0;
        col.a += orange.a / 7.0;
        half4 yellow = child.eval(refracted + dc * (1.0 / 3.0));
        col.r += yellow.r / 3.5;
        col.g += yellow.g / 3.5;
        col.a += yellow.a / 7.0;
        half4 green = child.eval(refracted);
        col.g += green.g / 3.5;
        col.a += green.a / 7.0;
        half4 cyan = child.eval(refracted - dc * (1.0 / 3.0));
        col.g += cyan.g / 3.5;
        col.b += cyan.b / 3.0;
        col.a += cyan.a / 7.0;
        half4 blue = child.eval(refracted - dc * (2.0 / 3.0));
        col.b += blue.b / 3.0;
        col.a += blue.a / 7.0;
        half4 purple = child.eval(refracted - dc);
        col.r += purple.r / 7.0;
        col.b += purple.b / 3.0;
        col.a += purple.a / 7.0;
        c = col;
    } else {
        c = evalBlur(refracted, uBlur);
    }

    float a = max(c.a, 0.0001);
    half3 rgb = half3(c.rgb) / half(a);
    // vibrancy()：官方 drawBackdrop 的第一个 effect，提升玻璃内色彩的饱和度。
    // 少了这一步，玻璃里的内容会显得"发灰"，跟周围页面不在一个观感上。
    half lum = dot(rgb, half3(0.299, 0.587, 0.114));
    rgb = mix(half3(lum), rgb, half3(1.35));
    rgb = mix(rgb, half3(1.0), half(uLighten));
    return half4(rgb * half(a), half(a));
}
"""

/** 是否支持 AGSL（Android 13+） */
val isGlassSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/**
 * 液态玻璃的三级质量回退。
 *
 * 设计来源：NadeemIqbal/liquid-glass 的 Full / Medium / Fallback 分级。
 *
 * 为什么要分级：AGSL 是逐像素的 GPU 程序，
 * 而 `RenderEffect` 处理的是**整个图层**，低端机上叠加整屏图层会掉帧甚至 OOM。
 */
enum class GlassQuality {
    /** 全量：折射 + 放大 + 色差 + 深度 */
    FULL,

    /** 简化：只做折射与放大，关掉色差（省掉 2 次 eval） */
    MEDIUM,

    /** 降级：API < 33 没有 RuntimeShader，只画半透明色块 */
    FALLBACK,
}

/** 按设备能力选档：API < 33 无 RuntimeShader；低内存设备降一档 */
fun detectGlassQuality(context: Context? = null): GlassQuality {
    if (!isGlassSupported) return GlassQuality.FALLBACK
    val am = context?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    if (am != null && am.isLowRamDevice) return GlassQuality.MEDIUM
    return GlassQuality.FULL
}

/**
 * 背景采样层。
 *
 * 除了 `GraphicsLayer` 本身，还要记住**录制内容的那个节点在窗口中的位置**。
 *
 * `layer.record { drawContent() }` 记录的是**节点自己的局部坐标**，
 * 即 layer 的 (0,0) = 内容节点左上角，而内容节点通常不在窗口 (0,0)。
 * 玻璃如果拿「自己相对 root 的位置」当采样原点，就会整体错开一个顶栏高度，
 * 采到完全不相干的地方 —— 这是前面几版错位的真正原因。
 */
class GlassBackdrop(val layer: GraphicsLayer) {
    var sourceOffset by mutableStateOf(Offset.Zero)
}

/** 背景层：内容画进它，玻璃才能采样 */
@Composable
fun rememberGlassBackdrop(): GlassBackdrop {
    // GraphicsLayer 的构造函数是 internal，只能通过 rememberGraphicsLayer() 创建
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdrop(layer) }
}

/**
 * 内容侧：把绘制结果录进 layer 并记住节点位置，但**自己仍然直接绘制**。
 *
 * 这里绝对不能写 `drawLayer(layer)` ——
 * layer 上挂着 renderEffect，那样会把整页内容也送去采样，
 * 表现就是文字重影、数字拖尾（v2.5.3 的真实事故）。
 *
 * 支持多个 backdrop：底栏里「长胶囊 + 圆形按钮」是两块形状和参数都不同的玻璃，
 * 而 `GraphicsLayer.renderEffect` 是**单个属性** —— 共用一份 layer 的话，
 * 后设置的会把先设置的覆盖掉（backdrop 作者在 GlassShaders.kt 里也专门标注了这点）。
 * 所以每块玻璃各要一份 layer，代价是内容多录制一遍。
 */
fun Modifier.glassSource(vararg backdrops: GlassBackdrop): Modifier = this
    .onGloballyPositioned { coords ->
        val pos = coords.positionInRoot()
        backdrops.forEach { it.sourceOffset = pos }
    }
    .drawWithContent {
        backdrops.forEach { bd ->
            bd.layer.record { this@drawWithContent.drawContent() }
        }
        drawContent()
    }

/**
 * 玻璃侧：把背景层透过当前形状画出来，做折射 + 放大 + 色差 + 提亮。
 *
 * @param backdrop       背景层（来自 [rememberGlassBackdrop]）
 * @param shape          玻璃形状
 * @param cornerRadiusPx 圆角像素（与 shape 保持一致，用于 SDF）
 * @param lighten        提亮比例 0..1
 * @param blur           磨砂强度（px），围绕折射后的坐标做五点模糊；0 = 纯折射
 * @param refractHeight  折射带宽度（px）——只有离边缘这么近的区域才会折射
 * @param refractAmount  折射位移量（px）。**负值 = 放大**，正值 = 缩小
 * @param dispersion     色差强度，0 = 关闭
 * @param depth          是否启用深度感（中心也有轻微推移）
 * @param tint           玻璃自身着色
 * @param quality        质量档位，见 [GlassQuality]
 */
@Composable
fun rememberLiquidGlass(
    backdrop: GlassBackdrop,
    shape: Shape = RoundedCornerShape(30.dp),
    cornerRadiusPx: Float = 90f,
    lighten: Float = 0.10f,
    blur: Float = 14f,
    refractHeight: Float = 60f,
    refractAmount: Float = -22f,
    dispersion: Float = 0.5f,
    depth: Boolean = true,
    tint: Color = Color.White.copy(alpha = 0.10f),
    quality: GlassQuality = detectGlassQuality(),
): Modifier {
    if (quality == GlassQuality.FALLBACK || !isGlassSupported) {
        // Android 13 以下（或强制降级）：没有 RuntimeShader，降级成纯半透明面
        return Modifier.drawBehind {
            val path = Path().apply {
                addOutline(shape.createOutline(size, layoutDirection, this@drawBehind))
            }
            drawPath(path, tint)
        }
    }

    val shader = remember { RuntimeShader(GLASS_AGSL) }
    var glassOffset by remember { mutableStateOf(Offset.Zero) }

    // 同样必须缓存：shader 实例是固定的，effect 对象可以复用。
    // 每帧在 drawBehind 里新建会带来和磨砂那边一样的 GPU 资源抖动。
    val shaderEffect = remember(shader) {
        android.graphics.RenderEffect
            .createRuntimeShaderEffect(shader, "child")
            .asComposeRenderEffect()
    }

    // MEDIUM 档：关掉色差与深度（省 2 次 eval），折射放大保留
    val full = quality == GlassQuality.FULL
    val effDispersion = if (full) dispersion else 0f
    val effDepth = if (full && depth) 1f else 0f
    val effBlur = if (full) blur else blur * 0.7f

    return Modifier
        .onGloballyPositioned { coords -> glassOffset = coords.positionInRoot() }
        .drawBehind {
            // path 构造在「组件本地坐标」里，即 (0,0)..(size)
            val path = Path().apply {
                addOutline(shape.createOutline(size, layoutDirection, this@drawBehind))
            }

            // 玻璃左上角相对「内容原点的窗口位置」的偏移。
            // layer 的 (0,0) 对应窗口的 sourceOffset，
            // 所以玻璃在 layer 坐标系里的位置就是这个相对值。
            val relX = glassOffset.x - backdrop.sourceOffset.x
            val relY = glassOffset.y - backdrop.sourceOffset.y

            // 关键顺序：
            //   裁剪在外 —— 用本地坐标的 path，圈住的正是组件自己那块区域；
            //   平移在内 —— layer 记录的是内容局部坐标，
            //              平移 -rel 后才能对齐到组件本地位置。
            clipPath(path) {
                // 玻璃自身着色
                drawPath(path, tint)

                if (isGlassSupported) {
                    translate(left = -relX, top = -relY) {
                        shader.setFloatUniform("uSize", size.width, size.height)
                        shader.setFloatUniform("uOffset", relX, relY)
                        shader.setFloatUniform("uRadius", cornerRadiusPx)
                        shader.setFloatUniform("uLighten", lighten)
                        shader.setFloatUniform("uBlur", effBlur)
                        shader.setFloatUniform("uRefractHeight", refractHeight)
                        shader.setFloatUniform("uRefractAmount", refractAmount)
                        shader.setFloatUniform("uDepth", effDepth)
                        shader.setFloatUniform("uDispersion", effDispersion)

                        // child 采样的就是背景层内容
                        backdrop.layer.renderEffect = shaderEffect
                        drawLayer(backdrop.layer)
                    }
                    // 采样完成后再把 tint 叠上来。
                    // 之前只在 drawLayer 之前画了一次 tint，被不透明的采样内容完全盖住，
                    // 于是"玻璃颜色"永远等于背后内容的模糊版 ——
                    // 在纯色背景上实测面板和背景都是 250~255，等于没有玻璃。
                    drawPath(path, tint)
                }
            }
        }
}

/**
 * 磨砂玻璃：**系统原生高斯模糊** + 半透明着色。
 *
 * 与 [rememberLiquidGlass] 的分工：
 *   · 这个 —— 只模糊、不折射、不放大、不色差。适合当底栏的底板。
 *   · 那个 —— 折射 + 放大 + 色差，适合小面积、需要"凸起"的元素（选中指示器）。
 *
 * 为什么底栏要换成这个：底栏是横贯屏幕的一条长条，
 * 折射会把背后的文字拉出弧形变形，面积一大就显得"花"、读不出内容；
 * 而磨砂只降低清晰度、不改变形状，背后内容仍然可辨。
 *
 * 用 `RenderEffect.createBlurEffect` 而不是自己写 AGSL 五点采样，原因是：
 *   · 它是系统级 GPU 模糊，质量比五点近似好得多（真正的多趟高斯）
 *   · 不需要额外采样次数，性能更好
 *
 * @param blurRadius 模糊半径（px）。注意单位是像素，不是 dp
 */
@Composable
fun rememberFrostedGlass(
    backdrop: GlassBackdrop,
    shape: Shape,
    blurRadius: Float = 60f,
    tint: Color = Color.White.copy(alpha = 0.55f),
): Modifier {
    var glassOffset by remember { mutableStateOf(Offset.Zero) }

    // ⚠ 关键：RenderEffect **必须缓存**。
    // drawBehind 是每帧执行的，如果在里面 `createBlurEffect(...)`，
    // 就会每帧新建一个模糊对象 → GPU 资源反复分配 → 页面滚动时背后的内容闪烁。
    // 这里按 blurRadius 记住一份，之后每帧只是把它赋给 layer。
    val blurEffect = remember(blurRadius) {
        if (isGlassSupported && blurRadius >= 1f) {
            android.graphics.RenderEffect
                .createBlurEffect(blurRadius, blurRadius, android.graphics.Shader.TileMode.CLAMP)
                .asComposeRenderEffect()
        } else {
            null
        }
    }

    return Modifier
        .onGloballyPositioned { coords -> glassOffset = coords.positionInRoot() }
        .drawBehind {
            val path = Path().apply {
                addOutline(shape.createOutline(size, layoutDirection, this@drawBehind))
            }

            val relX = glassOffset.x - backdrop.sourceOffset.x
            val relY = glassOffset.y - backdrop.sourceOffset.y

            clipPath(path) {
                // 磨砂层自身的着色
                drawPath(path, tint)

                // 底层内容的高斯模糊；API < 31 无 RenderEffect 时只剩 tint
                if (blurEffect != null) {
                    translate(left = -relX, top = -relY) {
                        backdrop.layer.renderEffect = blurEffect
                        drawLayer(backdrop.layer)
                    }
                }
            }
        }
}