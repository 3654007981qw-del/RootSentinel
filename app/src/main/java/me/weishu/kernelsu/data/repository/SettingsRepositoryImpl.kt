package me.weishu.kernelsu.data.repository

import com.rootsentinel.data.SettingsStore

/**
 * Backed by RootSentinel's SettingsStore (Compose-State-backed, persisted in
 * the rs_settings SharedPreferences). MainActivity installs [store] before
 * composition; the copied KernelSU components construct this impl directly
 * via `remember { SettingsRepositoryImpl() }`, so it reads through the bridge.
 */
class SettingsRepositoryImpl : SettingsRepository {
    companion object {
        @Volatile
        var store: SettingsStore? = null
    }

    override val uiMode: String get() = store?.uiMode ?: "miuix"
    override val themeMode: Int get() = store?.themeMode ?: 0
    override val miuixMonet: Boolean get() = store?.miuixMonet ?: false
    override val keyColor: Int get() = store?.keyColor ?: 0
    override val colorStyle: String get() = store?.colorStyle ?: "TonalSpot"
    override val colorSpec: String get() = store?.colorSpec ?: "SPEC_2025"
    override var navigationRailExpanded: Boolean
        get() = store?.navigationRailExpanded ?: true
        set(value) {
            store?.navigationRailExpanded = value
        }
}
