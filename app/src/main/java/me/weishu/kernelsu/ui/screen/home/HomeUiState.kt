package me.weishu.kernelsu.ui.screen.home

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext

/**
 * RootSentinel seam for KernelSU's HomeUiState.
 *
 * KernelSU's state carries its kernel facts (ksuVersion, LKM/GKI mode, UAPI
 * versions). RootSentinel's home shows its own real facts instead — the scan
 * verdict and the device info — so this data class holds RootSentinel's
 * fields. The pager composables (HomeMiuix / HomeMaterial) keep KernelSU's
 * screen structure and card designs; only the bindings read these fields.
 */
@Immutable
data class SystemInfo(
    val appVersion: String,
    val kernelVersion: String,
    val deviceModel: String,
    val fingerprint: String,
    val selinuxStatus: String,
)

@Immutable
data class HomeUiState(
    val checkUpdateEnabled: Boolean,
    val hasUpdate: Boolean,
    val latestVersionName: String,
    val showIncompleteWarning: Boolean,
    val showHidingWarning: Boolean,
    val hasResult: Boolean,
    val scanning: Boolean,
    val scanModuleName: String,
    val scanPercent: Int,
    /** 结论词：正常 / 可疑 / 已提权 / 已提权+隐藏 / 无法判定 / 尚未检测 / 正在扫描 */
    val gradeWord: String,
    /** 状态卡副行：ROOT 分 · 覆盖率 · 命中/未知计数 */
    val gradeSummary: String,
    /** 0 未检测/扫描中，1 正常，2 可疑，3 已提权（含隐藏），4 无法判定 */
    val gradeKind: Int,
    val systemInfo: SystemInfo,
    /** v2.22.70：SukiSU Ultra 风格（沉浸式主页）开关与背景图路径（"" = 内置默认图）。 */
    val sukisuStyle: Boolean = false,
    val homeBgPath: String = "",
)

@Immutable
data class HomeActions(
    val onScanClick: () -> Unit,
    val onReportClick: () -> Unit,
    val onUpdateClick: () -> Unit,
    val onOpenUrl: (String) -> Unit,
    val onDeviceClick: () -> Unit,
)

/**
 * v2.22.70 (RootSentinel seam): SukiSU 风格的沉浸式主页背景层。
 * 自定义图（filesDir 副本）优先，否则用内置默认图（assets/sukisu_home_bg.webp）。
 * SukiSU 本体用 Coil 加载背景；RS 的依赖栈没有 Coil，这里用 BitmapFactory
 * 直读——静态背景图不需要异步加载管线，视觉结果一致。
 */
@Composable
fun HomeBackgroundImage(path: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val bitmap = remember(path) {
        runCatching {
            if (path.isNotEmpty()) {
                BitmapFactory.decodeFile(path)
            } else {
                ctx.assets.open("sukisu_home_bg.webp").use { BitmapFactory.decodeStream(it) }
            }
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}
