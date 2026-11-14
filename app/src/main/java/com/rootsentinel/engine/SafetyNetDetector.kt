package com.rootsentinel.engine

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.rootsentinel.model.Category
import com.rootsentinel.model.Confidence
import com.rootsentinel.model.Evidence
import com.rootsentinel.model.Severity
import com.rootsentinel.model.Verdict
import com.rootsentinel.util.KeyAttestation
import java.security.KeyPairGenerator
import java.security.KeyStore

/**
 * 安全模型校验（M9）— 4 项
 *
 *   StrongBox/TEE   硬件密钥是否真的可用（Attestation 的基石）
 *   包列表一致性    queryIntentActivities 与 getInstalledApplications 是否对得上
 *   ActivityManager 本进程的 importance 是否合理
 *   Timing          重复操作耗时分布是否异常（hook 常带来首次解析开销）
 */
class SafetyNetDetector(private val context: Context) {

    fun collect(): List<Evidence> = listOf(
        attestChain(), strongbox(), pkgConsistency(), amProcs(), timing(),
    )

    // -------------------------------------------------------- G.ATTEST_CHAIN
    // 真正的 Key Attestation：
    //   向 TEE/StrongBox 申请带 challenge 的密钥 → 解析硬件签名的 KeyDescription
    //   → 拿到 verifiedBootState / deviceLocked / verifiedBootHash。
    //
    // 这一项最独特的地方是「交叉验证」：
    //   attestation 里的 bootState 是 TEE 签名的，用户态改不了；
    //   ro.boot.verifiedbootstate 是属性，resetprop 能改。
    //   两者不一致 → 属性被伪造。
    private fun attestChain(): Evidence = try {
        val r = KeyAttestation.probe(context)

        // 读属性侧的值（反射 SystemProperties，Android 上是灰名单，通常可读）
        val propState = try {
            val c = Class.forName("android.os.SystemProperties")
            val m = c.getMethod("get", String::class.java)
            (m.invoke(null, "ro.boot.verifiedbootstate") as? String)?.trim()
        } catch (t: Throwable) {
            null
        }

        val vec = mutableListOf<String>()
        vec.add("securityLevel=${r.securityLevelName()}")
        vec.add("attestBootState=${r.bootStateName()}")
        vec.add("chain=${r.chainLength}")
        if (r.deviceLocked != null) vec.add("deviceLocked=${r.deviceLocked}")
        if (r.osVersion > 0) vec.add("osVersion=${r.osVersion}")
        if (r.patchLevel > 0) vec.add("patchLevel=${r.patchLevel}")
        if (r.verifiedBootHash != null) {
            vec.add("bootHash=" + KeyAttestation.hex(r.verifiedBootHash).take(16) + "…")
        }
        if (propState != null) vec.add("propBootState=$propState")
        // 密码学可信度：没有这三项，任何 VERIFIED 结论都是空口
        vec.add("challengeMatch=${r.challengeMatch}")
        vec.add("chainVerified=${r.chainVerified}")
        vec.add("cryptoTrusted=${r.cryptoTrusted}")
        vec.add("rootIsGoogle=${r.rootIsGoogle}")
        vec.add("bootFieldsFromTee=${r.bootFieldsFromTee}")
        if (r.error != null) vec.add("error=${r.error}")

        // 门槛之下的定性分支都建立在 cryptoTrusted 之上（链验签 +
        // challenge + TEE/StrongBox + 链根锚定全部通过），统一用
        // Confidence.VERIFIED。v2.22.41 修正：之前全部用 HIGH，
        // 导致评分器的 integrityBreach（要求 VERIFIED）永远触发不了，
        // 「硬件证明确认启动链不可信」这条硬结论从未生效过。
        when {
            // ★ 前置门槛：密码学不成立时，绝不允许输出 VERIFIED。
            //
            //   之前的问题：只要证书里有 verifiedBootState 字段就当作
            //   「TEE 签名的铁证」，但从不校验：
            //     · challenge 是否本次生成（不校验 → 证书可回放）
            //     · 证书链签名是否逐级有效（不校验 → 可自签伪造）
            //     · securityLevel 是否真是 TEE/StrongBox（软件级也可进判定）
            //   结果是：一份伪造的、或重放的证书就能把「启动链不可信」
            //   报成「Verified + 已锁定」，也能反过来。
            !r.cryptoTrusted -> {
                val why = when {
                    r.securityLevel == 0 ->
                        "attestationSecurityLevel=Software（软件级证明，用户态可伪造）"
                    r.challengeMatch == false ->
                        "attestationChallenge 与本次请求不一致（证书被回放）"
                    r.chainVerified == false ->
                        "证书链签名校验失败（链被篡改或自签伪造）"
                    r.challengeMatch == null && r.chainVerified == null ->
                        "无法校验 challenge 与证书链（结构异常）"
                    else -> "密码学前提不成立"
                }
                ev(
                    "G.ATTEST_CHAIN", Severity.MEDIUM, Verdict.UNKNOWN,
                    "硬件证明的密码学前提不成立，启动链结论不可用",
                    conf = Confidence.LOW,
                    cat = Category.ATTESTATION, vec = vec,
                    detail = why + "。证书里虽然带有启动状态字段，但未经可信验证，" +
                        "不能作为设备已锁定/已解锁的依据。",
                    fp = "部分老设备与非 GMS 设备只能提供 Software 级证明，" +
                        "此时本项按 UNKNOWN 处理，不代表设备异常",
                )
            }

            // 连硬件密钥都没有 → 真的测不了
            r.securityLevel < 0 -> ev(
                "G.ATTEST_CHAIN", Severity.LOW, Verdict.UNKNOWN,
                "Key Attestation 不可用", conf = Confidence.LOW,
                cat = Category.ATTESTATION, vec = vec,
                detail = r.error ?: "无法从证书里取到 attestation 数据",
                fp = "部分设备（尤其老机型 / 无 GMS）不支持 attestation，"
                    + "此时本项无法判定，不代表设备异常")

            // 有硬件密钥（TEE/StrongBox）但 ROM 没提供 bootState。
            // 这种情况仍然可以利用 deviceLocked 做判断。
            r.verifiedBootState < 0 && r.deviceLocked == true -> ev(
                "G.ATTEST_CHAIN", Severity.INFO, Verdict.PASS,
                "硬件密钥可用且设备已锁定（ROM 未提供 bootState）",
                conf = Confidence.VERIFIED,
                cat = Category.ATTESTATION, vec = vec,
                detail = "attestationSecurityLevel=" + r.securityLevelName() +
                    "，deviceLocked=true；该 ROM 的 KeyDescription 里没有 "
                    + "verifiedBootState 字段，但已锁定状态由 TEE 签名确认")

            r.verifiedBootState < 0 && r.deviceLocked == false -> ev(
                "G.ATTEST_CHAIN", Severity.HIGH, Verdict.HIT,
                "硬件证明显示设备未锁定", conf = Confidence.VERIFIED,
                cat = Category.ATTESTATION, vec = vec,
                detail = "attestationSecurityLevel=" + r.securityLevelName() +
                    "，deviceLocked=false —— 由 TEE 签名，无法伪造")

            r.verifiedBootState < 0 -> ev(
                "G.ATTEST_CHAIN", Severity.LOW, Verdict.UNKNOWN,
                "已取得硬件密钥但缺少 bootState / deviceLocked",
                conf = Confidence.LOW,
                cat = Category.ATTESTATION, vec = vec,
                detail = "证书链长度 " + r.chainLength +
                    "，但 AuthorizationList 里没有可用的启动状态字段")
            // 证书说有 bootState 但属性对不上 → 属性被改。
            //
            // v2.4.0 修复：这里之前是 `propState != r.bootStateName().lowercase()`，
            // 拿 Android 属性的 "green" 去比 attestation 枚举的 "verified"，
            // 两套枚举永远不相等 → 任何提供 verifiedBootState 的原厂机都被判 CRITICAL。
            // 现在用 bootStatePropName() 做正确映射（green/yellow/orange/red）。
            propState != null && r.bootStatePropName().isNotEmpty() &&
                propState != r.bootStatePropName() -> ev(
                "G.ATTEST_CHAIN", Severity.HIGH, Verdict.HIT,
                "启动链属性与硬件证明矛盾", conf = Confidence.VERIFIED,
                cat = Category.ATTESTATION, vec = vec,
                detail = "TEE 签名的 bootState 是 " + r.bootStateName() +
                    "（对应属性应为 " + r.bootStatePropName() + "），" +
                    "但 ro.boot.verifiedbootstate 是 " + propState +
                    " —— 属性被 resetprop 之类的工具改过（硬件证明伪造不了）")

            // Verified + 已锁定 → 最健康
            r.verifiedBootState == 0 && r.deviceLocked == true -> ev(
                "G.ATTEST_CHAIN", Severity.INFO, Verdict.PASS,
                "启动链 Verified 且设备已锁定", conf = Confidence.VERIFIED,
                cat = Category.ATTESTATION, vec = vec,
                detail = "硬件证明确认 bootloader 锁定 + 启动链完整" +
                    "（securityLevel=" + r.securityLevelName() +
                    "，链验签通过，challenge 一致" +
                    (if (r.rootIsGoogle) "，链根 Google" else "，链根非 Google") + "）")

            // Verified 但未锁定
            // Verified 但 deviceLocked 字段缺失 → 不能猜成「未锁定」
            r.verifiedBootState == 0 && r.deviceLocked == null -> ev(
                "G.ATTEST_CHAIN", Severity.LOW, Verdict.UNKNOWN,
                "启动链 Verified，但 deviceLocked 缺失，锁定状态无法确认",
                conf = Confidence.LOW,
                cat = Category.ATTESTATION, vec = vec,
                detail = "attestationSecurityLevel=" + r.securityLevelName() +
                    "，teeEnforced 里没有 deviceLocked 字段；" +
                    "字段缺失只能按 UNKNOWN 处理，不能解释为未锁定")

            r.verifiedBootState == 0 -> ev(
                "G.ATTEST_CHAIN", Severity.MEDIUM, Verdict.HIT,
                "启动链 Verified 但设备未锁定", conf = Confidence.VERIFIED,
                cat = Category.ATTESTATION, vec = vec,
                detail = "bootloader 处于解锁状态，刷机无需擦除数据")

            // SelfSigned / Unverified / Failed
            else -> ev(
                "G.ATTEST_CHAIN", Severity.CRITICAL, Verdict.HIT,
                "硬件证明判定启动链不可信（" + r.bootStateName() + "）",
                conf = Confidence.VERIFIED,
                cat = Category.ATTESTATION, vec = vec,
                detail = "由 TEE 签名的结论，无法通过用户态手段伪造",
                fp = "SelfSigned 在部分自研 ROM / 工程机上属正常")
        }
    } catch (t: Throwable) {
        ev("G.ATTEST_CHAIN", Severity.LOW, Verdict.UNKNOWN,
            "Key Attestation 探测异常", cat = Category.ATTESTATION,
            detail = t.javaClass.simpleName)
    }

    // ----------------------------------------------------------- G.STRONGBOX
    // 真机的 AndroidKeyStore 由 TEE / StrongBox 支撑。
    // 能生成带 attestation challenge 的密钥，说明硬件密钥链是通的。
    private fun strongbox(): Evidence = try {
        val pm = context.packageManager
        val hasStrongBox = if (Build.VERSION.SDK_INT >= 28) {
            pm.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        } else false

        val alias = "rs_probe_" + System.currentTimeMillis()
        val kpg = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")

        // v2.22.43 修正：之前从不调用 setIsStrongBoxBacked(true)，
        // 生成的一直是普通（TEE）密钥 —— 本项 PASS 只能证明
        // AndroidKeyStore 可用，证明不了 StrongBox。现在设备声明
        // StrongBox 特性时真正按 StrongBox 生成；StrongBox 临时
        // 不可用（StrongBoxUnavailableException）时退回 TEE 并如实标注。
        var strongBoxBacked = false
        if (hasStrongBox && Build.VERSION.SDK_INT >= 28) {
            try {
                val sbSpec = KeyGenParameterSpec.Builder(
                    alias, KeyProperties.PURPOSE_SIGN)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setIsStrongBoxBacked(true)
                    .build()
                kpg.initialize(sbSpec)
                kpg.generateKeyPair()
                strongBoxBacked = true
            } catch (e: android.security.keystore.StrongBoxUnavailableException) {
                val teeSpec = KeyGenParameterSpec.Builder(
                    alias, KeyProperties.PURPOSE_SIGN)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
                kpg.initialize(teeSpec)
                kpg.generateKeyPair()
            }
        } else {
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
            kpg.initialize(spec)
            kpg.generateKeyPair()
        }

        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val cert = ks.getCertificate(alias)
        runCatching { ks.deleteEntry(alias) }

        if (cert != null) {
            when {
                strongBoxBacked -> ev(
                    "G.STRONGBOX", Severity.INFO, Verdict.PASS,
                    "StrongBox 可用（密钥按 StrongBox 生成）", Confidence.HIGH,
                    cat = Category.ATTESTATION,
                    vec = listOf("strongbox=true", "strongboxBacked=true",
                                 "certLen=${cert.encoded?.size ?: 0}"),
                    detail = "硬件密钥可用是 Key Attestation 的前提")
                hasStrongBox -> ev(
                    "G.STRONGBOX", Severity.INFO, Verdict.PASS,
                    "AndroidKeyStore 可用（StrongBox 生成失败，已退回 TEE）",
                    Confidence.MEDIUM,
                    cat = Category.ATTESTATION,
                    vec = listOf("strongbox=true", "strongboxBacked=false",
                                 "certLen=${cert.encoded?.size ?: 0}"),
                    detail = "设备声明 StrongBox 特性，但 StrongBox 密钥生成" +
                        "不可用，本次仅以 TEE 密钥验证了 KeyStore 链路")
                else -> ev(
                    "G.STRONGBOX", Severity.INFO, Verdict.PASS,
                    "AndroidKeyStore 可用（设备无 StrongBox 特性）",
                    Confidence.HIGH,
                    cat = Category.ATTESTATION,
                    vec = listOf("strongbox=false", "strongboxBacked=false",
                                 "certLen=${cert.encoded?.size ?: 0}"),
                    detail = "硬件密钥可用是 Key Attestation 的前提")
            }
        } else {
            ev("G.STRONGBOX", Severity.MEDIUM, Verdict.HIT,
                "生成的密钥无证书", Confidence.MEDIUM,
                cat = Category.ATTESTATION,
                detail = "正常硬件密钥会附带 attestation 证书链")
        }
    } catch (t: Throwable) {
        ev("G.STRONGBOX", Severity.LOW, Verdict.UNKNOWN,
            "KeyStore 探测失败", cat = Category.ATTESTATION,
            detail = t.javaClass.simpleName)
    }

    // ---------------------------------------------------------- B.PKG_HIDDEN
    // 判定标准：一个能通过 ACTION_MAIN/LAUNCHER 解析到的包，
    // 必须同时出现在「已安装应用列表」里。少了就是被某个接口过滤过。
    //
    // v2.4.0 修复：之前比的是「launcher activity 数量 > 应用数量」，
    // 但一个应用可以有多个 Launcher Activity，两者本来就不可比。
    // 现在改成比较**包名集合**。
    private fun pkgConsistency(): Evidence = try {
        val pm = context.packageManager
        val installPkgs = pm.getInstalledApplications(0)
            .mapNotNull { it.packageName }
            .toHashSet()

        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        val launcherPkgs = activities.mapNotNull { it.activityInfo?.packageName }.toHashSet()

        val orphan = launcherPkgs - installPkgs

        if (orphan.isNotEmpty()) {
            ev("B.PKG_HIDDEN", Severity.HIGH, Verdict.HIT,
                "有包能启动但不在已安装列表里", Confidence.HIGH,
                cat = Category.PROPERTY,
                vec = listOf(
                    "installed=${installPkgs.size}",
                    "launchers=${launcherPkgs.size}",
                    "orphan=${orphan.size}",
                ) + orphan.take(5),
                detail = "这些包能通过 Launcher Intent 解析到，"
                    + "但 getInstalledApplications 里没有 → 包列表被某个接口过滤过")
        } else {
            ev("B.PKG_HIDDEN", Severity.INFO, Verdict.PASS,
                "启动器包集合完全包含于已安装列表", Confidence.HIGH,
                cat = Category.PROPERTY,
                vec = listOf(
                    "installed=${installPkgs.size}",
                    "launchers=${launcherPkgs.size}",
                ))
        }
    } catch (t: Throwable) {
        ev("B.PKG_HIDDEN", Severity.LOW, Verdict.UNKNOWN,
            "包列表不可读", cat = Category.PROPERTY)
    }

    // ------------------------------------------------------------ F.AM_PROCS
    private fun amProcs(): Evidence {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am == null) {
                return ev("F.AM_PROCS", Severity.LOW, Verdict.UNKNOWN,
                    "ActivityManager 不可用", cat = Category.SELINUX)
            }

            val procs = am.runningAppProcesses
            if (procs.isNullOrEmpty()) {
                return ev("F.AM_PROCS", Severity.INFO, Verdict.PASS,
                    "运行进程列表不可用（Android 5+ 正常限制）",
                    Confidence.HIGH, cat = Category.SELINUX)
            }

            val me = procs.firstOrNull { it.pid == Process.myPid() }
            if (me == null) {
                return ev("F.AM_PROCS", Severity.LOW, Verdict.UNKNOWN,
                    "本进程不在列表中", cat = Category.SELINUX)
            }

            val imp = me.importance
            val name = when {
                imp <= 100 -> "FOREGROUND"
                imp <= 200 -> "VISIBLE"
                imp <= 300 -> "SERVICE"
                imp <= 400 -> "CACHED"
                else -> "OTHER_$imp"
            }

            ev("F.AM_PROCS", Severity.INFO, Verdict.PASS,
                "进程 importance 已读取", Confidence.HIGH,
                cat = Category.SELINUX,
                vec = listOf("importance=$imp", "level=$name", "procs=${procs.size}"))
        } catch (t: Throwable) {
            ev("F.AM_PROCS", Severity.LOW, Verdict.UNKNOWN,
                "进程 importance 不可读", cat = Category.SELINUX)
        }
    }

    // ---------------------------------------------------------- X.TIMING_HOOK
    // 对一个固定操作采样 30 次，看最慢与中位数的比值。
    // hook 常在首次调用时做符号解析，导致明显的长尾。
    private fun timing(): Evidence = try {
        val samples = LongArray(30)
        for (i in samples.indices) {
            val t0 = System.nanoTime()
            // 固定开销的操作
            @Suppress("UNUSED_EXPRESSION")
            System.currentTimeMillis()
            samples[i] = System.nanoTime() - t0
        }
        samples.sort()

        val median = samples[samples.size / 2]
        val p95 = samples[(samples.size * 95) / 100]
        val ratio = if (median > 0) p95.toDouble() / median.toDouble() else 0.0

        val buf = String.format("%.1f", ratio)

        if (ratio > 50.0) {
            ev("X.TIMING_HOOK", Severity.LOW, Verdict.HIT,
                "操作耗时分布长尾明显",
                Confidence.LOW, cat = Category.CROSSCHECK,
                vec = listOf("median_ns=$median", "p95_ns=$p95", "ratio=$buf"),
                detail = "p95/中位数 超过 50 倍 → 可能存在按需解析的 hook",
                fp = "系统调度抖动、GC 都会造成长尾，单独看不成立")
        } else {
            ev("X.TIMING_HOOK", Severity.INFO, Verdict.PASS,
                "耗时分布正常", Confidence.MEDIUM,
                cat = Category.CROSSCHECK,
                vec = listOf("median_ns=$median", "ratio=$buf"))
        }
    } catch (t: Throwable) {
        ev("X.TIMING_HOOK", Severity.LOW, Verdict.UNKNOWN,
            "计时失败", cat = Category.CROSSCHECK)
    }

    private fun ev(
        id: String, sev: Severity, v: Verdict, title: String,
        conf: Confidence = Confidence.MEDIUM,
        cat: Category = Category.INTEGRITY,
        vec: List<String> = emptyList(), detail: String = "", fp: String = "",
    ) = Evidence(
        id = id, category = cat, severity = sev, confidence = conf,
        verdict = v, title = title, vectors = vec, detail = detail, fpNote = fp,
    )
}