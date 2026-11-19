package com.rootsentinel.engine

import android.content.Context
import android.content.pm.PackageManager
import com.rootsentinel.NativeBridge
import com.rootsentinel.model.Category
import com.rootsentinel.model.Confidence
import com.rootsentinel.model.Evidence
import com.rootsentinel.model.Severity
import com.rootsentinel.model.Verdict
import java.security.MessageDigest

/**
 * 自保：校验自身 APK 的签名证书摘要。
 *
 * 这里只在 Java 侧做「取证书摘要」这一步；
 * 与内置期望值的比对交给调用方（值不进代码，放 assets 或构建期注入）。
 *
 * v2 包第五节第 2 条要求「native 直接解析 APK Signing Block」，
 * 属于 M3 的范围，这里先给出可用的 Java 实现。
 */
class IntegrityChecker(private val context: Context) {

    fun collect(): List<Evidence> {
        val out = mutableListOf<Evidence>()

        val digests = signingDigests()
        if (digests.isEmpty()) {
            out += Evidence(
                id = "I.APK_SIGNATURE",
                category = Category.INTEGRITY,
                severity = Severity.MEDIUM,
                confidence = Confidence.LOW,
                verdict = Verdict.UNKNOWN,
                title = "无法读取自身签名证书",
            )
        } else {
            // v2.4.0：真正做比对。
            // 之前只把摘要列出来就判 PASS，只能证明「读到了签名」，
            // 不能证明「签名是本项目的构建密钥」——被重打包后依然 PASS。
            // 现在交给 native 与编译进 .so 的基线比对。
            // v2.23.0：双通道——Java 通道（PackageManager 摘要）与
            // native 通道（.so 直读 APK Signing Block 的证书摘要）
            // 数据源独立；任一通道报不符即不符，双通道都读不到
            // 才算未知（-1 = 该通道无法判断，如基线未注入）。
            val apkPath = context.applicationInfo?.sourceDir ?: ""
            val results = digests.map { NativeBridge.verifyOwnSigner(it, apkPath) }
            fun chName(v: Int) = when (v) {
                1 -> "一致"
                0 -> "不符"
                else -> "未知"
            }
            val chanVectors = digests.mapIndexed { i, d ->
                "signer[$i]=${d.take(16)}… (java=${chName(results[i].java)}, native=${chName(results[i].native)})"
            }
            val mismatch = results.count { it.java == 0 || it.native == 0 }
            val allUnknown = results.all { it.java == -1 && it.native == -1 }
            val anyMatch = results.any { it.java == 1 || it.native == 1 }
            val bothMatched = results.all { it.java == 1 && it.native == 1 }

            when {
                // 先判「未知」：没有基线时不能报「不符」，
                // 否则未配置签名的正常构建会被误报为重打包
                allUnknown -> out += Evidence(
                    id = "I.APK_SIGNATURE",
                    category = Category.INTEGRITY,
                    severity = Severity.LOW,
                    confidence = Confidence.LOW,
                    verdict = Verdict.UNKNOWN,
                    title = "签名基线未注入，无法校验",
                    vectors = digests.mapIndexed { i, d -> "signer[$i]=${d.take(16)}…" },
                    detail = "本次构建没有注入签名指纹（未配置 RS_KS_PATH/RS_KS_PASS，"
                        + "或 keytool 不可用）。签名自校验降为 UNKNOWN —— "
                        + "这是「测不了」，不是「被重打包」。",
                    fpNote = "本地开发构建、CI 未配签名时会命中此状态",
                )

                mismatch > 0 -> out += Evidence(
                    id = "I.APK_SIGNATURE",
                    category = Category.INTEGRITY,
                    severity = Severity.HIGH,
                    confidence = Confidence.HIGH,
                    verdict = Verdict.HIT,
                    title = "APK 签名与构建基线不符",
                    vectors = chanVectors,
                    detail = "证书摘要与编译进 native 库的期望值不一致 → "
                        + "APK 被重新签名 / 重打包过（双通道任一报不符即成立）",
                    fpNote = "仅当项目更换签名密钥时会误报；"
                        + "基线随 .so 一起发布，改 dex 无效",
                )

                anyMatch -> out += Evidence(
                    id = "I.APK_SIGNATURE",
                    category = Category.INTEGRITY,
                    severity = Severity.INFO,
                    confidence = Confidence.HIGH,
                    verdict = Verdict.PASS,
                    title = if (bothMatched) {
                        "签名与构建基线一致（双通道，${digests.size} 个签名者）"
                    } else {
                        "签名与构建基线一致（单通道，${digests.size} 个签名者）"
                    },
                    vectors = chanVectors,
                    detail = "证书摘要与编译进 native 库的期望值完全相同" +
                        if (bothMatched) "；PackageManager 与 APK 文件直读两通道结论一致"
                        else "；仅一条通道完成比对，另一条无法判断（未见矛盾）",
                )

                else -> out += Evidence(
                    id = "I.APK_SIGNATURE",
                    category = Category.INTEGRITY,
                    severity = Severity.LOW,
                    confidence = Confidence.LOW,
                    verdict = Verdict.UNKNOWN,
                    title = "签名基线不可用",
                    vectors = digests.mapIndexed { i, d -> "signer[$i]=${d.take(16)}…" },
                    detail = "native 侧无法完成比对（基线未编译进去或调用失败）",
                )
            }
        }

        return out
    }

    private fun signingDigests(): List<String> {
        return try {
            val pm = context.packageManager
            val info = if (android.os.Build.VERSION.SDK_INT >= 28) {
                pm.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES,
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            }

            val signatures = if (android.os.Build.VERSION.SDK_INT >= 28) {
                info.signingInfo?.apkContentsSigners ?: emptyArray()
            } else {
                @Suppress("DEPRECATION")
                info.signatures ?: emptyArray()
            }

            signatures.map { sig ->
                val md = MessageDigest.getInstance("SHA-256")
                md.digest(sig.toByteArray()).joinToString("") { b ->
                    "%02x".format(b)
                }
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }
}