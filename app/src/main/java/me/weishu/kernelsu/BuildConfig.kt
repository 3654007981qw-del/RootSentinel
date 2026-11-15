package me.weishu.kernelsu

/**
 * RootSentinel seam: copied KernelSU components reference
 * me.weishu.kernelsu.BuildConfig; the values are RootSentinel's own build
 * facts (Version is the pipeline-generated single source of truth).
 */
object BuildConfig {
    const val VERSION_NAME: String = com.rootsentinel.RootSentinelVersion.NAME
    const val VERSION_CODE: Int = com.rootsentinel.RootSentinelVersion.CODE
    const val APPLICATION_ID: String = "com.rootsentinel"
    val DEBUG: Boolean get() = com.rootsentinel.BuildConfig.DEBUG
}
