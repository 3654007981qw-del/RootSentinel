package com.rootsentinel

import android.app.Application

class RootSentinelApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 这里可以预热 native 库，但不做任何会暴露行为的初始化
    }
}