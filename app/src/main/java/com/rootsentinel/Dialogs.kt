package com.rootsentinel

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TextButton as M3TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.ui.UiMode
import me.weishu.kernelsu.ui.component.markdown.GithubMarkdown
import me.weishu.kernelsu.ui.LocalUiMode
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator as MiuixLinearProgress
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * RootSentinel 的两个自有弹窗，套 KernelSU 的弹窗形态：
 * Miuix 模式用 miuix 的 WindowDialog，Material 模式用 material3 AlertDialog，
 * 两套组件各自独立实现（miuix 组件依赖 MiuixTheme，不能在 Material 主题下渲染）。
 * 内容与流程（声明即同意、更新校验、下载进度/测速/取消）与旧版一致。
 */

private fun fmtBytes(b: Long): String = when {
    b <= 0 -> "—"
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> String.format("%.1f KB", b / 1024.0)
    else -> String.format("%.2f MB", b / (1024.0 * 1024))
}

private fun fmtSpeed(bps: Long): String = if (bps <= 0) "—" else fmtBytes(bps) + "/s"

private val DECLARATION_PARAGRAPHS = listOf(
    "RootSentinel 是一款设备安全自检工具，检测结果仅供参考，不构成对设备状态的保证。",
    "为改良检测准确率，继续使用即表示您同意：每次扫描完成后，本应用上传一份诊断摘要，包括：应用版本与版本号；设备品牌、型号、制造商与 Android 版本；检测总评级与四项分项评分（Root、完整性、Hook、环境）、总体置信度与覆盖率；命中数、未知数、检测项总数与本次检测耗时；每条检测结果的编号、类别、严重度、置信度、判定与启发式标记；以及上报时间戳；应用发生崩溃时，下次启动将上传该次崩溃的异常堆栈（异常类型、消息、完整堆栈与发生线程）及同样的版本与机型信息。",
    "不上传：设备序列号、IMEI、设备指纹、文件路径、已安装应用列表与扫描报告全文。",
    "上传默认开启，可随时在「设置 → 隐私与数据」中关闭，关闭后立即停止。",
)

// ---------------------------------------------------------------- 声明弹窗

@Composable
fun DeclarationDialog(onConfirm: () -> Unit) {
    if (LocalUiMode.current == UiMode.Miuix) {
        val show = remember { mutableStateOf(true) }
        WindowDialog(
            show = show.value,
            title = "使用声明与数据说明",
            onDismissRequest = { /* 必须点按钮确认，不提供绕过路径 */ },
            content = {
                // heightIn 不是装饰：miuix WindowDialog 内部自带一层
                // 滚动容器，内层 verticalScroll 会拿到无限高度约束，
                // 测量时直接抛 IllegalStateException 闪退（v2.22.64
                // 真机崩溃日志定位，更新弹窗同因）。先用 heightIn 把
                // 高度钉在有限值内，嵌套滚动才合法。四个弹窗的滚动
                // 列都按此处理（v2.22.66）。
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    DECLARATION_PARAGRAPHS.forEach { p ->
                        Text(p, fontSize = 13.sp, color = MiuixTheme.colorScheme.onBackgroundVariant)
                        Spacer(Modifier.height(8.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        text = "我已阅读，继续使用",
                        onClick = onConfirm,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            },
        )
    } else {
        AlertDialog(
            onDismissRequest = { /* 同上 */ },
            title = { M3Text("使用声明与数据说明") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    DECLARATION_PARAGRAPHS.forEach { p ->
                        M3Text(p, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                    }
                }
            },
            confirmButton = {
                M3TextButton(onClick = onConfirm) { M3Text("我已阅读，继续使用") }
            },
        )
    }
}

// ---------------------------------------------------------------- 更新弹窗

@Composable
fun UpdateDialog(controller: UpdateController, onDismiss: () -> Unit) {
    if (LocalUiMode.current == UiMode.Miuix) {
        UpdateDialogMiuix(controller, onDismiss)
    } else {
        UpdateDialogMaterial(controller, onDismiss)
    }
}

private fun updateTitle(phase: UpdateController.Phase): String = when (phase) {
    UpdateController.Phase.Available -> "发现新版本"
    UpdateController.Phase.Downloading -> "正在下载更新"
    UpdateController.Phase.Verifying -> "正在校验"
    UpdateController.Phase.NeedPermission -> "需要安装权限"
    else -> "检查更新"
}

@Composable
private fun UpdateDialogMiuix(controller: UpdateController, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val phase = controller.phase
    val info = controller.info
    val show = remember { mutableStateOf(true) }
    WindowDialog(
        show = show.value,
        title = updateTitle(phase),
        onDismissRequest = onDismiss,
        content = {
            Column {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    when (phase) {
                        UpdateController.Phase.Checking, UpdateController.Phase.Idle ->
                            Text("正在检查更新…", fontSize = 14.sp)
                        UpdateController.Phase.UpToDate ->
                            Text("已是最新版本（v${RootSentinelVersion.NAME}）。", fontSize = 14.sp)
                        UpdateController.Phase.Error ->
                            Text(controller.errorText, fontSize = 14.sp)
                        UpdateController.Phase.Available -> if (info != null) {
                            Text(
                                "当前版本 v${RootSentinelVersion.NAME} → 新版本 v${info.versionName}",
                                fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text("来源：${info.source} · 大小：${fmtBytes(info.apkSize)}", fontSize = 12.sp)
                            if (info.notes.isNotBlank()) {
                                Spacer(Modifier.height(10.dp))
                                // v2.22.69：不用 MarkdownContent——它自带一层
                                // verticalScroll，嵌在本弹窗的滚动列里会被以
                                // 无限高测量而抛 IllegalStateException（.64/
                                // .65/.66 更新弹窗闪退的真因；.66 给外层列加
                                // heightIn 治不了，因为滚动容器给子项的永远
                                // 是无限高）。直接用无滚动包装的 GithubMarkdown
                                // （WebView 本身不是 Compose 滚动容器）。
                                GithubMarkdown(content = info.notes, isMarkdown = true)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("下载完成后将自动校验 SHA-256、包名与签名，校验通过才调起安装。", fontSize = 12.sp)
                        }
                        UpdateController.Phase.Downloading -> {
                            MiuixLinearProgress(progress = controller.progress, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "${(controller.progress * 100).toInt()}% · ${fmtSpeed(controller.speedBps)} · " +
                                    "${fmtBytes(controller.doneBytes)} / ${fmtBytes(controller.totalBytes)}",
                                fontSize = 13.sp,
                            )
                        }
                        UpdateController.Phase.Verifying ->
                            Text("下载完成，正在校验安装包（哈希、包名、版本、签名）…", fontSize = 14.sp)
                        UpdateController.Phase.NeedPermission ->
                            Text(
                                "安装更新需要「安装未知应用」权限。请在系统设置中为 RootSentinel 开启后，回到这里点「继续安装」。",
                                fontSize = 14.sp,
                            )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    when (phase) {
                        UpdateController.Phase.Available -> {
                            TextButton(text = "稍后", onClick = onDismiss, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(20.dp))
                            TextButton(
                                text = "立即更新",
                                onClick = { controller.startDownload(scope, ctx) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                            )
                        }
                        UpdateController.Phase.Downloading -> TextButton(
                            text = "取消下载",
                            onClick = { controller.cancelDownload() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        UpdateController.Phase.NeedPermission -> {
                            TextButton(
                                text = "去开启",
                                onClick = {
                                    runCatching {
                                        ctx.startActivity(
                                            Intent(
                                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                                Uri.parse("package:" + ctx.packageName),
                                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(20.dp))
                            TextButton(
                                text = "继续安装",
                                onClick = { controller.startDownload(scope, ctx) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                            )
                        }
                        UpdateController.Phase.Error -> {
                            TextButton(text = "关闭", onClick = onDismiss, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(20.dp))
                            TextButton(
                                text = "重试",
                                onClick = { controller.checkManual(scope) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                            )
                        }
                        UpdateController.Phase.Checking, UpdateController.Phase.Verifying ->
                            TextButton(text = "后台运行", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
                        else -> TextButton(text = "关闭", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
    )
}

@Composable
private fun UpdateDialogMaterial(controller: UpdateController, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val phase = controller.phase
    val info = controller.info
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { M3Text(updateTitle(phase)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                when (phase) {
                    UpdateController.Phase.Checking, UpdateController.Phase.Idle ->
                        M3Text("正在检查更新…", fontSize = 14.sp)
                    UpdateController.Phase.UpToDate ->
                        M3Text("已是最新版本（v${RootSentinelVersion.NAME}）。", fontSize = 14.sp)
                    UpdateController.Phase.Error ->
                        M3Text(controller.errorText, fontSize = 14.sp)
                    UpdateController.Phase.Available -> if (info != null) {
                        M3Text(
                            "当前版本 v${RootSentinelVersion.NAME} → 新版本 v${info.versionName}",
                            fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(4.dp))
                        M3Text("来源：${info.source} · 大小：${fmtBytes(info.apkSize)}", fontSize = 12.sp)
                        if (info.notes.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            M3Text(info.notes, fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        M3Text("下载完成后将自动校验 SHA-256、包名与签名，校验通过才调起安装。", fontSize = 12.sp)
                    }
                    UpdateController.Phase.Downloading -> {
                        LinearProgressIndicator(progress = { controller.progress }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        M3Text(
                            "${(controller.progress * 100).toInt()}% · ${fmtSpeed(controller.speedBps)} · " +
                                "${fmtBytes(controller.doneBytes)} / ${fmtBytes(controller.totalBytes)}",
                            fontSize = 13.sp,
                        )
                    }
                    UpdateController.Phase.Verifying ->
                        M3Text("下载完成，正在校验安装包（哈希、包名、版本、签名）…", fontSize = 14.sp)
                    UpdateController.Phase.NeedPermission ->
                        M3Text(
                            "安装更新需要「安装未知应用」权限。请在系统设置中为 RootSentinel 开启后，回到这里点「继续安装」。",
                            fontSize = 14.sp,
                        )
                }
            }
        },
        confirmButton = {
            when (phase) {
                UpdateController.Phase.Available -> {
                    M3TextButton(onClick = onDismiss) { M3Text("稍后") }
                    M3TextButton(onClick = { controller.startDownload(scope, ctx) }) { M3Text("立即更新") }
                }
                UpdateController.Phase.Downloading ->
                    M3TextButton(onClick = { controller.cancelDownload() }) { M3Text("取消下载") }
                UpdateController.Phase.NeedPermission -> {
                    M3TextButton(onClick = {
                        runCatching {
                            ctx.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + ctx.packageName),
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }) { M3Text("去开启") }
                    M3TextButton(onClick = { controller.startDownload(scope, ctx) }) { M3Text("继续安装") }
                }
                UpdateController.Phase.Error -> {
                    M3TextButton(onClick = onDismiss) { M3Text("关闭") }
                    M3TextButton(onClick = { controller.checkManual(scope) }) { M3Text("重试") }
                }
                UpdateController.Phase.Checking, UpdateController.Phase.Verifying ->
                    M3TextButton(onClick = onDismiss) { M3Text("后台运行") }
                else -> M3TextButton(onClick = onDismiss) { M3Text("关闭") }
            }
        },
    )
}
