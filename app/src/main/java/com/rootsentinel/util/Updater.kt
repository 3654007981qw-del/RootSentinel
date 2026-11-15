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
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

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
        /**
         * 全部候选下载地址（v2.22.52 多源切换）：顺序为 GitHub 直连
         * → 公益镜像 ×3 → Gitee。下载引擎逐个尝试、自动切换；
         * apkUrl 恒为第一个候选（官方直链），供展示与复制/浏览器
         * 兜底使用。候选指向的都是同一个文件，最终完整性由安装前
         * 的 SHA-256/包名/签名三重校验保证，镜像只能影响速度。
         */
        val candidates: List<String> = listOf(apkUrl),
    )

    /** GitHub 公益加速镜像前缀（v2.22.52）：完整 GitHub 链接拼在其后。 */
    private val MIRROR_PREFIXES = listOf(
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://ghproxy.net/",
    )

    /** 单个 GitHub 地址派生的镜像地址列表（纯函数，可单测）。 */
    fun mirrorUrls(githubUrl: String): List<String> =
        MIRROR_PREFIXES.map { it + githubUrl }

    /**
     * 候选下载列表（纯函数，可单测）：GitHub 直连 → 镜像 → Gitee，
     * 去重保序。任一参数为 null 表示该源没有可用地址。
     */
    fun candidates(githubUrl: String?, giteeUrl: String?): List<String> =
        buildList {
            if (githubUrl != null) {
                add(githubUrl)
                addAll(mirrorUrls(githubUrl))
            }
            if (giteeUrl != null) add(giteeUrl)
        }.distinct()

    /**
     * 分片计划（纯函数，可单测）：把 [0, total) 切成至多 chunks 段
     * 连续不重叠的闭区间。total <= 0 返回空表。
     */
    fun chunkPlan(total: Long, chunks: Int): List<LongRange> {
        if (total <= 0 || chunks <= 0) return emptyList()
        val size = (total + chunks - 1) / chunks
        val out = ArrayList<LongRange>()
        var i = 0
        while (true) {
            val s = i * size
            if (s >= total) break
            out.add(s..minOf(s + size, total) - 1)
            i++
        }
        return out
    }

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
        if (gh is SourceOutcome.Found) {
            // v2.22.52：GitHub 命中后顺手（尽力而为，短超时）取一次
            // Gitee 同版本资产地址，追加为下载候选的最后一环。
            // 取不到不影响结论，decide() 的三态语义不变。
            val gtUrl = giteeAssetUrl(gh.info.versionName)
            val info = if (gtUrl != null) {
                gh.info.copy(candidates = candidates(gh.info.apkUrl, gtUrl))
            } else {
                gh.info
            }
            return CheckResult.Found(info)
        }
        val gt = checkFrom(GITEE_LATEST, "Gitee")
        return decide(gh, gt)
    }

    /** Gitee 上指定版本（tag，不带 v）的 release APK 地址；任何异常返回 null。 */
    private fun giteeAssetUrl(versionName: String): String? {
        val conn = try {
            openGet(GITEE_LATEST, shortTimeout = true)
        } catch (t: Throwable) {
            return null
        }
        try {
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            if (json.optString("tag_name").removePrefix("v") != versionName) return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name").endsWith("-release.apk")) {
                    return a.optString("browser_download_url").ifEmpty { null }
                }
            }
            return null
        } catch (t: Throwable) {
            return null
        } finally {
            conn.disconnect()
        }
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
            // v2.22.52：候选列表在结论产生处就建好。GitHub 源直接
            // 派生镜像候选（Gitee 地址由 check() 命中后补取）；
            // Gitee 源则按本仓库固定的发布地址规律反推 GitHub
            // 直链（/releases/download/v<tag>/<资产名>），使候选
            // 顺序与 GitHub 命中时一致。
            val cands = if (source == "GitHub") {
                candidates(url, null)
            } else {
                val canonical = "$REPO_URL/releases/download/v$tag/${a.optString("name")}"
                candidates(canonical, url)
            }
            return SourceOutcome.Found(
                UpdateInfo(
                    versionName = tag,
                    notes = json.optString("body"),
                    apkUrl = cands.first(),
                    apkSize = a.optLong("size"),
                    sha256 = a.optString("digest").removePrefix("sha256:")
                        .ifEmpty { null },
                    source = source,
                    candidates = cands,
                ),
            )
        } catch (t: Throwable) {
            return SourceOutcome.Failed
        } finally {
            conn.disconnect()
        }
    }

    private fun openGet(url: String, shortTimeout: Boolean = false): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = if (shortTimeout) 5_000 else 10_000
        conn.readTimeout = if (shortTimeout) 5_000 else 15_000
        conn.setRequestProperty("User-Agent", "RootSentinel/${RootSentinelVersion.NAME}")
        conn.setRequestProperty("Accept", "application/json")
        return conn
    }

    /**
     * 下载 APK 到 dest（v2.22.52 多源 + 分片续传）。阻塞调用，走 IO 线程。
     *
     * 流程：候选源（GitHub 直连 → 镜像 → Gitee）任一支持 Range 时
     * 走分片模式——文件切 4 段并行下载，每段落盘为 part 文件，
     * 中断后凭已有 part 长度断点续传；某段在某个源失败自动换下
     * 一个源续下同一段（各源是同一文件，字节区间可跨源拼）。
     * 所有源都不支持 Range 时退回单流模式，同样逐源切换。
     * 完成后拼装并算 SHA-256；接口公布了 digest 时在此先比对
     * 一次，不一致删文件报错（防续传残段污染导致的死循环重试）。
     * 取消时保留 part 文件，下次重试即续传；dest 只在拼装成功
     * 后出现。
     *
     * @param onProgress (已下载字节, 总字节)；总字节未知时为 -1
     * @param isCancelled 返回 true 时中止（分片保留）
     * @return 实际文件的 SHA-256（hex 小写）
     */
    fun download(
        info: UpdateInfo,
        dest: File,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean,
    ): String {
        dest.parentFile?.mkdirs()
        val urls = info.candidates.ifEmpty { listOf(info.apkUrl) }
        val total = resolveTotal(info, urls)
        if (total > MAX_APK_BYTES) {
            throw IllegalStateException("安装包体积异常（$total 字节），已中止下载")
        }
        if (total > 0) {
            val sha = downloadChunked(info, urls, total, dest, onProgress, isCancelled)
            if (sha != null) return sha
            // 所有源都不支持分片 → 落单流模式
        }
        return downloadSingle(info, urls, dest, onProgress, isCancelled)
    }

    /** 总字节数：接口给的 size 优先；没有就用 Range 探测第一个可用源。 */
    private fun resolveTotal(info: UpdateInfo, urls: List<String>): Long {
        if (info.apkSize > 0) return info.apkSize
        for (u in urls) {
            val r = openRange(u, 0, 0)
            when (r) {
                is RangeResult.Part -> {
                    val t = r.total
                    r.close()
                    if (t > 0) return t
                }
                is RangeResult.Full -> {
                    val t = r.contentLength
                    r.close()
                    if (t > 0) return t
                }
                is RangeResult.Fail -> Unit
            }
        }
        return -1
    }

    private sealed interface RangeResult {
        fun close()

        /** 206：conn 已定位到请求的字节区间，total 为文件总长（-1 未知） */
        class Part(val conn: HttpURLConnection, val total: Long) : RangeResult {
            override fun close() = conn.disconnect()
        }

        /** 200：源不理会 Range，conn 是完整文件流 */
        class Full(val conn: HttpURLConnection, val contentLength: Long) : RangeResult {
            override fun close() = conn.disconnect()
        }

        class Fail(val code: Int) : RangeResult {
            override fun close() = Unit
        }
    }

    /** 发一个带 Range 的 GET，手动跟随重定向（每次都带 Range 头）。 */
    private fun openRange(url: String, start: Long, end: Long): RangeResult {
        var current = url
        var redirects = 0
        while (true) {
            val conn = try {
                openGet(current)
            } catch (t: Throwable) {
                return RangeResult.Fail(-1)
            }
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Range", "bytes=$start-$end")
            val code = try {
                conn.responseCode
            } catch (t: Throwable) {
                conn.disconnect()
                return RangeResult.Fail(-1)
            }
            when {
                code in intArrayOf(301, 302, 303, 307, 308) -> {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (loc.isNullOrEmpty() || ++redirects > 5) {
                        return RangeResult.Fail(code)
                    }
                    current = loc
                }
                code == 206 -> {
                    val cr = conn.getHeaderField("Content-Range")
                    val t = cr?.substringAfterLast('/')?.toLongOrNull() ?: -1
                    return RangeResult.Part(conn, t)
                }
                code == 200 -> return RangeResult.Full(conn, conn.contentLengthLong)
                else -> {
                    conn.disconnect()
                    return RangeResult.Fail(code)
                }
            }
        }
    }

    /**
     * 分片模式。返回拼装文件的 SHA-256；若所有源都不支持 Range
     * 返回 null 让调用方退回单流。失败抛异常，取消抛
     * InterruptedException（part 保留）。
     */
    private fun downloadChunked(
        info: UpdateInfo,
        urls: List<String>,
        total: Long,
        dest: File,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean,
    ): String? {
        val plan = chunkPlan(total, 4)
        if (plan.isEmpty()) return null
        val partsDir = File(dest.parentFile, dest.name + ".parts")
        partsDir.mkdirs()
        val partFiles = plan.mapIndexed { i, _ -> File(partsDir, "part-$i") }
        // 已有 part 的有效长度 = 续传基线；超长视为污染，删掉重下
        val doneBytes = AtomicLong(0)
        plan.forEachIndexed { i, range ->
            val len = partFiles[i].length()
            val chunkLen = range.last - range.first + 1
            when {
                len in 1..chunkLen -> doneBytes.addAndGet(len)
                len > chunkLen -> partFiles[i].delete()
            }
        }
        onProgress(doneBytes.get(), total)
        val noRange = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val pool = Executors.newFixedThreadPool(plan.size.coerceAtMost(4))
        try {
            val futures = plan.mapIndexed { i, range ->
                pool.submit<Unit> {
                    downloadChunk(urls, range, partFiles[i], noRange, doneBytes, total, onProgress, isCancelled)
                }
            }
            while (futures.any { !it.isDone }) {
                if (isCancelled()) throw InterruptedException("已取消")
                onProgress(doneBytes.get(), total)
                Thread.sleep(200)
            }
            futures.forEach { it.get() } // 失败在此以 ExecutionException 抛出
        } catch (e: InterruptedException) {
            pool.shutdownNow()
            throw e
        } catch (e: Exception) {
            pool.shutdownNow()
            if (noRange.containsAll(urls)) return null
            val cause = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
            if (cause is InterruptedException) throw cause
            throw IllegalStateException(cause.message ?: "下载失败，请检查网络后重试")
        }
        pool.shutdown()
        // 拼装 + 算哈希
        val digest = MessageDigest.getInstance("SHA-256")
        var assembled = 0L
        FileOutputStream(dest).use { out ->
            val buf = ByteArray(64 * 1024)
            for (f in partFiles) {
                f.inputStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        assembled += n
                    }
                }
            }
        }
        if (assembled != total) {
            dest.delete()
            throw IllegalStateException("下载不完整（$assembled/$total 字节），请重试")
        }
        partsDir.deleteRecursively()
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        val expected = info.sha256
        if (expected != null && !expected.equals(sha, ignoreCase = true)) {
            dest.delete()
            throw IllegalStateException("下载内容与发布哈希不一致，已删除，请重试")
        }
        onProgress(total, total)
        return sha
    }

    /** 下载单个分片：按候选顺序逐源续下同一字节区间。 */
    private fun downloadChunk(
        urls: List<String>,
        range: LongRange,
        part: File,
        noRange: MutableSet<String>,
        doneBytes: AtomicLong,
        total: Long,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val chunkLen = range.last - range.first + 1
        var have = if (part.exists()) part.length() else 0L
        if (have > chunkLen) {
            part.delete()
            have = 0
        }
        var lastError: Exception? = null
        while (have < chunkLen) {
            if (isCancelled()) throw InterruptedException("已取消")
            var progressed = false
            for (u in urls) {
                if (have >= chunkLen) break
                if (noRange.contains(u)) continue
                when (val r = openRange(u, range.first + have, range.last)) {
                    is RangeResult.Part -> {
                        try {
                            r.conn.inputStream.use { input ->
                                RandomAccessFile(part, "rw").use { raf ->
                                    raf.seek(have)
                                    val buf = ByteArray(64 * 1024)
                                    while (have < chunkLen) {
                                        if (isCancelled()) {
                                            throw InterruptedException("已取消")
                                        }
                                        val n = input.read(
                                            buf, 0,
                                            minOf(buf.size.toLong(), chunkLen - have).toInt(),
                                        )
                                        if (n < 0) break
                                        raf.write(buf, 0, n)
                                        have += n
                                        doneBytes.addAndGet(n.toLong())
                                        progressed = true
                                        onProgress(doneBytes.get(), total)
                                    }
                                }
                            }
                        } catch (e: InterruptedException) {
                            throw e
                        } catch (e: Exception) {
                            lastError = e
                        } finally {
                            r.close()
                        }
                    }
                    is RangeResult.Full -> {
                        r.close()
                        noRange.add(u)
                    }
                    is RangeResult.Fail -> {
                        lastError = IllegalStateException("HTTP ${r.code}")
                    }
                }
            }
            if (!progressed && have < chunkLen) {
                throw lastError ?: IllegalStateException("分片下载失败")
            }
        }
    }

    /** 单流模式（源不支持 Range 时）：逐源尝试完整下载，任一成功即返回。 */
    private fun downloadSingle(
        info: UpdateInfo,
        urls: List<String>,
        dest: File,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean,
    ): String {
        var lastError: Exception? = null
        for (u in urls) {
            try {
                return downloadSingleFrom(info, u, dest, onProgress, isCancelled)
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                dest.delete()
            }
        }
        throw lastError ?: IllegalStateException("所有下载源均失败")
    }

    private fun downloadSingleFrom(
        info: UpdateInfo,
        startUrl: String,
        dest: File,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean,
    ): String {
        var url = startUrl
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
            // 下载体积硬帽（v2.22.50，第六轮审查 #21）：发布包约 16 MB，
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
