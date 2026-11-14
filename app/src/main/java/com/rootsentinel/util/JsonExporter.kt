package com.rootsentinel.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.rootsentinel.engine.DetectorEngine
import com.rootsentinel.model.Evidence
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断包导出 —— 写到公共下载目录：
 *
 *     /sdcard/Download/RootSentinel/rootsentinel-YYYYMMDD-HHmmss.json
 *
 * 实现分两条路：
 *   API 29+ : MediaStore.Downloads（无需任何权限，scoped storage 合规）
 *   API 26-28: 直接 File + WRITE_EXTERNAL_STORAGE
 *
 * 只写「检测相关」的最小信息：设备型号、内核串、逐项证据。
 * 不含账号、位置、通讯录等任何个人数据。
 */
object JsonExporter {

    private const val SUB_DIR = "RootSentinel"

    // 由 export() 在每次导出前刷新，供 buildJson 使用。
    // 之前 JSON 里完全没有 version 字段 —— 报告与 APK 版本对不上，
    // 拿旧报告分析新版行为时会误判。
    @Volatile
    private var appVersion: String = "?"

    private fun readVersion(context: Context): String = try {
        context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName ?: "?"
    } catch (t: Throwable) {
        "?"
    }

    fun export(context: Context, result: DetectorEngine.Result): File? {
        appVersion = readVersion(context)
        return try {
            val json = buildJson(result).toString(2)
            val fileName = "rootsentinel-${stamp()}.json"

            if (Build.VERSION.SDK_INT >= 29) {
                exportViaMediaStore(context, fileName, json)
            } else {
                exportViaFile(context, fileName, json)
            }
        } catch (t: Throwable) {
            null
        }
    }

    // ---------------------------------------------------------------- API 29+
    private fun exportViaMediaStore(
        context: Context,
        fileName: String,
        json: String,
    ): File? {
        val resolver = context.contentResolver

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/$SUB_DIR",
            )
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            values,
        ) ?: return null

        // v2.22.43 修正：之前 openOutputStream 失败时直接 return null，
        // IS_PENDING=1 的占位记录不会被清掉，下载目录里会留下一条
        // 0 字节的幽灵条目。现在任何失败路径都先删掉占位记录。
        val written = try {
            resolver.openOutputStream(uri)?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
                out.flush()
            } != null
        } catch (t: Throwable) {
            false
        }
        if (!written) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }

        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        // 对外返回约定的真实路径（MediaStore 不直接暴露绝对路径；
        // 多用户/外置卷等非常规布局下该路径可能不精确，导出成功与否
        // 以本函数返回值非 null 为准）
        return File("/storage/emulated/0/Download/$SUB_DIR/$fileName")
    }

    // ---------------------------------------------------------------- API 26-28
    private fun exportViaFile(context: Context, fileName: String, json: String): File? {
        @Suppress("DEPRECATION")
        val base = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS,
        )
        val dir = File(base, SUB_DIR)
        // 公共目录需要 WRITE_EXTERNAL_STORAGE 运行时授权（manifest 已声明，
        // maxSdkVersion=28）。未授权时 writeText 会抛 SecurityException ——
        // v2.22.43 起改为退回应用专属外部目录，保证导出至少能落盘，
        // 而不是静默失败。
        return try {
            if (!dir.exists() && !dir.mkdirs()) throw java.io.IOException("mkdirs failed")
            val file = File(dir, fileName)
            file.writeText(json)
            file
        } catch (t: Throwable) {
            val fallbackDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: return null
            val sub = File(fallbackDir, SUB_DIR)
            if (!sub.exists() && !sub.mkdirs()) return null
            val file = File(sub, fileName)
            file.writeText(json)
            file
        }
    }

    // ---------------------------------------------------------------- JSON
    private fun buildJson(result: DetectorEngine.Result): JSONObject {
        val root = JSONObject()
        root.put("schema", "rootsentinel/1")
        root.put("version", appVersion)
        // v2.22.43：补上 native 版本。之前导出里没有它，
        // 「应用与 .so 版本不一致」这类结果无法从报告本身复核。
        root.put("nativeVersion", result.nativeVersion)
        root.put("exportedAt", isoNow())
        root.put("elapsedMs", result.elapsedMs)
        result.nativeError?.let { root.put("nativeError", it) }

        // ---------------- 设备最小信息
        val device = JSONObject()
        device.put("sdk", result.profile.sdk)
        device.put("brand", result.profile.brand)
        device.put("model", result.profile.model)
        device.put("manufacturer", result.profile.manufacturer)
        device.put("buildType", result.profile.buildType)
        device.put("buildTags", result.profile.buildTags)
        // v2.22.43 脱敏：完整 Build 指纹是稳定的设备/构建标识，
        // 导出文件又常被转发。改为 SHA-256 摘要 —— 同设备同构建
        // 摘要相同，仍可做「两份报告是否同一构建」的比对，
        // 但不再直接泄露确切构建串。
        device.put("fingerprintSha256", sha256Hex(result.profile.fingerprint))
        device.put("isEmulator", result.profile.isEmulator)
        device.put("isGsi", result.profile.isGsi)
        device.put("isUserdebug", result.profile.isUserdebug)
        device.put("isVendorRom", result.profile.isVendorRom)
        root.put("device", device)

        // ---------------- 评分
        val score = JSONObject()
        score.put("grade", result.score.grade.name)
        score.put("score", result.score.score)
        score.put("hardScore", result.score.hardScore)
        score.put("softScore", result.score.softScore)
        score.put("confidence", result.score.confidence.name)
        score.put("hitCount", result.score.hitCount)
        score.put("unknownCount", result.score.unknownCount)
        score.put("coverage", result.score.coverage)
        // v2.22.43：补齐四个维度分与完整性标志。之前导出只有总分，
        // 拿到 JSON 无法复算定级（ROOTED 与 ROOTED_HIDING 的区分、
        // integrityBreach 都不在文件里）。
        score.put("rootScore", result.score.rootScore)
        score.put("hookScore", result.score.hookScore)
        score.put("integrityScore", result.score.integrityScore)
        score.put("environmentScore", result.score.environmentScore)
        score.put("integrityBreach", result.score.integrityBreach)
        score.put("envRisk", result.score.envRisk)
        val notes = org.json.JSONArray()
        result.score.notes.forEach { notes.put(it) }
        score.put("notes", notes)
        root.put("score", score)

        // ---------------- 证据
        val items = org.json.JSONArray()
        result.items.forEach { e -> items.put(evidenceToJson(e)) }
        root.put("items", items)

        // ---------------- 交叉验证
        // v2.22.43：之前 crosses 完全不导出，而矛盾项会给 HOOK 维度
        // 加分 —— 缺了它，报告无法复算 hookScore。
        val crosses = org.json.JSONArray()
        result.crosses.forEach { c ->
            val o = JSONObject()
            o.put("id", c.id)
            o.put("subject", c.subject)
            o.put("consistent", c.consistent)
            val obs = org.json.JSONArray()
            c.observations.forEach { (k, v) ->
                val p = JSONObject()
                p.put("source", k)
                p.put("value", v)
                obs.put(p)
            }
            o.put("observations", obs)
            if (c.note.isNotEmpty()) o.put("note", c.note)
            crosses.put(o)
        }
        root.put("crosses", crosses)

        return root
    }

    private fun evidenceToJson(e: Evidence): JSONObject {
        val o = JSONObject()
        o.put("id", e.id)
        o.put("category", e.category.code)
        o.put("severity", e.severity.name)
        o.put("confidence", e.confidence.name)
        o.put("verdict", e.verdict.name)
        // v2.22.43：补上证据状态与所属维度。之前只有 verdict，
        // SKIPPED / ERROR / TIMEOUT 与普通 UNKNOWN 在导出里无法区分，
        // 维度归属也无从复核。
        o.put("state", e.effectiveState.name)
        o.put("dimension", com.rootsentinel.model.ScoringModel.dimensionOf(e).name)
        o.put("heuristic", e.heuristic)
        o.put("title", e.title)
        if (e.detail.isNotEmpty()) o.put("detail", e.detail)
        if (e.vectors.isNotEmpty()) {
            val v = org.json.JSONArray()
            e.vectors.forEach { v.put(it) }
            o.put("vectors", v)
        }
        if (e.fpNote.isNotEmpty()) o.put("fpNote", e.fpNote)
        if (e.verifyNote.isNotEmpty()) o.put("verifyNote", e.verifyNote)
        return o
    }

    private fun sha256Hex(s: String): String = try {
        val d = java.security.MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
        d.joinToString("") { "%02x".format(it) }
    } catch (t: Throwable) {
        ""
    }

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date())

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
}