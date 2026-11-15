package com.rootsentinel

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rootsentinel.data.HistoryEntry
import com.rootsentinel.data.HistoryStore
import com.rootsentinel.data.SettingsStore
import com.rootsentinel.engine.DetectorEngine
import com.rootsentinel.model.Grade
import com.rootsentinel.model.Verdict
import com.rootsentinel.util.Updater

// 版式（v2.22.59）：与 PagesCore 同口径——KernelSU 管理器的
// 分组卡片列表；设置页分组顺序也照它：外观 → 数据 → 应用 → 关于。

// ============================================================ 历史

@Composable
fun HistoryPage(history: HistoryStore, onScan: () -> Unit, onClear: () -> Unit) {
    // 读 State：扫描 append 后历史页会自动刷新
    val list = history.entries

    if (list.isEmpty()) {
        PageScroll {
            Spacer(Modifier.height(12.dp))
            RsCard {
                Column {
                    RsText("还没有扫描记录", RsTextStyle.TitleM)
                    Spacer(Modifier.height(6.dp))
                    RsText("完成一次扫描后，摘要会保存在这里（最多 30 条）。", RsTextStyle.Label)
                }
            }
            Spacer(Modifier.height(16.dp))
            RsButton("开始检测", onClick = onScan, modifier = Modifier.fillMaxWidth())
        }
        return
    }

    PageScroll {
        RsText("共 ${list.size} 条 · 最多保留 30 条", RsTextStyle.Caption)
        Spacer(Modifier.height(8.dp))

        var prev: HistoryEntry? = null
        list.forEach { h ->
            val gc = gradeColorOf(h.grade)
            RsCard(Modifier.padding(bottom = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RsText(
                            gradeLabel(h.grade),
                            RsTextStyle.TitleM,
                            modifier = Modifier.weight(1f),
                        )
                        RsText(
                            String.format("%.1f", h.rootScore),
                            RsTextStyle.TitleM,
                            color = gc,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    RsText(
                        "${formatTime(h.at)} · 可读率 ${(h.coverage * 100).toInt()}% · ${h.hit} 命中",
                        RsTextStyle.Caption,
                    )
                    prev?.let { pv ->
                        val d = h.rootScore - pv.rootScore
                        val txt = when {
                            d > 0.5 -> "较上次 ↑ ${String.format("%.1f", d)}"
                            d < -0.5 -> "较上次 ↓ ${String.format("%.1f", -d)}"
                            else -> "与上次持平"
                        }
                        val col = when {
                            d > 0.5 -> RsStatusColors.Danger
                            d < -0.5 -> RsStatusColors.Success
                            else -> null
                        }
                        Spacer(Modifier.height(4.dp))
                        RsText(txt, RsTextStyle.Caption, color = col)
                    }
                }
            }
            prev = h
        }

        Spacer(Modifier.height(4.dp))
        RsButton("清空历史", onClick = onClear, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
    }
}

private fun gradeLabel(name: String): String = when (name) {
    Grade.CLEAN.name -> "正常"
    Grade.SUSPICIOUS.name -> "可疑"
    Grade.ROOTED.name -> "已提权"
    Grade.ROOTED_HIDING.name -> "已提权 + 隐藏"
    else -> "无法判定"
}

@Composable
private fun gradeColorOf(name: String): Color {
    val p = LocalRsPalette.current
    val g = try {
        Grade.valueOf(name)
    } catch (e: Throwable) {
        null
    }
    return if (g == null) p.onVariant else gradeColor(g)
}

private fun formatTime(ms: Long): String {
    val f = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
    return f.format(java.util.Date(ms))
}

// ============================================================ 设备

@Composable
fun DevicePage(result: DetectorEngine.Result?, onBack: () -> Unit) {
    val p = result?.profile
    val items = result?.items ?: emptyList()

    val selinux = items.firstOrNull { it.id.startsWith("F.SELINUX") }
    val boot = items.firstOrNull { it.id.startsWith("B.VBMETA") || it.id.startsWith("B.BOOT") }
    val attest = items.firstOrNull { it.id == "G.ATTEST_CHAIN" }

    PageScroll {
        RsSectionTitle("系统")
        RsCard(insideMargin = RowCardMargin) {
            Column {
                RowKV("Android", Build.VERSION.RELEASE ?: "—")
                RsDivider()
                RowKV("SDK", Build.VERSION.SDK_INT.toString())
                RsDivider()
                RowKV("安全补丁", Build.VERSION.SECURITY_PATCH ?: "—")
                RsDivider()
                RowKV("内核", System.getProperty("os.version") ?: "—")
                RsDivider()
                RowKV("ABI", Build.SUPPORTED_ABIS.joinToString(", "))
            }
        }

        Spacer(Modifier.height(16.dp))
        RsSectionTitle("硬件")
        RsCard(insideMargin = RowCardMargin) {
            Column {
                RowKV("厂商", p?.manufacturer?.ifEmpty { Build.MANUFACTURER } ?: Build.MANUFACTURER)
                RsDivider()
                RowKV("型号", p?.model?.ifEmpty { Build.MODEL } ?: Build.MODEL)
                RsDivider()
                RowKV("SoC", p?.socManufacturer?.ifEmpty { "—" } ?: "—")
            }
        }

        Spacer(Modifier.height(16.dp))
        RsSectionTitle("安全状态")
        RsCard(insideMargin = RowCardMargin) {
            Column {
                RowKV(
                    "SELinux",
                    selinux?.title ?: "—",
                    selinux?.let { if (it.verdict == Verdict.HIT) RsStatusColors.Warning else null },
                )
                RsDivider()
                RowKV(
                    "Bootloader",
                    boot?.title ?: "—",
                    boot?.let { if (it.verdict == Verdict.HIT) RsStatusColors.Warning else null },
                )
                RsDivider()
                RowKV(
                    "硬件证明",
                    attest?.title ?: "—",
                    attest?.let { if (it.verdict == Verdict.HIT) RsStatusColors.Warning else null },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        RsSectionTitle("运行环境")
        RsCard(insideMargin = RowCardMargin) {
            Column {
                if (p == null) {
                    RsText(
                        "先完成一次扫描",
                        RsTextStyle.Label,
                        modifier = Modifier.padding(vertical = 14.dp),
                    )
                } else {
                    RowKV("模拟器", if (p.isEmulator) "是" else "否", if (p.isEmulator) RsStatusColors.Warning else null)
                    RsDivider()
                    RowKV("GSI", if (p.isGsi) "是" else "否", if (p.isGsi) RsStatusColors.Warning else null)
                    RsDivider()
                    RowKV("厂商 ROM", if (p.isVendorRom) "是" else "否")
                    RsDivider()
                    RowKV("Debuggable", if (p.isDebuggable) "是" else "否")
                    RsDivider()
                    RowKV("userdebug/eng", if (p.isUserdebug || p.isEng) "是" else "否")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ============================================================ 设置

@Composable
fun SettingsPage(
    settings: SettingsStore,
    onDevice: () -> Unit,
    onAbout: () -> Unit,
    onClearHistory: () -> Unit,
    onCheckUpdate: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current

    PageScroll {
        RsSectionTitle("外观")
        RsCard(insideMargin = RowCardMargin) {
            Column {
                RsRadioRow(
                    title = "Miuix 风格",
                    sub = "类 MIUI 的卡片与浮动底栏（KernelSU 默认）",
                    selected = settings.uiMode != UiMode.Material.value,
                    onClick = { settings.uiMode = UiMode.Miuix.value },
                )
                RsDivider()
                RsRadioRow(
                    title = "Material 风格",
                    sub = "Material 3 的导航栏与组件",
                    selected = settings.uiMode == UiMode.Material.value,
                    onClick = { settings.uiMode = UiMode.Material.value },
                )
                RsDivider()
                RsRadioRow(
                    title = "深色模式 · 跟随系统",
                    selected = settings.colorMode == 0,
                    onClick = { settings.colorMode = 0 },
                )
                RsDivider()
                RsRadioRow(
                    title = "深色模式 · 浅色",
                    selected = settings.colorMode == 1,
                    onClick = { settings.colorMode = 1 },
                )
                RsDivider()
                RsRadioRow(
                    title = "深色模式 · 深色",
                    selected = settings.colorMode == 2,
                    onClick = { settings.colorMode = 2 },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        RsSectionTitle("隐私与数据")
        RsCard(insideMargin = RowCardMargin) {
            Column {
                RsSwitchRow(
                    title = "扫描后自动导出 JSON",
                    checked = settings.autoExport,
                    onCheckedChange = { settings.autoExport = it },
                )
                RsText(
                    "关闭时报告只留在内存，只有点「导出 JSON」才写文件。",
                    RsTextStyle.Caption,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                RsDivider()
                RsSwitchRow(
                    title = "上传诊断摘要",
                    checked = settings.telemetryEnabled,
                    onCheckedChange = { settings.telemetryEnabled = it },
                )
                RsText(
                    "开启时，每次扫描后上传不含设备标识的诊断摘要（版本、机型、评分与逐项判定），用于改良检测准确率。关闭后立即停止上传。",
                    RsTextStyle.Caption,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                RsDivider()
                RsSwitchRow(
                    title = "记录扫描历史",
                    checked = settings.keepHistory,
                    onCheckedChange = { settings.keepHistory = it },
                )
                RsText(
                    "本地最多保存 30 条摘要，不联网、不上传。",
                    RsTextStyle.Caption,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        RsSectionTitle("版本与更新")
        RsCard(insideMargin = RowCardMargin) {
            Column {
                RsNavRow(
                    icon = Icons.Filled.Refresh,
                    title = "检查更新",
                    sub = "当前版本 ${RootSentinelVersion.NAME} · 手动检查一次",
                    onClick = { onCheckUpdate() },
                )
                RsDivider()
                RsNavRow(
                    icon = Icons.Filled.ExitToApp,
                    title = "GitHub 仓库",
                    sub = "AURORA-0711/RootSentinel · 源码与版本发布",
                    onClick = { uriHandler.openUri(Updater.REPO_URL) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        RsSectionTitle("更多")
        RsCard(insideMargin = RowCardMargin) {
            Column {
                RsNavRow(
                    icon = Icons.Filled.Info,
                    title = "设备信息",
                    sub = "Android / 内核 / ABI / SELinux",
                    onClick = { onDevice() },
                )
                RsDivider()
                RsNavRow(
                    icon = Icons.Filled.Star,
                    title = "关于",
                    sub = "版本 / 许可 / 免责声明",
                    onClick = { onAbout() },
                )
                RsDivider()
                RsNavRow(
                    icon = Icons.Filled.Delete,
                    title = "清除历史",
                    sub = "删除本地保存的所有扫描记录",
                    tint = RsStatusColors.Danger,
                    onClick = { onClearHistory() },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ============================================================ 关于

@Composable
fun AboutPage(result: DetectorEngine.Result?, onBack: () -> Unit) {
    val version = RootSentinelVersion.NAME

    PageScroll {
        Spacer(Modifier.height(8.dp))
        RsText("RootSentinel", RsTextStyle.TitleL, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        RsText("Android root 环境检测器", RsTextStyle.Label)
        Spacer(Modifier.height(12.dp))
        RsText(
            "只做被动只读检测：不执行 su、不提权、不改系统。诊断摘要上传仅在你确认声明后、且开关开启时发生，字段口径见设置页说明。",
            RsTextStyle.BodyS,
        )

        Spacer(Modifier.height(20.dp))
        RsSectionTitle("版本")
        RsCard(insideMargin = RowCardMargin) {
            Column {
                RowKV("应用", version)
                RsDivider()
                RowKV("检测引擎", version)
                RsDivider()
                RowKV("检测项", result?.items?.size?.toString() ?: "—")
            }
        }

        Spacer(Modifier.height(16.dp))
        RsSectionTitle("开源组件")
        RsCard(insideMargin = RowCardMargin) {
            Column {
                RowKV("Miuix", "Apache-2.0")
                RsDivider()
                RowKV("Jetpack Compose", "Apache-2.0")
                RsDivider()
                RowKV("Kotlin stdlib", "Apache-2.0")
            }
        }

        Spacer(Modifier.height(16.dp))
        RsSectionTitle("免责声明")
        RsCard {
            RsText(
                "检测结论基于当前进程可见的信息。SELinux 限制、厂商 ROM 差异、" +
                    "内核裁剪都可能让某些检测项无法读取，这类情况会标记为 UNKNOWN 或 " +
                    "Incomplete，而不是判为安全。请勿把本工具作为唯一依据。",
                RsTextStyle.BodyS,
                color = LocalRsPalette.current.onVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
