package com.rootsentinel.engine

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.rootsentinel.model.Category
import com.rootsentinel.model.Confidence
import com.rootsentinel.model.Evidence
import com.rootsentinel.model.Severity
import com.rootsentinel.model.Verdict
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate

/**
 * G 硬件级证明 —— Key Attestation。
 *
 * 说明（M1 阶段的范围）：
 *  - 完整实现需要 ASN.1 解析 KeyDescription（verifiedBootState / deviceLocked /
 *    osPatchLevel / verifiedBootKey 等），并对证书链逐级验签到内置的 Google 根。
 *  - 这里先做到「能拿到 attestation 扩展 + 能判断链根是否 Google」，
 *    足以区分「设备支持硬件证明」和「完全拿不到证明」。
 *  - 逐字段解析、链验证、吊销检查、Play Integrity 服务端对接放在 M2。
 *
 * 铁律（v2 包第四节第 4 条）：
 *  证书校验失败 **不等于** root 证据，只能记为 UNKNOWN 或弱信号。
 */
class AttestationDetector(private val context: Context) {

    private val keyStore: KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    fun collect(): List<Evidence> {
        val out = mutableListOf<Evidence>()

        val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val alias = "rs_attest_" + System.currentTimeMillis()

        val chain: Array<X509Certificate>? = try {
            generateAttestedKey(alias, challenge)
        } catch (t: Throwable) {
            null
        }

        if (chain == null || chain.isEmpty()) {
            out += Evidence(
                id = "G.ATTESTATION",
                category = Category.ATTESTATION,
                severity = Severity.LOW,
                confidence = Confidence.LOW,
                verdict = Verdict.UNKNOWN,
                title = "无法获取 Key Attestation 证书链",
                detail = "设备可能不支持硬件证明，或 Keystore 不可用",
                fpNote = "模拟器、部分老设备、以及被 hook 的 Keystore 都会走到这里；" +
                    "按规则不得据此判定 root",
            )
            // v2.22.42（P2-03）：提前返回也必须清理别名。
            // 密钥可能已经生成成功、只是证书链没取到，
            // 不删会在 KeyStore 里留下残留条目。
            runCatching { keyStore.deleteEntry(alias) }
            return out
        }

        // 1) 证书链结构
        out += Evidence(
            id = "G.CHAIN",
            category = Category.ATTESTATION,
            severity = Severity.INFO,
            confidence = Confidence.HIGH,
            verdict = Verdict.PASS,
            title = "Key Attestation 证书链长度 = ${chain.size}",
            vectors = chain.mapIndexed { i, c ->
                "cert[$i].subject=${c.subjectDN.name}"
            },
        )

        // 2) attestation 扩展是否存在
        val leaf = chain[0]
        val extOid = "1.3.6.1.4.1.11129.2.1.17"
        val extValue = leaf.getExtensionValue(extOid)

        if (extValue == null || extValue.isEmpty()) {
            out += Evidence(
                id = "G.ATTESTATION_EXT",
                category = Category.ATTESTATION,
                severity = Severity.MEDIUM,
                confidence = Confidence.MEDIUM,
                verdict = Verdict.UNKNOWN,
                title = "证书缺少 Key Attestation 扩展",
                detail = "可能是软件层伪造的证书链",
                fpNote = "软件级 KeyStore 实现同样没有该扩展，不等于 root",
            )
        } else {
            out += Evidence(
                id = "G.ATTESTATION_EXT",
                category = Category.ATTESTATION,
                severity = Severity.INFO,
                confidence = Confidence.HIGH,
                verdict = Verdict.PASS,
                title = "Key Attestation 扩展存在",
                vectors = listOf("extLength=${extValue.size}"),
                verifyNote = "需真机验证：字段解析（verifiedBootState/deviceLocked）在 M2 实现",
            )
        }

        // 3) 链根是否 Google（弱判据，只做记录）
        val root = chain.last()
        val rootName = root.subjectDN.name
        val isGoogleRoot = rootName.contains("Google", ignoreCase = true)
        out += Evidence(
            id = "G.ROOT_ANCHOR",
            category = Category.ATTESTATION,
            severity = if (isGoogleRoot) Severity.INFO else Severity.LOW,
            confidence = Confidence.MEDIUM,
            verdict = if (isGoogleRoot) Verdict.PASS else Verdict.UNKNOWN,
            title = if (isGoogleRoot) "证书链根为 Google" else "证书链根不是 Google",
            detail = "root=$rootName",
            fpNote = "国内厂商可能使用自有根证书；此处只记录，不判 root",
        )

        // 清理
        runCatching { keyStore.deleteEntry(alias) }
        return out
    }

    private fun generateAttestedKey(
        alias: String,
        challenge: ByteArray,
    ): Array<X509Certificate>? {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(
                java.security.spec.ECGenParameterSpec("secp256r1"),
            )
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(challenge)
            .build()

        val kpg = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            PROVIDER,
        )
        kpg.initialize(spec)
        kpg.generateKeyPair()

        val entry = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            ?: return null

        return entry.certificateChain
            ?.filterIsInstance<X509Certificate>()
            ?.toTypedArray()
    }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
    }
}