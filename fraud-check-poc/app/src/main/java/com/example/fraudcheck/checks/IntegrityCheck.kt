package com.example.fraudcheck.checks

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import com.example.fraudcheck.BuildConfig
import com.example.fraudcheck.core.Signal
import com.example.fraudcheck.core.Trust
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import kotlinx.coroutines.tasks.await
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Server-verifiable checks. The client can only COLLECT these; the backend must verify them:
 *  - Play Integrity token -> decodeIntegrityToken on your server (the token is encrypted; the app cannot read it).
 *  - Key attestation chain -> validate to Google roots + revocation list on your server.
 */
object IntegrityCheck {

    data class Result(val signal: Signal, val payload: String?)

    fun requestHash(sessionId: String, signalsDigest: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest("$sessionId|$signalsDigest".toByteArray())
        return Base64.encodeToString(d, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /** Play Integrity standard request, bound to this session + signal payload through requestHash. */
    suspend fun playIntegrity(ctx: Context, requestHash: String): Result {
        val projectNumber = BuildConfig.CLOUD_PROJECT_NUMBER
        if (projectNumber == 0L) {
            return Result(Signal("integrity.play", "Play Integrity token", flagged = false, weight = 0,
                evidence = "not configured: set cloudProjectNumber in gradle.properties", trust = Trust.SERVER_VERIFIED), null)
        }
        return try {
            val manager = IntegrityManagerFactory.createStandard(ctx.applicationContext)
            // In production, prepare once at app start and reuse the provider; it is valid for a while.
            val provider = manager.prepareIntegrityToken(
                PrepareIntegrityTokenRequest.builder().setCloudProjectNumber(projectNumber).build()
            ).await()
            val token = provider.request(
                StandardIntegrityTokenRequest.builder().setRequestHash(requestHash).build()
            ).await().token()
            Result(Signal("integrity.play", "Play Integrity token", flagged = false, weight = 0,
                evidence = "token obtained (${token.length} chars). Send to backend -> decodeIntegrityToken; " +
                    "require PLAY_RECOGNIZED + MEETS_DEVICE_INTEGRITY + matching requestHash.",
                trust = Trust.SERVER_VERIFIED), token)
        } catch (e: Exception) {
            // No Play Store / Play services (typical of emulators and de-Googled farms) lands here.
            Result(Signal("integrity.play", "Play Integrity token unavailable", flagged = true, weight = 40,
                evidence = "${e.javaClass.simpleName}: ${e.message}", trust = Trust.SERVER_VERIFIED), null)
        }
    }

    /** Generates a hardware-backed key with an attestation challenge and reports the security level. */
    fun keyAttestation(ctx: Context, challenge: ByteArray): Result {
        val alias = "fraudcheck_attest"
        return try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(alias)) ks.deleteEntry(alias)
            val hasStrongBox = Build.VERSION.SDK_INT >= 28 &&
                ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
            fun generate(strongBox: Boolean) {
                val b = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setAttestationChallenge(challenge)
                if (strongBox && Build.VERSION.SDK_INT >= 28) b.setIsStrongBoxBacked(true)
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                    initialize(b.build()); generateKeyPair()
                }
            }
            try { generate(hasStrongBox) } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= 28 && e is StrongBoxUnavailableException) generate(false) else throw e
            }
            val chain = ks.getCertificateChain(alias).map { it as X509Certificate }
            val leaf = chain.first()
            val hasAttestationExt = leaf.getExtensionValue("1.3.6.1.4.1.11129.2.1.17") != null
            val key = ks.getKey(alias, null) as PrivateKey
            val info = KeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java)
            val level = if (Build.VERSION.SDK_INT >= 31) when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> "software"
                else -> "unknown-secure"
            } else @Suppress("DEPRECATION") if (info.isInsideSecureHardware) "hardware" else "software"
            val keyId = MessageDigest.getInstance("SHA-256").digest(leaf.publicKey.encoded)
                .joinToString("") { "%02x".format(it) }.take(16)
            val chainB64 = chain.joinToString(",") { Base64.encodeToString(it.encoded, Base64.NO_WRAP) }
            val weak = level == "software" || !hasAttestationExt || chain.size < 2
            Result(Signal("integrity.keystore", "Key attestation not hardware-backed", weak, weight = 35,
                evidence = "securityLevel=$level chainLength=${chain.size} attestationExt=$hasAttestationExt " +
                    "root=${chain.last().subjectX500Principal.name.take(60)} deviceKeyId=$keyId (verify chain on server)",
                trust = Trust.SERVER_VERIFIED), chainB64)
        } catch (e: Exception) {
            Result(Signal("integrity.keystore", "Key attestation failed", true, weight = 35,
                evidence = "${e.javaClass.simpleName}: ${e.message}", trust = Trust.SERVER_VERIFIED), null)
        }
    }
}
