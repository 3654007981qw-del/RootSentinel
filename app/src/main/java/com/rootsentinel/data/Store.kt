package com.rootsentinel.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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

    // v2.22.44 删除（第四份审查 P2-10）：themeMode / animations /
    // haptics 三个设置只存不用 —— themeMode 全仓零消费者，另两个
    // 只有设置页自己在读。留着只会让用户以为开关生效。等真有对应
    // 功能（主题切换 / 动效与震动接线）时连功能一起加回来。
    // SharedPreferences 里的旧键保留不管，不影响任何东西。

    /**
     * 界面风格（v2.22.59，新 UI 按 KernelSU 管理器的双模式结构）：
     * "miuix"（默认）或 "material"。RootApp 真实消费：切换后整个
     * 界面在 Miuix 主题与 Material 3 主题之间切换，不是摆设开关。
     */
    private var _uiMode by mutableStateOf(sp.getString("uiMode", "miuix") ?: "miuix")
    var uiMode: String
        get() = _uiMode
        set(v) {
            _uiMode = v
            sp.edit().putString("uiMode", v).apply()
        }

    /** 深色模式（v2.22.59）：0 跟随系统 / 1 浅色 / 2 深色。RootApp 真实消费。 */
    private var _colorMode by mutableStateOf(sp.getInt("colorMode", 0))
    var colorMode: Int
        get() = _colorMode
        set(v) {
            _colorMode = v
            sp.edit().putInt("colorMode", v).apply()
        }

    // ---- v2.22.60：KernelSU 外观设置全套（照抄 KSU 的 ThemeController 口径） ----
    // 下面每一项都被照抄过来的 KSU 主题/底栏代码真实消费，经
    // me.weishu.kernelsu.data.repository.SettingsRepositoryImpl 桥接读取。
    // themeMode 用 KSU ColorMode 的值域（0 系统/1 浅/2 深/3..5 Monet/6 AMOLED 深），
    // 首次读取时以 .59 的 colorMode（0/1/2）为默认迁移。

    /** KSU ColorMode 值（0..6）。KernelSUTheme/ThemeController 真实消费。 */
    private var _themeMode by mutableStateOf(sp.getInt("themeMode", sp.getInt("colorMode", 0)))
    var themeMode: Int
        get() = _themeMode
        set(v) {
            _themeMode = v
            sp.edit().putInt("themeMode", v).apply()
        }

    /** Miuix 模式下是否启用 Monet 取色。ThemeController 真实消费。 */
    private var _miuixMonet by mutableStateOf(sp.getBoolean("miuixMonet", false))
    var miuixMonet: Boolean
        get() = _miuixMonet
        set(v) {
            _miuixMonet = v
            sp.edit().putBoolean("miuixMonet", v).apply()
        }

    /** 主题种子色（0 = 无/默认）。material-kolor 生成色板时真实消费。 */
    private var _keyColor by mutableStateOf(sp.getInt("keyColor", 0))
    var keyColor: Int
        get() = _keyColor
        set(v) {
            _keyColor = v
            sp.edit().putInt("keyColor", v).apply()
        }

    /** 色板风格（material-kolor PaletteStyle 名）。真实消费。 */
    private var _colorStyle by mutableStateOf(sp.getString("colorStyle", "TonalSpot") ?: "TonalSpot")
    var colorStyle: String
        get() = _colorStyle
        set(v) {
            _colorStyle = v
            sp.edit().putString("colorStyle", v).apply()
        }

    /** 色彩规范版本（SPEC_2021 / SPEC_2025）。真实消费。 */
    private var _colorSpec by mutableStateOf(sp.getString("colorSpec", "SPEC_2025") ?: "SPEC_2025")
    var colorSpec: String
        get() = _colorSpec
        set(v) {
            _colorSpec = v
            sp.edit().putString("colorSpec", v).apply()
        }

    /** 顶栏/底栏毛玻璃模糊总开关（LocalEnableBlur）。真实消费。 */
    private var _enableBlur by mutableStateOf(sp.getBoolean("enableBlur", true))
    var enableBlur: Boolean
        get() = _enableBlur
        set(v) {
            _enableBlur = v
            sp.edit().putBoolean("enableBlur", v).apply()
        }

    /**
     * 浮动底栏开关（LocalEnableFloatingBottomBar）。默认开——这是用户
     * 明确要的 KernelSU 液态玻璃浮动底栏；关掉则回到底部贴边栏。
     */
    private var _enableFloatingBottomBar by mutableStateOf(sp.getBoolean("enableFloatingBottomBar", true))
    var enableFloatingBottomBar: Boolean
        get() = _enableFloatingBottomBar
        set(v) {
            _enableFloatingBottomBar = v
            sp.edit().putBoolean("enableFloatingBottomBar", v).apply()
        }

    /** 浮动底栏的液态玻璃模糊（LocalEnableFloatingBottomBarBlur）。默认开。 */
    private var _enableFloatingBottomBarBlur by mutableStateOf(sp.getBoolean("enableFloatingBottomBarBlur", true))
    var enableFloatingBottomBarBlur: Boolean
        get() = _enableFloatingBottomBarBlur
        set(v) {
            _enableFloatingBottomBarBlur = v
            sp.edit().putBoolean("enableFloatingBottomBarBlur", v).apply()
        }

    /** 底栏角标开关（LocalEnableNavigationBadge）。真实消费。 */
    private var _enableNavigationBadge by mutableStateOf(sp.getBoolean("enableNavigationBadge", true))
    var enableNavigationBadge: Boolean
        get() = _enableNavigationBadge
        set(v) {
            _enableNavigationBadge = v
            sp.edit().putBoolean("enableNavigationBadge", v).apply()
        }

    /** 宽屏导航栏展开状态（照抄的 NavigationRail 组件读写）。 */
    private var _navigationRailExpanded by mutableStateOf(sp.getBoolean("navigationRailExpanded", true))
    var navigationRailExpanded: Boolean
        get() = _navigationRailExpanded
        set(v) {
            _navigationRailExpanded = v
            sp.edit().putBoolean("navigationRailExpanded", v).apply()
        }

    /** 自动检查更新（照抄的 KSU 设置项 checkUpdate）。RootApp 真实消费：关则启动不查。 */
    private var _checkUpdate by mutableStateOf(sp.getBoolean("checkUpdate", true))
    var checkUpdate: Boolean
        get() = _checkUpdate
        set(v) {
            _checkUpdate = v
            sp.edit().putBoolean("checkUpdate", v).apply()
        }

    // ---- KernelSU 外观设置补齐（v2.22.60 照抄 KSU 设置面所需） ----

    private var _enablePredictiveBack by mutableStateOf(sp.getBoolean("enablePredictiveBack", false))
    var enablePredictiveBack: Boolean
        get() = _enablePredictiveBack
        set(v) { _enablePredictiveBack = v; sp.edit().putBoolean("enablePredictiveBack", v).apply() }

    private var _enableSwipeDismiss by mutableStateOf(sp.getBoolean("enableSwipeDismiss", true))
    var enableSwipeDismiss: Boolean
        get() = _enableSwipeDismiss
        set(v) { _enableSwipeDismiss = v; sp.edit().putBoolean("enableSwipeDismiss", v).apply() }

    private var _pagerInterceptionMode by mutableIntStateOf(sp.getInt("pagerInterceptionMode", 1))
    var pagerInterceptionMode: Int
        get() = _pagerInterceptionMode
        set(v) { _pagerInterceptionMode = v; sp.edit().putInt("pagerInterceptionMode", v).apply() }

    private var _pageScale by mutableFloatStateOf(sp.getFloat("pageScale", 1.0f))
    var pageScale: Float
        get() = _pageScale
        set(v) { _pageScale = v; sp.edit().putFloat("pageScale", v).apply() }

    private var _moduleDescriptionMaxLines by mutableIntStateOf(sp.getInt("moduleDescriptionMaxLines", 4))
    var moduleDescriptionMaxLines: Int
        get() = _moduleDescriptionMaxLines
        set(v) { _moduleDescriptionMaxLines = v; sp.edit().putInt("moduleDescriptionMaxLines", v).apply() }

    private var _checkModuleUpdate by mutableStateOf(sp.getBoolean("checkModuleUpdate", true))
    var checkModuleUpdate: Boolean
        get() = _checkModuleUpdate
        set(v) { _checkModuleUpdate = v; sp.edit().putBoolean("checkModuleUpdate", v).apply() }

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

    /**
     * 是否已看过首次使用声明（v2.22.46）。false 时 RootApp 弹声明，
     * 且 Telemetry 一律不上传——先声明、后上传，顺序不能反。
     */
    private var _declarationSeen by mutableStateOf(sp.getBoolean("declarationSeen", false))
    var declarationSeen: Boolean
        get() = _declarationSeen
        set(v) {
            _declarationSeen = v
            sp.edit().putBoolean("declarationSeen", v).apply()
        }

    /**
     * 诊断摘要上传开关（v2.22.46）。默认开启：声明的同意模型是
     * 「继续使用即同意」；用户在设置里关掉后立即停传。字段口径
     * 见 util/Telemetry 类注释。
     */
    private var _telemetryEnabled by mutableStateOf(sp.getBoolean("telemetryEnabled", true))
    var telemetryEnabled: Boolean
        get() = _telemetryEnabled
        set(v) {
            _telemetryEnabled = v
            sp.edit().putBoolean("telemetryEnabled", v).apply()
        }
}
/**
 * 最新报告 JSON 的提供者（v2.22.60）：照抄过来的 KernelSU 设置页里有
 * 「发送日志」入口，其对话框（component/miuix/SendLogDialog 的 RS 接缝）
 * 通过这里拿到当前最新报告的 JSON 再走系统文档选择器导出。
 * MainActivity 在 RootApp 里安装（有结果时返回报告 JSON，否则 null）。
 */
object LatestReportProvider {
    @Volatile
    var latestReportJson: (() -> String?)? = null
}
