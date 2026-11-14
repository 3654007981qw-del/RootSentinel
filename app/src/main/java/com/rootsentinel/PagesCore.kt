package com.rootsentinel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rootsentinel.data.HistoryEntry
import com.rootsentinel.data.HistoryStore
import com.rootsentinel.data.SettingsStore
import com.rootsentinel.engine.DetectorEngine
import com.rootsentinel.model.Evidence
import com.rootsentinel.model.EvidenceState
import com.rootsentinel.model.Grade
import com.rootsentinel.model.Severity
import com.rootsentinel.model.Verdict
import com.rootsentinel.util.JsonExporter
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

// ============================================================ 公共小件

/**
 * 顶栏（含状态栏）的实际高度。
 *
 * 页面内容**不**再被 Scaffold 的 topBar 占位推开，而是自己留出这段空白：
 * 这样滚动时内容会从玻璃顶栏下面滑过，顶栏才有东西可以模糊/折射。
 */
val LocalTopInset = androidx.compose.runtime.compositionLocalOf { 0.dp }

@Composable
fun PageScroll(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = LocalTopInset.current + 8.dp,
                // 让出玻璃底栏的高度。**故意留得比底栏矮一些**：
                // 底栏实际占 76dp + 28dp 底距 + 手势条，这里给 BottomNavHeight - 32dp，
                // 使最后一条内容会伸进玻璃后面 —— 液态玻璃的模糊/折射必须有东西可折，
                // 否则在纯色背景上怎么调都只是"一块淡灰"。
                bottom = BottomNavHeight - 56.dp,
            ),
        content = content,
    )
}

/** 分隔线（Miuix 风格：左缩进 16dp、1dp 高） */
@Composable
fun ThinDivider(indent: Boolean = true) {
    val c = MiuixTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = if (indent) 16.dp else 0.dp)
            .height(1.dp)
            .background(c.onBackground.copy(alpha = 0.08f)),
    )
}

/** 细进度条（自绘，避免依赖具体 API 签名） */
@Composable
fun ThinProgress(fraction: Float, color: Color) {
    val bg = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.10f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(bg),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(3.dp))
                .background(color),
        )
    }
}

/** 一行「标签 — 值」 */
@Composable
fun RowKV(name: String, value: String, valueColor: Color? = null) {
    val c = MiuixTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            fontSize = 15.sp,
            color = c.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            fontSize = 14.sp,
            color = valueColor ?: c.onBackgroundVariant,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** 紧凑的两栏统计（Hero 卡右侧用） */
@Composable
fun StatLine(name: String, value: String) {
    val c = MiuixTheme.colorScheme
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = name,
            fontSize = 12.sp,
            color = c.onBackgroundVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            fontSize = 13.sp,
            color = c.onBackground,
            maxLines = 1,
            softWrap = false,
        )
    }
}

fun gradeWord(g: Grade): String = when (g) {
    Grade.CLEAN -> "正常"
    Grade.SUSPICIOUS -> "可疑"
    Grade.ROOTED -> "已提权"
    Grade.ROOTED_HIDING -> "已提权 + 隐藏"
    Grade.INCOMPLETE -> "无法判定"
}

@Composable
fun gradeColor(g: Grade): Color {
    val c = MiuixTheme.colorScheme
    return when (g) {
        Grade.CLEAN -> Color(0xFF34C759)
        Grade.SUSPICIOUS -> c.primary
        Grade.ROOTED -> Color(0xFFFF3B30)
        Grade.ROOTED_HIDING -> Color(0xFFFF3B30)
        Grade.INCOMPLETE -> c.onBackgroundVariant
    }
}

@Composable
fun severityColor(sev: String): Color {
    val c = MiuixTheme.colorScheme
    return when (sev) {
        "CRITICAL" -> Color(0xFFFF3B30)
        "HIGH" -> Color(0xFFFF9500)
        "MEDIUM" -> Color(0xFFFFCC00)
        "LOW" -> c.onBackgroundVariant
        else -> c.onBackgroundVariant
    }
}

// ============================================================ 首页

@Composable
fun HomePage(
    result: DetectorEngine.Result?,
    scanning: Boolean,
    onScan: () -> Unit,
    onDevice: () -> Unit,
) {
    val c = MiuixTheme.colorScheme
    val s = result?.score
    PageScroll {
        Spacer(Modifier.height(4.dp))

        RsCard {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeroGauge(
                    fraction = if (s == null) 0f else (s.rootScore / 100.0).toFloat(),
                    color = if (s == null) c.onBackgroundVariant else gradeColor(s.grade),
                    size = 148.dp,
                    strokeWidth = 10.dp,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (s == null) {
                            Text("—", fontSize = 30.sp, color = c.onBackgroundVariant)
                        } else {
                            AnimatedNumber(
                                value = s.rootScore,
                                fontSize = 32.sp,
                                color = gradeColor(s.grade),
                            )
                        }
                        Text(
                            if (s == null) "未扫描" else s.grade.label,
                            fontSize = 12.sp,
                            color = if (s == null) c.onBackgroundVariant else gradeColor(s.grade),
                        )
                    }
                }

                Spacer(Modifier.width(16.dp))

                Column(Modifier.weight(1f)) {
                    Text("安全状态", fontSize = 13.sp, color = c.onBackgroundVariant)
                    Spacer(Modifier.height(8.dp))
                    StatLine(
                        "可读率",
                        if (result == null) "—" else "${(result.score.coverage * 100).toInt()}%",
                    )
                    Spacer(Modifier.height(6.dp))
                    StatLine("检测项", if (result == null) "—" else "${result.items.size}")
                    Spacer(Modifier.height(6.dp))
                    StatLine("耗时", if (result == null) "—" else "${result.elapsedMs} ms")
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        RsCard(insideMargin = RowCardMargin) {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("ROOT 状态", fontSize = 15.sp, color = c.onBackground, modifier = Modifier.weight(1f))
                    Text(
                        if (s == null) "—" else String.format("%.1f", s.rootScore),
                        fontSize = 14.sp,
                        color = c.onBackgroundVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                ThinDivider(indent = false)
                Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("系统完整性", fontSize = 15.sp, color = c.onBackground, modifier = Modifier.weight(1f))
                    val txt = when {
                        s == null -> "—"
                        s.integrityBreach -> "启动链不可信"
                        s.integrityScore > 0 -> "存疑"
                        else -> "可信"
                    }
                    Text(txt, fontSize = 14.sp, color = c.onBackgroundVariant, maxLines = 1, softWrap = false)
                }
                ThinDivider(indent = false)
                Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("运行环境", fontSize = 15.sp, color = c.onBackground, modifier = Modifier.weight(1f))
                    val txt = if (s == null) "—" else if (s.envRisk) "存在风险" else "正常"
                    Text(txt, fontSize = 14.sp, color = c.onBackgroundVariant, maxLines = 1, softWrap = false)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth()) {
            RsCard(Modifier.weight(1f)) {
                Column {
                    Text(
                        if (result == null) "—" else "${(result.score.coverage * 100).toInt()}%",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                        color = c.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("覆盖率", fontSize = 12.sp, color = c.onBackgroundVariant)
                }
            }
            Spacer(Modifier.width(12.dp))
            RsCard(Modifier.weight(1f)) {
                Column {
                    Text(
                        if (result == null) "—" else "${result.items.size}",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                        color = c.onBackground,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("检测项", fontSize = 12.sp, color = c.onBackgroundVariant)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth()) {
            RsCard(Modifier.weight(1f)) {
                Column {
                    Text(
                        if (s == null) "—" else "${s.hitCount}",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFFF9500),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("风险项", fontSize = 12.sp, color = c.onBackgroundVariant)
                }
            }
            Spacer(Modifier.width(12.dp))
            RsCard(Modifier.weight(1f)) {
                Column {
                    Text(
                        if (s == null) "—" else "${s.unknownCount}",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                        color = c.onBackgroundVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("UNKNOWN", fontSize = 12.sp, color = c.onBackgroundVariant)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        RsCard(Modifier.clickable { onDevice() }) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("设备", fontSize = 13.sp, color = c.onBackgroundVariant)
                    Text(
                        result?.profile?.let { "${it.brand} ${it.model}" } ?: "尚未扫描",
                        fontSize = 15.sp,
                        color = c.onBackground,
                    )
                }
                Text("›", fontSize = 18.sp, color = c.onBackgroundVariant)
            }
        }

        Spacer(Modifier.height(24.dp))

        GlassButton(onClick = { onScan() }, modifier = Modifier.fillMaxWidth(), enabled = !scanning) {
            Text(if (scanning) "正在扫描…" else "开始安全检测", fontSize = 16.sp)
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ============================================================ 扫描页

@Composable
fun ScanPage(scan: ScanState, onRescan: () -> Unit, onCancel: () -> Unit) {
    val c = MiuixTheme.colorScheme
    val elapsed = if (scan.startedAt > 0L) {
        ((if (scan.scanning) System.currentTimeMillis() else scan.startedAt) - scan.startedAt)
            .coerceAtLeast(0L)
    } else 0L

    PageScroll {
        Spacer(Modifier.height(8.dp))
        Text(
            text = when {
                scan.scanning -> scan.moduleName
                scan.failed -> "扫描失败"
                else -> "扫描完成"
            },
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            color = when {
                scan.failed -> Color(0xFFFF3B30)
                else -> c.onBackground
            },
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "阶段 ${scan.moduleIdx}/51   ·   ${String.format("%.1f", elapsed / 1000.0)} s",
            fontSize = 12.sp,
            color = c.onBackgroundVariant,
        )

        Spacer(Modifier.height(12.dp))
        MiuixProgress(
            fraction = if (scan.moduleIdx <= 0) 0f else scan.moduleIdx / 51f,
            color = c.primary,
            height = 8.dp,
        )

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth()) {
            RsCard(Modifier.weight(1f)) {
                Column {
                    Text("${scan.total}", fontSize = 22.sp, fontWeight = FontWeight.Medium, color = c.onBackground)
                    Spacer(Modifier.height(4.dp))
                    Text("已检测", fontSize = 12.sp, color = c.onBackgroundVariant)
                }
            }
            Spacer(Modifier.width(12.dp))
            RsCard(Modifier.weight(1f)) {
                Column {
                    Text("${scan.hit}", fontSize = 22.sp, fontWeight = FontWeight.Medium, color = Color(0xFFFF9500))
                    Spacer(Modifier.height(4.dp))
                    Text("命中", fontSize = 12.sp, color = c.onBackgroundVariant)
                }
            }
        }

        if (scan.failed) {
            Spacer(Modifier.height(12.dp))
            RsCard {
                Column {
                    Text("扫描未完成", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.onBackground)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "已完成 ${scan.total} 项，但检测面不完整，不应据此判断设备安全。",
                        fontSize = 12.sp,
                        color = c.onBackgroundVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        SmallTitle("实时发现")
        Spacer(Modifier.height(4.dp))

        if (scan.live.isEmpty()) {
            Text(
                if (scan.scanning) "扫描中…" else "本次没有命中项",
                fontSize = 13.sp,
                color = c.onBackgroundVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            RsCard(insideMargin = RowCardMargin) {
                Column(Modifier.fillMaxWidth()) {
                    val items = scan.live.take(10)
                    items.forEachIndexed { idx, it ->
                        if (idx > 0) ThinDivider(indent = false)
                        Row(
                            Modifier.fillMaxWidth().height(52.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .width(8.dp)
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(severityColor(it.severity)),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(it.title, fontSize = 14.sp, color = c.onBackground)
                                Text(it.id, fontSize = 11.sp, color = c.onBackgroundVariant)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        if (scan.scanning) {
            GlassButton(onClick = { onCancel() }, modifier = Modifier.fillMaxWidth()) {
                Text("取消扫描", fontSize = 16.sp)
            }
        } else {
            GlassButton(onClick = { onRescan() }, modifier = Modifier.fillMaxWidth()) {
                Text("重新检测", fontSize = 16.sp)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ============================================================ 报告页

@Composable
fun ReportPage(
    result: DetectorEngine.Result?,
    onRescan: () -> Unit,
    onDetail: (Evidence) -> Unit,
) {
    val c = MiuixTheme.colorScheme
    val ctx = LocalContext.current
    val exportScope = rememberCoroutineScope()

    if (result == null) {
        PageScroll {
            Spacer(Modifier.height(24.dp))
            Text("还没有报告", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = c.onBackground)
            Spacer(Modifier.height(6.dp))
            Text("先完成一次扫描", fontSize = 13.sp, color = c.onBackgroundVariant)
            Spacer(Modifier.height(24.dp))
            GlassButton(onClick = { onRescan() }, modifier = Modifier.fillMaxWidth()) {
                Text("开始检测", fontSize = 16.sp)
            }
        }
        return
    }

    val s = result.score
    val hits = result.items.filter { it.verdict == Verdict.HIT }

    PageScroll {
        Spacer(Modifier.height(4.dp))

        RsCard {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("结论", fontSize = 13.sp, color = c.onBackgroundVariant)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        s.grade.label,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Medium,
                        color = gradeColor(s.grade),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "ROOT ${String.format("%.1f", s.rootScore)}   ·   置信度 ${s.confidence.name}",
                        fontSize = 12.sp,
                        color = c.onBackgroundVariant,
                    )
                    if (s.grade == Grade.INCOMPLETE) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "检测面不完整，本次不给出「干净」结论",
                            fontSize = 12.sp,
                            color = Color(0xFFFF9500),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                HeroGauge(
                    fraction = (s.rootScore / 100.0).toFloat(),
                    color = gradeColor(s.grade),
                    size = 86.dp,
                    strokeWidth = 7.dp,
                ) {
                    AnimatedNumber(
                        value = s.rootScore,
                        fontSize = 19.sp,
                        color = gradeColor(s.grade),
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        SmallTitle("维度得分")
        RsCard {
            Column {
                RowKV("Root 状态", String.format("%.1f", s.rootScore))
                ThinDivider(indent = false)
                RowKV("系统完整性", String.format("%.1f", s.integrityScore))
                ThinDivider(indent = false)
                RowKV("运行环境", String.format("%.1f", s.environmentScore))
            }
        }

        Spacer(Modifier.height(16.dp))
        SmallTitle("检测完整性")
        RsCard {
            Column {
                val errored = result.items.count {
                    it.effectiveState == EvidenceState.ERROR || it.effectiveState == EvidenceState.TIMEOUT
                }
                RowKV("可读率", "${(s.coverage * 100).toInt()}%")
                ThinDivider(indent = false)
                RowKV("证据条目", "${result.items.size}")
                ThinDivider(indent = false)
                RowKV("命中", "${s.hitCount}")
                ThinDivider(indent = false)
                RowKV("UNKNOWN", "${s.unknownCount}")
                ThinDivider(indent = false)
                RowKV("执行失败", "$errored", if (errored > 0) Color(0xFFFF3B30) else null)
            }
        }

        if (hits.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            SmallTitle("风险分布")
            RsCard {
                Column {
                    val max = hits.size.coerceAtLeast(1)
                    val list = listOf(
                        "CRITICAL" to Color(0xFFFF3B30),
                        "HIGH" to Color(0xFFFF9500),
                        "MEDIUM" to Color(0xFFFFCC00),
                        "LOW" to c.onBackgroundVariant,
                    )
                    list.forEach { (sev, color) ->
                        val n = hits.count { it.severity.name == sev }
                        if (n > 0) {
                            Row(
                                Modifier.fillMaxWidth().height(34.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(sev, fontSize = 12.sp, color = c.onBackgroundVariant, modifier = Modifier.width(72.dp))
                                Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(c.onBackground.copy(alpha = 0.08f))) {
                                    Box(
                                        Modifier
                                            .fillMaxHeight()
                                            .fillMaxWidth(n.toFloat() / max)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(color),
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Text("$n", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.onBackground)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            SmallTitle("高风险项目")
            RsCard {
                Column(Modifier.fillMaxWidth()) {
                    val top = hits
                        .sortedWith(
                            compareByDescending<Evidence> { it.severity.ordinal }
                                .thenByDescending { it.confidence.ordinal },
                        )
                        .take(10)
                    top.forEachIndexed { idx, e ->
                        if (idx > 0) ThinDivider(indent = false)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .clickable { onDetail(e) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .width(8.dp)
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(severityColor(e.severity.name)),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.title, fontSize = 14.sp, color = c.onBackground)
                                Text(e.id, fontSize = 11.sp, color = c.onBackgroundVariant)
                            }
                            Text("›", fontSize = 18.sp, color = c.onBackgroundVariant)
                        }
                    }
                }
            }
            if (hits.size > 10) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "另有 ${hits.size - 10} 项命中未列出",
                    fontSize = 12.sp,
                    color = c.onBackgroundVariant,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth()) {
            GlassButton(onClick = { onRescan() }, modifier = Modifier.weight(1f)) {
                Text("重新扫描", fontSize = 15.sp)
            }
            Spacer(Modifier.width(12.dp))
            GlassButton(
                // v2.22.44（P2-08）：导出不在点击回调（主线程）里
                // 同步执行，改走 IO 协程。
                onClick = {
                    exportScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        JsonExporter.export(ctx, result)
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("导出 JSON", fontSize = 15.sp)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ============================================================ 详情页

@Composable
fun DetailPage(item: Evidence?, onBack: () -> Unit) {
    val c = MiuixTheme.colorScheme
    if (item == null) {
        PageScroll { Text("没有选中项", fontSize = 15.sp, color = c.onBackgroundVariant) }
        return
    }

    PageScroll {
        Spacer(Modifier.height(4.dp))
        Text(
            "‹ 返回",
            fontSize = 15.sp,
            color = c.primary,
            modifier = Modifier.clickable { onBack() }.padding(vertical = 8.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(item.title, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = c.onBackground)
        Spacer(Modifier.height(6.dp))
        Text(item.id, fontSize = 12.sp, color = c.onBackgroundVariant)

        Spacer(Modifier.height(12.dp))
        Row {
            Tag(item.effectiveState.name, if (item.verdict == Verdict.HIT) severityColor(item.severity.name) else c.onBackgroundVariant)
            Spacer(Modifier.width(6.dp))
            Tag(item.severity.label, severityColor(item.severity.name))
            Spacer(Modifier.width(6.dp))
            Tag("置信度 ${item.confidence.name}", c.onBackgroundVariant)
        }

        if (item.detail.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            SmallTitle("风险解释")
            RsCard { Text(item.detail, fontSize = 14.sp, color = c.onBackground) }
        }

        if (item.vectors.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            SmallTitle("原始证据 (${item.vectors.size})")
            RsCard {
                Column {
                    item.vectors.take(16).forEachIndexed { idx, v ->
                        if (idx > 0) ThinDivider(indent = false)
                        Text(
                            v,
                            fontSize = 12.sp,
                            color = c.onBackgroundVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        SmallTitle("检测来源")
        RsCard {
            Column {
                RowKV("类别", "${item.category.code} · ${item.category.label}")
                ThinDivider(indent = false)
                RowKV("权重", "${item.severity.weight}")
                ThinDivider(indent = false)
                RowKV("维度", com.rootsentinel.model.ScoringModel.dimensionOf(item).label)
                ThinDivider(indent = false)
                RowKV("启发式", if (item.heuristic) "是（×0.35）" else "否")
            }
        }

        if (item.fpNote.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            SmallTitle("误报说明")
            RsCard {
                Text(item.fpNote, fontSize = 13.sp, color = c.onBackgroundVariant)
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** 小标签（胶囊） */
@Composable
fun Tag(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(11.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        // 胶囊里的文字不允许换行，否则窄宽度下会被压成两行、看起来像被遮挡
        Text(text, fontSize = 11.sp, color = color, maxLines = 1, softWrap = false)
    }
}