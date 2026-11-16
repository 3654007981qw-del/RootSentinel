package com.sukisu.nova

import android.app.Activity
import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Bundle
import okhttp3.OkHttpClient

/**
 * RootSentinel seams for SukiSU-Nova's application globals: upstream,
 * `ksuApp` is the KernelSUApplication instance (the app's Application class)
 * and `okhttpClient` its lateinit shared client. RootSentinel's
 * RootSentinelApp extends this seam class (see AndroidManifest
 * android:name) so the vendored code sees the exact upstream shapes;
 * onCreate assigns ksuApp and builds the shared client. The
 * started-activity counter + isAppInForeground mirror upstream (its
 * on-stop launcher-icon restore is omitted: RootSentinel's launcher icon
 * is fixed). The predictive-back reflection helper is upstream verbatim.
 */
lateinit var ksuApp: KernelSUApplication

open class KernelSUApplication : Application() {

    lateinit var okhttpClient: OkHttpClient

    @Volatile
    private var startedActivities = 0

    /** True while a manager activity is visible. */
    val isAppInForeground: Boolean
        get() = startedActivities > 0

    override fun onCreate() {
        super.onCreate()
        ksuApp = this
        okhttpClient = OkHttpClient()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities--
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    companion object {
        fun setEnableOnBackInvokedCallback(appInfo: ApplicationInfo, enable: Boolean) {
            runCatching {
                val applicationInfoClass = ApplicationInfo::class.java
                val method = applicationInfoClass.getDeclaredMethod(
                    "setEnableOnBackInvokedCallback",
                    Boolean::class.javaPrimitiveType
                )
                method.isAccessible = true
                method.invoke(appInfo, enable)
            }
        }
    }
}
