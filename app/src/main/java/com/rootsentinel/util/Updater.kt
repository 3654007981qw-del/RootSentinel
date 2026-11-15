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
         * 全部候选下载地址（v2.22.52 多源切换）：初始顺序为 GitHub
         * 直连 → 公益镜像 ×3 → Gitee；v2.22.55 起下载前先并行测速，
         * 按实测吞吐重排（见 probeAndRank），此顺序只作测速前的
         * 初始偏好与同速时的稳定次序。apkUrl 恒为第一个候选（官方
         * 直链），供展示与复制/浏览器兜底使用。候选指向的都是同一
         * 个文件，最终完整性由安装前的 SHA-256/包名/签名三重校验
         * 保证，镜像只能影响速度。
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
     * Gitee 命中时的候选列表（纯函数，可单测，v2.22.56）：Gitee
     * 地址排第一——它能答复检查，就说明这条国内路径此刻是通的；
     * 其后仍是 GitHub 直连与镜像作补充，下载前的测速排序还会再
     * 按实测吞吐重排一次。
     */
    fun giteeFirstCandidates(githubUrl: String?, giteeUrl: String?): List<String> =
        buildList {
            if (giteeUrl != null) add(giteeUrl)
            if (githubUrl != null) {
                add(githubUrl)
                addAll(mirrorUrls(githubUrl))
            }
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

    /**
     * 下载速度计（纯逻辑，时间由调用方注入，可单测）：滑动窗口内
     * 的平均速度（字节/秒）。窗口默认 3 秒；窗口内样本跨度不足
     * 500ms 时返回 0（读数还没意义，避免开局爆表）。
     */
    class SpeedMeter(private val windowMs: Long = 3_000) {
        private val samples = ArrayDeque<LongArray>() // [timeMs, totalBytes]

        @Synchronized
        fun add(nowMs: Long, totalBytes: Long): Long {
            samples.addLast(longArrayOf(nowMs, totalBytes))
            while (samples.size > 2 && nowMs - samples.first()[0] > windowMs) {
                samples.removeFirst()
            }
            val first = samples.first()
            val span = nowMs - first[0]
            if (span < 500) return 0
            val bytes = totalBytes - first[1]
            if (bytes <= 0) return 0
            return bytes * 1000 / span
        }

        @Synchronized
        fun reset() = samples.clear()
    }

    /** 速度读数格式化（纯函数，可单测）。 */
    fun formatSpeed(bps: Long): String = when {
        bps >= 1024 * 1024 -> "%.1f MB/s".format(bps / 1048576.0)
        bps >= 1024 -> "%.0f KB/s".format(bps / 1024.0)
        bps > 0 -> "$bps B/s"
        else -> "—"
    }

    /**
     * 按实测速度给候选源排序（纯函数，可单测）：速度高者在前；
     * 没有读数的（探测失败/未测）沉底，且彼此保持原顺序——它们
     * 仍是可用的兜底，只是优先级最低。
     */
    fun rankBySpeed(urls: List<String>, speeds: Map<String, Long>): List<String> =
        urls.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<String>> { speeds[it.value] ?: -1L }
                    .thenBy { it.index },
            )
            .map { it.value }

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

    /**
     * 查新版：GitHub 与 Gitee 并行竞速（v2.22.56），先答先用。
     * 阻塞调用，走 IO 线程。
     *
     * 旧版串行先问 GitHub：国内不挂梯子时 GitHub 经常黑洞式挂起
     * （连接不报错也不返回），用户要干等它超时完才轮到 Gitee，
     * 甚至直接失败——这是「国内无法更新」的检查侧主因。现在两源
     * 同时问：任一 Found 立即采用；Gitee 先答复且 GitHub 2.5 秒
     * 内没跟上就直接按 Gitee 下结论，不再陪等。GitHub 命中时仍
     * 顺手（尽力而为，短超时）取一次 Gitee 同版本资产地址追加为
     * 下载候选。decide() 的三态语义不变。
     */
    fun check(): CheckResult {
        activeConns.clear()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val ecs = java.util.concurrent.ExecutorCompletionService<
                Pair<String, SourceOutcome>,
                >(pool)
            ecs.submit {
                "GitHub" to try {
                    checkFrom(GITHUB_LATEST, "GitHub")
                } catch (t: Throwable) {
                    SourceOutcome.Failed
                }
            }
            ecs.submit {
                "Gitee" to try {
                    checkFrom(GITEE_LATEST, "Gitee")
                } catch (t: Throwable) {
                    SourceOutcome.Failed
                }
            }
            var gh: SourceOutcome? = null
            var gt: SourceOutcome? = null
            var gtDoneAt = 0L
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) {
                if (gh is SourceOutcome.Found || gt is SourceOutcome.Found) break
                if (gh != null && gt != null) break
                if (gt != null && gh == null &&
                    System.currentTimeMillis() - gtDoneAt > 2_500
                ) {
                    break
                }
                val done = ecs.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                    ?: continue
                // 任务本身已在提交处兜底捕获，这里再兜一层：取结果
                // 失败就当这个源没答复，继续等另一个或等到截止。
                val (src, outcome) = runCatching { done.get() }.getOrNull()
                    ?: continue
                if (src == "GitHub") {
                    gh = outcome
                } else {
                    gt = outcome
                    if (gtDoneAt == 0L) gtDoneAt = System.currentTimeMillis()
                }
            }
            val ghInfo = (gh as? SourceOutcome.Found)?.info
            if (ghInfo != null) {
                val gtUrl = giteeAssetUrl(ghInfo.versionName)
                val info = if (gtUrl != null) {
                    ghInfo.copy(candidates = candidates(ghInfo.apkUrl, gtUrl))
                } else {
                    ghInfo
                }
                return CheckResult.Found(info)
            }
            val gtInfo = (gt as? SourceOutcome.Found)?.info
            if (gtInfo != null) return CheckResult.Found(gtInfo)
            return decide(gh ?: SourceOutcome.Failed, gt ?: SourceOutcome.Failed)
        } finally {
            pool.shutdownNow()
        }
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
            // 直链（/releases/download/v<tag>/<资产名>）。
            // v2.22.56：Gitee 命中时把 Gitee 地址排到候选第一位
            // （这条国内路径此刻是通的）；展示/复制用的官方地址
            // apkUrl 仍恒为 GitHub 直链，不随候选顺序变化。
            val official: String
            val cands: List<String>
            if (source == "GitHub") {
                official = url
                cands = candidates(url, null)
            } else {
                official = "$REPO_URL/releases/download/v$tag/${a.optString("name")}"
                cands = giteeFirstCandidates(official, url)
            }
            return SourceOutcome.Found(
                UpdateInfo(
                    versionName = tag,
                    notes = json.optString("body"),
                    apkUrl = official,
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

    /**
     * 当前打开中的连接登记表（v2.22.57）：取消下载时统一
     * disconnect，让阻塞在 connect/read 上的调用立刻抛错返回，
     * 而不是等 10~15 秒超时——旧版取消按钮在黑洞源上看起来
     * 完全没反应就是因为没这一手。登记在 openGet 统一做；
     * check()/download() 入口会先清表，避免误伤别的阶段。
     */
    private val activeConns =
        java.util.Collections.synchronizedSet(mutableSetOf<HttpURLConnection>())

    /** 断开当前登记的全部连接（取消下载时调用）。 */
    fun cancelActive() {
        val snapshot = synchronized(activeConns) { activeConns.toList() }
        snapshot.forEach { runCatching { it.disconnect() } }
    }

    private fun openGet(
        url: String,
        shortTimeout: Boolean = false,
        timeoutMs: Int? = null,
    ): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs ?: if (shortTimeout) 5_000 else 10_000
        conn.readTimeout = timeoutMs ?: if (shortTimeout) 5_000 else 15_000
        conn.setRequestProperty("User-Agent", "RootSentinel/${RootSentinelVersion.NAME}")
        conn.setRequestProperty("Accept", "application/json")
        activeConns.add(conn)
        return conn
    }

    /**
     * 下载 APK 到 dest（v2.22.52 多源 + 分片续传）。阻塞调用，走 IO 线程。
     *
     * 流程：候选源先测速排序（v2.22.55，见 probeAndRank），任一
     * 支持 Range 时走分片模式——文件切 6 段并行下载，每段落盘为 part 文件，
     * 中断后凭已有 part 长度断点续传；某段在某个源失败自动换下
     * 一个源续下同一段（各源是同一文件，字节区间可跨源拼）。
     * 所有源都不支持 Range 时退回单流模式，同样逐源切换。
     * 完成后拼装并算 SHA-256；接口公布了 digest 时在此先比对
     * 一次，不一致删文件报错（防续传残段污染导致的死循环重试）。
     * 取消时保留 part 文件，下次重试即续传；dest 只在拼装成功
     * 后出现。
     *
     * @param onProgress (已下载字节, 总字节, 当前速度字节/秒)；
     * 总字节未知时为 -1
     * @param isCancelled 返回 true 时中止（分片保留）
     * @return 实际文件的 SHA-256（hex 小写）
     */
    fun download(
        info: UpdateInfo,
        dest: File,
        onProgress: (Long, Long, Long) -> Unit,
        isCancelled: () -> Boolean,
    ): String {
        activeConns.clear()
        dest.parentFile?.mkdirs()
        val meter = SpeedMeter()
        val report = { done: Long, total: Long ->
            onProgress(done, total, meter.add(System.currentTimeMillis(), done))
        }
        val rawUrls = info.candidates.ifEmpty { listOf(info.apkUrl) }
        val total = resolveTotal(info, rawUrls)
        // 先报一次 0 进度：让弹窗立刻显示总大小，测速阶段不再
        // 是长时间 0% 且总量空白（v2.22.57）
        if (total > 0) report(0L, total)
        if (total > MAX_APK_BYTES) {
            throw IllegalStateException("安装包体积异常（$total 字节），已中止下载")
        }
        // v2.22.55 测速选源：多源且文件不算小时，先并行探测各源的
        // 实际吞吐再排序——国内网络下 GitHub 直连与各镜像的速度
        // 差异极大且时时不同，固定顺序必然经常选错源（旧版慢的主
        // 因）。探测失败的源不剔除，只沉底作兜底。
        val (urls, preNoRange) =
            if (rawUrls.size > 1 && (total < 0 || total > 1024 * 1024)) {
                probeAndRank(rawUrls, total, isCancelled)
            } else {
                rawUrls to emptySet()
            }
        if (total > 0) {
            val sha = downloadChunked(info, urls, total, dest, report, isCancelled, preNoRange)
            if (sha != null) return sha
            // 所有源都不支持分片 → 落单流模式
        }
        return downloadSingle(info, urls, dest, report, isCancelled)
    }

    /** 测速探测取样字节数与单源超时（v2.22.55）。 */
    private const val PROBE_BYTES = 256L * 1024
    private const val PROBE_TIMEOUT_MS = 4_000

    /**
     * 并行探测每个候选源的实际吞吐并排序（v2.22.55）。
     * 返回（排序后的地址表，探测中确认不支持 Range 的源集合）。
     */
    private fun probeAndRank(
        urls: List<String>,
        total: Long,
        isCancelled: () -> Boolean = { false },
    ): Pair<List<String>, Set<String>> {
        val speeds = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val noRange = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        // 从文件三分之一处取样，避开各源对开头区间的缓存差异
        val start = if (total > PROBE_BYTES * 4) total / 3 else 0L
        val end = if (total > 0) {
            minOf(start + PROBE_BYTES - 1, total - 1)
        } else {
            PROBE_BYTES - 1
        }
        val pool = Executors.newFixedThreadPool(urls.size.coerceIn(1, 6))
        try {
            val futures = urls.map { u ->
                pool.submit<Unit> {
                    val sp = probeOne(u, start, end, noRange)
                    if (sp > 0) speeds[u] = sp
                }
            }
            futures.forEach {
                if (isCancelled()) throw InterruptedException("已取消")
                runCatching { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
            }
        } finally {
            pool.shutdownNow()
        }
        return rankBySpeed(urls, speeds) to noRange
    }

    /** 单源探测：读满取样量或超时为止，返回字节/秒（失败为 0）。 */
    private fun probeOne(
        url: String,
        start: Long,
        end: Long,
        noRange: MutableSet<String>,
    ): Long {
        val conn = when (val r = openRange(url, start, end, PROBE_TIMEOUT_MS)) {
            is RangeResult.Part -> r.conn
            is RangeResult.Full -> {
                noRange.add(url)
                r.conn
            }
            is RangeResult.Fail -> return 0
        }
        val t0 = System.nanoTime()
        var read = 0L
        try {
            conn.inputStream.use { input ->
                val buf = ByteArray(32 * 1024)
                while (read < PROBE_BYTES) {
                    if ((System.nanoTime() - t0) / 1_000_000 > PROBE_TIMEOUT_MS) break
                    val n = input.read(
                        buf, 0,
                        minOf(buf.size.toLong(), PROBE_BYTES - read).toInt(),
                    )
                    if (n < 0) break
                    read += n
                }
            }
        } catch (e: Exception) {
            // 读中断不算源死亡，速度按已读部分计
        } finally {
            conn.disconnect()
        }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        if (read <= 0 || elapsedMs <= 0) return 0
        return read * 1000 / elapsedMs
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
    private fun openRange(
        url: String,
        start: Long,
        end: Long,
        timeoutMs: Int? = null,
    ): RangeResult {
        var current = url
        var redirects = 0
        while (true) {
            val conn = try {
                openGet(current, timeoutMs = timeoutMs)
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
        initialNoRange: Set<String> = emptySet(),
    ): String? {
        // v2.22.55：分片数 4 → 6，慢速 CDN 上多连接更容易跑满带宽
        val plan = chunkPlan(total, 6)
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
        val noRange = java.util.Collections.synchronizedSet(
            mutableSetOf<String>().apply { addAll(initialNoRange) },
        )
        // 连接级失败（黑洞/超时）的源隔离（v2.22.57）：同一源在
        // 本次下载里连接失败累计 2 次就拉黑，不再让每个分片都
        // 去它身上白等 10~25 秒——旧版在国内黑洞源上长时间
        // 0% 的主因之一。
        val dead = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val failCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val pool = Executors.newFixedThreadPool(plan.size.coerceAtMost(6))
        try {
            val futures = plan.mapIndexed { i, range ->
                pool.submit<Unit> {
                    downloadChunk(urls, range, partFiles[i], noRange, dead, failCounts, doneBytes, total, onProgress, isCancelled)
                }
            }
            // 全局无进度看门狗（v2.22.57）：所有分片合计 25 秒
            // 一个字节都没进账，说明源全在黑洞/假死，直接中止
            // 报错让用户重试，不再无限期挂在 0%。
            var lastDone = doneBytes.get()
            var lastProgressAt = System.currentTimeMillis()
            while (futures.any { !it.isDone }) {
                if (isCancelled()) throw InterruptedException("已取消")
                val nowDone = doneBytes.get()
                if (nowDone != lastDone) {
                    lastDone = nowDone
                    lastProgressAt = System.currentTimeMillis()
                } else if (System.currentTimeMillis() - lastProgressAt > STALL_LIMIT_MS) {
                    throw StallException()
                }
                onProgress(nowDone, total)
                Thread.sleep(200)
            }
            futures.forEach { it.get() } // 失败在此以 ExecutionException 抛出
        } catch (e: InterruptedException) {
            pool.shutdownNow()
            throw e
        } catch (e: StallException) {
            pool.shutdownNow()
            cancelActive()
            throw IllegalStateException("下载长时间无进度（网络无响应），已自动中止，请检查网络后重试")
        } catch (e: Exception) {
            pool.shutdownNow()
            // 分片走不通但存在已知的不支持 Range 的源 → 退单流
            if (urls.all { it in noRange || it in dead } && urls.any { it in noRange }) {
                return null
            }
            if (noRange.containsAll(urls)) return null
            val cause = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
            if (cause is InterruptedException) throw cause
            if (cause is StallException) {
                cancelActive()
                throw IllegalStateException("下载长时间无进度（网络无响应），已自动中止，请检查网络后重试")
            }
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

    /**
     * 慢源淘汰阈值（v2.22.55）：一条连接 5 秒内连 192 KB 都没下到
     * （约 38 KB/s），视为慢源，断开换下一个源续下同一区间。
     * 每个分片只在前若干次连接上启用淘汰（见 downloadChunk 的
     * attempts 预算），避免所有源都慢时来回横跳。
     */
    private const val SLOW_WINDOW_MS = 5_000L
    private const val SLOW_MIN_BYTES = 192L * 1024

    /** 慢源内部信号：当前连接太慢，断开换源。 */
    private class SlowSourceException : Exception("下载源速度过慢")

    /** 全局无进度看门狗阈值（v2.22.57）：见 downloadChunked。 */
    private const val STALL_LIMIT_MS = 25_000L

    /** 无进度内部信号：全部源合计长时间零字节进账。 */
    private class StallException : Exception("下载长时间无进度")

    /** 下载单个分片：按候选顺序逐源续下同一字节区间。 */
    private fun downloadChunk(
        urls: List<String>,
        range: LongRange,
        part: File,
        noRange: MutableSet<String>,
        dead: MutableSet<String>,
        failCounts: java.util.concurrent.ConcurrentHashMap<String, Int>,
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
        var attempts = 0
        while (have < chunkLen) {
            if (isCancelled()) throw InterruptedException("已取消")
            var progressed = false
            for (u in urls) {
                if (have >= chunkLen) break
                if (noRange.contains(u) || dead.contains(u)) continue
                when (val r = openRange(u, range.first + have, range.last, timeoutMs = 8_000)) {
                    is RangeResult.Part -> {
                        attempts++
                        // 慢源淘汰只在前 urls.size 次连接启用：每个源
                        // 都至少被给过一次机会后，就不再横跳，老实
                        // 在能连的源上慢慢下完。
                        val evictSlow = attempts <= urls.size
                        try {
                            r.conn.inputStream.use { input ->
                                RandomAccessFile(part, "rw").use { raf ->
                                    raf.seek(have)
                                    val buf = ByteArray(64 * 1024)
                                    val connStart = System.currentTimeMillis()
                                    var connBytes = 0L
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
                                        connBytes += n
                                        doneBytes.addAndGet(n.toLong())
                                        progressed = true
                                        onProgress(doneBytes.get(), total)
                                        if (evictSlow &&
                                            connBytes < SLOW_MIN_BYTES &&
                                            System.currentTimeMillis() - connStart >=
                                            SLOW_WINDOW_MS
                                        ) {
                                            throw SlowSourceException()
                                        }
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
                        // code == -1 是连接级失败（黑洞/超时/断连）：
                        // 累计 2 次拉黑这个源，本次下载不再碰它
                        if (r.code == -1) {
                            val c = failCounts.merge(u, 1) { a, b -> a + b } ?: 1
                            if (c >= 2) dead.add(u)
                        }
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
        for ((index, u) in urls.withIndex()) {
            try {
                // 慢源淘汰（v2.22.55）：不是最后一个源时才启用，
                // 最后一个源再慢也让它下完，不做无谓的失败。
                return downloadSingleFrom(
                    info, u, dest, onProgress, isCancelled,
                    evictable = index < urls.lastIndex,
                )
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
        evictable: Boolean = false,
    ): String {
        var url = startUrl
        var redirects = 0
        while (true) {
            val conn = openGet(url)
            // v2.22.53（第八轮审查 P3-03 残留项）：本连接的整个使用
            // 周期包在 try/finally 里，非 200、读流异常等一切退出路
            // 径都 disconnect，不再只靠正常路径关流。
            try {
                return downloadSingleBody(
                    info, conn, dest, onProgress, isCancelled, evictable,
                ) { loc ->
                    url = loc
                }
            } catch (e: RedirectSignal) {
                if (++redirects > 5) throw IllegalStateException("下载重定向异常")
            } finally {
                conn.disconnect()
            }
        }
    }

    /** 重定向内部信号：把 Location 交回 [downloadSingleFrom] 换地址重连。 */
    private class RedirectSignal : Exception()

    private fun downloadSingleBody(
        info: UpdateInfo,
        conn: HttpURLConnection,
        dest: File,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean,
        evictable: Boolean = false,
        onRedirect: (String) -> Unit,
    ): String {
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            if (code in intArrayOf(301, 302, 303, 307, 308)) {
                val loc = conn.getHeaderField("Location")
                if (loc.isNullOrEmpty()) {
                    throw IllegalStateException("下载重定向异常")
                }
                onRedirect(loc)
                throw RedirectSignal()
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
            val bodyStart = System.currentTimeMillis()
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
                        // 慢源淘汰（v2.22.55，口径同分片模式）：还有
                        // 备用源时，慢源不值得等——中止换源重下。
                        if (evictable &&
                            done < SLOW_MIN_BYTES &&
                            System.currentTimeMillis() - bodyStart >= SLOW_WINDOW_MS
                        ) {
                            dest.delete()
                            throw IllegalStateException("下载源速度过慢，切换备用源")
                        }
                    }
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
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
