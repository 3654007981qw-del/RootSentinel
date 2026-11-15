package me.weishu.kernelsu.ui.screen.home

import androidx.compose.runtime.Immutable

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
)

@Immutable
data class HomeActions(
    val onScanClick: () -> Unit,
    val onReportClick: () -> Unit,
    val onUpdateClick: () -> Unit,
    val onOpenUrl: (String) -> Unit,
    val onDeviceClick: () -> Unit,
)
