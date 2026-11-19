package com.rootsentinel.util

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * 每设备身份（v2.23.0，协议 v3）：首次需要时在 Android
 * Keystore 里生成一对 EC P-256 密钥，私钥不可导出、只在本机
 * 用来给上报签名；公钥（SPKI）随上报带给服务端登记，服务端
 * 以公钥的 SHA-256（kid）识别同一台设备的多次上报。
 *
 * 设计口径（与隐私声明一致）：公钥不是硬件序列号，重置即
 * 换新身份；密钥等级（StrongBox/TEE/软件）只作自述随上报
 * 带出，服务端按「自述」处理。任何一步失败都返回 null，
 * 调用方回退 v2 通道——身份不可用绝不阻断扫描与上报。
 */
object Identity {

    private const val ALIAS = "rs_identity_v1"

    @Volatile private var cachedKid: String? = null
    @Volatile private var cachedPubB64: String? = null
    @Volatile private var cachedLevel: String? = null

    private fun keyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /** 确保密钥存在（不存在则生成）。成功返回 true。必须在 IO 线程调用。 */
    @Synchronized
    fun ensureKey(): Boolean = runCatching {
        val ks = keyStore()
        if (ks.containsAlias(ALIAS)) return true
        val spec = { strongBox: Boolean ->
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .apply { if (strongBox) setIsStrongBoxBacked(true) }
                .build()
        }
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        if (Build.VERSION.SDK_INT >= 28) {
            runCatching { gen.initialize(spec(true)); gen.generateKeyPair() }
                .getOrElse { gen.initialize(spec(false)); gen.generateKeyPair() }
        } else {
            gen.initialize(spec(false)); gen.generateKeyPair()
        }
        clearCache()
        true
    }.getOrDefault(false)

    private fun publicKeyEncoded(): ByteArray? = runCatching {
        keyStore().getCertificate(ALIAS)?.publicKey?.encoded
    }.getOrNull()

    /** kid = 公钥 SPKI 的 SHA-256（hex 小写）。无密钥时 null。 */
    fun kid(): String? {
        cachedKid?.let { return it }
        val spki = publicKeyEncoded() ?: return null
        val hex = MessageDigest.getInstance("SHA-256").digest(spki)
            .joinToString("") { "%02x".format(it) }
        cachedKid = hex
        return hex
    }

    /** 公钥 SPKI 的 Base64（标准字母表、带填充、无换行）。 */
    fun pubB64(): String? {
        cachedPubB64?.let { return it }
        val spki = publicKeyEncoded() ?: return null
        val b64 = Base64.encodeToString(spki, Base64.NO_WRAP)
        cachedPubB64 = b64
        return b64
    }

    /** 密钥等级自述：strongbox / tee / software / unknown。 */
    fun level(): String {
        cachedLevel?.let { return it }
        val lv = runCatching {
            val ks = keyStore()
            val priv = ks.getKey(ALIAS, null) as? PrivateKey ?: return@runCatching "unknown"
            val info = KeyFactory.getInstance(priv.algorithm, "AndroidKeyStore")
                .getKeySpec(priv, KeyInfo::class.java)
            when {
                // securityLevel 的取值是文档常量（0 软件 / 1 TEE /
                // 2 StrongBox / -1 未知安全级）；TEE 与软件两个具名
                // 常量在当前编译用的 android.jar 里不存在，直接用
                // 数值，避免引用不到符号。
                Build.VERSION.SDK_INT >= 31 -> when (info.securityLevel) {
                    2 -> "strongbox"
                    1 -> "tee"
                    0 -> "software"
                    else -> "unknown"
                }
                @Suppress("DEPRECATION") info.isInsideSecureHardware -> "tee"
                else -> "software"
            }
        }.getOrDefault("unknown")
        cachedLevel = lv
        return lv
    }

    /** 用设备私钥签名（SHA256withECDSA，返回 DER 编码）。失败 null。IO 线程调用。 */
    fun sign(msg: ByteArray): ByteArray? = runCatching {
        val priv = keyStore().getKey(ALIAS, null) as? PrivateKey ?: return null
        Signature.getInstance("SHA256withECDSA").run {
            initSign(priv)
            update(msg)
            sign()
        }
    }.getOrNull()

    /** 重置身份：删除密钥对，下次上报时生成新对并以新 kid 登记。 */
    @Synchronized
    fun reset(): Boolean = runCatching {
        val ks = keyStore()
        if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
        clearCache()
        true
    }.getOrDefault(false)

    /** 身份是否已就绪（密钥存在）。 */
    fun ready(): Boolean = runCatching { keyStore().containsAlias(ALIAS) }.getOrDefault(false)

    private fun clearCache() {
        cachedKid = null
        cachedPubB64 = null
        cachedLevel = null
    }

    /** 给设置页展示用：kid 前 8 位 + 等级。无身份时 null。 */
    fun statusSummary(context: Context): String? {
        if (!ready()) return null
        val k = kid() ?: return null
        return "kid ${k.take(8)}… · ${level()}"
    }
}
