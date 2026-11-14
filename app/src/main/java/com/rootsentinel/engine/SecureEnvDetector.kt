package com.rootsentinel.engine

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Debug
import android.view.accessibility.AccessibilityManager
import com.rootsentinel.model.Category
import com.rootsentinel.model.Confidence
import com.rootsentinel.model.Evidence
import com.rootsentinel.model.Severity
import com.rootsentinel.model.Verdict
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 安全环境检测（L5 调试/虚拟化 + L6 应用与通信）— v2.18
 *
 * 补的是审计里点名的缺口：
 *   I.USER_CA      用户安装的 CA（抓包前置条件）
 *   I.CA_SUSPECT   MITM 工具的 CA 是否进了系统信任库
 *   I.ACCESSIBILITY 无障碍服务滥用（可读屏 / 自动点击）
 *   I.OVERLAY      悬浮窗权限（可覆盖界面、截屏伪造）
 *   I.JDWP         JDWP / 调试器**交叉验证**
 *   E.FRIDA_PORT   frida-server 默认端口是否在监听
 *   E.FRIDA_THREAD Frida 线程名（gum-js-loop 等）
 *   D.MAPS_FRIDA   maps 里是否映射了 frida / gum / linjector
 *   X.CONTAINER    容器 / 多开环境
 *
 * 设计口径：
 *   1. 每一项都必须写明误报来源（fpNote）；
 *   2. 单一弱特征只给 LOW/启发式，不下结论；
 *   3. 能交叉验证的地方一定交叉验证（JDWP 就是典型）。
 */
class SecureEnvDetector(private val context: Context) {

    fun collect(): List<Evidence> {
        val out = mutableListOf<Evidence>()
        fun safe(block: () -> Evidence) {
            try {
                out.add(block())
            } catch (t: Throwable) {
                out.add(
                    ev(
                        "X.SECENV", Severity.LOW, Verdict.UNKNOWN,
                        "安全环境检测异常: ${t.javaClass.simpleName}",
                    ),
                )
            }
        }
        safe { userCa() }
        safe { suspectCa() }
        safe { accessibility() }
        safe { overlay() }
        safe { jdwp() }
        safe { fridaPort() }
        safe { fridaThread() }
        safe { mapsFrida() }
        safe { container() }
        return out
    }

    // ------------------------------------------------------------ I.USER_CA
    private fun userCa(): Evidence {
        val dirs = listOf(
            "/data/misc/user/0/cacerts-added",
            "/data/misc/keychain/cacerts-added",
        ).map { File(it) }
        val files = mutableListOf<File>()
        var readable = false
        for (d in dirs) {
            val list = d.listFiles()
            if (list != null) {
                readable = true
                files.addAll(list)
            } else if (d.exists()) {
                // 目录在但读不了（通常意味着确实有内容、只是权限不够）
                return ev(
                    "I.USER_CA", Severity.MEDIUM, Verdict.UNKNOWN,
                    "用户证书目录存在但不可读",
                    Confidence.LOW,
                    vec = listOf(d.absolutePath),
                    detail = "应用没有读取 cacerts-added 的权限，无法确认内容",
                )
            }
        }
        if (files.isNotEmpty()) {
            return ev(
                "I.USER_CA", Severity.HIGH, Verdict.HIT,
                "存在用户安装的 CA 证书（${files.size} 个）",
                Confidence.HIGH,
                vec = files.take(8).map { it.name },
                detail = "用户 CA 能解密任意 App 的 HTTPS 流量，是抓包 / 中间人的前置条件",
                fp = "企业 MDM、私人代理、调试环境属正常用途",
            )
        }
        return ev(
            "I.USER_CA", Severity.INFO, if (readable) Verdict.PASS else Verdict.UNKNOWN,
            if (readable) "无用户安装的 CA 证书" else "无法枚举用户证书目录",
            Confidence.MEDIUM,
        )
    }

    // --------------------------------------------------------- I.CA_SUSPECT
    private fun suspectCa(): Evidence {
        val markers = listOf(
            "mitmproxy", "charles", "fiddler", "burp", "proxyman",
            "httptoolkit", "http toolkit", "portswigger",
        )
        val dir = File("/system/etc/security/cacerts")
        val list = dir.listFiles() ?: return ev(
            "I.CA_SUSPECT", Severity.LOW, Verdict.UNKNOWN,
            "系统信任库不可读", Confidence.LOW,
        )
        val hits = mutableListOf<String>()
        var scanned = 0
        for (f in list.take(120)) {
            try {
                val text = f.readText().lowercase()
                scanned++
                val m = markers.firstOrNull { text.contains(it) }
                if (m != null) hits.add("${f.name} → $m")
            } catch (_: Throwable) {
                // 单个证书读不动，跳过
            }
        }
        if (hits.isNotEmpty()) {
            return ev(
                "I.CA_SUSPECT", Severity.CRITICAL, Verdict.HIT,
                "系统信任库里出现抓包工具 CA",
                Confidence.HIGH,
                vec = hits.take(6),
                detail = "该 CA 由 MITM 工具生成并被装进系统信任链，HTTPS 可被透明解密",
            )
        }
        return ev(
            "I.CA_SUSPECT", Severity.INFO, Verdict.PASS,
            "系统信任库未见已知抓包 CA（已扫 $scanned 个）",
            Confidence.MEDIUM,
        )
    }

    // ------------------------------------------------------ I.ACCESSIBILITY
    private fun accessibility(): Evidence {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return ev(
                "I.ACCESSIBILITY", Severity.LOW, Verdict.UNKNOWN,
                "AccessibilityManager 不可用", Confidence.LOW,
            )
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val suspicious = enabled.filter { info ->
            val si = info.resolveInfo?.serviceInfo ?: return@filter false
            val ai = si.applicationInfo ?: return@filter false
            si.packageName != context.packageName &&
                (ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0
        }
        if (suspicious.isNotEmpty()) {
            return ev(
                "I.ACCESSIBILITY", Severity.HIGH, Verdict.HIT,
                "启用了第三方无障碍服务（${suspicious.size} 个）",
                Confidence.HIGH,
                vec = suspicious.map {
                    "${it.resolveInfo.serviceInfo.packageName}/${it.id}"
                }.take(8),
                detail = "无障碍服务能读取屏幕全部内容、模拟点击，是自动化外挂与远程控制的主要载体",
                fp = "输入法、按键映射、屏幕朗读类应用也会启用，需要结合包名判断",
            )
        }
        return ev(
            "I.ACCESSIBILITY", Severity.INFO, Verdict.PASS,
            "无第三方无障碍服务在运行",
            Confidence.MEDIUM,
        )
    }

    // ----------------------------------------------------------- I.OVERLAY
    private fun overlay(): Evidence {
        val pm = context.packageManager
        val pkgs = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        // 只统计**第三方**应用：系统应用几乎人手一个悬浮窗权限，
        // 不过滤的话会一次报出上百个包，纯噪声（实测 176 个）。
        val holders = pkgs.filter { p ->
            val ai = p.applicationInfo
            p.packageName != context.packageName &&
                p.requestedPermissions?.contains(Manifest.permission.SYSTEM_ALERT_WINDOW) == true &&
                ai != null && (ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0
        }
        // 注意：AppOpsManager.getPackagesForOps 是 @hide，普通应用无法枚举
        // “哪些包真的被授予了”悬浮窗，只能看到“声明了该权限”。
        // 因此这一项刻意保持 LOW + 启发式，不作为结论使用。
        if (holders.isNotEmpty()) {
            return ev(
                "I.OVERLAY", Severity.LOW,
                Verdict.HIT,
                "存在声明悬浮窗权限的应用（${holders.size} 个）",
                Confidence.LOW,
                vec = holders.map { it.packageName }.take(8),
                detail = "悬浮窗可以覆盖界面、伪造提示，也是部分注入框架的落地方式",
                fp = "仅声明不代表已授予；系统桌面、录屏、输入法普遍持有",
            )
        }
        return ev(
            "I.OVERLAY", Severity.INFO, Verdict.PASS,
            "未发现声明悬浮窗权限的第三方应用",
            Confidence.LOW,
        )
    }

    // -------------------------------------------------------------- I.JDWP
    private fun jdwp(): Evidence {
        val connected = Debug.isDebuggerConnected()
        val waiting = Debug.waitingForDebugger()
        val tracer = tracerPid()
        // 交叉验证：TracerPid 归零、但 Native 侧仍有 ptrace 是常见绕过手法
        val inconsistent = (tracer > 0) != connected
        val vec = mutableListOf(
            "isDebuggerConnected=$connected",
            "waitingForDebugger=$waiting",
            "TracerPid=$tracer",
        )
        if (connected || waiting || tracer > 0) {
            return ev(
                "I.JDWP", Severity.CRITICAL, Verdict.HIT,
                if (inconsistent) "调试状态交叉验证不一致" else "进程正在被调试",
                if (inconsistent) Confidence.HIGH else Confidence.MEDIUM,
                vec = vec,
                detail = if (inconsistent)
                    "TracerPid 与 Debug API 结论矛盾：常见于隐藏 ptrace 的 hook / 反调试绕过"
                else
                    "有调试器 attach，攻击者可以任意读写内存与调用栈",
                fp = "MIUI/HyperOS 的部分机型 TracerPid 会被系统监控进程占用",
            )
        }
        return ev(
            "I.JDWP", Severity.INFO, Verdict.PASS,
            "未检测到调试器",
            Confidence.MEDIUM,
            vec = vec,
        )
    }

    private fun tracerPid(): Int = try {
        val status = File("/proc/self/status").readText()
        val line = status.lineSequence().firstOrNull { it.startsWith("TracerPid:") }
        line?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
    } catch (_: Throwable) {
        0
    }

    // --------------------------------------------------------- E.FRIDA_PORT
    private fun fridaPort(): Evidence {
        val ports = intArrayOf(27042, 27043, 27044, 27960)
        val open = mutableListOf<Int>()
        for (p in ports) {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", p), 150)
                    open.add(p)
                }
            } catch (_: Throwable) {
                // 未监听
            }
        }
        if (open.isNotEmpty()) {
            return ev(
                "E.FRIDA_PORT", Severity.CRITICAL, Verdict.HIT,
                "frida-server 默认端口在监听：${open.joinToString()}",
                Confidence.HIGH,
                vec = open.map { "127.0.0.1:$it" },
                detail = "本机存在可被注入的调试服务端，任意进程都能被 attach",
                fp = "极少数调试环境会临时开放；端口也可被自定义",
            )
        }
        return ev(
            "E.FRIDA_PORT", Severity.INFO, Verdict.PASS,
            "frida-server 默认端口未监听",
            Confidence.MEDIUM,
        )
    }

    // ------------------------------------------------------- E.FRIDA_THREAD
    private fun fridaThread(): Evidence {
        val markers = listOf(
            "gum-js-loop", "gmain", "gdbus", "pool-frida",
            "frida", "linjector", "gum", "re.frida",
        )
        val hits = mutableListOf<String>()
        val taskDir = File("/proc/self/task")
        val tasks = taskDir.listFiles() ?: return ev(
            "E.FRIDA_THREAD", Severity.LOW, Verdict.UNKNOWN,
            "/proc/self/task 不可读", Confidence.LOW,
        )
        for (t in tasks) {
            try {
                val comm = File(t, "comm").readText().trim()
                if (comm.isEmpty()) continue
                val m = markers.firstOrNull { comm.lowercase().contains(it) }
                if (m != null) hits.add("${t.name}:$comm($m)")
            } catch (_: Throwable) {
                // 线程可能已退出
            }
        }
        if (hits.isNotEmpty()) {
            return ev(
                "E.FRIDA_THREAD", Severity.CRITICAL, Verdict.HIT,
                "发现 Frida 特征线程",
                Confidence.HIGH,
                vec = hits.take(8),
                detail = "gum-js-loop / pool-frida 是 Frida 运行时创建的固定线程名",
            )
        }
        return ev(
            "E.FRIDA_THREAD", Severity.INFO, Verdict.PASS,
            "线程名中无 Frida 特征（已扫 ${tasks.size} 个线程）",
            Confidence.MEDIUM,
        )
    }

    // --------------------------------------------------------- D.MAPS_FRIDA
    private fun mapsFrida(): Evidence {
        val markers = listOf(
            "frida-agent", "frida-gadget", "libfrida", "linjector",
            "gum-js-loop", "re.frida.server", "libgum",
        )
        val maps = try {
            File("/proc/self/maps").readLines()
        } catch (_: Throwable) {
            return ev(
                "D.MAPS_FRIDA", Severity.LOW, Verdict.UNKNOWN,
                "/proc/self/maps 不可读", Confidence.LOW,
            )
        }
        val hits = mutableListOf<String>()
        for (l in maps) {
            val lower = l.lowercase()
            val m = markers.firstOrNull { lower.contains(it) }
            if (m != null) hits.add("$m ← ${l.substringAfterLast(' ').take(90)}")
        }
        if (hits.isNotEmpty()) {
            return ev(
                "D.MAPS_FRIDA", Severity.CRITICAL, Verdict.HIT,
                "进程内映射了 Frida 组件",
                Confidence.HIGH,
                vec = hits.distinct().take(6),
                detail = "Gadget 以 so 形式被注入进程，maps 一定会留下映射记录",
            )
        }
        return ev(
            "D.MAPS_FRIDA", Severity.INFO, Verdict.PASS,
            "maps 中无 Frida 组件（共 ${maps.size} 段）",
            Confidence.MEDIUM,
        )
    }

    // ---------------------------------------------------------- X.CONTAINER
    private fun container(): Evidence {
        val markers = listOf(
            "io.virtualapp", "com.lody.virtual", "virtual.xposed",
            "me.weishu.exposed", "exposed.lite", "com.taichi",
            "dual.paradise", "multiple.accounts", "clone.app",
        )
        val hits = mutableListOf<String>()
        try {
            for (l in File("/proc/self/maps").readLines()) {
                val lower = l.lowercase()
                val m = markers.firstOrNull { lower.contains(it) }
                if (m != null) hits.add("maps:$m")
            }
        } catch (_: Throwable) {
            // ignore
        }
        // 宿主进程名通常会被改写成容器包名
        try {
            val cmd = File("/proc/self/cmdline").readText().trim('\u0000', ' ', '\n')
            val m = markers.firstOrNull { cmd.lowercase().contains(it) }
            if (m != null) hits.add("cmdline:$m")
        } catch (_: Throwable) {
            // ignore
        }
        if (hits.isNotEmpty()) {
            return ev(
                "X.CONTAINER", Severity.HIGH, Verdict.HIT,
                "运行在容器 / 多开环境中",
                Confidence.MEDIUM,
                vec = hits.distinct().take(6),
                detail = "容器环境会改写进程视图与 /proc，检测结果本身已不可信",
                fp = "部分厂商的「应用分身」也会走到类似路径",
            )
        }
        return ev(
            "X.CONTAINER", Severity.INFO, Verdict.PASS,
            "未发现容器 / 多开特征",
            Confidence.LOW,
        )
    }

    // ---------------------------------------------------------------- util
    private fun ev(
        id: String, sev: Severity, v: Verdict, title: String,
        conf: Confidence = Confidence.MEDIUM,
        vec: List<String> = emptyList(), detail: String = "", fp: String = "",
    ) = Evidence(
        id = id, category = catOf(id), severity = sev, confidence = conf,
        verdict = v, title = title, vectors = vec, detail = detail, fpNote = fp,
    )

    private fun catOf(id: String): Category = when {
        id.startsWith("E.") -> Category.HOOK
        id.startsWith("D.") -> Category.MEMORY
        id.startsWith("X.") -> Category.CROSSCHECK
        else -> Category.INTEGRITY
    }
}