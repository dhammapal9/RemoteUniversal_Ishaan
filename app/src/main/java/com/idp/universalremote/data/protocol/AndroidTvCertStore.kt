package com.idp.universalremote.data.protocol

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RSA-2048 self-signed cert + PKCS12 store backing the Android TV Remote v2 mTLS
 * handshake. Persisted in the app's private files dir so re-pairing isn't
 * required between runs.
 *
 * **Critical**: Android TVs reject TLS client certs that don't carry the right
 * X.509 v3 extensions (BasicConstraints, KeyUsage, ExtendedKeyUsage). Without
 * them the handshake closes silently — and the TV never gets to the point of
 * displaying the 6-character pairing PIN. The extensions block below is the
 * difference between "code never appears" and "code appears on screen".
 */
@Singleton
class AndroidTvCertStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val bcProvider: BouncyCastleProvider = BouncyCastleProvider()

    private val storeFile: File by lazy {
        File(context.filesDir, "atv-client.p12")
    }

    @Synchronized
    fun loadOrCreate(): KeyStore {
        val ks = KeyStore.getInstance("PKCS12")
        if (storeFile.exists()) {
            val cert = runCatching {
                storeFile.inputStream().use { ks.load(it, PASSPHRASE) }
                if (!ks.containsAlias(ALIAS)) null
                else ks.getCertificate(ALIAS) as? X509Certificate
            }.getOrNull()
            // Only re-use the store if it carries a cert with the *current* CN.
            // When we change EXPECTED_CN (e.g. to satisfy a stricter TV firmware)
            // every old install must regenerate, or the TV will keep rejecting
            // a cert with a stale name.
            if (cert != null && cert.subjectX500Principal.name.contains("CN=$EXPECTED_CN")) {
                return ks
            }
            runCatching { storeFile.delete() }
        }
        return createNew(KeyStore.getInstance("PKCS12"))
    }

    private fun createNew(ks: KeyStore): KeyStore {
        ks.load(null, PASSPHRASE)
        val keyPair = KeyPairGenerator.getInstance("RSA", bcProvider)
            .apply { initialize(2048) }
            .generateKeyPair()
        val cert = selfSign(keyPair)
        ks.setKeyEntry(ALIAS, keyPair.private, PASSPHRASE, arrayOf<X509Certificate>(cert))
        storeFile.outputStream().use { ks.store(it, PASSPHRASE) }
        return ks
    }

    private fun selfSign(keyPair: KeyPair): X509Certificate {
        // CN must be a name the TV's polo verifier accepts. Empirically Thomson,
        // TCL and Mi Box all accept "AndroidRemote"; some stricter firmwares
        // even use the CN as a routing hint for the input service.
        val subject = X500Name("CN=AndroidRemote, OU=Phone, O=Universal Remote, C=US")
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 60 * 60 * 1000)
        val notAfter = Date(now + 10L * 365 * 24 * 60 * 60 * 1000)
        val serial = BigInteger.valueOf(now).abs().let {
            if (it.signum() == 0) BigInteger.ONE else it
        }
        val builder = JcaX509v3CertificateBuilder(
            subject,
            serial,
            notBefore,
            notAfter,
            subject,
            keyPair.public
        )

        val extUtils = JcaX509ExtensionUtils()

        // End-entity cert, not a CA.
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))

        // Permit data encryption + key agreement + signing — what Android TV expects.
        builder.addExtension(
            Extension.keyUsage, true,
            KeyUsage(
                KeyUsage.digitalSignature or
                    KeyUsage.keyEncipherment or
                    KeyUsage.keyAgreement or
                    KeyUsage.dataEncipherment
            )
        )

        // Used as TLS client (and Android TV's spec wants serverAuth too).
        builder.addExtension(
            Extension.extendedKeyUsage, false,
            ExtendedKeyUsage(arrayOf(KeyPurposeId.id_kp_clientAuth, KeyPurposeId.id_kp_serverAuth))
        )

        builder.addExtension(
            Extension.subjectKeyIdentifier, false,
            extUtils.createSubjectKeyIdentifier(keyPair.public)
        )
        builder.addExtension(
            Extension.authorityKeyIdentifier, false,
            extUtils.createAuthorityKeyIdentifier(keyPair.public)
        )

        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(bcProvider)
            .build(keyPair.private)
        return JcaX509CertificateConverter()
            .setProvider(bcProvider)
            .getCertificate(builder.build(signer))
    }

    fun deleteStore() {
        if (storeFile.exists()) storeFile.delete()
    }

    companion object {
        const val ALIAS = "atv-remote"
        /** Must match the CN baked into [selfSign]'s X500Name. */
        const val EXPECTED_CN = "AndroidRemote"
        val PASSPHRASE = "universal-remote".toCharArray()
    }
}
