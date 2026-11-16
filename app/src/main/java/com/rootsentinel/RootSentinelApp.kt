package com.rootsentinel

import com.sukisu.nova.KernelSUApplication

/**
 * The app's Application. Extends the SukiSU seam application so the
 * vendored SukiSU-Nova UI sees its upstream `ksuApp`/`okhttpClient`
 * globals (assigned in the seam's onCreate).
 */
class RootSentinelApp : KernelSUApplication() {

    override fun onCreate() {
        // v2.22.73: crash capture + pending upload move to the earliest
        // possible point — before super.onCreate() and before any
        // Activity exists. The .71/.72 lesson: a crash in the first
        // composition of a persisted alternate shell killed the process
        // before any Activity-level upload thread could finish its POST,
        // so the log never left the phone.
        com.rootsentinel.util.CrashReporter.install(this)
        Thread {
            val sp = getSharedPreferences("rs_settings", MODE_PRIVATE)
            if (sp.getBoolean("declarationSeen", false) &&
                sp.getBoolean("crashUploadEnabled", true)
            ) {
                com.rootsentinel.util.CrashReporter.uploadPending(this)
            }
        }.start()
        super.onCreate()
        // 这里可以预热 native 库，但不做任何会暴露行为的初始化
    }
}
