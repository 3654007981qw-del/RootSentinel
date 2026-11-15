package com.rootsentinel

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 首次使用声明（v2.22.46）。
 *
 * 同意模型（作者定）：首次启动弹出，读完点「我已阅读，继续使用」
 * 即视为同意诊断摘要上传；之后可在「设置 → 隐私与数据」里随时
 * 关闭。弹窗不可点外部/返回键关闭——必须读完并点按钮，避免
 * 「没看见就同意了」。确认状态存 SettingsStore.declarationSeen。
 *
 * 文案与 Telemetry.buildPayload 的实际字段严格对应：
 * 文案承诺不传的，代码里就不许出现；改字段先改这里。
 */
@Composable
fun DeclarationDialog(onConfirm: () -> Unit) {
    val c = MiuixTheme.colorScheme
    Dialog(
        onDismissRequest = { /* 必须点按钮确认，不提供绕过路径 */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        RsCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text(
                    "使用声明与数据说明",
                    fontSize = 17.sp,
                    color = c.onBackground,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "RootSentinel 是一款设备安全自检工具，检测结果仅供参考，不构成对设备状态的保证。",
                    fontSize = 13.sp,
                    color = c.onBackgroundVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "为改良检测准确率，继续使用即表示您同意：每次扫描完成后，本应用上传一份诊断摘要，包括应用版本、设备品牌/型号、Android 版本、检测评分与覆盖率，以及各检测项的编号与判定结果。",
                    fontSize = 13.sp,
                    color = c.onBackgroundVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "不上传：设备序列号、IMEI、设备指纹、文件路径、已安装应用列表与扫描报告全文。",
                    fontSize = 13.sp,
                    color = c.onBackgroundVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "您可随时在「设置 → 隐私与数据 → 上传诊断摘要」中关闭上传，关闭后立即停止。",
                    fontSize = 13.sp,
                    color = c.onBackgroundVariant,
                )
                Spacer(Modifier.height(16.dp))
                GlassButton(
                    onClick = onConfirm,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                ) {
                    Text("我已阅读，继续使用", fontSize = 15.sp)
                }
            }
        }
    }
}
