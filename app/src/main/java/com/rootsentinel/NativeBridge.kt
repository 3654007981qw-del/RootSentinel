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
     * 签名基线比对（v2.4.0；v2.23.0 起双通道）。
     *
     * 期望的证书 SHA-256 编译进 .so（RSs 混淆），不在 dex 里，
     * 所以重打包者改了签名后必须同时改 .so 才能绕过。
     *
     * v2.23.0：native 侧返回两个独立通道的比对结果——
     * Java 通道（调用方经 PackageManager 得到的证书摘要）与
     * native 通道（.so 直接解析 APK Signing Block 的证书摘要），
     * 数据源独立，任一通道的不符都成立。
     *
     * @return 打包值：bits 0-1 = Java 通道，bits 2-3 = native 通道；
     * 每通道 0 = 无法判断，1 = 一致，2 = 不符。见 [SignerChannels]。
     */
    private external fun sigCheck(sha256Hex: String, apkPath: String): Int

    /** 双通道解码结果，每通道：1 = 一致，0 = 不符，-1 = 无法判断。 */
    data class SignerChannels(val java: Int, val native: Int)

    fun verifyOwnSigner(sha256Hex: String, apkPath: String): SignerChannels = try {
        val packed = sigCheck(sha256Hex, apkPath)
        fun dec(v: Int) = when (v) {
            1 -> 1
            2 -> 0
            else -> -1
        }
        SignerChannels(dec(packed and 3), dec((packed shr 2) and 3))
    } catch (t: Throwable) {
        SignerChannels(-1, -1)
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