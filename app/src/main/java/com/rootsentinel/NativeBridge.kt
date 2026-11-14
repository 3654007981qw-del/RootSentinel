package com.rootsentinel

/**
 * JNI 桥。所有检测与聚合都在 native 完成，这里只负责把 JSON 取回来。
 *
 * 刻意保持极小的接口面：只有 nativeScan() 一个方法，
 * 减少被 hook 定位的入口数量。
 */
object NativeBridge {

    init {
        System.loadLibrary("rootsentinel")
    }

    /**
     * 进度回调。native 会在这两个方法上同步调用，
     * 实现方必须自己切到 UI 线程。
     */
    interface ProgressCallback {
        fun onModule(name: String)
        fun onItem(
            id: String,
            title: String,
            severity: String,
            verdict: String,
            category: String,
        )
    }

    private external fun nativeScan(cb: ProgressCallback?): String

    /**
     * 签名基线比对（v2.4.0）。
     *
     * 期望的证书 SHA-256 编译进 .so（RSs 混淆），不在 dex 里，
     * 所以重打包者改了签名后必须同时改 .so 才能绕过。
     *
     * @return 1 = 与构建基线一致　0 = 不一致（可能被重打包）　-1 = 无法判断
     */
    private external fun sigCheck(sha256Hex: String): Int

    fun verifyOwnSigner(sha256Hex: String): Int = try {
        sigCheck(sha256Hex)
    } catch (t: Throwable) {
        -1
    }

    /**
     * 失败时返回一个带 error 字段的最小 JSON，
     * 让上层能区分「native 崩了」与「检测结果为空」。
     */
    fun scan(cb: ProgressCallback? = null): String = try {
        nativeScan(cb)
    } catch (t: Throwable) {
        """{"schema":"rootsentinel/1","error":"${t.javaClass.simpleName}"}"""
    }
}