package com.godotdroid.exporter.signer

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date

data class SigningCredentials(
    val privateKey: PrivateKey,
    val certificate: X509Certificate
)

/**
 * On-Device Keystore manager and APK Signer.
 * Generates an RSA-2048 keypair + self-signed X.509 certificate and signs APKs
 * using Android's V1 (JAR), V2, and V3 signing schemes via apksig.
 */
class ApkSignerEngine {

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    /**
     * Loads existing signing key from PKCS12 keystore or generates a new RSA-2048 keypair
     * and self-signed certificate valid for 25 years.
     */
    fun getOrCreateSigningKey(
        keystoreFile: File,
        password: String = "godotdroid",
        alias: String = "godotdroid"
    ): SigningCredentials {
        val passChars = password.toCharArray()

        if (keystoreFile.exists()) {
            try {
                val ks = KeyStore.getInstance("PKCS12")
                FileInputStream(keystoreFile).use { fis ->
                    ks.load(fis, passChars)
                }
                val key = ks.getKey(alias, passChars) as? PrivateKey
                val cert = ks.getCertificate(alias) as? X509Certificate
                if (key != null && cert != null) {
                    return SigningCredentials(key, cert)
                }
            } catch (_: Exception) {
                // If corrupted, re-generate below
            }
        }

        keystoreFile.parentFile?.mkdirs()

        // Generate RSA-2048 KeyPair
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val keyPair = kpg.generateKeyPair()

        // Create 25-year self-signed X.509 Certificate
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24 * 3600 * 1000L) // yesterday
        val notAfter = Date(now + 25L * 365 * 24 * 3600 * 1000L) // 25 years

        val subject = X500Name("CN=GodotDroid Standalone Exporter, O=GodotDroid, C=US")
        val serial = BigInteger.valueOf(now)

        val certBuilder = JcaX509v3CertificateBuilder(
            subject,
            serial,
            notBefore,
            notAfter,
            subject,
            keyPair.public
        )

        val signer = JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private)
        val cert = JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(certBuilder.build(signer))

        // Store into PKCS12 Keystore
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(alias, keyPair.private, passChars, arrayOf(cert))
        FileOutputStream(keystoreFile).use { fos ->
            ks.store(fos, passChars)
        }

        return SigningCredentials(keyPair.private, cert)
    }

    /**
     * Signs an unsigned APK with V1, V2, and V3 signature schemes.
     */
    fun signApk(
        unsignedApk: File,
        signedApk: File,
        credentials: SigningCredentials,
        minSdkVersion: Int = 26
    ): Boolean {
        if (!unsignedApk.exists()) {
            throw IllegalArgumentException("Unsigned APK does not exist: ${unsignedApk.absolutePath}")
        }

        if (signedApk.exists()) {
            signedApk.delete()
        }

        val signerConfig = ApkSigner.SignerConfig.Builder(
            "godotdroid",
            credentials.privateKey,
            listOf(credentials.certificate)
        ).build()

        val apkSigner = ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(unsignedApk)
            .setOutputApk(signedApk)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setMinSdkVersion(minSdkVersion)
            .build()

        apkSigner.sign()
        return signedApk.exists() && signedApk.length() > 0
    }

    /**
     * Verifies the APK's cryptographic signatures using ApkVerifier.
     */
    fun verifyApk(apkFile: File, minSdkVersion: Int = 26): Boolean {
        if (!apkFile.exists()) return false
        return try {
            val verifier = ApkVerifier.Builder(apkFile)
                .setMinCheckedPlatformVersion(minSdkVersion)
                .build()
            val result = verifier.verify()
            result.isVerified
        } catch (_: Exception) {
            false
        }
    }
}
