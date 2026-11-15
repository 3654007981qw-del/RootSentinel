package com.rootsentinel

/**
 * 版本常量（v2.5.0）
 *
 * 与 app/build.gradle.kts 里的 versionName / versionCode 保持一致。
 * 之所以单独放一个常量，是因为「关于」页面要显示它，
 * 而 Compose 里拿 BuildConfig 需要 buildFeatures.buildConfig = true。
 */
object RootSentinelVersion {
    const val NAME = "2.22.46"
    const val CODE = 22246
}