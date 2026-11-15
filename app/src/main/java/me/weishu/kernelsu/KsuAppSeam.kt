package me.weishu.kernelsu

import android.app.Application
import okhttp3.OkHttpClient

/**
 * RootSentinel seam: KernelSU code reaches its Application via the global
 * `ksuApp`. RootSentinel's MainActivity installs its application here before
 * composition so the copied components keep working unchanged.
 */
lateinit var ksuApp: Application

/**
 * RootSentinel seam: KernelSU's Application subclass carries an
 * `okhttpClient` member used by the markdown/WebView components.
 * RootSentinel provides the same surface as an extension with a shared
 * default client (registry/markdown sub-resource loading only).
 */
val Application.okhttpClient: OkHttpClient
    get() = SharedOkHttpClient.instance

object SharedOkHttpClient {
    val instance: OkHttpClient by lazy { OkHttpClient() }
}
