package com.rootsentinel

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rootsentinel.data.HistoryEntry
import com.rootsentinel.data.HistoryStore
import com.rootsentinel.data.SettingsStore
import com.rootsentinel.engine.DetectorEngine
import com.rootsentinel.model.Grade
import com.rootsentinel.model.Verdict
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

// ============================================================ 历史

@Composable
fun HistoryPage(history: HistoryStore, onScan: () -> Unit, onClear: () -> Unit) {
    val c = MiuixTheme.colorScheme
    // 读 State：扫描 append 后历史页会自动刷新
    val list = history.entries

    if (list.isEmpty()) {
        PageScroll {
            Spacer(Modifier.height(24.dp))
            Text("还没有扫描记录", fontSize = 15.sp, color = c.onBackgroundVariant)
            Spacer(Modifier.height(24.dp))
            GlassButton(onClick = { onScan() }, modifier = Modifier.fillMaxWidth()) {
                Text("开始检测", fontSize = 16.sp)
            }
        }
        return
    }

    PageScroll {
        Spacer(Modifier.height(8.dp))
        Text(
            "共 ${list.size} 条 · 最多保留 30 条",
            fontSize = 12.sp,
            color = c.onBackgroundVariant,
        )
        Spacer(Modifier.height(8.dp))

        var prev: HistoryEntry? = null
        list.forEach { h ->
            val gc = gradeColorOf(h.grade)
            RsCard(Modifier.padding(bottom = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            gradeLabel(h.grade),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = c.onBackground,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            String.format("%.1f", h.rootScore),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = gc,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${formatTime(h.at)}   ·   可读率 ${(h.coverage * 100).toInt()}%   ·   ${h.hit} 命中",
                        fontSize = 12.sp,
                        color = c.onBackgroundVariant,
                    )
                    prev?.let { p ->
                        val d = h.rootScore - p.rootScore
                        val txt = when {
                            d > 0.5 -> "较上次 ↑ ${String.format("%.1f", d)}"
                            d < -0.5 -> "较上次 ↓ ${String.format("%.1f", -d)}"
                            else -> "与上次持平"
                        }
                        val col = when {
                            d > 0.5 -> Color(0xFFFF3B30)
                            d < -0.5 -> Color(0xFF34C759)
                            else -> c.onBackgroundVariant
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(txt, fontSize = 12.sp, color = col)
                    }
                }
            }
            prev = h
        }

        Spacer(Modifier.height(12.dp))
        GlassButton(onClick = { onClear() }, modifier = Modifier.fillMaxWidth()) {
            Text("清空历史", fontSize = 16.sp)
        }
        Spacer(Modifier.height(24.dp))
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
    val c = MiuixTheme.colorScheme
    // 注意：try/catch 不能包住 @Composable 调用，
    // 所以先解析枚举，再在外面取颜色。
    val g = try {
        Grade.valueOf(name)
    } catch (e: Throwable) {
        null
    }
    return if (g == null) c.onBackgroundVariant else gradeColor(g)
}

private fun formatTime(ms: Long): String {
    val f = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
    return f.format(java.util.Date(ms))
}

// ============================================================ 设备

@Composable
fun DevicePage(result: DetectorEngine.Result?, onBack: () -> Unit) {
    val c = MiuixTheme.colorScheme
    val p = result?.profile
    val items = result?.items ?: emptyList()

    val selinux = items.firstOrNull { it.id.startsWith("F.SELINUX") }
    val boot = items.firstOrNull { it.id.startsWith("B.VBMETA") || it.id.startsWith("B.BOOT") }
    val attest = items.firstOrNull { it.id == "G.ATTEST_CHAIN" }

    PageScroll {
        Spacer(Modifier.height(4.dp))
        Text(
            "‹ 返回",
            fontSize = 15.sp,
            color = c.primary,
            modifier = Modifier.clickable { onBack() }.padding(vertical = 8.dp),
        )

        SmallTitle("系统")
        RsCard {
            Column {
                RowKV("Android", Build.VERSION.RELEASE ?: "—")
                ThinDivider(indent = false)
                RowKV("SDK", Build.VERSION.SDK_INT.toString())
                ThinDivider(indent = false)
                RowKV("安全补丁", Build.VERSION.SECURITY_PATCH ?: "—")
                ThinDivider(indent = false)
                RowKV("内核", System.getProperty("os.version") ?: "—")
                ThinDivider(indent = false)
                RowKV("ABI", Build.SUPPORTED_ABIS.joinToString(", "))
            }
        }

        Spacer(Modifier.height(16.dp))
        SmallTitle("硬件")
        RsCard {
            Column {
                RowKV("厂商", p?.manufacturer?.ifEmpty { Build.MANUFACTURER } ?: Build.MANUFACTURER)
                ThinDivider(indent = false)
                RowKV("型号", p?.model?.ifEmpty { Build.MODEL } ?: Build.MODEL)
                ThinDivider(indent = false)
                RowKV("SoC", p?.socManufacturer?.ifEmpty { "—" } ?: "—")
            }
        }

        Spacer(Modifier.height(16.dp))
        SmallTitle("安全状态")
        RsCard {
            Column {
                RowKV(
                    "SELinux",
                    selinux?.title ?: "—",
                    selinux?.let { if (it.verdict == Verdict.HIT) Color(0xFFFF9500) else null },
                )
                ThinDivider(indent = false)
                RowKV(
                    "Bootloader",
                    boot?.title ?: "—",
                    boot?.let { if (it.verdict == Verdict.HIT) Color(0xFFFF9500) else null },
                )
                ThinDivider(indent = false)
                RowKV(
                    "硬件证明",
                    attest?.title ?: "—",
                    attest?.let { if (it.verdict == Verdict.HIT) Color(0xFFFF9500) else null },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        SmallTitle("运行环境")
        RsCard {
            Column {
                if (p == null) {
                    Text("先完成一次扫描", fontSize = 13.sp, color = c.onBackgroundVariant)
                } else {
                    RowKV("模拟器", if (p.isEmulator) "是" else "否", if (p.isEmulator) Color(0xFFFF9500) else null)
                    ThinDivider(indent = false)
                    RowKV("GSI", if (p.isGsi) "是" else "否", if (p.isGsi) Color(0xFFFF9500) else null)
                    ThinDivider(indent = false)
                    RowKV("厂商 ROM", if (p.isVendorRom) "是" else "否")
                    ThinDivider(indent = false)
                    RowKV("Debuggable", if (p.isDebuggable) "是" else "否")
                    ThinDivider(indent = false)
                    RowKV("userdebug/eng", if (p.isUserdebug || p.isEng) "是" else "否")
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        GlassButton(onClick = { onBack() }, modifier = Modifier.fillMaxWidth()) {
            Text("返回设置", fontSize = 16.sp)
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ============================================================ 设置

@Composable
fun SettingsPage(
    settings: SettingsStore,
    onDevice: () -> Unit,
    onAbout: () -> Unit,
    onClearHistory: () -> Unit,
) {
    val c = MiuixTheme.colorScheme

    PageScroll {
        SmallTitle("隐私与数据")
        RsCard(insideMargin = RowCardMargin) {
            Column {
                Row(
                    Modifier.fillMaxWidth().height(52.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("扫描后自动导出 JSON", fontSize = 15.sp, color = c.onBackground, modifier = Modifier.weight(1f))
                    GlassSwitch(
                        checked = settings.autoExport,
                        onCheckedChange = { settings.autoExport = it },
                    )
                }
                Text(
                    "关闭时报告只留在内存，只有点「导出 JSON」才写文件。",
                    fontSize = 12.sp,
                    color = c.onBackgroundVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                ThinDivider(indent = false)
                Row(
                    Modifier.fillMaxWidth().height(52.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("记录扫描历史", fontSize = 15.sp, color = c.onBackground, modifier = Modifier.weight(1f))
                    GlassSwitch(
                        checked = settings.keepHistory,
                        onCheckedChange = { settings.keepHistory = it },
                    )
                }
                Text(
                    "本地最多保存 30 条摘要，不联网、不上传。",
                    fontSize = 12.sp,
                    color = c.onBackgroundVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        SmallTitle("更多")
        RsCard {
            Column {
                IconRow(
                    icon = Icons.Filled.Info,
                    title = "设备信息",
                    sub = "Android / 内核 / ABI / SELinux",
                    onClick = { onDevice() },
                )
                ThinDivider(indent = false)
                IconRow(
                    icon = Icons.Filled.Star,
                    title = "关于",
                    sub = "版本 / 许可 / 免责声明",
                    onClick = { onAbout() },
                )
                ThinDivider(indent = false)
                IconRow(
                    icon = Icons.Filled.Delete,
                    title = "清除历史",
                    sub = "删除本地保存的所有扫描记录",
                    tint = Color(0xFFFF3B30),
                    onClick = { onClearHistory() },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "主题跟随系统深色模式（Miuix 默认行为）。",
            fontSize = 12.sp,
            color = c.onBackgroundVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun NavRow(title: String, sub: String, onClick: () -> Unit) {
    val c = MiuixTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = c.onBackground)
            Text(sub, fontSize = 12.sp, color = c.onBackgroundVariant)
        }
        Text("›", fontSize = 18.sp, color = c.onBackgroundVariant)
    }
}

// ============================================================ 关于

@Composable
fun AboutPage(result: DetectorEngine.Result?, onBack: () -> Unit) {
    val c = MiuixTheme.colorScheme
    val version = try {
        com.rootsentinel.RootSentinelVersion.NAME
    } catch (e: Throwable) {
        "2.5.0"
    }

    PageScroll {
        Spacer(Modifier.height(8.dp))
        Text("RootSentinel", fontSize = 24.sp, fontWeight = FontWeight.Medium, color = c.onBackground)
        Spacer(Modifier.height(4.dp))
        Text("Android root 环境检测器", fontSize = 13.sp, color = c.onBackgroundVariant)
        Spacer(Modifier.height(12.dp))
        Text(
            "只做被动只读检测：不执行 su、不提权、不改系统、不联网、不上传任何数据。",
            fontSize = 13.sp,
            color = c.onBackgroundVariant,
        )

        Spacer(Modifier.height(20.dp))
        SmallTitle("版本")
        RsCard {
            Column {
                RowKV("应用", version)
                ThinDivider(indent = false)
                RowKV("检测引擎", version)
                ThinDivider(indent = false)
                RowKV("检测项", result?.items?.size?.toString() ?: "—")
            }
        }

        Spacer(Modifier.height(16.dp))
        SmallTitle("开源组件")
        RsCard {
            Column {
                RowKV("Miuix", "Apache-2.0")
                ThinDivider(indent = false)
                RowKV("Jetpack Compose", "Apache-2.0")
                ThinDivider(indent = false)
                RowKV("Kotlin stdlib", "Apache-2.0")
            }
        }

        Spacer(Modifier.height(16.dp))
        SmallTitle("免责声明")
        RsCard {
            Text(
                "检测结论基于当前进程可见的信息。SELinux 限制、厂商 ROM 差异、"
                    + "内核裁剪都可能让某些检测项无法读取，这类情况会标记为 UNKNOWN 或 "
                    + "Incomplete，而不是判为安全。请勿把本工具作为唯一依据。",
                fontSize = 13.sp,
                color = c.onBackgroundVariant,
            )
        }

        Spacer(Modifier.height(24.dp))
        GlassButton(onClick = { onBack() }, modifier = Modifier.fillMaxWidth()) {
            Text("返回设置", fontSize = 16.sp)
        }
        Spacer(Modifier.height(24.dp))
    }
}