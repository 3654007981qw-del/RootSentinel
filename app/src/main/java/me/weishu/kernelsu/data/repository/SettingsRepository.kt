package me.weishu.kernelsu.data.repository

/**
 * RootSentinel seam for KernelSU's SettingsRepository.
 *
 * KernelSU's own interface also carries its kernel-feature surface (su compat,
 * kernel umount, SELinux hide, sulog, adb root, ...) which RootSentinel does
 * not have; this seam keeps exactly the members the copied UI code reads:
 * the theme/appearance properties used by ThemeController and the navigation
 * rail state used by the copied NavigationRail components.
 */
interface SettingsRepository {
    val uiMode: String
    val themeMode: Int
    val miuixMonet: Boolean
    val keyColor: Int
    val colorStyle: String
    val colorSpec: String
    var navigationRailExpanded: Boolean
}
