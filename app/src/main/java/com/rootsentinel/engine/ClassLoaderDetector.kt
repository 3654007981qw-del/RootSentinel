package com.rootsentinel.engine

import android.content.Context
import com.rootsentinel.model.Category
import com.rootsentinel.model.Confidence
import com.rootsentinel.model.Evidence
import com.rootsentinel.model.Severity
import com.rootsentinel.model.Verdict
import java.lang.reflect.Modifier

/**
 * Xposed / LSPosed 的 Java 层检测（M3）。
 *
 * 关键点：Java 层看到的「类是否存在」「方法修饰符」与 native 看到的
 * 「maps 里有没有 dex」是两条完全独立的证据链。
 * 两边同时命中才能确认是注入，而不是应用自身引用了同名库。
 */
class ClassLoaderDetector(private val context: Context) {

    // 已知 hook 框架的类名
    private val xposedClasses = listOf(
        "de.robv.android.xposed.XposedBridge",
        "de.robv.android.xposed.XC_MethodHook",
        "de.robv.android.xposed.XposedHelpers",
        "de.robv.android.xposed.callbacks.XC_LoadPackage",
        "org.lsposed.lspd.core.Main",
        "org.lsposed.lspd.nativebridge.HookBridge",
        "io.github.lsposed.lspd.core.Main",
        "top.canyie.pine.Pine",
    )

    fun collect(): List<Evidence> {
        val out = mutableListOf<Evidence>()
        out += checkClassLoaderChain()
        out += checkXposedClasses()
        out += checkMethodModifiers()
        return out
    }

    // ---------------------------------------------------------------- ClassLoader 链
    private fun checkClassLoaderChain(): List<Evidence> {
        val chain = mutableListOf<String>()
        var cl: ClassLoader? = javaClass.classLoader
        var guard = 0
        while (cl != null && guard++ < 32) {
            chain.add(
                "${cl.javaClass.name}@" +
                    Integer.toHexString(System.identityHashCode(cl)),
            )
            cl = cl.parent
        }

        // 正常 App：BootClassLoader → PathClassLoader（2 层）
        // 加一层 DelegateLastClassLoader 之类的也算正常，超过 4 层要留意
        val abnormal = chain.size > 4

        return listOf(
            Evidence(
                id = "E.CLASSLOADER",
                category = Category.HOOK,
                severity = if (abnormal) Severity.HIGH else Severity.INFO,
                confidence = Confidence.MEDIUM,
                verdict = if (abnormal) Verdict.HIT else Verdict.PASS,
                title = if (abnormal) {
                    "ClassLoader 链异常长（${chain.size} 层）"
                } else {
                    "ClassLoader 链正常（${chain.size} 层）"
                },
                vectors = chain.mapIndexed { i, s -> "loader[$i]=$s" },
                fpNote = "热修复 / 插件化框架也会拉长 ClassLoader 链",
            ),
        )
    }

    // ---------------------------------------------------------------- Xposed 类
    private fun checkXposedClasses(): List<Evidence> {
        val found = mutableListOf<String>()
        for (name in xposedClasses) {
            try {
                Class.forName(name, false, javaClass.classLoader)
                found.add(name)
            } catch (_: Throwable) {
                // 未加载，正常
            }
        }

        return if (found.isNotEmpty()) {
            listOf(
                Evidence(
                    id = "E.XPOSED_CLASS",
                    category = Category.HOOK,
                    severity = Severity.HIGH,
                    confidence = Confidence.HIGH,
                    verdict = Verdict.HIT,
                    title = "加载了 Xposed/LSPosed 的类（${found.size} 个）",
                    vectors = found.map { "class=$it" },
                    fpNote = "少数检测库会自带同名类做对抗测试，需与 D.EXTRA_DEX 交叉确认",
                ),
            )
        } else {
            listOf(
                Evidence(
                    id = "E.XPOSED_CLASS",
                    category = Category.HOOK,
                    severity = Severity.INFO,
                    confidence = Confidence.HIGH,
                    verdict = Verdict.PASS,
                    title = "${xposedClasses.size} 个 Xposed 类均未加载",
                ),
            )
        }
    }

    // ---------------------------------------------------------------- 方法修饰符
    private fun checkMethodModifiers(): List<Evidence> {
        val out = mutableListOf<Evidence>()

        // hook 框架常把目标方法改为 native，从而接管调用
        val targets = listOf<Pair<Class<*>, String>>(
            java.io.File::class.java to "exists",
            java.lang.Runtime::class.java to "exec",
        )

        for ((cls, name) in targets) {
            val methods = try {
                cls.declaredMethods.filter { it.name == name }
            } catch (_: Throwable) {
                continue
            }
            for (m in methods) {
                if (Modifier.isNative(m.modifiers)) {
                    out += Evidence(
                        id = "E.METHOD_NATIVE",
                        category = Category.HOOK,
                        severity = Severity.CRITICAL,
                        confidence = Confidence.HIGH,
                        verdict = Verdict.HIT,
                        title = "关键方法被改为 native: ${cls.simpleName}.$name",
                        detail = "hook 框架常把方法声明改成 native 以便接管调用",
                        fpNote = "部分 ROM（如带性能优化的定制系统）会把个别方法下沉到 native",
                    )
                }
            }
        }

        if (out.isEmpty()) {
            out += Evidence(
                id = "E.METHOD_NATIVE",
                category = Category.HOOK,
                severity = Severity.INFO,
                confidence = Confidence.MEDIUM,
                verdict = Verdict.PASS,
                title = "File.exists / Runtime.exec 未被改为 native",
            )
        }
        return out
    }
}