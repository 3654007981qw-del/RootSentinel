package com.rootsentinel.util

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate

/**
 * Android Key Attestation 解析（M10）
 *
 * 这是唯一能「密码学证明」启动链状态的本地手段。
 *
 * 原理：
 *   向 AndroidKeyStore 申请一个带 attestationChallenge 的密钥 →
 *   TEE / StrongBox 会在证书里嵌入一段由硬件签名的 KeyDescription →
 *   里面包含 verifiedBootState / deviceLocked / verifiedBootHash 等字段。
 *
 *   这段数据由硬件密钥签名，用户态伪造不了（除非把 TEE 也攻破）。
 *   所以它能识别「resetprop 改 ro.boot.verifiedbootstate」这类伪装。
 *
 * 结构（ASN.1，OID 1.3.6.1.4.1.11129.2.1.17）：
 *
 *   KeyDescription ::= SEQUENCE {
 *     attestationVersion       INTEGER,
 *     attestationSecurityLevel ENUMERATED,   ← Software(0) / TEE(1) / StrongBox(2)
 *     keymasterVersion         INTEGER,
 *     keymasterSecurityLevel   ENUMERATED,
 *     attestationChallenge     OCTET STRING,
 *     uniqueId                 OCTET STRING,
 *     softwareEnforced         AuthorizationList,
 *     teeEnforced              AuthorizationList,
 *   }
 *
 *   AuthorizationList 里用 context-specific 标签（高标签号形式）：
 *     [702] osVersion          INTEGER
 *     [704] deviceLocked       BOOLEAN
 *     [709] verifiedBootState  ENUMERATED
 *     [710] verifiedBootHash   OCTET STRING
 *     [718] vendorPatchLevel   INTEGER
 *     [719] bootPatchLevel     INTEGER
 *
 * 注意：没有第三方 ASN.1 库，下面是手写的极简 DER 读取器，
 * 只处理这个结构会用到的类型。
 */
object KeyAttestation {

    private const val ATTESTATION_OID = "1.3.6.1.4.1.11129.2.1.17"

    /**
     * 受信任的硬件证明根证书 SHA-256 指纹（证书 DER 本身，小写 hex）。
     *
     * v2.22.42：钉扎正式启用。以下指纹由构建方在本机从 Google 官方
     * 端点 https://android.googleapis.com/attestation/root 下载的证书
     * DER 直接计算（2026-11-14），不是凭记忆填写：
     *   ① RSA 根（serial f92009e853b6b045，2022-03 → 2042-03）
     *   ② EC 根「Key Attestation CA1」（2025 年启用的新一代证明根，
     *      2025-07 → 2035-07）
     * 链根指纹不在此集合内 → 未锚定 → cryptoTrusted 不成立。
     * 空集**不再**放行（v2.22.41 及之前空集 = 只看 Subject 的弱锚定，
     * 自签伪造链能通过，属 P1 缺陷）。使用 OEM 自有证明根的设备其链根
     * 不在集合内，证明只记 HIGH 不给 VERIFIED —— 已知取舍，后续以
     * 可维护的 OEM 根集合扩展，不要把空集放行加回来。
     */
    private val TRUSTED_ROOT_CERT_SHA256: Set<String> = setOf(
        "cedb1cb6dc896ae5ec797348bce9286753c2b38ee71ce0fbe34a9a1248800dfc",
        "6d9db4ce6c5c0b293166d08986e05774a8776ceb525d9e4329520de12ba4bcc0",
    )

    // 标签常量：context-specific [x] 的高标签号编码
    // [702] = 0x2BE → 0xBF 0x85 0x3E
    private const val TAG_OS_VERSION        = 0xBF853E
    private const val TAG_DEVICE_LOCKED     = 0xBF8540
    private const val TAG_VERIFIED_BOOT_STATE = 0xBF8545
    private const val TAG_VERIFIED_BOOT_HASH  = 0xBF8546
    private const val TAG_VENDOR_PATCH      = 0xBF854E
    private const val TAG_BOOT_PATCH        = 0xBF854F

    data class Result(
        val supported: Boolean = false,
        val chainLength: Int = 0,
        /** 证书里携带的 attestationChallenge 原文 */
        val challenge: ByteArray? = null,
        /** 0 = Software, 1 = TEE, 2 = StrongBox（attestationSecurityLevel） */
        val securityLevel: Int = -1,
        /** keymasterSecurityLevel，与 securityLevel 交叉核对（v2.22.42） */
        val keymasterSecurityLevel: Int = -1,
        /** 0 = Verified, 1 = SelfSigned, 2 = Unverified, 3 = Failed */
        val verifiedBootState: Int = -1,
        val deviceLocked: Boolean? = null,
        val verifiedBootHash: ByteArray? = null,
        val osVersion: Int = -1,
        val patchLevel: Int = -1,
        /**
         * challenge 是否与本次请求一致。
         *
         * null = 没有解析到（老设备/结构异常），false = 明确不一致 → 证书是重放的。
         * 这一项不做校验，任何伪造者都可以把上次抓到的合法证书直接回放，
         * 里面照样是 Verified + locked。
         */
        val challengeMatch: Boolean? = null,
        /**
         * 证书链验签结果。
         * null = 未验证（缺根证书），true = 逐级签名校验通过，false = 验签失败。
         */
        val chainVerified: Boolean? = null,
        /** 链根是否是已知的 Google 硬件证明根 */
        val rootIsGoogle: Boolean = false,
        /** 密码学可信度：只有「链验签通过 + challenge 一致 + TEE/StrongBox」才为 true */
        val cryptoTrusted: Boolean = false,
        /** 启动状态字段（deviceLocked / bootState / bootHash）是否来自 teeEnforced */
        val bootFieldsFromTee: Boolean = false,
        /** 链根证书的 SHA-256 指纹（小写 hex），根锚钉扎与排查用 */
        val rootCertSha256: String? = null,
        val error: String? = null,
    ) {
        fun securityLevelName(): String = when (securityLevel) {
            0 -> "Software"
            1 -> "TEE"
            2 -> "StrongBox"
            else -> "Unknown($securityLevel)"
        }

        fun bootStateName(): String = when (verifiedBootState) {
            0 -> "Verified"
            1 -> "SelfSigned"
            2 -> "Unverified"
            3 -> "Failed"
            else -> "Unknown($verifiedBootState)"
        }

        /**
         * 与 [verifiedBootState] 一一对应的 Android 系统属性值。
         *
         * 这两套枚举**不是同一套字符串**，之前直接拿 "green" 和 "verified" 比较，
         * 导致凡是 ROM 提供了 verifiedBootState 的设备 100% 误报（见 v2.4.0 修复）。
         *
         *   Verified(0)   → green
         *   SelfSigned(1) → yellow
         *   Unverified(2) → orange
         *   Failed(3)     → red
         */
        fun bootStatePropName(): String = when (verifiedBootState) {
            0 -> "green"
            1 -> "yellow"
            2 -> "orange"
            3 -> "red"
            else -> ""
        }
    }

    fun probe(context: Context): Result {
        val alias = "rs_attest_" + System.currentTimeMillis()
        var ks: KeyStore? = null

        return try {
            // ---------- 1. 生成带 challenge 的密钥 ----------
            val kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")

            val challenge = ByteArray(32)
            SecureRandom().nextBytes(challenge)

            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAttestationChallenge(challenge)
                .build()

            kpg.initialize(spec)
            kpg.generateKeyPair()

            // ---------- 2. 取证书链 ----------
            ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val chain = ks.getCertificateChain(alias)
            if (chain.isNullOrEmpty()) {
                return Result(error = "证书链为空（设备可能不支持 attestation）")
            }

            val leaf = chain[0] as? X509Certificate
                ?: return Result(chainLength = chain.size, error = "leaf 不是 X509")

            // ---------- 3. 取 attestation extension ----------
            val extRaw = leaf.getExtensionValue(ATTESTATION_OID)
                ?: return Result(
                    supported = false,
                    chainLength = chain.size,
                    error = "证书里没有 attestation 扩展",
                )

            // ---------- 4. 解析 ----------
            val parsed = parseKeyDescription(extRaw)
                ?: return Result(
                    supported = true,
                    chainLength = chain.size,
                    error = "attestation 扩展解析失败",
                )

            // ---------- 5. challenge 一致性 ----------
            // 证书里的 attestationChallenge 必须等于本次随机生成的值。
            // 不比对的话，攻击者可以把上次抓到的合法证书整段回放 ——
            // 里面同样写着 Verified + deviceLocked=true。
            val challengeMatch = parsed.challenge?.let { got ->
                got.size == challenge.size && got.contentEquals(challenge)
            }

            // ---------- 6. 证书链验签 ----------
            val chainVerified = verifyChain(chain)

            // ---------- 7. 链根是否 Google ----------
            val rootIsGoogle = chain.isNotEmpty() && runCatching {
                (chain.last() as? X509Certificate)
                    ?.subjectDN?.name?.contains("Google", ignoreCase = true) == true
            }.getOrDefault(false)

            // ---------- 8. 强制要求硬件级证明 ----------
            // securityLevel: 0 = Software, 1 = TEE, 2 = StrongBox
            // 软件级证明（Software）由用户态实现，任何 root 框架都能伪造，
            // 不能作为启动链可信的依据。
            // v2.22.42：keymaster 交叉核对。attestationSecurityLevel
            // 声称 TEE/StrongBox，但 keymasterSecurityLevel = Software(0)
            // 时两字段矛盾 —— 至少有一侧被伪造，硬件级不成立。
            // keymaster 解析不到（-1，老式/异常编码）时不单独否决，
            // 由 attestation 侧与链校验兜底。
            val hardwareBacked = parsed.securityLevel >= 1 &&
                parsed.keymasterSecurityLevel != 0

            // v2.22.41：链根锚定也进公式。
            // 之前 rootIsGoogle 只算不用 —— verifyChain 只证明「链自洽」
            // （含根证书自签自验），一份 Subject 里写着 Google 的自造链
            // 照样能让 cryptoTrusted = true。
            // v2.22.42：锚定 = 链根指纹命中钉扎集合（见
            // TRUSTED_ROOT_CERT_SHA256）。空集不再放行；rootIsGoogle
            // 降为信息字段（展示与排查），不参与信任判定。
            val rootCertSha256 = (chain.lastOrNull() as? X509Certificate)?.let { root ->
                runCatching {
                    hex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(root.encoded))
                }.getOrNull()
            }
            val rootAnchored = rootCertSha256 != null &&
                rootCertSha256 in TRUSTED_ROOT_CERT_SHA256

            val cryptoTrusted = hardwareBacked &&
                challengeMatch == true &&
                chainVerified == true &&
                rootAnchored

            parsed.copy(
                supported = true,
                chainLength = chain.size,
                challengeMatch = challengeMatch,
                chainVerified = chainVerified,
                rootIsGoogle = rootIsGoogle,
                cryptoTrusted = cryptoTrusted,
                rootCertSha256 = rootCertSha256,
            )
        } catch (t: Throwable) {
            Result(error = "${t.javaClass.simpleName}: ${t.message}")
        } finally {
            runCatching { ks?.deleteEntry(alias) }
        }
    }

    // ================================================================ DER 读取
    private class Der(private val d: ByteArray, private var p: Int = 0) {

        fun eof() = p >= d.size

        /** 返回 tag 的完整数值（多字节标签会合并成 24 位整数） */
        fun readTag(): Int {
            val b0 = d[p].toInt() and 0xFF
            p++
            if ((b0 and 0x1F) != 0x1F) return b0

            var tag = b0
            var count = 0
            while (p < d.size && count < 3) {
                val c = d[p].toInt() and 0xFF
                p++
                tag = (tag shl 8) or c
                count++
                if ((c and 0x80) == 0) break
            }
            return tag
        }

        fun readLength(): Int {
            if (p >= d.size) return -1
            val b = d[p].toInt() and 0xFF
            p++
            if ((b and 0x80) == 0) return b
            val n = b and 0x7F
            if (n <= 0 || n > 4) return -1
            var v = 0
            repeat(n) {
                if (p >= d.size) return -1
                v = (v shl 8) or (d[p].toInt() and 0xFF)
                p++
            }
            return v
        }

        fun readIntValue(len: Int): Int {
            var v = 0
            repeat(len) {
                if (p >= d.size) return v
                v = (v shl 8) or (d[p].toInt() and 0xFF)
                p++
            }
            return v
        }

        fun readRaw(len: Int): ByteArray {
            val n = minOf(len, d.size - p)
            val out = d.copyOfRange(p, p + n)
            p += n
            return out
        }

        /** 读一个 TLV，返回 (tag, valueBytes) */
        fun readTlv(): Pair<Int, ByteArray>? {
            if (eof()) return null
            val tag = readTag()
            val len = readLength()
            if (len < 0) return null
            val v = readRaw(len)
            return tag to v
        }

        fun skip(len: Int) {
            p = minOf(d.size, p + len)
        }

        fun peek(): Int = if (eof()) -1 else (d[p].toInt() and 0xFF)
    }

    /** 去掉两层 OCTET STRING，拿到里面的 SEQUENCE 内容 */
    private fun unwrapOctetStrings(raw: ByteArray): ByteArray {
        var cur = raw
        repeat(2) {
            val der = Der(cur)
            val tlv = der.readTlv() ?: return cur
            if ((tlv.first and 0xFF) == 0x04) {   // OCTET STRING
                cur = tlv.second
            }
        }
        return cur
    }

    private fun parseKeyDescription(extRaw: ByteArray): Result? {
        val body = unwrapOctetStrings(extRaw)
        val der = Der(body)

        // 外层 SEQUENCE
        val outer = der.readTlv() ?: return null
        if ((outer.first and 0xFF) != 0x30) return null     // SEQUENCE

        val seq = Der(outer.second)
        var securityLevel = -1
        var keymasterSecurityLevel = -1
        var osVersion = -1
        var patchLevel = -1
        var deviceLocked: Boolean? = null
        var bootHash: ByteArray? = null
        var bootState = -1

        var challengeBytes: ByteArray? = null

        // v2.22.41：softwareEnforced(6) 与 teeEnforced(7) 分开解析。
        // 之前两者合并进同一组字段，启动状态字段的来源被抹掉 ——
        // 软件层自报的 deviceLocked 会被当成 TEE 事实采信。
        var softParsed = AuthParsed()
        var teeParsed = AuthParsed()

        var index = 0
        while (!seq.eof()) {
            val tlv = seq.readTlv() ?: break
            val tag = tlv.first and 0xFF
            val value = tlv.second

            when (index) {
                // 4: attestationChallenge OCTET STRING
                4 -> {
                    if (tag == 0x04) challengeBytes = value
                }
                // 1: attestationVersion  INTEGER
                // 2: attestationSecurityLevel ENUMERATED  ← 取这个
                1 -> {
                    if (tag == 0x0A) {   // ENUMERATED
                        securityLevel = Der(value).readIntValue(value.size)
                    }
                }
                // 3: keymasterVersion  INTEGER
                // 4: keymasterSecurityLevel ENUMERATED ← v2.22.42 新增：
                // 与 attestationSecurityLevel 交叉核对（见 probe 的
                // hardwareBacked）。attestation 自称 TEE 而 keymaster
                // 却在 Software 层 = 两字段互相矛盾，证明不可信。
                3 -> {
                    if (tag == 0x0A) {   // ENUMERATED
                        keymasterSecurityLevel = Der(value).readIntValue(value.size)
                    }
                }
                // 6: softwareEnforced AuthorizationList
                6 -> {
                    if (tag == 0x30) softParsed = parseAuthList(value)
                }
                // 7: teeEnforced AuthorizationList
                7 -> {
                    if (tag == 0x30) teeParsed = parseAuthList(value)
                }
            }
            index++
        }

        // osVersion / patchLevel 是信息字段，两个列表合并取有值的一侧即可
        if (softParsed.osVersion > 0) osVersion = softParsed.osVersion
        if (teeParsed.osVersion > 0) osVersion = teeParsed.osVersion
        if (softParsed.patchLevel > 0) patchLevel = softParsed.patchLevel
        if (teeParsed.patchLevel > 0) patchLevel = teeParsed.patchLevel

        // 启动状态三件套（deviceLocked / verifiedBootState / verifiedBootHash）
        // 是信任关键字段，必须按来源采信：
        //   securityLevel >= 1（TEE / StrongBox）时只认 teeEnforced 里的值；
        //   只出现在 softwareEnforced 里 = 软件层自说自话，一律丢弃，
        //   让下游按「字段缺失」走 UNKNOWN，而不是把软件声明当 TEE 事实。
        val teeHasBoot = teeParsed.deviceLocked != null ||
            teeParsed.bootState >= 0 || teeParsed.bootHash != null
        if (securityLevel >= 1) {
            deviceLocked = teeParsed.deviceLocked
            bootState = teeParsed.bootState
            bootHash = teeParsed.bootHash
        } else {
            deviceLocked = softParsed.deviceLocked ?: teeParsed.deviceLocked
            bootState = if (softParsed.bootState >= 0) softParsed.bootState
            else teeParsed.bootState
            bootHash = softParsed.bootHash ?: teeParsed.bootHash
        }

        return Result(
            challenge = challengeBytes,
            securityLevel = securityLevel,
            keymasterSecurityLevel = keymasterSecurityLevel,
            verifiedBootState = bootState,
            deviceLocked = deviceLocked,
            verifiedBootHash = bootHash,
            osVersion = osVersion,
            patchLevel = patchLevel,
            bootFieldsFromTee = teeHasBoot,
        )
    }

    private class AuthParsed(
        var osVersion: Int = -1,
        var patchLevel: Int = -1,
        var deviceLocked: Boolean? = null,
        var bootHash: ByteArray? = null,
        var bootState: Int = -1,
    )

    private fun parseAuthList(body: ByteArray): AuthParsed {
        val out = AuthParsed()
        val der = Der(body)

        while (!der.eof()) {
            val tlv = der.readTlv() ?: break
            val tag = tlv.first
            val value = tlv.second

            when (tag) {
                TAG_OS_VERSION -> {
                    out.osVersion = Der(value).readIntValue(value.size)
                }
                TAG_VERIFIED_BOOT_STATE -> {
                    out.bootState = Der(value).readIntValue(value.size)
                }
                TAG_DEVICE_LOCKED -> {
                    if (value.isNotEmpty()) {
                        out.deviceLocked = (value[0].toInt() != 0)
                    }
                }
                TAG_VERIFIED_BOOT_HASH -> {
                    if (value.size == 32) out.bootHash = value
                }
                TAG_VENDOR_PATCH, TAG_BOOT_PATCH -> {
                    val v = Der(value).readIntValue(value.size)
                    if (v > out.patchLevel) out.patchLevel = v
                }
            }
        }
        return out
    }

    /**
     * 逐级验证证书链签名。
     *
     * 之前完全没验签 —— 只看「链长」和「subjectDN 里有没有 Google 字样」。
     * 这两项都是纯文本，改一个字就能骗过。
     *
     * 现在的做法：
     *   · chain[i] 用 chain[i+1] 的公钥验签
     *   · 链尾（自签根）用自己的公钥验自己的签名
     * 只要任一级失败就返回 false。
     *
     * 注意：这里**不**校验证书有效期，也不做吊销检查 ——
     * 设备时钟可能被改，吊销需要联网。这两项留作后续。
     *
     * @return true = 全部验签通过；false = 任一级失败；null = 无法验证（链为空）
     */
    private fun verifyChain(chain: Array<out java.security.cert.Certificate>): Boolean? {
        if (chain.isEmpty()) return null
        return try {
            // v2.22.42：路径校验补齐 —— 之前只做逐级验签，
            // 过期证书、非 CA 证书充当签发者一样能过。
            // 现在每张证书必须在有效期内；除叶子外每张签发者
            // 必须是 CA（BasicConstraints >= 0），且 KeyUsage
            // 若存在必须含 keyCertSign。
            for (c in chain) {
                (c as? X509Certificate)?.checkValidity() ?: return false
            }
            for (i in 0 until chain.size - 1) {
                val cur = chain[i] as? X509Certificate ?: return false
                val issuer = chain[i + 1] as? X509Certificate ?: return false
                if (issuer.basicConstraints < 0) return false
                val ku = issuer.keyUsage
                if (ku != null && (ku.size <= 5 || !ku[5])) return false
                cur.verify(issuer.publicKey)
            }
            // 自签根：自己验自己，且根自身也必须是 CA
            val root = chain.last() as? X509Certificate ?: return false
            if (root.basicConstraints < 0) return false
            root.verify(root.publicKey)
            true
        } catch (t: Throwable) {
            // SignatureException / InvalidKeyException / CertificateExpiredException 等
            false
        }
    }

    /** 把 verifiedBootHash 转成十六进制，用于展示 / 对比 */
    fun hex(bytes: ByteArray?): String {
        if (bytes == null) return "null"
        return bytes.joinToString("") { String.format("%02x", it) }
    }

    /** 供 UI 展示：把 Result 压成几行文本 */
    fun describe(r: Result): String = buildString {
        if (r.error != null) append("error=").append(r.error).append('\n')
        append("supported=").append(r.supported).append('\n')
        append("chain=").append(r.chainLength).append('\n')
        append("securityLevel=").append(r.securityLevelName()).append('\n')
        append("bootState=").append(r.bootStateName()).append('\n')
        append("deviceLocked=").append(r.deviceLocked).append('\n')
        append("osVersion=").append(r.osVersion).append('\n')
        append("patchLevel=").append(r.patchLevel).append('\n')
        append("challengeMatch=").append(r.challengeMatch).append('\n')
        append("chainVerified=").append(r.chainVerified).append('\n')
        append("rootIsGoogle=").append(r.rootIsGoogle).append('\n')
        append("cryptoTrusted=").append(r.cryptoTrusted).append('\n')
        if (r.verifiedBootHash != null) {
            append("bootHash=").append(hex(r.verifiedBootHash).take(16)).append("…")
        }
        if (Build.VERSION.SDK_INT < 24) append("\n(API < 24 不支持 attestation)")
    }
}