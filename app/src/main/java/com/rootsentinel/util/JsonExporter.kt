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
                exportViaFile(fileName, json)
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

        resolver.openOutputStream(uri)?.use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
            out.flush()
        } ?: return null

        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        // 对外返回约定的真实路径（MediaStore 不直接暴露绝对路径）
        return File("/storage/emulated/0/Download/$SUB_DIR/$fileName")
    }

    // ---------------------------------------------------------------- API 26-28
    private fun exportViaFile(fileName: String, json: String): File? {
        @Suppress("DEPRECATION")
        val base = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS,
        )
        val dir = File(base, SUB_DIR)
        if (!dir.exists() && !dir.mkdirs()) return null

        val file = File(dir, fileName)
        file.writeText(json)
        return file
    }

    // ---------------------------------------------------------------- JSON
    private fun buildJson(result: DetectorEngine.Result): JSONObject {
        val root = JSONObject()
        root.put("schema", "rootsentinel/1")
        root.put("version", appVersion)
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
        device.put("fingerprint", result.profile.fingerprint)
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
        val notes = org.json.JSONArray()
        result.score.notes.forEach { notes.put(it) }
        score.put("notes", notes)
        root.put("score", score)

        // ---------------- 证据
        val items = org.json.JSONArray()
        result.items.forEach { e -> items.put(evidenceToJson(e)) }
        root.put("items", items)

        return root
    }

    private fun evidenceToJson(e: Evidence): JSONObject {
        val o = JSONObject()
        o.put("id", e.id)
        o.put("category", e.category.code)
        o.put("severity", e.severity.name)
        o.put("confidence", e.confidence.name)
        o.put("verdict", e.verdict.name)
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

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date())

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
}