package me.weishu.kernelsu.ui.component.miuix

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.rootsentinel.data.LatestReportProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ui.component.dialog.LoadingDialogHandle

/**
 * RootSentinel seam for KernelSU's SendLogDialog: same signature and the same
 * interaction shape (system document picker, then the file is written), but
 * the payload is RootSentinel's latest scan report JSON instead of a KernelSU
 * bugreport tarball. The report text comes from [LatestReportProvider], which
 * RootSentinel's MainActivity installs.
 */
@Composable
fun SendLogDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    loadingDialog: LoadingDialogHandle,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val json = LatestReportProvider.latestReportJson?.invoke()
        if (json == null) {
            Toast.makeText(context, "暂无可导出的报告", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        loadingDialog.show()
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                }.isSuccess
            }
            loadingDialog.hide()
            Toast.makeText(context, if (ok) "报告已保存" else "保存失败", Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(show) {
        if (show) {
            if (LatestReportProvider.latestReportJson?.invoke() == null) {
                Toast.makeText(context, "暂无可导出的报告", Toast.LENGTH_SHORT).show()
            } else {
                launcher.launch("rootsentinel-report.json")
            }
            onDismissRequest()
        }
    }
}
