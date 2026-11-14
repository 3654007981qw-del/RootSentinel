package com.rootsentinel.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.rootsentinel.model.Category
import com.rootsentinel.model.Confidence
import com.rootsentinel.model.Evidence
import com.rootsentinel.model.Severity
import com.rootsentinel.model.Verdict

/**
 * 完整性深度检测（M9）— 9 项
 *
 * 全部围绕「这个 APK / 这个进程有没有被动过」：
 * 签名方案、静默授权、debuggable 标记、安装来源、调试开关、调试叠加层…
 */
class IntegrityDeepDetector(private val context: Context) {

    fun collect(): List<Evidence> = listOf(
        sigScheme(), permGranted(), appFlags(), installer(),
        adbEnabled(), devOptions(), overlayPerm(), unknownSources(),
        signatureHash(),
    )

    // ---------------------------------------------------------- I.SIG_SCHEME
    private fun sigScheme(): Evidence = try {
        val pm = context.packageManager
        val pi = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val si = pi.signingInfo
        if (si == null) {
            ev("I.SIG_SCHEME", Severity.LOW, Verdict.UNKNOWN, "无 signingInfo")
        } else {
            val signers = runCatching { si.apkContentsSigners }.getOrNull()
            val n = signers?.size ?: 0
            val multi = runCatching { si.hasMultipleSigners() }.getOrDefault(false)
            if (n == 1 && !multi) {
                ev("I.SIG_SCHEME", Severity.INFO, Verdict.PASS, "签名方案正常（单签名者）",
                    conf = Confidence.HIGH, vec = listOf("signers=$n"))
            } else {
                ev("I.SIG_SCHEME", Severity.HIGH, Verdict.HIT,
                    "签名者数量异常（$n）", conf = Confidence.HIGH,
                    vec = listOf("signers=$n", "multi=$multi"),
                    detail = "多签名者通常是「二次签名 / 重打包」的特征")
            }
        }
    } catch (t: Throwable) {
        ev("I.SIG_SCHEME", Severity.LOW, Verdict.UNKNOWN, "签名信息不可读", detail = t.toString())
    }

    // -------------------------------------------------------- I.PERM_GRANTED
    // 实际被授予的权限若多于 Manifest 声明的，说明有人绕过权限模型。
    private fun permGranted(): Evidence = try {
        val pm = context.packageManager
        val pi = pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val declared = pi.requestedPermissions?.toSet() ?: emptySet()

        var grantedExtra = 0
        val extras = mutableListOf<String>()

        for (p in declared) {
            if (context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) {
                // 只统计「运行时应授权但可能被静默给」的危险权限
            }
        }
        // 反向：检查是否持有未声明的权限
        val all = listOf(
            "android.permission.READ_SMS", "android.permission.READ_CALL_LOG",
            "android.permission.READ_CONTACTS", "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.CAMERA", "android.permission.RECORD_AUDIO",
        )
        for (p in all) {
            if (p !in declared &&
                context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
            ) {
                grantedExtra++
                extras.add(p)
            }
        }

        if (grantedExtra == 0) {
            ev("I.PERM_GRANTED", Severity.INFO, Verdict.PASS, "无越权授予的权限",
                conf = Confidence.HIGH)
        } else {
            ev("I.PERM_GRANTED", Severity.CRITICAL, Verdict.HIT,
                "持有未声明的权限（$grantedExtra）", conf = Confidence.HIGH,
                vec = extras.take(5).map { "perm=$it" },
                detail = "权限模型被绕过：Manifest 未声明却已授予")
        }
    } catch (t: Throwable) {
        ev("I.PERM_GRANTED", Severity.LOW, Verdict.UNKNOWN, "权限信息不可读", detail = t.toString())
    }

    // ----------------------------------------------------------- I.APP_FLAGS
    private fun appFlags(): Evidence = try {
        val ai = context.applicationInfo
        val debuggable = (ai.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val testOnly = (ai.flags and ApplicationInfo.FLAG_TEST_ONLY) != 0

        if (debuggable) {
            ev("I.APP_FLAGS", Severity.HIGH, Verdict.HIT, "APK 被标记为 debuggable",
                conf = Confidence.HIGH,
                vec = listOf("debuggable=1", "testOnly=$testOnly"),
                detail = "release 版不应带 debuggable；这是重打包的典型痕迹")
        } else {
            ev("I.APP_FLAGS", Severity.INFO, Verdict.PASS, "APK 标记正常",
                conf = Confidence.HIGH, vec = listOf("debuggable=0", "testOnly=$testOnly"))
        }
    } catch (t: Throwable) {
        ev("I.APP_FLAGS", Severity.LOW, Verdict.UNKNOWN, "ApplicationInfo 不可读")
    }

    // ------------------------------------------------------------ I.INSTALLER
    private fun installer(): Evidence = try {
        val pm = context.packageManager
        val src = if (Build.VERSION.SDK_INT >= 30) {
            pm.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(context.packageName)
        }

        if (src.isNullOrEmpty()) {
            ev("I.INSTALLER", Severity.LOW, Verdict.HIT, "无安装来源信息",
                conf = Confidence.LOW,
                detail = "侧载（adb / 文件管理器）安装通常没有 installer",
                fp = "用 adb install 正常安装也会是这个状态")
        } else {
            ev("I.INSTALLER", Severity.INFO, Verdict.PASS, "安装来源已记录",
                conf = Confidence.HIGH, vec = listOf("installer=$src"))
        }
    } catch (t: Throwable) {
        ev("I.INSTALLER", Severity.LOW, Verdict.UNKNOWN, "安装来源不可读")
    }

    // ----------------------------------------------------------- I.ADB_ENABLED
    private fun adbEnabled(): Evidence = try {
        val v = Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, -1)
        when (v) {
            1 -> ev("I.ADB_ENABLED", Severity.MEDIUM, Verdict.HIT, "USB 调试已开启",
                vec = listOf("ADB_ENABLED=1"),
                detail = "调试通道打开，可被 adb shell 直接操作",
                fp = "开发机常态")
            0 -> ev("I.ADB_ENABLED", Severity.INFO, Verdict.PASS, "USB 调试未开启",
                conf = Confidence.HIGH, vec = listOf("ADB_ENABLED=0"))
            else -> ev("I.ADB_ENABLED", Severity.LOW, Verdict.UNKNOWN, "ADB 状态读取失败")
        }
    } catch (t: Throwable) {
        ev("I.ADB_ENABLED", Severity.LOW, Verdict.UNKNOWN, "ADB 状态不可读")
    }

    // ----------------------------------------------------------- I.DEV_OPTIONS
    private fun devOptions(): Evidence = try {
        val v = Settings.Global.getInt(
            context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, -1)
        when (v) {
            1 -> ev("I.DEV_OPTIONS", Severity.LOW, Verdict.HIT, "开发者选项已启用",
                vec = listOf("dev_options=1"), fp = "开发机常态")
            0 -> ev("I.DEV_OPTIONS", Severity.INFO, Verdict.PASS, "开发者选项未启用",
                conf = Confidence.HIGH)
            else -> ev("I.DEV_OPTIONS", Severity.LOW, Verdict.UNKNOWN, "开发者选项状态不可读")
        }
    } catch (t: Throwable) {
        ev("I.DEV_OPTIONS", Severity.LOW, Verdict.UNKNOWN, "开发者选项不可读")
    }

    // ----------------------------------------------------------- I.OVERLAY_PERM
    private fun overlayPerm(): Evidence = try {
        if (Build.VERSION.SDK_INT < 23) {
            ev("I.OVERLAY_PERM", Severity.LOW, Verdict.UNKNOWN, "版本过低无悬浮窗权限模型")
        } else {
            val ok = Settings.canDrawOverlays(context)
            if (ok) {
                ev("I.OVERLAY_PERM", Severity.MEDIUM, Verdict.HIT, "已获得悬浮窗权限",
                    detail = "悬浮窗可用于覆盖攻击（点按劫持）",
                    fp = "用户可能主动授权")
            } else {
                ev("I.OVERLAY_PERM", Severity.INFO, Verdict.PASS, "无悬浮窗权限",
                    conf = Confidence.HIGH)
            }
        }
    } catch (t: Throwable) {
        ev("I.OVERLAY_PERM", Severity.LOW, Verdict.UNKNOWN, "悬浮窗权限不可读")
    }

    // -------------------------------------------------------- I.UNKNOWN_SOURCES
    @Suppress("DEPRECATION")
    private fun unknownSources(): Evidence = try {
        val v = Settings.Secure.getInt(
            context.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, -1)
        when (v) {
            1 -> ev("I.UNKNOWN_SOURCES", Severity.MEDIUM, Verdict.HIT,
                "允许安装未知来源应用", vec = listOf("install_non_market=1"))
            0 -> ev("I.UNKNOWN_SOURCES", Severity.INFO, Verdict.PASS,
                "禁止未知来源安装", conf = Confidence.HIGH)
            else -> ev("I.UNKNOWN_SOURCES", Severity.LOW, Verdict.UNKNOWN,
                "未知来源设置不可读（Android 8+ 按来源授权，属正常）")
        }
    } catch (t: Throwable) {
        ev("I.UNKNOWN_SOURCES", Severity.LOW, Verdict.UNKNOWN, "未知来源设置不可读")
    }

    // --------------------------------------------------------- I.SIGNATURE_HASH
    // 记录本 APK 的签名摘要，便于跨版本对比（同一签名应始终一致）。
    private fun signatureHash(): Evidence = try {
        val pm = context.packageManager
        val pi = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val sigs = pi.signingInfo?.apkContentsSigners
        if (sigs.isNullOrEmpty()) {
            ev("I.SIGNATURE_HASH", Severity.LOW, Verdict.UNKNOWN, "无签名证书")
        } else {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val h = md.digest(sigs[0].toByteArray())
            val hex = h.joinToString("") { String.format("%02x", it) }
            ev("I.SIGNATURE_HASH", Severity.INFO, Verdict.PASS, "签名摘要已记录",
                conf = Confidence.HIGH,
                vec = listOf("sha256=${hex.take(32)}…"),
                detail = "用于跨版本对比：签名变了说明 APK 被重新签名")
        }
    } catch (t: Throwable) {
        ev("I.SIGNATURE_HASH", Severity.LOW, Verdict.UNKNOWN, "签名摘要计算失败")
    }

    // ---------------------------------------------------------------- helper
    private fun ev(
        id: String,
        sev: Severity,
        v: Verdict,
        title: String,
        cat: Category = Category.INTEGRITY,
        conf: Confidence = Confidence.MEDIUM,
        vec: List<String> = emptyList(),
        detail: String = "",
        fp: String = "",
    ) = Evidence(
        id = id, category = cat, severity = sev, confidence = conf,
        verdict = v, title = title, vectors = vec, detail = detail, fpNote = fp,
    )
}