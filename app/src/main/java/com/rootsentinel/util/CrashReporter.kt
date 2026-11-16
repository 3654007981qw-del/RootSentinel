package com.rootsentinel.util

import android.app.Application
import android.os.Build
import com.rootsentinel.RootSentinelVersion
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 崩溃日志自动上传（v2.22.64，用户要求加入）。
 *
 * ## 机制
 * [install] 装一个默认未捕获异常处理器：本应用任何线程崩溃时，
 * 先把崩溃信息（异常类型/消息/完整堆栈/线程名 + 版本与机型）
 * 同步写进应用私有目录的待发文件（只保留最近一份），再把异常
 * 交回系统原处理器——崩溃表现与以往完全一致，不吞异常。
 * 下次启动时，若声明已确认且「设置 → 隐私与数据 → 上传崩溃日志」
 * 开着，[uploadPending] 把这份文件经 [ReportCrypto] 加密封信后
 * 发到接收端的 /crash 路径；发送成功（HTTP 2xx）才删文件，
 * 失败保留、下次启动再试。
 *
 * ## 隐私口径
 * 只传本应用自己的崩溃堆栈——**不抓 logcat**、不带任何设备
 * 标识；堆栈内容是本应用与系统库的类名、方法名与行号。
 * 与诊断摘要共用同一 v2 加密信封协议与接收端密钥。
 */
object CrashReporter {

    /** 堆栈文本上限（与接收端 /crash 的 schema 上限 65536 对齐，留余量）。 */
    private const val MAX_STACK_CHARS = 48 * 1024
    private const val FILE_NAME = "pending-crash.json"
    private val uploadLock = Any()

    @Volatile
    private var installed = false

    /** 在 MainActivity.onCreate 尽早调用；重复调用无副作用。 */
    fun install(app: Application) {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { writePending(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun pendingFile(app: Application): File =
        File(File(app.filesDir, "crash").apply { mkdirs() }, FILE_NAME)

    private fun writePending(app: Application, thread: Thread, error: Throwable) {
        pendingFile(app).writeText(buildPayload(thread, error).toString())
    }

    /** 组装崩溃载荷。字段口径见类注释，与接收端 validCrash 对齐。 */
    fun buildPayload(thread: Thread, error: Throwable): JSONObject {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        var stack = sw.toString()
        if (stack.length > MAX_STACK_CHARS) stack = stack.substring(0, MAX_STACK_CHARS)
        return JSONObject().apply {
            put("kind", "crash")
            put("app", JSONObject().apply {
                put("version", RootSentinelVersion.NAME)
                put("code", RootSentinelVersion.CODE)
            })
            put("device", JSONObject().apply {
                put("brand", Build.BRAND ?: "")
                put("model", Build.MODEL ?: "")
                put("manufacturer", Build.MANUFACTURER ?: "")
                put("sdk", Build.VERSION.SDK_INT)
            })
            put("crash", JSONObject().apply {
                put("thread", thread.name)
                put("exception", error.javaClass.name)
                put("message", error.message ?: "")
                put("stack", stack)
                put("time", System.currentTimeMillis())
            })
            put("ts", System.currentTimeMillis())
        }
    }

    /**
     * 上传待发的崩溃文件。必须在 IO 线程调用。无待发文件直接返回；
     * 发送成功才删除，失败保留待下次启动重试。任何异常都吞掉——
     * 这是启动旁路，绝不许影响应用打开。
     */
    fun uploadPending(app: Application) {
        // v2.22.72: onCreate 提前上传与 RootApp 的 LaunchedEffect 可能并发，
        // 用锁串行化，避免同一份日志重复发送（删除只在 2xx 后发生）。
        synchronized(uploadLock) { uploadPendingLocked(app) }
    }

    private fun uploadPendingLocked(app: Application) {
        if (!Telemetry.configured) return
        runCatching {
            val f = pendingFile(app)
            if (!f.exists()) return
            val plain = f.readText()
            if (plain.isBlank()) {
                f.delete()
                return
            }
            val body = ReportCrypto.seal(plain).toByteArray(Charsets.UTF_8)
            val conn = URL(Telemetry.UPLOAD_ENDPOINT + "/crash").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 5_000
                conn.readTimeout = 5_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
                if (conn.responseCode in 200..299) f.delete()
            } finally {
                conn.disconnect()
            }
        }
    }
}
