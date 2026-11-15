package com.rootsentinel.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.rootsentinel.RootSentinelVersion
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 应用内更新（v2.22.47）。
 *
 * 数据源：GitHub Releases（主）/ Gitee 镜像（备）的 latest 接口，
 * 均为公开 API、无需令牌。检查在每次启动时自动做一次，设置页也有
 * 手动入口。发现新版 → 弹窗展示版本号与更新说明 → 下载 release
 * APK → **安装前强制校验** → 调起系统安装。
 *
 * 校验三件（缺一不可，任一不过不安装）：
 *  1. 文件 SHA-256 与发布接口给出的 asset digest 一致（接口没给
 *     digest 时跳过这一项，但 2、3 仍强制）；
 *  2. APK 包名与本应用一致、版本号高于当前；
 *  3. APK 签名证书 SHA-256 与**当前已安装应用**的签名证书完全
 *     一致——保证更新包出自同一把密钥，系统安装时也会再验一次。
 *
 * 安装走 FileProvider + ACTION_VIEW 的标准路径；系统始终会弹
 * 安装确认，本应用不做也做不了静默安装。未授予「安装未知应用」
 * 时先引导去系统设置开启。
 */
object Updater {

    const val REPO_URL = "https://github.com/AURORA-0711/RootSentinel"
    private const val GITHUB_LATEST =
        "https://api.github.com/repos/AURORA-0711/RootSentinel/releases/latest"
    private const val GITEE_LATEST =
        "https://gitee.com/api/v5/repos/AURORAqw/RootSentinel/releases/latest"
    private const val FILEPROVIDER_AUTHORITY = "com.rootsentinel.fileprovider"

    /** 更新包体积硬帽（64 MB；当前发布包约 16 MB） */
    private const val MAX_APK_BYTES = 64L * 1024 * 1024

    data class UpdateInfo(
        val versionName: String,
        val notes: String,
        val apkUrl: String,
        val apkSize: Long,
        /** 发布接口给出的 asset SHA-256（GitHub 有，Gitee 可能没有） */
        val sha256: String?,
        val source: String,
    )

    /** 三段式版本号数值比较：remote > current 才算新版。解析失败按不新处理。 */
    fun isNewer(remote: String, current: String): Boolean {
        fun parts(v: String): List<Int> =
            v.removePrefix("v").split(".").map { it.toIntOrNull() ?: return listOf() }
        val r = parts(remote)
        val c = parts(current)
        if (r.isEmpty() || c.isEmpty()) return false
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }

    /** 单个更新源的答复。只有拿到 HTTP 200 的源才有资格下结论。 */
    sealed interface SourceOutcome {
        data class Found(val info: UpdateInfo) : SourceOutcome

        /** 源明确答复：没有更新的版本（或新版没有可用的 release APK） */
        data object NoUpdate : SourceOutcome

        /** 网络错误 / 非 200 / 内容损坏：这个源没能给出答复 */
        data object Failed : SourceOutcome
    }

    /** 检查更新的最终结论（v2.22.50 起三分，见 [decide]）。 */
    sealed interface CheckResult {
        data class Found(val info: UpdateInfo) : CheckResult
        data object UpToDate : CheckResult
        data object Failed : CheckResult
    }

    /**
     * 两源答符合并规则（纯函数，可单测）：任一源 Found → Found；
     * 否则任一源明确 NoUpdate → 已是最新；两源都 Failed → 检查失败。
     * v2.22.50（第六轮审查 #12）：此前 check() 用一个 null 同时表示
     * 这三种情况，双源不可达时界面会误报「已是最新版本」。
     */
    fun decide(first: SourceOutcome, second: SourceOutcome): CheckResult = when {
        first is SourceOutcome.Found -> CheckResult.Found(first.info)
        second is SourceOutcome.Found -> CheckResult.Found(second.info)
        first is SourceOutcome.NoUpdate || second is SourceOutcome.NoUpdate ->
            CheckResult.UpToDate
        else -> CheckResult.Failed
    }

    /** 查新版：GitHub 优先，找到就不问 Gitee。阻塞调用，走 IO 线程。 */
    fun check(): CheckResult {
        val gh = checkFrom(GITHUB_LATEST, "GitHub")
        if (gh is SourceOutcome.Found) return CheckResult.Found(gh.info)
        val gt = checkFrom(GITEE_LATEST, "Gitee")
        return decide(gh, gt)
    }

    private fun checkFrom(api: String, source: String): SourceOutcome {
        val conn = try {
            openGet(api)
        } catch (t: Throwable) {
            return SourceOutcome.Failed
        }
        try {
            if (conn.responseCode != 200) return SourceOutcome.Failed
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val tag = json.optString("tag_name").removePrefix("v")
            if (tag.isEmpty()) return SourceOutcome.Failed
            if (!isNewer(tag, RootSentinelVersion.NAME)) return SourceOutcome.NoUpdate
            val assets = json.optJSONArray("assets") ?: return SourceOutcome.NoUpdate
            var apk: JSONObject? = null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name").endsWith("-release.apk")) {
                    apk = a
                    break
                }
            }
            val a = apk ?: return SourceOutcome.NoUpdate
            val url = a.optString("browser_download_url")
            if (url.isEmpty()) return SourceOutcome.NoUpdate
            return SourceOutcome.Found(
                UpdateInfo(
                    versionName = tag,
                    notes = json.optString("body"),
                    apkUrl = url,
                    apkSize = a.optLong("size"),
                    sha256 = a.optString("digest").removePrefix("sha256:")
                        .ifEmpty { null },
                    source = source,
                ),
            )
        } catch (t: Throwable) {
            return SourceOutcome.Failed
        } finally {
            conn.disconnect()
        }
    }

    private fun openGet(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "RootSentinel/${RootSentinelVersion.NAME}")
        conn.setRequestProperty("Accept", "application/json")
        return conn
    }

    /**
     * 下载 APK 到 dest，边下边算 SHA-256。阻塞调用，走 IO 线程。
     * @param onProgress (已下载字节, 总字节)；总字节未知时为 -1
     * @param isCancelled 返回 true 时中止并删半成品
     * @return 实际文件的 SHA-256（hex 小写）
     */
    fun download(
        info: UpdateInfo,
        dest: File,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean,
    ): String {
        dest.parentFile?.mkdirs()
        var url = info.apkUrl
        var redirects = 0
        while (true) {
            val conn = openGet(url)
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            if (code in intArrayOf(301, 302, 303, 307, 308)) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrEmpty() || ++redirects > 5) {
                    throw IllegalStateException("下载重定向异常")
                }
                url = loc
                continue
            }
            if (code != 200) throw IllegalStateException("下载失败（HTTP $code）")
            val total = if (conn.contentLengthLong > 0) {
                conn.contentLengthLong
            } else {
                info.apkSize
            }
            // v2.22.50（第六轮审查 #21）：下载体积硬帽。发布包约 16 MB，
            // 64 MB 给足余量；超限说明来源异常，直接中止——后面还有
            // SHA/包名/签名三重校验，这里是纵深防御的第一道。
            if (total > MAX_APK_BYTES) {
                throw IllegalStateException("安装包体积异常（$total 字节），已中止下载")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled()) {
                            dest.delete()
                            throw InterruptedException("已取消")
                        }
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (done > MAX_APK_BYTES) {
                            // 实际流量超限（Content-Length 缺失/造假时）
                            dest.delete()
                            throw IllegalStateException("安装包体积异常，已中止下载")
                        }
                        onProgress(done, total)
                    }
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    sealed interface VerifyResult {
        data object Ok : VerifyResult
        data class Fail(val reason: String) : VerifyResult
    }

    /** 安装前校验，口径见类注释。 */
    fun verify(
        context: Context,
        file: File,
        info: UpdateInfo,
        actualSha256: String,
    ): VerifyResult {
        // 1) 与发布方公布的 digest 比对（有公布时）
        val expected = info.sha256
        if (expected != null && !expected.equals(actualSha256, ignoreCase = true)) {
            return VerifyResult.Fail("安装包 SHA-256 与发布值不一致，已弃用该文件")
        }
        // 2) 包名 + 版本号
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        }
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags)
            ?: return VerifyResult.Fail("安装包无法解析")
        if (archive.packageName != context.packageName) {
            return VerifyResult.Fail("安装包包名不符（${archive.packageName}）")
        }
        val newCode = archive.versionCodeCompat()
        val curCode = pm.getPackageInfo(context.packageName, 0).versionCodeCompat()
        if (newCode <= curCode) {
            return VerifyResult.Fail("安装包版本不高于当前版本")
        }
        // 3) 签名证书必须与已安装应用完全一致
        val newSigs = signerDigests(archive.signaturesCompat())
        val curSigs = signerDigests(
            pm.getPackageInfo(context.packageName, flags).signaturesCompat(),
        )
        if (newSigs.isEmpty() || newSigs != curSigs) {
            return VerifyResult.Fail("安装包签名与当前应用不一致，已拒绝安装")
        }
        return VerifyResult.Ok
    }

    @Suppress("DEPRECATION")
    private fun android.content.pm.PackageInfo.versionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun android.content.pm.PackageInfo.signaturesCompat(): Array<Signature> =
        if (Build.VERSION.SDK_INT >= 28) {
            signingInfo?.apkContentsSigners ?: emptyArray()
        } else {
            signatures ?: emptyArray()
        }

    private fun signerDigests(sigs: Array<Signature>): Set<String> =
        sigs.map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray())
                .joinToString("") { b -> "%02x".format(b) }
        }.toSet()

    enum class InstallGate { LAUNCHED, NEED_PERMISSION }

    /**
     * 调起系统安装。未授予「安装未知应用」时先打开对应设置页，
     * 返回 NEED_PERMISSION，调用方提示用户开启后重试（文件已留好）。
     */
    fun install(context: Context, file: File): InstallGate {
        if (!context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return InstallGate.NEED_PERMISSION
        }
        val uri = FileProvider.getUriForFile(context, FILEPROVIDER_AUTHORITY, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return InstallGate.LAUNCHED
    }

    /** 下载目录：cacheDir/updates（与 res/xml/file_paths.xml 对应）。 */
    fun updateFile(context: Context, versionName: String): File =
        File(File(context.cacheDir, "updates"), "RootSentinel-v$versionName-release.apk")
}
