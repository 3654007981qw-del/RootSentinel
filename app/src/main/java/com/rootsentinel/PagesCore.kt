package com.rootsentinel

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Build

import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Warning

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rootsentinel.engine.DetectorEngine
import com.rootsentinel.model.Evidence
import com.rootsentinel.model.EvidenceState
import com.rootsentinel.model.Grade
import com.rootsentinel.model.Verdict
import com.rootsentinel.util.JsonExporter
import kotlinx.coroutines.launch

// ============================================================ 语义色与文案
// 版式说明（v2.22.59）：页面结构按 KernelSU 管理器——
// 首页 = 更新卡 → 警告卡 → 状态卡 → 信息卡；报告 = 结论卡 +
// 分组卡片列表；详情 = 顶栏返回 + 分组卡片。组件走 KsuUi 的
// 模式感知层（Miuix / Material 双外观），页面只写一份。

fun gradeWord(g: Grade): String = when (g) {
    Grade.CLEAN -> "正常"
    Grade.SUSPICIOUS -> "可疑"
    Grade.ROOTED -> "已提权"
    Grade.ROOTED_HIDING -> "已提权 + 隐藏"
    Grade.INCOMPLETE -> "无法判定"
}

@Composable
fun gradeColor(g: Grade): Color {
    val p = LocalRsPalette.current
    return when (g) {
        Grade.CLEAN -> RsStatusColors.Success
        Grade.SUSPICIOUS -> p.primary
        Grade.ROOTED -> RsStatusColors.Danger
        Grade.ROOTED_HIDING -> RsStatusColors.Danger
        Grade.INCOMPLETE -> p.onVariant
    }
}

@Composable
fun severityColor(sev: String): Color {
    val p = LocalRsPalette.current
    return when (sev) {
        "CRITICAL" -> RsStatusColors.Danger
        "HIGH" -> RsStatusColors.Warning
        "MEDIUM" -> RsStatusColors.Medium
        else -> p.onVariant
    }
}

// ============================================================ 首页

@Composable
fun HomePage(
    result: DetectorEngine.Result?,
    scanning: Boolean,
    updateVersion: String?,
    onScan: () -> Unit,
    onDevice: () -> Unit,
    onUpdateClick: () -> Unit,
) {
    val p = LocalRsPalette.current
    val s = result?.score

    PageScroll {
        // 更新卡（KernelSU 首页第一张：有新版才出现）
        if (updateVersion != null) {
            RsCard(
                containerColor = p.primary.copy(alpha = 0.12f),
                onClick = onUpdateClick,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        RsText("新版本可用", RsTextStyle.Body, color = p.primary, fontWeight = FontWeight.Medium)
                        RsText("v$updateVersion · 点此查看并更新", RsTextStyle.Caption, color = p.primary)
                    }
                    RsText("›", RsTextStyle.TitleM, color = p.primary)
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // 警告卡（上次结论不完整时提醒，不当作安全结论）
        if (s != null && s.grade == Grade.INCOMPLETE) {
            RsWarningCard("上次扫描检测面不完整，未能给出完整结论，建议重新检测。", RsWarningLevel.Notice)
            Spacer(Modifier.height(12.dp))
        }

        // 状态卡（KernelSU StatusCard：整卡语义色 + 大图标 + 状态词）
        val statusColor = if (s == null) null else gradeColor(s.grade)
        RsCard(containerColor = statusColor) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val icon = when {
                    s == null -> Icons.Filled.Info
                    s.grade == Grade.CLEAN -> Icons.Filled.CheckCircle
                    s.grade == Grade.INCOMPLETE -> Icons.Filled.Info
                    else -> Icons.Filled.Warning
                }
                val fg = if (statusColor != null) Color.White else p.onVariant
                androidx.compose.material3.Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    RsText(
                        if (s == null) "尚未检测" else gradeWord(s.grade),
                        RsTextStyle.TitleL,
                        color = if (statusColor != null) Color.White else p.onBackground,
                    )
                    RsText(
                        when {
                            s == null -> "点下方按钮开始第一次检测"
                            else -> "ROOT ${String.format("%.1f", s.rootScore)} · 覆盖率 ${(s.coverage * 100).toInt()}% · ${s.hitCount} 命中 / ${s.unknownCount} 未知"
                        },
                        RsTextStyle.BodyS,
                        color = if (statusColor != null) Color.White.copy(alpha = 0.85f) else p.onVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        RsButton(
            text = when {
                scanning -> "正在扫描…"
                result == null -> "开始检测"
                else -> "重新检测"
            },
            onClick = onScan,
            modifier = Modifier.fillMaxWidth(),
            enabled = !scanning,
        )

        Spacer(Modifier.height(16.dp))
        RsSectionTitle("设备信息")
        RsCard(insideMargin = RowCardMargin) {
            Column(Modifier.fillMaxWidth()) {
                RsInfoRow(
                    Icons.Filled.Phone, "设备",
                    result?.profile?.let { "${it.brand} ${it.model}" }
                        ?: "${Build.MANUFACTURER} ${Build.MODEL}",
                )
                RsDivider()
                RsInfoRow(
                    Icons.Filled.Build, "Android",
                    "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                )
                RsDivider()
                RsInfoRow(
                    Icons.Filled.Lock, "SELinux",
                    result?.items?.firstOrNull { it.id.startsWith("F.SELINUX") }?.title ?: "扫描后可见",
                )
                RsDivider()
                RsInfoRow(
                    Icons.Filled.Info, "应用版本",
                    "RootSentinel ${RootSentinelVersion.NAME}",
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        RsCard(insideMargin = RowCardMargin) {
            RsNavRow(
                icon = Icons.Filled.Info,
                title = "全部设备信息",
                sub = "系统 / 硬件 / 安全状态 / 运行环境",
                onClick = onDevice,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ============================================================ 扫描页

@Composable
fun ScanPage(scan: ScanState, onRescan: () -> Unit, onCancel: () -> Unit) {
    val p = LocalRsPalette.current
    val elapsed = if (scan.startedAt > 0L) {
        ((if (scan.scanning) System.currentTimeMillis() else scan.startedAt) - scan.startedAt)
            .coerceAtLeast(0L)
    } else 0L

    PageScroll {
        RsCard {
            Column(Modifier.fillMaxWidth()) {
                RsText(
                    when {
                        scan.scanning -> scan.moduleName
                        scan.failed -> "扫描失败"
                        else -> "扫描完成"
                    },
                    RsTextStyle.TitleM,
                    color = if (scan.failed) RsStatusColors.Danger else p.onBackground,
                )
                Spacer(Modifier.height(6.dp))
                RsText(
                    "阶段 ${scan.moduleIdx}/51 · ${String.format("%.1f", elapsed / 1000.0)} s",
                    RsTextStyle.Caption,
                )
                Spacer(Modifier.height(12.dp))
                RsProgress(
                    fraction = if (scan.moduleIdx <= 0) 0f else scan.moduleIdx / 51f,
                    color = p.primary,
                )
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        RsText("${scan.total}", RsTextStyle.TitleL)
                        RsText("已检测", RsTextStyle.Caption)
                    }
                    Column(Modifier.weight(1f)) {
                        RsText("${scan.hit}", RsTextStyle.TitleL, color = RsStatusColors.Warning)
                        RsText("命中", RsTextStyle.Caption)
                    }
                }
            }
        }

        if (scan.failed) {
            Spacer(Modifier.height(12.dp))
            RsWarningCard(
                "扫描未完成：已完成 ${scan.total} 项，但检测面不完整，不应据此判断设备安全。",
                RsWarningLevel.Error,
            )
        }

        Spacer(Modifier.height(16.dp))
        RsSectionTitle("实时发现")
        Spacer(Modifier.height(4.dp))
        if (scan.live.isEmpty()) {
            RsText(
                if (scan.scanning) "扫描中…" else "本次没有命中项",
                RsTextStyle.Label,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            RsCard(insideMargin = RowCardMargin) {
                Column(Modifier.fillMaxWidth()) {
                    val items = scan.live.take(10)
                    items.forEachIndexed { idx, it ->
                        if (idx > 0) RsDivider()
                        Row(
                            Modifier.fillMaxWidth().height(52.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(severityColor(it.severity)),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                RsText(it.title, RsTextStyle.BodyS)
                                RsText(it.id, RsTextStyle.Caption)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        if (scan.scanning) {
            RsButton("取消扫描", onClick = onCancel, modifier = Modifier.fillMaxWidth())
        } else {
            RsButton("重新检测", onClick = onRescan, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ============================================================ 报告页

@Composable
fun ReportPage(
    result: DetectorEngine.Result?,
    onRescan: () -> Unit,
    onDetail: (Evidence) -> Unit,
) {
    val p = LocalRsPalette.current
    val ctx = LocalContext.current
    val exportScope = rememberCoroutineScope()

    if (result == null) {
        PageScroll {
            Spacer(Modifier.height(12.dp))
            RsCard {
                Column {
                    RsText("还没有报告", RsTextStyle.TitleM)
                    Spacer(Modifier.height(6.dp))
                    RsText("先完成一次扫描，这里会显示结论与全部证据。", RsTextStyle.Label)
                }
            }
            Spacer(Modifier.height(16.dp))
            RsButton("开始检测", onClick = onRescan, modifier = Modifier.fillMaxWidth())
        }
        return
    }

    val s = result.score
    val hits = result.items.filter { it.verdict == Verdict.HIT }

    PageScroll {
        // 结论卡
        RsCard(containerColor = gradeColor(s.grade)) {
            Column(Modifier.fillMaxWidth()) {
                RsText("结论", RsTextStyle.Caption, color = Color.White.copy(alpha = 0.85f))
                Spacer(Modifier.height(4.dp))
                RsText(s.grade.label, RsTextStyle.TitleL, color = Color.White)
                Spacer(Modifier.height(4.dp))
                RsText(
                    "ROOT ${String.format("%.1f", s.rootScore)} · 置信度 ${s.confidence.name}",
                    RsTextStyle.BodyS,
                    color = Color.White.copy(alpha = 0.85f),
                )
                if (s.grade == Grade.INCOMPLETE) {
                    Spacer(Modifier.height(6.dp))
                    RsText(
                        "检测面不完整，本次不给出「干净」结论",
                        RsTextStyle.BodyS,
                        color = Color.White,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        RsSectionTitle("维度得分")
        RsCard(insideMargin = RowCardMargin) {
            Column {
                RowKV("Root 状态", String.format("%.1f", s.rootScore))
                RsDivider()
                RowKV("系统完整性", String.format("%.1f", s.integrityScore))
                RsDivider()
                RowKV("挂钩与注入", String.format("%.1f", s.hookScore))
                RsDivider()
                RowKV("运行环境", String.format("%.1f", s.environmentScore))
            }
        }

        Spacer(Modifier.height(16.dp))
        RsSectionTitle("检测完整性")
        RsCard(insideMargin = RowCardMargin) {
            Column {
                val errored = result.items.count {
                    it.effectiveState == EvidenceState.ERROR || it.effectiveState == EvidenceState.TIMEOUT
                }
                RowKV("可读率", "${(s.coverage * 100).toInt()}%")
                RsDivider()
                RowKV("证据条目", "${result.items.size}")
                RsDivider()
                RowKV("命中", "${s.hitCount}")
                RsDivider()
                RowKV("UNKNOWN", "${s.unknownCount}")
                RsDivider()
                RowKV("执行失败", "$errored", if (errored > 0) RsStatusColors.Danger else null)
            }
        }

        if (hits.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            RsSectionTitle("风险分布")
            RsCard {
                Column {
                    val max = hits.size.coerceAtLeast(1)
                    val list = listOf(
                        "CRITICAL" to RsStatusColors.Danger,
                        "HIGH" to RsStatusColors.Warning,
                        "MEDIUM" to RsStatusColors.Medium,
                        "LOW" to p.onVariant,
                    )
                    list.forEach { (sev, color) ->
                        val n = hits.count { it.severity.name == sev }
                        if (n > 0) {
                            Row(
                                Modifier.fillMaxWidth().height(34.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RsText(sev, RsTextStyle.Caption, modifier = Modifier.width(72.dp))
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(5.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(p.onBackground.copy(alpha = 0.08f)),
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxHeight()
                                            .fillMaxWidth(n.toFloat() / max)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(color),
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                RsText("$n", RsTextStyle.BodyS, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            RsSectionTitle("高风险项目")
            RsCard(insideMargin = RowCardMargin) {
                Column(Modifier.fillMaxWidth()) {
                    val top = hits
                        .sortedWith(
                            compareByDescending<Evidence> { it.severity.ordinal }
                                .thenByDescending { it.confidence.ordinal },
                        )
                        .take(10)
                    top.forEachIndexed { idx, e ->
                        if (idx > 0) RsDivider()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .clickable { onDetail(e) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(severityColor(e.severity.name)),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                RsText(e.title, RsTextStyle.BodyS)
                                RsText(e.id, RsTextStyle.Caption)
                            }
                            RsText("›", RsTextStyle.TitleM, color = p.onVariant)
                        }
                    }
                }
            }
            if (hits.size > 10) {
                Spacer(Modifier.height(8.dp))
                RsText("另有 ${hits.size - 10} 项命中未列出", RsTextStyle.Caption)
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth()) {
            RsButton("重新扫描", onClick = onRescan, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            RsButton(
                "导出 JSON",
                // 导出走 IO 协程 + Toast 反馈（沿用既有实现与注释口径）
                onClick = {
                    exportScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val file = JsonExporter.export(ctx, result)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            android.widget.Toast.makeText(
                                ctx,
                                if (file != null) "已导出: ${file.name}" else "导出失败",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ============================================================ 详情页

@Composable
fun DetailPage(item: Evidence?, onBack: () -> Unit) {
    val p = LocalRsPalette.current
    if (item == null) {
        PageScroll { RsText("没有选中项", RsTextStyle.Label) }
        return
    }

    PageScroll {
        RsText(item.title, RsTextStyle.TitleL)
        Spacer(Modifier.height(4.dp))
        RsText(item.id, RsTextStyle.Caption)
        Spacer(Modifier.height(12.dp))
        Row {
            RsTag(
                item.effectiveState.name,
                if (item.verdict == Verdict.HIT) severityColor(item.severity.name) else p.onVariant,
            )
            Spacer(Modifier.width(6.dp))
            RsTag(item.severity.label, severityColor(item.severity.name))
            Spacer(Modifier.width(6.dp))
            RsTag("置信度 ${item.confidence.name}", p.onVariant)
        }

        if (item.detail.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            RsSectionTitle("风险解释")
            RsCard { RsText(item.detail, RsTextStyle.BodyS) }
        }

        if (item.vectors.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            RsSectionTitle("原始证据 (${item.vectors.size})")
            RsCard(insideMargin = RowCardMargin) {
                Column {
                    item.vectors.take(16).forEachIndexed { idx, v ->
                        if (idx > 0) RsDivider()
                        RsText(
                            v,
                            RsTextStyle.Caption,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        RsSectionTitle("检测来源")
        RsCard(insideMargin = RowCardMargin) {
            Column {
                RowKV("类别", "${item.category.code} · ${item.category.label}")
                RsDivider()
                RowKV("权重", "${item.severity.weight}")
                RsDivider()
                RowKV("维度", com.rootsentinel.model.ScoringModel.dimensionOf(item).label)
                RsDivider()
                RowKV("启发式", if (item.heuristic) "是（×0.35）" else "否")
            }
        }

        if (item.fpNote.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            RsSectionTitle("误报说明")
            RsCard {
                RsText(item.fpNote, RsTextStyle.BodyS, color = p.onVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
