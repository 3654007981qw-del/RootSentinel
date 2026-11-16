package com.rootsentinel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.IconButton as M3IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rootsentinel.data.HistoryEntry
import com.rootsentinel.model.Evidence
import com.rootsentinel.model.Verdict
import com.sukisu.nova.ui.LocalUiMode
import com.sukisu.nova.ui.UiMode
import com.sukisu.nova.ui.component.material.ExpressiveScaffold
import com.sukisu.nova.ui.component.material.SegmentedColumn
import com.sukisu.nova.ui.component.material.SegmentedListItem
import com.sukisu.nova.ui.component.material.expressiveTopAppBarColors
import com.sukisu.nova.ui.component.miuix.AppCard
import com.sukisu.nova.ui.component.miuix.AppScaffold
import com.sukisu.nova.ui.theme.LocalBackgroundImagePath
import com.sukisu.nova.ui.theme.LocalEnableBlur
import com.sukisu.nova.ui.util.BlurredBar
import com.sukisu.nova.ui.util.CustomTextKey
import com.sukisu.nova.ui.util.customText
import com.sukisu.nova.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * SukiSU 外壳内的 RootSentinel 报表 / 历史页（v2.22.75 起）。
 *
 * 与 PagesRs.kt 里给 KSU 外壳用的版本同数据、同文案、同动作，视觉与
 * 结构照 SukiSU-Nova 自己的页面：Miuix 走 AppScaffold + 自家
 * BlurredBar/rememberBlurBackdrop + AppCard（背景感知透明度）；
 * Material 走自家 ExpressiveScaffold + expressiveTopAppBarColors +
 * SegmentedColumn。模式切换读 SukiSU 自己的 LocalUiMode（与首页、
 * 设置页一致），不再读 KSU 外壳的 LocalUiMode。
 */

// ------------------------------------------------------------------ 报表页

@Composable
fun SukisuReportPager(state: ReportUiState, actions: ReportActions, bottomInnerPadding: Dp) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> SukisuReportPagerMiuix(state, actions, bottomInnerPadding)
        UiMode.Material -> SukisuReportPagerMaterial(state, actions, bottomInnerPadding)
    }
}

@Composable
private fun SukisuReportPagerMiuix(state: ReportUiState, actions: ReportActions, bottomInnerPadding: Dp) {
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val hasCustomBackground = LocalBackgroundImagePath.current.isNotEmpty()
    AppScaffold(
        containerColor = if (hasCustomBackground) Color.Transparent else colorScheme.surface,
        topBar = {
            BlurredBar(backdrop = backdrop, scrollBehavior = scrollBehavior) {
                TopAppBar(
                    color = Color.Transparent,
                    title = customText(CustomTextKey.PAGE_REPORT),
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = bottomInnerPadding + 12.dp,
                ),
                overscrollEffect = null,
            ) {
                if (!state.hasResult) {
                    item {
                        AppCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                            BasicComponent(
                                title = if (state.scanning) "正在扫描…" else "尚无报告",
                                summary = if (state.scanning) "扫描完成后自动生成报告" else "先在首页开始一次检测",
                            )
                        }
                    }
                    return@LazyColumn
                }
                item {
                    AppCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
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
                        AppCard(Modifier.fillMaxWidth()) {
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
                    AppCard(Modifier.fillMaxWidth()) {
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
                        AppCard(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
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
                        AppCard(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
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
                    AppCard(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SukisuReportPagerMaterial(state: ReportUiState, actions: ReportActions, bottomInnerPadding: Dp) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val hasCustomBackground = LocalBackgroundImagePath.current.isNotEmpty()
    ExpressiveScaffold(
        containerColor = if (hasCustomBackground) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { M3Text(customText(CustomTextKey.PAGE_REPORT)) },
                colors = expressiveTopAppBarColors(
                    containerColor = if (hasCustomBackground) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = if (hasCustomBackground) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
                ),
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                scrollBehavior = scrollBehavior,
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxHeight().nestedScroll(scrollBehavior.nestedScrollConnection),
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
            fun section(title: String, list: List<Evidence>) {
                if (list.isEmpty()) return
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
                        title = title,
                        content = sectionItems,
                    )
                }
            }
            section("命中项（${state.hits.size}）", state.hits)
            section("未知项（${state.unknowns.size}）", state.unknowns)
            section("全部检测项（${state.all.size}）", state.all)
        }
    }
}

// ------------------------------------------------------------------ 历史页

@Composable
fun SukisuHistoryPager(
    entries: List<HistoryEntry>,
    onClear: () -> Unit,
    bottomInnerPadding: Dp,
) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> SukisuHistoryPagerMiuix(entries, onClear, bottomInnerPadding)
        UiMode.Material -> SukisuHistoryPagerMaterial(entries, onClear, bottomInnerPadding)
    }
}

@Composable
private fun SukisuHistoryPagerMiuix(
    entries: List<HistoryEntry>,
    onClear: () -> Unit,
    bottomInnerPadding: Dp,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val hasCustomBackground = LocalBackgroundImagePath.current.isNotEmpty()
    AppScaffold(
        containerColor = if (hasCustomBackground) Color.Transparent else colorScheme.surface,
        topBar = {
            BlurredBar(backdrop = backdrop, scrollBehavior = scrollBehavior) {
                TopAppBar(
                    color = Color.Transparent,
                    title = customText(CustomTextKey.PAGE_HISTORY),
                    scrollBehavior = scrollBehavior,
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
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = bottomInnerPadding + 12.dp,
                ),
                overscrollEffect = null,
            ) {
                if (entries.isEmpty()) {
                    item {
                        AppCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                            BasicComponent(title = "暂无历史", summary = "完成一次扫描后，摘要会保存在这里（本机）")
                        }
                    }
                    return@LazyColumn
                }
                itemsIndexed(entries, key = { idx, e -> "h$idx-" + e.at }) { idx, e ->
                    AppCard(Modifier.fillMaxWidth().padding(top = if (idx == 0) 12.dp else 0.dp, bottom = 8.dp)) {
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SukisuHistoryPagerMaterial(
    entries: List<HistoryEntry>,
    onClear: () -> Unit,
    bottomInnerPadding: Dp,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val hasCustomBackground = LocalBackgroundImagePath.current.isNotEmpty()
    ExpressiveScaffold(
        containerColor = if (hasCustomBackground) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { M3Text(customText(CustomTextKey.PAGE_HISTORY)) },
                colors = expressiveTopAppBarColors(
                    containerColor = if (hasCustomBackground) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = if (hasCustomBackground) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
                ),
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                scrollBehavior = scrollBehavior,
                actions = {
                    if (entries.isNotEmpty()) {
                        M3IconButton(onClick = onClear) {
                            M3Icon(Icons.Filled.Delete, "清空历史")
                        }
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxHeight().nestedScroll(scrollBehavior.nestedScrollConnection),
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
