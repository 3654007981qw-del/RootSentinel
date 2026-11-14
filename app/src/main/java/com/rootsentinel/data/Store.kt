package com.rootsentinel.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rootsentinel.engine.DetectorEngine
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条扫描历史（v2.4.1）。
 *
 * 只存摘要，不存全部证据 —— 证据列表动辄 200+ 条，
 * 存 30 次扫描会到几 MB，而且历史页也不需要逐条证据。
 */
data class HistoryEntry(
    val at: Long,
    val grade: String,
    val rootScore: Double,
    val integrityScore: Double,
    val envScore: Double,
    val coverage: Double,
    val hit: Int,
    val unknown: Int,
    val items: Int,
    val elapsedMs: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("at", at)
        put("grade", grade)
        put("root", rootScore)
        put("integ", integrityScore)
        put("env", envScore)
        put("coverage", coverage)
        put("hit", hit)
        put("unknown", unknown)
        put("items", items)
        put("elapsed", elapsedMs)
    }

    companion object {
        fun from(e: DetectorEngine.Result, now: Long = System.currentTimeMillis()) = HistoryEntry(
            at = now,
            grade = e.score.grade.name,
            rootScore = e.score.rootScore,
            integrityScore = e.score.integrityScore,
            envScore = e.score.environmentScore,
            coverage = e.score.coverage,
            hit = e.score.hitCount,
            unknown = e.score.unknownCount,
            items = e.items.size,
            elapsedMs = e.elapsedMs,
        )

        fun parse(o: JSONObject): HistoryEntry? = try {
            HistoryEntry(
                at = o.optLong("at"),
                grade = o.optString("grade"),
                rootScore = o.optDouble("root"),
                integrityScore = o.optDouble("integ"),
                envScore = o.optDouble("env"),
                coverage = o.optDouble("coverage"),
                hit = o.optInt("hit"),
                unknown = o.optInt("unknown"),
                items = o.optInt("items"),
                elapsedMs = o.optLong("elapsed"),
            )
        } catch (t: Throwable) {
            null
        }
    }
}

/** 扫描历史（最多保留 N 条，只读本地存储） */
class HistoryStore(context: Context) {

    private val sp = context.getSharedPreferences("rs_history", Context.MODE_PRIVATE)

    /**
     * 历史列表用 Compose State 承载。
     *
     * 之前 `load()` 是普通函数，`append()` 写进 SharedPreferences 之后
     * Compose 完全不知道数据变了 —— 扫完切到历史页能碰巧看到（因为切页会重组），
     * 但停在历史页时永远是旧列表。
     */
    private val _entries = mutableStateOf(readFromDisk())

    /** 供 UI 直接读取（Compose 会自动订阅） */
    val entries: List<HistoryEntry> get() = _entries.value

    fun load(): List<HistoryEntry> = entries

    fun append(entry: HistoryEntry, max: Int = 30) {
        val list = (listOf(entry) + _entries.value).take(max)
        _entries.value = list
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    fun clear() {
        _entries.value = emptyList()
        sp.edit().remove(KEY).apply()
    }

    private fun readFromDisk(): List<HistoryEntry> {
        val raw = sp.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { HistoryEntry.parse(it) }
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private companion object {
        const val KEY = "entries"
    }
}

/**
 * 设置项（v2.4.1）。
 *
 * 关键一条：[autoExport] 默认 **false**。
 * v2.4.0 之前 renderReport 无条件导出 JSON，属于未经用户同意落盘。
 */
class SettingsStore(context: Context) {

    private val sp = context.getSharedPreferences("rs_settings", Context.MODE_PRIVATE)

    // ---- 全部用 Compose State 承载 ----
    // getter 返回 State 值 → Compose 读它就建立订阅，值变了会重组；
    // setter 同时更新 State 与 SharedPreferences → 界面立刻刷新、重启后保持。
    //
    // 之前直接 get/set SharedPreferences，那不是 State，
    // 所以「点了开关界面不动」—— 其实值已经改了。

    /** 0 = 跟随系统，1 = 深色，2 = 浅色 */
    private var _themeMode by mutableIntStateOf(sp.getInt("themeMode", 0))
    var themeMode: Int
        get() = _themeMode
        set(v) {
            _themeMode = v
            sp.edit().putInt("themeMode", v).apply()
        }

    private var _animations by mutableStateOf(sp.getBoolean("animations", true))
    var animations: Boolean
        get() = _animations
        set(v) {
            _animations = v
            sp.edit().putBoolean("animations", v).apply()
        }

    private var _haptics by mutableStateOf(sp.getBoolean("haptics", true))
    var haptics: Boolean
        get() = _haptics
        set(v) {
            _haptics = v
            sp.edit().putBoolean("haptics", v).apply()
        }

    /** 扫描完成后自动导出 JSON。默认关闭。 */
    private var _autoExport by mutableStateOf(sp.getBoolean("autoExport", false))
    var autoExport: Boolean
        get() = _autoExport
        set(v) {
            _autoExport = v
            sp.edit().putBoolean("autoExport", v).apply()
        }

    /** 是否记录扫描历史。默认开启。 */
    private var _keepHistory by mutableStateOf(sp.getBoolean("keepHistory", true))
    var keepHistory: Boolean
        get() = _keepHistory
        set(v) {
            _keepHistory = v
            sp.edit().putBoolean("keepHistory", v).apply()
        }
}