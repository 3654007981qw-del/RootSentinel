package me.weishu.kernelsu.ui.component.rebootlistpopup

import androidx.compose.runtime.Composable

/**
 * RootSentinel seam: KernelSU's home top-bar carries a reboot menu (reboot to
 * recovery / bootloader / ...). RootSentinel has no reboot functionality, so
 * the popup renders nothing — the copied home screens keep their TopBar code
 * unchanged and simply show no reboot action.
 */
@Composable
fun RebootListPopup() = Unit

@Composable
fun RebootListPopupMiuix() = Unit

@Composable
fun RebootListPopupMaterial() = Unit
