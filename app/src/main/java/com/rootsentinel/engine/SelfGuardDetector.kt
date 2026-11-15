package com.rootsentinel.engine

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Debug
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.rootsentinel.model.Category
import com.rootsentinel.model.Confidence
import com.rootsentinel.model.Evidence
import com.rootsentinel.model.Severity
import com.rootsentinel.model.Verdict

/**
 * 检测器自加固（M6）
 *
 * 这一层看的是「检测过程本身有没有被动过」：
 *   · 调试器是否已附加（Frida / jdwp / LLDB）
 *   · 无障碍服务是否被接管（可以读取屏幕、模拟点击）
 *   · 设备管理器是否被注册（可以锁屏、抹机）
 *   · 输入法是否被替换（键记录）
 */
class SelfGuardDetector(private val context: Context) {

    fun collect(): List<Evidence> = listOf(
        debugger(),
        accessibility(),
        inputMethod(),
        deviceAdmin(),
    )

    // --------------------------------------------------------------- I.DEBUGGER
    private fun debugger(): Evidence {
        // v2.22.50（第六轮审查 #9）：异常不再吞成 false。两个 API
        // 任一抛异常 = 状态不可读，必须落 UNKNOWN —— 此前异常会
        // 一路走到 PASS「无调试器附加」，违反「无法检测 ≠ 通过」。
        val connected = try {
            Debug.isDebuggerConnected()
        } catch (t: Throwable) {
            null
        }
        val waiting = try {
            Debug.waitingForDebugger()
        } catch (t: Throwable) {
            null
        }
        // v2.22.51（第七轮审查 #2）：真值表优先于异常处理——任一 API
        // 明确报 true 就是确定性命中，不许被另一个 API 的异常盖成
        // UNKNOWN（.50 的改法矫枉过正）。只有双 false 才 PASS；
        // 剩下的组合（无命中但有一侧读不到）才落 UNKNOWN。
        if (connected == true || waiting == true) {
            return Evidence(
                id = "I.DEBUGGER",
                category = Category.INTEGRITY,
                severity = Severity.CRITICAL,
                confidence = Confidence.HIGH,
                verdict = Verdict.HIT,
                title = "检测到调试器",
                vectors = listOf(
                    "connected=$connected",
                    "waiting=$waiting",
                ),
                detail = "调试器可绕过几乎所有 Java 层检测",
            )
        }
        if (connected == null || waiting == null) {
            return Evidence(
                id = "I.DEBUGGER",
                category = Category.INTEGRITY,
                severity = Severity.LOW,
                confidence = Confidence.LOW,
                verdict = Verdict.UNKNOWN,
                title = "调试器状态不可读",
                detail = "Debug API 调用异常，无法确认是否有调试器附加",
            )
        }

        return if (!connected && !waiting) {
            Evidence(
                id = "I.DEBUGGER",
                category = Category.INTEGRITY,
                severity = Severity.INFO,
                confidence = Confidence.HIGH,
                verdict = Verdict.PASS,
                title = "无调试器附加",
            )
        } else {
            Evidence(
                id = "I.DEBUGGER",
                category = Category.INTEGRITY,
                severity = Severity.CRITICAL,
                confidence = Confidence.HIGH,
                verdict = Verdict.HIT,
                title = "检测到调试器",
                vectors = listOf(
                    "connected=$connected",
                    "waiting=$waiting",
                ),
                detail = "调试器可绕过几乎所有 Java 层检测",
            )
        }
    }

    // ---------------------------------------------------------- I.ACCESSIBILITY
    private fun accessibility(): Evidence {
        val services = try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
                as? AccessibilityManager
            am?.getEnabledAccessibilityServiceList(-1)?.mapNotNull { it.id }
                ?: emptyList()
        } catch (t: Throwable) {
            return Evidence(
                id = "I.ACCESSIBILITY",
                category = Category.INTEGRITY,
                severity = Severity.LOW,
                confidence = Confidence.LOW,
                verdict = Verdict.UNKNOWN,
                title = "无障碍服务列表不可读",
                detail = "${t.javaClass.simpleName}: ${t.message}",
            )
        }

        if (services.isEmpty()) {
            return Evidence(
                id = "I.ACCESSIBILITY",
                category = Category.INTEGRITY,
                severity = Severity.INFO,
                confidence = Confidence.HIGH,
                verdict = Verdict.PASS,
                title = "无启用的无障碍服务",
            )
        }

        val raw = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: ""
        } catch (t: Throwable) {
            ""
        }

        return Evidence(
            id = "I.ACCESSIBILITY",
            category = Category.INTEGRITY,
            severity = Severity.MEDIUM,
            confidence = Confidence.LOW,
            verdict = Verdict.HIT,
            title = "存在启用的无障碍服务（${services.size}）",
            vectors = services.take(4).map { "service=$it" } +
                if (raw.isNotEmpty()) listOf("raw=${raw.take(80)}") else emptyList(),
            detail = "无障碍服务可以读屏、模拟点击，常被用作自动化 / 木马通道",
            fpNote = "用户可能主动开启（如输入法、自动点击、屏幕朗读），需人工判断",
        )
    }

    // --------------------------------------------------------- I.INPUT_METHOD
    private fun inputMethod(): Evidence {
        val list = try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
            imm?.enabledInputMethodList?.mapNotNull { it.packageName }
                ?: emptyList()
        } catch (t: Throwable) {
            return Evidence(
                id = "I.INPUT_METHOD",
                category = Category.INTEGRITY,
                severity = Severity.LOW,
                confidence = Confidence.LOW,
                verdict = Verdict.UNKNOWN,
                title = "输入法列表不可读",
            )
        }

        if (list.isEmpty()) {
            return Evidence(
                id = "I.INPUT_METHOD",
                category = Category.INTEGRITY,
                severity = Severity.INFO,
                confidence = Confidence.MEDIUM,
                verdict = Verdict.PASS,
                title = "无启用的第三方输入法",
            )
        }

        // 系统 / 大厂输入法视为正常
        val known = listOf(
            "com.android.", "com.google.", "com.sohu.", "com.baidu.",
            "com.iflytek.", "com.tencent.", "com.oppo.", "com.vivo.",
            "com.miui.", "com.huawei.", "com.samsung.", "com.sec.",
        )
        val odd = list.filter { p -> known.none { p.startsWith(it) } }

        return if (odd.isEmpty()) {
            Evidence(
                id = "I.INPUT_METHOD",
                category = Category.INTEGRITY,
                severity = Severity.INFO,
                confidence = Confidence.MEDIUM,
                verdict = Verdict.PASS,
                title = "输入法均为已知厂商",
                vectors = list.take(4).map { "ime=$it" },
            )
        } else {
            Evidence(
                id = "I.INPUT_METHOD",
                category = Category.INTEGRITY,
                severity = Severity.LOW,
                confidence = Confidence.LOW,
                verdict = Verdict.HIT,
                title = "存在未知来源输入法",
                vectors = odd.take(4).map { "ime=$it" },
                detail = "第三方输入法可以记录所有按键",
                fpNote = "第三方输入法本身合法，需结合用户使用习惯判断",
            )
        }
    }

    // ----------------------------------------------------------- I.DEVICE_ADMIN
    private fun deviceAdmin(): Evidence {
        val admins = try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                as? DevicePolicyManager
            dpm?.activeAdmins?.mapNotNull { it.packageName } ?: emptyList()
        } catch (t: Throwable) {
            return Evidence(
                id = "I.DEVICE_ADMIN",
                category = Category.INTEGRITY,
                severity = Severity.LOW,
                confidence = Confidence.LOW,
                verdict = Verdict.UNKNOWN,
                title = "设备管理器列表不可读",
            )
        }

        return if (admins.isEmpty()) {
            Evidence(
                id = "I.DEVICE_ADMIN",
                category = Category.INTEGRITY,
                severity = Severity.INFO,
                confidence = Confidence.HIGH,
                verdict = Verdict.PASS,
                title = "无激活的设备管理器",
            )
        } else {
            Evidence(
                id = "I.DEVICE_ADMIN",
                category = Category.INTEGRITY,
                severity = Severity.LOW,
                confidence = Confidence.LOW,
                verdict = Verdict.HIT,
                title = "存在激活的设备管理器（${admins.size}）",
                vectors = admins.take(4).map { "admin=$it" },
                detail = "设备管理器可执行锁屏、清除数据等高权限操作",
                fpNote = "企业设备 / MDM 场景下属正常",
            )
        }
    }
}