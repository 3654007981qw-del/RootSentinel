package com.rootsentinel

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rootsentinel.util.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    /** 实时下载速度（字节/秒，v2.22.55）与已下载/总字节，供弹窗展示 */
    var speedBps by mutableStateOf(0L)
        private set
    var doneBytes by mutableStateOf(0L)
        private set
    var totalBytes by mutableStateOf(0L)
        private set
    var errorText by mutableStateOf("")
        private set

    // IO 线程高频读、主线程写：必须 @Volatile，旧版普通 var
    // 不保证可见性，取消可能永远传不到下载线程（v2.22.57）
    @Volatile
    private var cancelFlag = false
    private var downloaded: File? = null
    private var busy = false

    /**
     * 检查完成的统一落点（v2.22.50，第六轮审查 #12）：
     * 结论三分——有新版永远弹窗；已最新/检查失败只在弹窗已开着时
     * 给反馈（手动检查、或自动检查进行中被用户打开的弹窗），
     * 没开就不打扰。此前 null 被一律当成「已是最新」，双源不可达
     * 时会误报；且手动检查撞上自动检查时弹窗停在 Idle 渲染空白。
     */
    private fun onCheckDone(result: Updater.CheckResult) {
        busy = false
        when (result) {
            is Updater.CheckResult.Found -> {
                info = result.info
                phase = Phase.Available
                dialogVisible = true
            }
            Updater.CheckResult.UpToDate -> {
                if (dialogVisible) phase = Phase.UpToDate
            }
            Updater.CheckResult.Failed -> {
                if (dialogVisible) {
                    errorText = "检查更新失败：网络不可达或更新源无响应，请稍后重试"
                    phase = Phase.Error
                }
            }
        }
    }

    /** 启动时自动检查：静默进行，只有发现新版才弹窗。 */
    fun checkAuto(scope: CoroutineScope) {
        if (busy || phase == Phase.Available) return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { Updater.check() }
            onCheckDone(result)
        }
    }

    /** 设置页手动检查：立即弹窗，任何结论都给明确反馈。 */
    fun checkManual(scope: CoroutineScope) {
        if (phase == Phase.Available && info != null) {
            dialogVisible = true
            return
        }
        dialogVisible = true
        phase = Phase.Checking
        if (busy) return // 已有检查在跑，其完成回调会填充本弹窗
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { Updater.check() }
            onCheckDone(result)
        }
    }

    /** 下载 → 校验 → 安装，一条链走完；中途失败停在 Error。 */
    fun startDownload(scope: CoroutineScope, context: Context) {
        val inf = info ?: return
        cancelFlag = false
        progress = 0f
        speedBps = 0L
        doneBytes = 0L
        totalBytes = inf.apkSize
        phase = Phase.Downloading
        scope.launch {
            try {
                val file = Updater.updateFile(context, inf.versionName)
                val sha = withContext(Dispatchers.IO) {
                    Updater.download(
                        inf,
                        file,
                        onProgress = { done, total, speed ->
                            doneBytes = done
                            if (total > 0) {
                                totalBytes = total
                                progress = done.toFloat() / total
                            }
                            speedBps = speed
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
        // 同时断开在飞连接：阻塞中的 connect/read 立刻抛错，
        // 取消即时生效，不再等超时（v2.22.57）
        Updater.cancelActive()
    }

    fun dismiss() {
        if (phase == Phase.Downloading || phase == Phase.Verifying) return
        dialogVisible = false
        if (phase != Phase.Available) phase = Phase.Idle
    }
}
