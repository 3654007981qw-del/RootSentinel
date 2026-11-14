package com.rootsentinel.engine

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import com.rootsentinel.NativeBridge

/**
 * 独立进程服务（:probe）—— M10 真正实现。
 *
 * 为什么需要它：
 *   Zygisk / Riru 类注入通常只 hook「目标进程」。如果检测器只有一个进程，
 *   攻击者只要 hook 掉那个进程的 openat / getdents 就够了。
 *
 *   这里在 **另一个进程** 里跑同样的 native 扫描：
 *     · 攻击者没 hook :probe  → 两边结果一致 → 无异常
 *     · 攻击者 hook 了主进程   → 两边结果不一致 → 直接暴露
 *
 * 注意：:probe 进程同样受 SELinux 约束，能读到的东西与主进程一样。
 * 但它有**独立的 maps / fd / 线程表 / uid 视图**，
 * 所以「摘链」式的隐藏（只摘主进程的 maps）会在对比中露馅。
 */
class ProbeService : Service() {

    private var messenger: Messenger? = null

    /** native 扫描放到专用线程，避免阻塞 Service 主线程 */
    private var worker: android.os.HandlerThread? = null

    override fun onCreate() {
        super.onCreate()
        val t = android.os.HandlerThread("probe-worker").apply { start() }
        worker = t
        // v2.4.0：之前 IncomingHandler 挂在主线程 Looper 上，
        // NativeBridge.scan() 要跑 2~4 秒，期间 Service 无法响应任何消息，
        // 极端情况下会 ANR。现在消息在 HandlerThread 上处理。
        messenger = Messenger(IncomingHandler(t.looper))
    }

    override fun onDestroy() {
        worker?.quitSafely()
        worker = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = messenger?.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    private inner class IncomingHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_PROBE -> {
                    val json = try {
                        NativeBridge.scan(null)
                    } catch (t: Throwable) {
                        ""
                    }
                    val reply = Message.obtain(null, MSG_RESULT)
                    reply.data = Bundle().apply {
                        putString(KEY_JSON, json)
                        putInt(KEY_PID, Process.myPid())
                    }
                    runCatching { msg.replyTo?.send(reply) }
                }
                else -> super.handleMessage(msg)
            }
        }
    }

    companion object {
        const val MSG_PROBE = 1
        const val MSG_RESULT = 2
        const val KEY_JSON = "json"
        const val KEY_PID = "pid"
    }
}