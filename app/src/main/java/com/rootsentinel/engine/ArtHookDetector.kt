package com.rootsentinel.engine

import android.content.Context
import com.rootsentinel.model.Category
import com.rootsentinel.model.Confidence
import com.rootsentinel.model.Evidence
import com.rootsentinel.model.Severity
import com.rootsentinel.model.Verdict
import java.lang.reflect.Modifier

/**
 * ART 层 hook 检测（M9）— 4 项
 *
 * 这一层看的是「Java 反射面」是否被改：
 *   Method 修饰符    被 hook 的方法常被标成 synthetic
 *   hidden API       黑名单 API 是否突然可用
 *   StackTrace       两个来源的栈深度不一致 = 有帧被吞
 *   BootClassLoader  java.lang.Object 必须来自 boot classloader
 */
class ArtHookDetector(private val context: Context) {

    fun collect(): List<Evidence> = listOf(
        methodHook(), hiddenApi(), stackFrame(), classVerify(),
    )

    // --------------------------------------------------------- E.METHOD_HOOK
    // Kotlin 编译器会为带默认参数的方法生成 `xxx$default` 桥方法，
    // 这是完全正常的。hook 插桩的特征是**数量异常多**。
    private fun methodHook(): Evidence = try {
        val cls = this::class.java
        val suspicious = mutableListOf<String>()

        for (m in cls.declaredMethods) {
            if (!(m.isSynthetic || m.isBridge)) continue
            // Kotlin 默认参数桥
            if (m.name.endsWith("\$default")) continue
            // Kotlin 的 access$ / <clinit> 之类
            if (m.name.startsWith("access\$") || m.name == "<clinit>") continue
            suspicious.add(m.name)
        }

        if (suspicious.size <= 10) {
            ev("E.METHOD_HOOK", Severity.INFO, Verdict.PASS, "方法修饰符正常",
                Confidence.HIGH,
                vec = listOf("methods=${cls.declaredMethods.size}",
                             "synthetic_odd=${suspicious.size}"))
        } else {
            ev("E.METHOD_HOOK", Severity.MEDIUM, Verdict.HIT,
                "synthetic 方法数量异常多",
                Confidence.LOW, vec = suspicious.take(5).map { "method=$it" },
                detail = "大量 synthetic 方法可能是 hook 框架插桩",
                fp = "Kotlin 协程 / 泛型 / 内联函数都会生成 synthetic")
        }
    } catch (t: Throwable) {
        ev("E.METHOD_HOOK", Severity.LOW, Verdict.UNKNOWN, "方法信息不可读")
    }

    // ----------------------------------------------------------- E.REFLECTION
    // Android 9+ 对 @hide API 有黑/灰名单。
    // 若一个黑名单 API 突然能调通，说明 hidden API 限制被解除。
    private fun hiddenApi(): Evidence = try {
        val cls = Class.forName("android.app.ActivityThread")
        val m = cls.getDeclaredMethod("currentActivityThread")
        m.isAccessible = true
        val r = m.invoke(null)
        if (r != null) {
            ev("E.REFLECTION", Severity.MEDIUM, Verdict.HIT,
                "hidden API 可正常调用",
                Confidence.LOW,
                vec = listOf("api=ActivityThread.currentActivityThread"),
                detail = "Android 9+ 应对多数 @hide API 有限制；"
                    + "全部可用说明限制被解除",
                fp = "灰名单 API 在部分版本仍可调用，需逐个确认")
        } else {
            ev("E.REFLECTION", Severity.LOW, Verdict.UNKNOWN, "hidden API 返回 null")
        }
    } catch (t: NoSuchMethodException) {
        ev("E.REFLECTION", Severity.LOW, Verdict.UNKNOWN, "方法不存在")
    } catch (t: Throwable) {
        ev("E.REFLECTION", Severity.INFO, Verdict.PASS,
            "hidden API 被限制（正常）", Confidence.MEDIUM,
            detail = t.javaClass.simpleName)
    }

    // -------------------------------------------------------- E.STACK_FRAME
    // Thread.getStackTrace() 与 Throwable().stackTrace 应给出相同深度的栈。
    // 若差异很大，说明某一侧被 hook 吞了帧。
    private fun stackFrame(): Evidence = try {
        val a = Thread.currentThread().stackTrace.size
        val b = Throwable().stackTrace.size
        val diff = if (a > b) a - b else b - a

        if (diff <= 5) {
            ev("E.STACK_FRAME", Severity.INFO, Verdict.PASS, "两处栈深度一致",
                Confidence.HIGH, vec = listOf("thread=$a", "throwable=$b"))
        } else {
            ev("E.STACK_FRAME", Severity.MEDIUM, Verdict.HIT,
                "两处栈深度差异较大",
                Confidence.LOW, vec = listOf("thread=$a", "throwable=$b", "diff=$diff"),
                detail = "Xposed / LSPosed 会往调用栈插帧，两侧可能不一致",
                fp = "两次采样之间有新调用会带来天然差异")
        }
    } catch (t: Throwable) {
        ev("E.STACK_FRAME", Severity.LOW, Verdict.UNKNOWN, "栈信息不可读")
    }

    // --------------------------------------------------------- E.CLASS_VERIFY
    // 注意：Class.getClassLoader() 对 boot class 返回的是 BootClassLoader
    // **实例**（不是文档里说的 null）。所以两种都要接受。
    private fun classVerify(): Evidence = try {
        fun isBoot(cl: ClassLoader?): Boolean =
            cl == null || cl.javaClass.name.contains("BootClassLoader")

        val objCl = java.lang.Object::class.java.classLoader
        val strCl = java.lang.String::class.java.classLoader
        val intCl = java.lang.Integer::class.java.classLoader

        val bad = mutableListOf<String>()
        if (!isBoot(objCl)) bad.add("Object=${objCl?.javaClass?.name}")
        if (!isBoot(strCl)) bad.add("String=${strCl?.javaClass?.name}")
        if (!isBoot(intCl)) bad.add("Integer=${intCl?.javaClass?.name}")

        if (bad.isEmpty()) {
            ev("E.CLASS_VERIFY", Severity.INFO, Verdict.PASS,
                "核心类均由 boot classloader 加载", Confidence.HIGH,
                vec = listOf("Object=${objCl?.javaClass?.simpleName ?: "null"}"))
        } else {
            ev("E.CLASS_VERIFY", Severity.CRITICAL, Verdict.HIT,
                "核心类的 ClassLoader 异常", Confidence.HIGH,
                vec = bad,
                detail = "java.lang.* 必须由 boot classloader 加载；"
                    + "否则类被替换过")
        }
    } catch (t: Throwable) {
        ev("E.CLASS_VERIFY", Severity.LOW, Verdict.UNKNOWN, "ClassLoader 不可读")
    }

    private fun ev(
        id: String, sev: Severity, v: Verdict, title: String,
        conf: Confidence = Confidence.MEDIUM,
        vec: List<String> = emptyList(), detail: String = "", fp: String = "",
    ) = Evidence(
        id = id, category = Category.HOOK, severity = sev, confidence = conf,
        verdict = v, title = title, vectors = vec, detail = detail, fpNote = fp,
    )
}