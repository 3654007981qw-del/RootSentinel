package com.sukisu.nova

import com.rootsentinel.RootSentinelVersion

/**
 * RootSentinel seam: SukiSU-Nova's code reads its AGP-generated BuildConfig.
 * Delegates to RootSentinel's own version constants (same pattern as the
 * KernelSU tree's BuildConfig seam).
 */
object BuildConfig {
    const val APPLICATION_ID: String = "com.rootsentinel"
    const val VERSION_CODE: Int = RootSentinelVersion.CODE
    const val VERSION_NAME: String = RootSentinelVersion.NAME
    const val DEBUG: Boolean = false
}
