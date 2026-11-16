package com.rootsentinel

import com.sukisu.nova.KernelSUApplication

/**
 * The app's Application. Extends the SukiSU seam application so the
 * vendored SukiSU-Nova UI sees its upstream `ksuApp`/`okhttpClient`
 * globals (assigned in the seam's onCreate).
 */
class RootSentinelApp : KernelSUApplication() {

    override fun onCreate() {
        super.onCreate()
        // 这里可以预热 native 库，但不做任何会暴露行为的初始化
    }
}
