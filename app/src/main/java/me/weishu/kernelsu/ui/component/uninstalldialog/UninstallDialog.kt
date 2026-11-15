package me.weishu.kernelsu.ui.component.uninstalldialog

import androidx.compose.runtime.Composable
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode

/**
 * RootSentinel seam: KernelSU's uninstall dialog restores stock boot images —
 * a KernelSU-manager operation RootSentinel does not have. Its only call site
 * sits inside a KsuIsValid block, which never renders in RootSentinel
 * (Natives.isManager == false), so this composable is unreachable; it exists
 * so the copied settings screen compiles unchanged.
 */
@Composable
fun UninstallDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> UninstallDialogMiuix(show, onDismissRequest)
        UiMode.Material -> UninstallDialogMaterial(show, onDismissRequest)
    }
}

@Composable
fun UninstallDialogMiuix(show: Boolean, onDismissRequest: () -> Unit) = Unit

@Composable
fun UninstallDialogMaterial(show: Boolean, onDismissRequest: () -> Unit) = Unit
