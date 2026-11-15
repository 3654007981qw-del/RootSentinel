package com.rootsentinel.engine

import android.content.Context
import com.rootsentinel.model.Category
import com.rootsentinel.model.Confidence
import com.rootsentinel.model.Evidence
import com.rootsentinel.model.Severity
import com.rootsentinel.model.Verdict
import dalvik.system.BaseDexClassLoader
import dalvik.system.InMemoryDexClassLoader

/**
 * ART / Java 层深度检测（M6）
 *
 * native 侧只能看到 mmap、符号表；这一层看的是**运行时对象图**：
 *   · 调用栈里有没有外部帧
 *   · ClassLoader 链是不是原生的两段式
 *   · dexElements 数量（InMemoryDexClassLoader 注入必然多加一个）
 *   · 链上有没有 InMemoryDexClassLoader
 *
 * 这几项能盖住「libc 没被改、但 Java 层被 LSPosed 类框架插桩」的情况。
 */
class ArtDeepDetector(private val context: Context) {

    fun collect(): List<Evidence> = listOf(
        javaStack(),
        classLoaderChain(),
        dexElements(),
        inMemoryLoader(),
    )

    // ------------------------------------------------------------ E.JAVA_STACK
    private fun javaStack(): Evidence {
        val frames = Thread.currentThread().stackTrace
        // v2.22.53（第八轮审查 P2-02 配套）：自家包前缀不能写死
        // "com.rootsentinel."——release 走 R8 后类名/包名会被改写，
        // 写死的前缀匹配不上自家帧，会把自己的调用栈误报成外部
        // 帧（假 HIT）。改为运行时按本类实际全名前两段推导：R8
        // 的包名映射是逐段一致的，未混淆时推导结果恰为
        // "com.rootsentinel."，行为与从前一致。
        val ownRoot = ArtDeepDetector::class.java.name
            .split('.').take(2).joinToString(".") + "."
        val allowed = listOf(
            "java.", "javax.", "android.", "androidx.", "kotlin.", "kotlinx.",
            "com.android.", "dalvik.", "sun.", "libcore.", "org.apache.",
            "org.json.", "org.xmlpull.", ownRoot,
        )

        val bad = frames.mapNotNull { f ->
            val n = f.className
            when {
                n.isEmpty() -> null
                allowed.any { n.startsWith(it) } -> null
                else -> n
            }
        }.distinct()

        return if (bad.isEmpty()) {
            Evidence(
                id = "E.JAVA_STACK",
                category = Category.HOOK,
                severity = Severity.INFO,
                confidence = Confidence.HIGH,
                verdict = Verdict.PASS,
                title = "Java 调用栈无外部帧",
                vectors = listOf("frames=${frames.size}"),
            )
        } else {
            Evidence(
                id = "E.JAVA_STACK",
                category = Category.HOOK,
                severity = Severity.HIGH,
                confidence = Confidence.MEDIUM,
                verdict = Verdict.HIT,
                title = "Java 调用栈出现外部类",
                vectors = bad.take(5).map { "frame=$it" },
                detail = "Xposed / LSPosed 会在调用栈里插入自身帧",
                fpNote = "厂商 SDK（HMS / 推送 / 加固）也可能出现在栈里，需人工确认",
            )
        }
    }

    // ------------------------------------------------------ E.CLASSLOADER_CHAIN
    private fun classLoaderChain(): Evidence {
        val chain = mutableListOf<ClassLoader>()
        var cl: ClassLoader? = javaClass.classLoader
        var depth = 0
        while (cl != null && depth < 12) {
            chain.add(cl)
            cl = cl.parent
            depth++
        }

        val legit = listOf(
            "dalvik.system.PathClassLoader",
            "dalvik.system.DelegateLastClassLoader",
            "dalvik.system.InMemoryDexClassLoader",
            "java.lang.BootClassLoader",
        )

        val bad = chain.filter { c ->
            legit.none { c.javaClass.name.startsWith(it) }
        }.map { it.javaClass.name + "@" + Integer.toHexString(System.identityHashCode(it)) }

        return if (bad.isEmpty()) {
            Evidence(
                id = "E.CLASSLOADER_CHAIN",
                category = Category.HOOK,
                severity = Severity.INFO,
                confidence = Confidence.HIGH,
                verdict = Verdict.PASS,
                title = "ClassLoader 链为标准结构",
                vectors = listOf("depth=${chain.size}"),
            )
        } else {
            Evidence(
                id = "E.CLASSLOADER_CHAIN",
                category = Category.HOOK,
                severity = Severity.MEDIUM,
                confidence = Confidence.MEDIUM,
                verdict = Verdict.HIT,
                title = "ClassLoader 链含非标准实现",
                vectors = bad.take(5).map { "loader=$it" },
                detail = "注入框架常用自定义 ClassLoader 承载 payload",
                fpNote = "部分加固 / 热修复方案也使用自定义 ClassLoader",
            )
        }
    }

    // -------------------------------------------------------- E.DEX_ELEMENTS
    // 这是 Java 层最硬的一条：
    // 正常 App 的 BaseDexClassLoader 里 dexElements 只有 1 个（自己的 classes.dex）。
    // InMemoryDexClassLoader / DexClassLoader 注入必然多加一个或多个。
    private fun dexElements(): Evidence {
        val cl = javaClass.classLoader
        if (cl !is BaseDexClassLoader) {
            return Evidence(
                id = "E.DEX_ELEMENTS",
                category = Category.HOOK,
                severity = Severity.LOW,
                confidence = Confidence.LOW,
                verdict = Verdict.UNKNOWN,
                title = "ClassLoader 不是 BaseDexClassLoader",
            )
        }

        return try {
            // Android 16 上 BaseDexClassLoader 的字段名已不是 pathList，
            // 需要按候选名 + 遍历父类查找。
            val pathListField = findField(cl, listOf("pathList", "mPathList"))
                ?: throw NoSuchFieldException("pathList / mPathList 均不存在")
            val pathList = pathListField.get(cl)
                ?: throw IllegalStateException("pathList = null")

            val elementsField = findField(
                pathList,
                listOf("dexElements", "mDexElements", "elements"),
            ) ?: throw NoSuchFieldException("dexElements 不存在")

            val elements = elementsField.get(pathList) as? Array<*>
                ?: throw IllegalStateException("dexElements 不是数组")

            val paths = elements.mapNotNull { el ->
                try {
                    val f = findField(el!!, listOf("path", "mPath", "dexFile", "mDexFile"))
                    f?.get(el)?.toString()
                } catch (t: Throwable) {
                    null
                }
            }

            if (elements.size <= 1) {
                Evidence(
                    id = "E.DEX_ELEMENTS",
                    category = Category.HOOK,
                    severity = Severity.INFO,
                    confidence = Confidence.HIGH,
                    verdict = Verdict.PASS,
                    title = "dexElements 数量正常",
                    vectors = listOf("count=${elements.size}") + paths.take(2).map { "path=$it" },
                )
            } else {
                Evidence(
                    id = "E.DEX_ELEMENTS",
                    category = Category.HOOK,
                    severity = Severity.CRITICAL,
                    confidence = Confidence.HIGH,
                    verdict = Verdict.HIT,
                    title = "dexElements 数量异常（${elements.size}）",
                    vectors = listOf("count=${elements.size}") + paths.map { "path=$it" },
                    detail = "多出的 dex element 说明有外部代码被插入类加载路径",
                    fpNote = "multidex 的 App 天然有多个 element，本 App 只有一个 dex",
                )
            }
        } catch (t: Throwable) {
            Evidence(
                id = "E.DEX_ELEMENTS",
                category = Category.HOOK,
                severity = Severity.LOW,
                confidence = Confidence.LOW,
                verdict = Verdict.UNKNOWN,
                title = "dexElements 反射失败",
                detail = "${t.javaClass.simpleName}: ${t.message}",
            )
        }
    }

    /** 按候选名查找字段，沿父类链向上找 */
    private fun findField(obj: Any, names: List<String>): java.lang.reflect.Field? {
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java) {
            for (n in names) {
                try {
                    val f = c.getDeclaredField(n)
                    f.isAccessible = true
                    return f
                } catch (t: Throwable) {
                    // 继续下一个候选名
                }
            }
            c = c.superclass
        }
        return null
    }

    // ------------------------------------------------------- E.INMEMORY_LOADER
    private fun inMemoryLoader(): Evidence {
        val chain = mutableListOf<ClassLoader>()
        var cl: ClassLoader? = javaClass.classLoader
        var d = 0
        while (cl != null && d < 12) {
            chain.add(cl)
            cl = cl.parent
            d++
        }

        val mem = chain.filterIsInstance<InMemoryDexClassLoader>()

        return if (mem.isEmpty()) {
            Evidence(
                id = "E.INMEMORY_LOADER",
                category = Category.HOOK,
                severity = Severity.INFO,
                confidence = Confidence.HIGH,
                verdict = Verdict.PASS,
                title = "ClassLoader 链无内存 dex 加载器",
            )
        } else {
            Evidence(
                id = "E.INMEMORY_LOADER",
                category = Category.HOOK,
                severity = Severity.CRITICAL,
                confidence = Confidence.HIGH,
                verdict = Verdict.HIT,
                title = "ClassLoader 链存在 InMemoryDexClassLoader",
                vectors = mem.map { "loader=${it.javaClass.name}" },
                detail = "内存 dex 加载是 LSPosed / 内存马的标准手法",
            )
        }
    }
}