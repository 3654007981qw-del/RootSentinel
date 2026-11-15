package com.rootsentinel.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import com.rootsentinel.model.Category
import com.rootsentinel.model.Confidence
import com.rootsentinel.model.Evidence
import com.rootsentinel.model.Severity
import com.rootsentinel.model.Verdict

/**
 * 系统服务 / Binder 层检测（M6）
 *
 * 这一层看的是「框架层有没有被动过」：
 *   · ServiceManager 里有没有多出 root 相关服务
 *   · ActivityManager 报告的自身 uid 与 Process.myUid 是否一致
 *   · APK 的真实路径是不是规范路径
 *   · 包列表数量是否异常（包名过滤的痕迹）
 */
class ServiceDetector(private val context: Context) {

    fun collect(): List<Evidence> = listOf(
        serviceList(),
        amSelf(),
        apkPath(),
        pkgCount(),
    )

    // ---------------------------------------------------------- F.SERVICE_LIST
    // ServiceManager 是 @hide API，反射在部分版本会被 blocklist 拦。
    // 拦住了就是 UNKNOWN，这是正常的，不能当 PASS。
    private fun serviceList(): Evidence {
        return try {
            val cls = Class.forName("android.os.ServiceManager")
            val m = cls.getDeclaredMethod("listServices").apply { isAccessible = true }

            @Suppress("UNCHECKED_CAST")
            val names = m.invoke(null) as? Array<String>
                ?: return unknown("F.SERVICE_LIST", "ServiceManager.listServices 返回 null")

            val keys = listOf(
                "susfs", "kernelsu", "ksu", "apatch", "magisk",
                "shamiko", "zygisk", "resetprop",
            )
            val bad = names.filter { n ->
                val low = n.lowercase()
                keys.any { low.contains(it) }
            }

            if (bad.isEmpty()) {
                Evidence(
                    id = "F.SERVICE_LIST",
                    category = Category.SELINUX,
                    severity = Severity.INFO,
                    confidence = Confidence.HIGH,
                    verdict = Verdict.PASS,
                    title = "系统服务列表无 root 相关项",
                    vectors = listOf("total=${names.size}"),
                )
            } else {
                Evidence(
                    id = "F.SERVICE_LIST",
                    category = Category.SELINUX,
                    severity = Severity.CRITICAL,
                    confidence = Confidence.HIGH,
                    verdict = Verdict.HIT,
                    title = "系统服务列表出现 root 相关服务",
                    vectors = bad.take(6).map { "service=$it" },
                    detail = "LKM 型 root 会向 ServiceManager 注册自己的 binder 服务",
                )
            }
        } catch (t: Throwable) {
            unknown("F.SERVICE_LIST", "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // --------------------------------------------------------------- F.AM_SELF
    private fun amSelf(): Evidence {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return unknown("F.AM_SELF", "ActivityManager 不可用")

        // v2.22.50（第六轮审查 #10）：读取异常与「列表为空」分开。
        // 此前异常被吞成 null，与空列表合流到下面的 PASS 分支——
        // 异常 ≠ 正常限制，必须落 UNKNOWN。
        val procs = try {
            am.runningAppProcesses
        } catch (t: Throwable) {
            return unknown(
                "F.AM_SELF",
                "runningAppProcesses 读取异常: ${t.javaClass.simpleName}",
            )
        }

        if (procs.isNullOrEmpty()) {
            return Evidence(
                id = "F.AM_SELF",
                category = Category.SELINUX,
                severity = Severity.INFO,
                confidence = Confidence.HIGH,
                verdict = Verdict.PASS,
                title = "运行进程列表不可用（Android 5+ 正常限制）",
            )
        }

        val self = procs.firstOrNull { it.pid == Process.myPid() }
            ?: return Evidence(
                id = "F.AM_SELF",
                category = Category.SELINUX,
                severity = Severity.LOW,
                confidence = Confidence.LOW,
                verdict = Verdict.UNKNOWN,
                title = "本进程不在运行进程列表中",
            )

        return if (self.uid == Process.myUid()) {
            Evidence(
                id = "F.AM_SELF",
                category = Category.SELINUX,
                severity = Severity.INFO,
                confidence = Confidence.HIGH,
                verdict = Verdict.PASS,
                title = "ActivityManager 报告的 uid 与自身一致",
                vectors = listOf("uid=${self.uid}", "procs=${procs.size}"),
            )
        } else {
            Evidence(
                id = "F.AM_SELF",
                category = Category.SELINUX,
                severity = Severity.CRITICAL,
                confidence = Confidence.HIGH,
                verdict = Verdict.HIT,
                title = "ActivityManager 报告的 uid 与 Process.myUid 不一致",
                vectors = listOf("am_uid=${self.uid}", "real_uid=${Process.myUid()}"),
                detail = "ActivityManager 返回的数据被改写",
            )
        }
    }

    // -------------------------------------------------------------- F.APK_PATH
    private fun apkPath(): Evidence {
        val dir = try {
            context.applicationInfo.sourceDir
        } catch (t: Throwable) {
            return unknown("F.APK_PATH", "sourceDir 不可读")
        }

        return if (dir.startsWith("/data/app/")) {
            Evidence(
                id = "F.APK_PATH",
                category = Category.SELINUX,
                severity = Severity.INFO,
                confidence = Confidence.HIGH,
                verdict = Verdict.PASS,
                title = "APK 位于规范安装路径",
                vectors = listOf("dir=$dir"),
            )
        } else {
            Evidence(
                id = "F.APK_PATH",
                category = Category.SELINUX,
                severity = Severity.HIGH,
                confidence = Confidence.HIGH,
                verdict = Verdict.HIT,
                title = "APK 不在 /data/app 下",
                vectors = listOf("dir=$dir"),
                detail = "APK 被搬到非规范位置，常与重打包 / 注入相关",
            )
        }
    }

    // ------------------------------------------------------------- F.PKG_COUNT
    private fun pkgCount(): Evidence {
        val count = try {
            context.packageManager.getInstalledApplications(0).size
        } catch (t: Throwable) {
            return unknown("F.PKG_COUNT", "getInstalledApplications 失败")
        }

        return if (count < 20) {
            Evidence(
                id = "F.PKG_COUNT",
                category = Category.SELINUX,
                severity = Severity.MEDIUM,
                confidence = Confidence.LOW,
                verdict = Verdict.HIT,
                title = "可见应用数量异常少（$count）",
                vectors = listOf("count=$count"),
                detail = "正常 Android 设备应有数十到数百个包；"
                    + "数量过少说明包列表被过滤",
                fpNote = "极简系统 / 刚刷机的设备也可能很少，需人工确认",
            )
        } else {
            Evidence(
                id = "F.PKG_COUNT",
                category = Category.SELINUX,
                severity = Severity.INFO,
                confidence = Confidence.HIGH,
                verdict = Verdict.PASS,
                title = "可见应用数量正常",
                vectors = listOf("count=$count"),
            )
        }
    }

    // ---------------------------------------------------------------- helpers
    // v2.22.50（第六轮审查 #10）：unknown() 必须显式传证据 ID。
    // 此前写死 F.SERVICE_LIST，却被 amSelf / apkPath / pkgCount 复用，
    // 产出的 UNKNOWN 顶着别人的 ID —— 评分前按 ID 去重时还会把
    // 真 F.SERVICE_LIST 证据合并掉一条。
    private fun unknown(id: String, msg: String) = Evidence(
        id = id,
        category = Category.SELINUX,
        severity = Severity.LOW,
        confidence = Confidence.LOW,
        verdict = Verdict.UNKNOWN,
        title = "检测通道不可用",
        detail = msg,
    )
}