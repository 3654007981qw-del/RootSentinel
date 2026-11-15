package com.rootsentinel

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rootsentinel.util.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/**
 * 应用内更新的流程控制（v2.22.47）。
 *
 * 启动自动检查（查到新版才弹窗）与设置页手动检查（立即弹窗给
 * 反馈）共用这一份状态机；下载/校验/安装的实际工作在
 * [Updater]。下载完成自动进入校验，校验通过自动调起系统安装；
 * 缺「安装未知应用」授权时停在 NeedPermission 阶段引导开启。
 */
class UpdateController {

    enum class Phase {
        Idle, Checking, UpToDate, Available, Downloading, Verifying,
        NeedPermission, Error,
    }

    var phase by mutableStateOf(Phase.Idle)
        private set
    var dialogVisible by mutableStateOf(false)
    var info by mutableStateOf<Updater.UpdateInfo?>(null)
        private set
    var progress by mutableStateOf(0f)
        private set
    var errorText by mutableStateOf("")
        private set

    private var cancelFlag = false
    private var downloaded: File? = null
    private var busy = false

    /** 启动时自动检查：静默进行，只有发现新版才弹窗。 */
    fun checkAuto(scope: CoroutineScope) {
        if (busy || phase == Phase.Available) return
        busy = true
        scope.launch {
            val found = withContext(Dispatchers.IO) { Updater.check() }
            busy = false
            if (found != null) {
                info = found
                phase = Phase.Available
                dialogVisible = true
            }
        }
    }

    /** 设置页手动检查：立即弹窗，查不到新版也给明确反馈。 */
    fun checkManual(scope: CoroutineScope) {
        if (busy) {
            dialogVisible = true
            return
        }
        busy = true
        dialogVisible = true
        phase = Phase.Checking
        scope.launch {
            val found = withContext(Dispatchers.IO) { Updater.check() }
            busy = false
            if (found != null) {
                info = found
                phase = Phase.Available
            } else {
                phase = Phase.UpToDate
            }
        }
    }

    /** 下载 → 校验 → 安装，一条链走完；中途失败停在 Error。 */
    fun startDownload(scope: CoroutineScope, context: Context) {
        val inf = info ?: return
        cancelFlag = false
        progress = 0f
        phase = Phase.Downloading
        scope.launch {
            try {
                val file = Updater.updateFile(context, inf.versionName)
                val sha = withContext(Dispatchers.IO) {
                    Updater.download(
                        inf,
                        file,
                        onProgress = { done, total ->
                            if (total > 0) progress = done.toFloat() / total
                        },
                        isCancelled = { cancelFlag },
                    )
                }
                downloaded = file
                phase = Phase.Verifying
                val vr = withContext(Dispatchers.IO) {
                    Updater.verify(context, file, inf, sha)
                }
                when (vr) {
                    Updater.VerifyResult.Ok -> installNow(context)
                    is Updater.VerifyResult.Fail -> {
                        errorText = vr.reason
                        phase = Phase.Error
                    }
                }
            } catch (e: InterruptedException) {
                phase = Phase.Available
            } catch (e: Exception) {
                errorText = e.message ?: "下载失败，请检查网络后重试"
                phase = Phase.Error
            }
        }
    }

    /** 调起系统安装；缺授权时 Updater 会打开设置页，这里停住等用户回来再点。 */
    fun installNow(context: Context) {
        val f = downloaded ?: return
        when (Updater.install(context, f)) {
            Updater.InstallGate.LAUNCHED -> {
                dialogVisible = false
                phase = Phase.Idle
            }
            Updater.InstallGate.NEED_PERMISSION -> phase = Phase.NeedPermission
        }
    }

    fun cancelDownload() {
        cancelFlag = true
    }

    fun dismiss() {
        if (phase == Phase.Downloading || phase == Phase.Verifying) return
        dialogVisible = false
        if (phase != Phase.Available) phase = Phase.Idle
    }
}

@Composable
fun UpdateDialog(
    controller: UpdateController,
    context: Context,
    scope: CoroutineScope,
) {
    val c = MiuixTheme.colorScheme
    val phase = controller.phase
    val inf = controller.info
    val cancellable = phase != UpdateController.Phase.Downloading &&
        phase != UpdateController.Phase.Verifying

    Dialog(
        onDismissRequest = { if (cancellable) controller.dismiss() },
        properties = DialogProperties(
            dismissOnBackPress = cancellable,
            dismissOnClickOutside = cancellable,
        ),
    ) {
        RsCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                when (phase) {
                    UpdateController.Phase.Checking -> {
                        DialogTitle("检查更新")
                        Text(
                            "正在检查新版本…",
                            fontSize = 13.sp,
                            color = c.onBackgroundVariant,
                        )
                    }

                    UpdateController.Phase.UpToDate -> {
                        DialogTitle("检查更新")
                        Text(
                            "已是最新版本（${RootSentinelVersion.NAME}）。",
                            fontSize = 13.sp,
                            color = c.onBackgroundVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        PrimaryButton("关闭") { controller.dismiss() }
                    }

                    UpdateController.Phase.Available -> {
                        if (inf != null) {
                            DialogTitle("发现新版本")
                            Text(
                                "${RootSentinelVersion.NAME} → ${inf.versionName}",
                                fontSize = 20.sp,
                                color = c.onBackground,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "更新来源：${inf.source}" +
                                    if (inf.apkSize > 0) {
                                        " · 约 %.1f MB".format(inf.apkSize / 1048576.0)
                                    } else {
                                        ""
                                    },
                                fontSize = 12.sp,
                                color = c.onBackgroundVariant,
                            )
                            if (inf.notes.isNotBlank()) {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    "本次更新内容",
                                    fontSize = 13.sp,
                                    color = c.onBackground,
                                )
                                Spacer(Modifier.height(4.dp))
                                Column(
                                    Modifier
                                        .heightIn(max = 220.dp)
                                        .verticalScroll(rememberScrollState()),
                                ) {
                                    Text(
                                        inf.notes.trim(),
                                        fontSize = 12.sp,
                                        color = c.onBackgroundVariant,
                                    )
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            PrimaryButton("立即更新") {
                                controller.startDownload(scope, context)
                            }
                            SecondaryText("稍后再说") { controller.dismiss() }
                        }
                    }

                    UpdateController.Phase.Downloading -> {
                        DialogTitle("正在下载更新")
                        LinearProgressIndicator(
                            progress = { controller.progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = c.primary,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "${(controller.progress * 100).toInt()}%",
                            fontSize = 13.sp,
                            color = c.onBackgroundVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        SecondaryText("取消下载") { controller.cancelDownload() }
                    }

                    UpdateController.Phase.Verifying -> {
                        DialogTitle("正在校验安装包")
                        Text(
                            "核对发布哈希、包名与签名证书，全部通过才安装。",
                            fontSize = 13.sp,
                            color = c.onBackgroundVariant,
                        )
                    }

                    UpdateController.Phase.NeedPermission -> {
                        DialogTitle("需要安装授权")
                        Text(
                            "已打开系统设置：请允许 RootSentinel 安装应用，返回后点「继续安装」。安装包已下载并通过校验，不用重新下载。",
                            fontSize = 13.sp,
                            color = c.onBackgroundVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        PrimaryButton("继续安装") { controller.installNow(context) }
                        SecondaryText("稍后再说") { controller.dismiss() }
                    }

                    UpdateController.Phase.Error -> {
                        DialogTitle("更新失败")
                        Text(
                            controller.errorText,
                            fontSize = 13.sp,
                            color = c.onBackgroundVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        PrimaryButton("关闭") { controller.dismiss() }
                    }

                    UpdateController.Phase.Idle -> Unit
                }
            }
        }
    }
}

@Composable
private fun DialogTitle(text: String) {
    val c = MiuixTheme.colorScheme
    Text(text, fontSize = 17.sp, color = c.onBackground)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    GlassButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
    ) {
        Text(text, fontSize = 15.sp)
    }
}

@Composable
private fun SecondaryText(text: String, onClick: () -> Unit) {
    val c = MiuixTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 13.sp, color = c.onBackgroundVariant)
    }
}
