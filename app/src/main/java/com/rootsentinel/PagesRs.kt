package com.rootsentinel

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.IconButton as M3IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.material.ExpressiveScaffold
import me.weishu.kernelsu.ui.component.material.SegmentedColumn
import me.weishu.kernelsu.ui.component.material.SegmentedListItem
import me.weishu.kernelsu.ui.theme.LocalEnableBlur
import me.weishu.kernelsu.ui.util.BlurredBar
import me.weishu.kernelsu.ui.util.rememberBlurBackdrop
import com.rootsentinel.data.HistoryEntry
import com.rootsentinel.model.Evidence
import com.rootsentinel.model.Grade
import com.rootsentinel.model.Verdict
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * RootSentinel 的报告 / 历史 / 扫描 / 详情 / 设备 / 关于页面。
 * 结构照 KernelSU 的 pager 与子页形态（Miuix: Scaffold+BlurredBar+Card；
 * Material: ExpressiveScaffold+LargeFlexibleTopAppBar+SegmentedColumn），
 * 内容是 RootSentinel 自己的扫描结果数据。
 */

fun gradeWord(g: Grade): String = when (g) {
    Grade.CLEAN -> "未发现提权"
    Grade.SUSPICIOUS -> "可疑迹象"
    Grade.ROOTED -> "已提权"
    Grade.ROOTED_HIDING -> "已提权 · 存在隐藏"
    Grade.INCOMPLETE -> "无法判定"
}

fun gradeKindOf(g: Grade): Int = when (g) {
    Grade.CLEAN -> 1
    Grade.SUSPICIOUS -> 2
    Grade.ROOTED, Grade.ROOTED_HIDING -> 3
    Grade.INCOMPLETE -> 4
}

fun verdictWord(v: Verdict): String = when (v) {
    Verdict.HIT -> "命中"
    Verdict.UNKNOWN -> "未知"
    Verdict.PASS -> "通过"
}

fun fmtTime(at: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(at))

// ------------------------------------------------------------------ 报告页

data class ReportUiState(
    val hasResult: Boolean,
    val scanning: Boolean,
    val gradeWord: String,
    val gradeKind: Int,
    val scoreLines: List<Pair<String, String>>,
    val notes: List<String>,
    val hits: List<Evidence>,
    val unknowns: List<Evidence>,
    val all: List<Evidence>,
    val elapsedText: String,
)

data class ReportActions(
    val onRescan: () -> Unit,
    val onExport: () -> Unit,
    val onOpenItem: (Evidence) -> Unit,
)

@Composable
fun ReportPagerMiuix(state: ReportUiState, actions: ReportActions, bottomInnerPadding: Dp) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(LocalEnableBlur.current)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface
    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(color = barColor, title = "报告", scrollBehavior = scrollBehavior)
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 12.dp,
                bottom = bottomInnerPadding + 12.dp,
                start = 12.dp, end = 12.dp,
            ),
        ) {
            if (!state.hasResult) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        BasicComponent(
                            title = if (state.scanning) "正在扫描…" else "尚无报告",
                            summary = if (state.scanning) "扫描完成后自动生成报告" else "先在首页开始一次检测",
                        )
                    }
                }
                return@LazyColumn
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    BasicComponent(
                        title = state.gradeWord,
                        summary = state.scoreLines.joinToString(" · ") { "${it.first} ${it.second}" } +
                            " · 用时 ${state.elapsedText}",
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            if (state.notes.isNotEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            state.notes.forEach { n ->
                                Text(n, fontSize = 13.sp, color = colorScheme.onBackgroundVariant)
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    BasicComponent(
                        title = "导出报告 JSON",
                        summary = "保存完整报告到应用文件目录",
                        endActions = { Icon(imageVector = Icons.Filled.SaveAlt, contentDescription = null) },
                        onClick = actions.onExport,
                    )
                    BasicComponent(
                        title = "重新检测",
                        summary = "回到首页开始新一轮扫描",
                        endActions = { Icon(imageVector = Icons.Filled.Refresh, contentDescription = null) },
                        onClick = actions.onRescan,
                    )
                }
            }
            if (state.hits.isNotEmpty()) {
                item { SmallTitle("命中项（${state.hits.size}）") }
                itemsIndexed(state.hits, key = { idx, e -> "hit-$idx-" + e.id }) { _, e ->
                    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        BasicComponent(
                            title = e.title,
                            summary = "${e.id} · ${e.severity.name} · ${e.category.name}",
                            endActions = {
                                Text(verdictWord(e.verdict), color = colorScheme.error, fontSize = 13.sp)
                            },
                            onClick = { actions.onOpenItem(e) },
                        )
                    }
                }
            }
            if (state.unknowns.isNotEmpty()) {
                item { SmallTitle("未知项（${state.unknowns.size}）") }
                itemsIndexed(state.unknowns, key = { idx, e -> "unk-$idx-" + e.id }) { _, e ->
                    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        BasicComponent(
                            title = e.title,
                            summary = "${e.id} · ${e.category.name}",
                            endActions = {
                                Text(verdictWord(e.verdict), color = colorScheme.onBackgroundVariant, fontSize = 13.sp)
                            },
                            onClick = { actions.onOpenItem(e) },
                        )
                    }
                }
            }
            item { SmallTitle("全部检测项（${state.all.size}）") }
            itemsIndexed(state.all, key = { idx, e -> "all-$idx-" + e.id }) { _, e ->
                Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    BasicComponent(
                        title = e.title,
                        summary = "${e.id} · ${e.category.name}",
                        endActions = {
                            Text(
                                verdictWord(e.verdict),
                                color = when (e.verdict) {
                                    Verdict.HIT -> colorScheme.error
                                    Verdict.UNKNOWN -> colorScheme.onBackgroundVariant
                                    Verdict.PASS -> colorScheme.primary
                                },
                                fontSize = 13.sp,
                            )
                        },
                        onClick = { actions.onOpenItem(e) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportPagerMaterial(state: ReportUiState, actions: ReportActions, bottomInnerPadding: Dp) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    ExpressiveScaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { M3Text("报告") },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = bottomInnerPadding + 12.dp,
            ),
        ) {
            if (!state.hasResult) {
                item {
                    SegmentedColumn(
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                        content = listOf {
                            SegmentedListItem(
                                onClick = actions.onRescan,
                                headlineContent = { M3Text(if (state.scanning) "正在扫描…" else "尚无报告") },
                                supportingContent = { M3Text(if (state.scanning) "扫描完成后自动生成报告" else "先在首页开始一次检测") },
                            )
                        },
                    )
                }
                return@LazyColumn
            }
            item {
                SegmentedColumn(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                    content = listOf(
                        {
                            SegmentedListItem(
                                onClick = {},
                                headlineContent = { M3Text(state.gradeWord) },
                                supportingContent = {
                                    M3Text(
                                        state.scoreLines.joinToString(" · ") { "${it.first} ${it.second}" } +
                                            " · 用时 ${state.elapsedText}",
                                    )
                                },
                            )
                        },
                        {
                            SegmentedListItem(
                                onClick = actions.onExport,
                                headlineContent = { M3Text("导出报告 JSON") },
                                supportingContent = { M3Text("保存完整报告到应用文件目录") },
                                leadingContent = { M3Icon(Icons.Filled.SaveAlt, null) },
                                trailingContent = { M3Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                            )
                        },
                        {
                            SegmentedListItem(
                                onClick = actions.onRescan,
                                headlineContent = { M3Text("重新检测") },
                                supportingContent = { M3Text("回到首页开始新一轮扫描") },
                                leadingContent = { M3Icon(Icons.Filled.Refresh, null) },
                                trailingContent = { M3Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                            )
                        },
                    ),
                )
            }
            fun section(title: String, list: List<Evidence>, keyPrefix: String) {
                if (list.isEmpty()) return
                item {
                    M3Text(
                        title,
                        fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 20.dp, top = 6.dp, bottom = 6.dp),
                    )
                }
                item {
                    val sectionItems = mutableListOf<@Composable () -> Unit>()
                    list.forEach { e ->
                        sectionItems += {
                            SegmentedListItem(
                                onClick = { actions.onOpenItem(e) },
                                headlineContent = { M3Text(e.title) },
                                supportingContent = { M3Text("${e.id} · ${e.category.name}") },
                                trailingContent = { M3Text(verdictWord(e.verdict)) },
                            )
                        }
                    }
                    SegmentedColumn(
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                        content = sectionItems,
                    )
                }
            }
            section("命中项（${state.hits.size}）", state.hits, "hit")
            section("未知项（${state.unknowns.size}）", state.unknowns, "unk")
            section("全部检测项（${state.all.size}）", state.all, "all")
        }
    }
}

// ------------------------------------------------------------------ 历史页

@Composable
fun HistoryPagerMiuix(
    entries: List<HistoryEntry>,
    onClear: () -> Unit,
    bottomInnerPadding: Dp,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(LocalEnableBlur.current)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface
    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor, title = "历史", scrollBehavior = scrollBehavior,
                    actions = {
                        if (entries.isNotEmpty()) {
                            IconButton(onClick = onClear) {
                                Icon(imageVector = Icons.Filled.Delete, contentDescription = "清空历史")
                            }
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 12.dp,
                bottom = bottomInnerPadding + 12.dp,
                start = 12.dp, end = 12.dp,
            ),
        ) {
            if (entries.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        BasicComponent(title = "暂无历史", summary = "完成一次扫描后，摘要会保存在这里（本机）")
                    }
                }
                return@LazyColumn
            }
            itemsIndexed(entries, key = { idx, e -> "h$idx-" + e.at }) { _, e ->
                Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    BasicComponent(
                        title = "${fmtTime(e.at)} · ${historyGradeWord(e.grade)}",
                        summary = "Root ${e.rootScore.toInt()} · 完整性 ${e.integrityScore.toInt()} · 环境 ${e.envScore.toInt()} · " +
                            "覆盖 ${(e.coverage * 100).toInt()}% · ${e.hit} 命中 / ${e.unknown} 未知 / ${e.items} 项",
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryPagerMaterial(
    entries: List<HistoryEntry>,
    onClear: () -> Unit,
    bottomInnerPadding: Dp,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    ExpressiveScaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { M3Text("历史") },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(),
                actions = {
                    if (entries.isNotEmpty()) {
                        M3IconButton(onClick = onClear) {
                            M3Icon(Icons.Filled.Delete, "清空历史")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = bottomInnerPadding + 12.dp,
            ),
        ) {
            if (entries.isEmpty()) {
                item {
                    SegmentedColumn(
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                        content = listOf {
                            SegmentedListItem(
                                onClick = {},
                                headlineContent = { M3Text("暂无历史") },
                                supportingContent = { M3Text("完成一次扫描后，摘要会保存在这里（本机）") },
                            )
                        },
                    )
                }
                return@LazyColumn
            }
            item {
                val historyItems = mutableListOf<@Composable () -> Unit>()
                entries.forEach { e ->
                    historyItems += {
                        SegmentedListItem(
                            onClick = {},
                            headlineContent = { M3Text("${fmtTime(e.at)} · ${historyGradeWord(e.grade)}") },
                            supportingContent = {
                                M3Text(
                                    "Root ${e.rootScore.toInt()} · 完整性 ${e.integrityScore.toInt()} · 环境 ${e.envScore.toInt()} · " +
                                        "覆盖 ${(e.coverage * 100).toInt()}% · ${e.hit} 命中 / ${e.unknown} 未知 / ${e.items} 项",
                                )
                            },
                        )
                    }
                }
                SegmentedColumn(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                    content = historyItems,
                )
            }
        }
    }
}

fun historyGradeWord(name: String): String = when (name) {
    "CLEAN" -> "未发现提权"
    "SUSPICIOUS" -> "可疑迹象"
    "ROOTED" -> "已提权"
    "ROOTED_HIDING" -> "已提权 · 存在隐藏"
    else -> "无法判定"
}

// ------------------------------------------------------------------ 扫描页

@Composable
fun ScanScreenMiuix(scan: ScanState, onCancel: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(LocalEnableBlur.current)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface
    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(color = barColor, title = "正在检测", scrollBehavior = scrollBehavior)
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 12.dp,
                bottom = 24.dp, start = 12.dp, end = 12.dp,
            ),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        BasicComponent(
                            title = scan.moduleName,
                            summary = "已检 ${scan.total} 项 · 命中 ${scan.hit} 项",
                            startAction = { CircularProgressIndicator() },
                        )
                        Spacer(Modifier.height(12.dp))
                        TextButton(
                            text = if (scan.cancelRequested) "正在停止…" else "取消检测",
                            onClick = onCancel,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !scan.cancelRequested,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            if (scan.live.isNotEmpty()) {
                item { SmallTitle("实时命中") }
                itemsIndexed(scan.live, key = { idx, li -> "live-$idx-" + li.id }) { _, li ->
                    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        BasicComponent(title = li.title, summary = "${li.id} · ${li.severity}")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreenMaterial(scan: ScanState, onCancel: () -> Unit) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    ExpressiveScaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { M3Text("正在检测") },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = 24.dp,
            ),
        ) {
            item {
                SegmentedColumn(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                    content = listOf(
                        {
                            SegmentedListItem(
                                onClick = {},
                                headlineContent = { M3Text(scan.moduleName) },
                                supportingContent = { M3Text("已检 ${scan.total} 项 · 命中 ${scan.hit} 项") },
                            )
                        },
                        {
                            SegmentedListItem(
                                onClick = { if (!scan.cancelRequested) onCancel() },
                                headlineContent = { M3Text(if (scan.cancelRequested) "正在停止…" else "取消检测") },
                            )
                        },
                    ),
                )
            }
            if (scan.live.isNotEmpty()) {
                item {
                    M3Text(
                        "实时命中", fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 20.dp, top = 6.dp, bottom = 6.dp),
                    )
                }
                item {
                    val liveItems = mutableListOf<@Composable () -> Unit>()
                    scan.live.forEach { li ->
                        liveItems += {
                            SegmentedListItem(
                                onClick = {},
                                headlineContent = { M3Text(li.title) },
                                supportingContent = { M3Text("${li.id} · ${li.severity}") },
                            )
                        }
                    }
                    SegmentedColumn(
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                        content = liveItems,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 详情页

@Composable
fun DetailScreenMiuix(item: Evidence, onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(LocalEnableBlur.current)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface
    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor, title = "检测项详情", scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 12.dp,
                bottom = 24.dp, start = 12.dp, end = 12.dp,
            ),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(item.title, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(10.dp))
                        DetailLine("编号", item.id)
                        DetailLine("类别", item.category.name)
                        DetailLine("判定", verdictWord(item.verdict))
                        DetailLine("严重度", item.severity.name)
                        DetailLine("置信度", item.confidence.name)
                        DetailLine("启发式", if (item.heuristic) "是（软证据）" else "否")
                        if (item.detail.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(item.detail, fontSize = 13.sp, color = colorScheme.onBackgroundVariant)
                        }
                    }
                }
            }
            if (item.vectors.isNotEmpty()) {
                item { SmallTitle("原始观测") }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            item.vectors.forEach { v ->
                                Text(v, fontSize = 12.sp, color = colorScheme.onBackgroundVariant)
                                Spacer(Modifier.height(3.dp))
                            }
                        }
                    }
                }
            }
            if (item.fpNote.isNotBlank() || item.verifyNote.isNotBlank()) {
                item { SmallTitle("说明") }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            if (item.fpNote.isNotBlank()) {
                                Text("误报说明：${item.fpNote}", fontSize = 13.sp, color = colorScheme.onBackgroundVariant)
                                Spacer(Modifier.height(6.dp))
                            }
                            if (item.verifyNote.isNotBlank()) {
                                Text("真机确认：${item.verifyNote}", fontSize = 13.sp, color = colorScheme.onBackgroundVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    BasicComponent(title = value, summary = label)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreenMaterial(item: Evidence, onBack: () -> Unit) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    ExpressiveScaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { M3Text("检测项详情") },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(),
                navigationIcon = {
                    M3IconButton(onClick = onBack) {
                        M3Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = 24.dp,
            ),
        ) {
            item {
                val rows = mutableListOf<Pair<String, String>>(
                    "编号" to item.id,
                    "类别" to item.category.name,
                    "判定" to verdictWord(item.verdict),
                    "严重度" to item.severity.name,
                    "置信度" to item.confidence.name,
                    "启发式" to if (item.heuristic) "是（软证据）" else "否",
                )
                val detailItems = mutableListOf<@Composable () -> Unit>()
                detailItems += {
                    SegmentedListItem(
                        onClick = {},
                        headlineContent = { M3Text(item.title) },
                        supportingContent = { if (item.detail.isNotBlank()) M3Text(item.detail) },
                    )
                }
                rows.forEach { (k, v) ->
                    detailItems += {
                        SegmentedListItem(
                            onClick = {},
                            headlineContent = { M3Text(v) },
                            supportingContent = { M3Text(k) },
                        )
                    }
                }
                SegmentedColumn(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                    content = detailItems,
                )
            }
            if (item.vectors.isNotEmpty()) {
                item {
                    val vectorItems = mutableListOf<@Composable () -> Unit>()
                    item.vectors.forEach { v ->
                        vectorItems += {
                            SegmentedListItem(
                                onClick = {},
                                headlineContent = { M3Text(v, fontSize = 12.sp) },
                            )
                        }
                    }
                    SegmentedColumn(
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                        content = vectorItems,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 设备页

// v2.22.68：设备页的 SELinux 显示改走与原生检测相同的分层通道。
// 此前这里直读 /sys/fs/selinux/enforce——部分厂商（如 vivo）把整个
// selinuxfs 对应用域关闭，直读永远失败、恒显示 Unknown，与检测
// 结论（.67 起金丝雀推断已判定通过）不一致，反而误导用户。
// 通道顺序：enforce 文件 → status 状态页（u32 @8）→ 金丝雀推断
// （stat 核对 DAC 权限位后，access 被 EACCES/EPERM 拒绝 ⇒
// Permissive 只记日志不拦截，能真拦住即 Enforcing）。
fun selinuxStatusText(): String {
    runCatching { java.io.File("/sys/fs/selinux/enforce").readText().trim() }
        .getOrNull()?.let { if (it == "1") return "Enforcing"; if (it == "0") return "Permissive" }
    runCatching {
        val b = java.io.File("/sys/fs/selinux/status").readBytes()
        if (b.size >= 12) {
            val flag = (b[8].toInt() and 0xFF) or ((b[9].toInt() and 0xFF) shl 8) or
                ((b[10].toInt() and 0xFF) shl 16) or ((b[11].toInt() and 0xFF) shl 24)
            if (flag == 1) return "Enforcing"
            if (flag == 0) return "Permissive"
        }
    }
    for (path in listOf("/sys/fs/selinux/enforce", "/sys/fs/selinux/status")) {
        val st = runCatching { android.system.Os.stat(path) }.getOrNull() ?: continue
        if (st.st_mode and android.system.OsConstants.S_IROTH == 0) continue
        if (st.st_uid == android.system.Os.getuid()) continue
        val denied = runCatching {
            android.system.Os.access(path, android.system.OsConstants.R_OK); false
        }.getOrElse { e ->
            val errno = (e as? android.system.ErrnoException)?.errno ?: -1
            errno == android.system.OsConstants.EACCES || errno == android.system.OsConstants.EPERM
        }
        if (denied) return "Enforcing（推断）"
    }
    return "Unknown"
}

fun deviceInfoRows(): List<Pair<String, String>> {
    val uname = runCatching { android.system.Os.uname() }.getOrNull()
    val selinux = selinuxStatusText()
    return listOf(
        "品牌" to Build.BRAND,
        "型号" to Build.MODEL,
        "制造商" to Build.MANUFACTURER,
        "设备代号" to Build.DEVICE,
        "主板" to Build.BOARD,
        "硬件" to Build.HARDWARE,
        "Android 版本" to Build.VERSION.RELEASE,
        "SDK" to Build.VERSION.SDK_INT.toString(),
        "内核" to (uname?.release ?: "—"),
        "系统版本字符串" to (uname?.version ?: "—"),
        "构建指纹" to Build.FINGERPRINT,
        "构建类型" to Build.TYPE,
        "构建标签" to Build.TAGS,
        "支持的 ABI" to Build.SUPPORTED_ABIS.joinToString(", "),
        "SELinux" to selinux,
    )
}

@Composable
fun DeviceScreenMiuix(onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(LocalEnableBlur.current)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface
    val rows = deviceInfoRows()
    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor, title = "设备信息", scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 12.dp,
                bottom = 24.dp, start = 12.dp, end = 12.dp,
            ),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column {
                        rows.forEach { (k, v) -> BasicComponent(title = v, summary = k) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreenMaterial(onBack: () -> Unit) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val rows = deviceInfoRows()
    ExpressiveScaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { M3Text("设备信息") },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(),
                navigationIcon = {
                    M3IconButton(onClick = onBack) {
                        M3Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = 24.dp,
            ),
        ) {
            item {
                val deviceItems = mutableListOf<@Composable () -> Unit>()
                rows.forEach { (k, v) ->
                    deviceItems += {
                        SegmentedListItem(
                            onClick = {},
                            headlineContent = { M3Text(v) },
                            supportingContent = { M3Text(k) },
                        )
                    }
                }
                SegmentedColumn(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                    content = deviceItems,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ 关于页

@Composable
fun AboutScreenMiuix(onBack: () -> Unit, onOpenUrl: (String) -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(LocalEnableBlur.current)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface
    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor, title = stringResource(R.string.about), scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 12.dp,
                bottom = 24.dp, start = 12.dp, end = 12.dp,
            ),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("RootSentinel", fontSize = 20.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "v${RootSentinelVersion.NAME} (${RootSentinelVersion.CODE})",
                            fontSize = 13.sp, color = colorScheme.onBackgroundVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "设备安全自检工具：多维度检测 Root、Hook、系统完整性与运行环境风险。" +
                                "判定只依据真实证据，无法测量的项目一律记为未知，绝不冒充通过。",
                            fontSize = 13.sp, color = colorScheme.onBackgroundVariant,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Card(Modifier.fillMaxWidth()) {
                    BasicComponent(
                        title = "项目仓库（GitHub）",
                        summary = "github.com/AURORA-0711/RootSentinel",
                        onClick = { onOpenUrl("https://github.com/AURORA-0711/RootSentinel") },
                    )
                    BasicComponent(
                        title = "Gitee 镜像",
                        summary = "gitee.com/AURORAqw/RootSentinel",
                        onClick = { onOpenUrl("https://gitee.com/AURORAqw/RootSentinel") },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreenMaterial(onBack: () -> Unit, onOpenUrl: (String) -> Unit) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    ExpressiveScaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { M3Text(stringResource(R.string.about)) },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(),
                navigationIcon = {
                    M3IconButton(onClick = onBack) {
                        M3Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = 24.dp,
            ),
        ) {
            item {
                SegmentedColumn(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                    content = listOf(
                        {
                            SegmentedListItem(
                                onClick = {},
                                headlineContent = { M3Text("RootSentinel v${RootSentinelVersion.NAME} (${RootSentinelVersion.CODE})") },
                                supportingContent = {
                                    M3Text("设备安全自检工具：多维度检测 Root、Hook、系统完整性与运行环境风险。判定只依据真实证据，无法测量的项目一律记为未知，绝不冒充通过。")
                                },
                            )
                        },
                        {
                            SegmentedListItem(
                                onClick = { onOpenUrl("https://github.com/AURORA-0711/RootSentinel") },
                                headlineContent = { M3Text("项目仓库（GitHub）") },
                                supportingContent = { M3Text("github.com/AURORA-0711/RootSentinel") },
                                trailingContent = { M3Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                            )
                        },
                        {
                            SegmentedListItem(
                                onClick = { onOpenUrl("https://gitee.com/AURORAqw/RootSentinel") },
                                headlineContent = { M3Text("Gitee 镜像") },
                                supportingContent = { M3Text("gitee.com/AURORAqw/RootSentinel") },
                                trailingContent = { M3Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                            )
                        },
                    ),
                )
            }
        }
    }
}
