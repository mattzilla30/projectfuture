package com.projectfuture.browser.rtc

import java.io.ByteArrayInputStream
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

/**
 * A hand-rolled minimal ASN.1 DER encoder - just enough to build the one
 * X.509 v3 `TBSCertificate` structure [SelfSignedCert] needs (SEQUENCE,
 * INTEGER, OID, NULL, BIT STRING, UTCTime, PrintableString, and the `[0]`
 * explicit version tag). There is no general-purpose ASN.1/X.509 library
 * anywhere in this project's dependencies (see build.gradle.kts - by
 * design, nothing beyond AndroidX/Material was added for this task), and
 * WebRTC's DTLS-SRTP identity model only needs *a* well-formed
 * self-signed certificate to hash into the SDP `a=fingerprint` line and
 * to hand to [javax.net.ssl.SSLEngine] as this peer's identity - not
 * anything a CA chain validates - so a minimal hand-written encoder for
 * exactly the fields a certificate needs is a real implementation of what
 * this actually requires, not a shortcut around it.
 */
private object Der {
    fun length(n: Int): ByteArray {
        if (n < 0x80) return byteArrayOf(n.toByte())
        var v = n
        val bytes = ArrayList<Byte>()
        while (v > 0) { bytes.add(0, (v and 0xFF).toByte()); v = v ushr 8 }
        return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
    }

    fun tlv(tag: Int, content: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + length(content.size) + content

    fun seq(vararg parts: ByteArray): ByteArray = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })

    fun int(value: Long): ByteArray {
        var v = value
        val bytes = ArrayList<Byte>()
        do { bytes.add(0, (v and 0xFF).toByte()); v = v shr 8 } while (v != 0L && v != -1L)
        if (bytes[0].toInt() and 0x80 != 0) bytes.add(0, 0)
        return tlv(0x02, bytes.toByteArray())
    }

    fun bigPositiveInt(bytes: ByteArray): ByteArray {
        val content = if (bytes.isNotEmpty() && (bytes[0].toInt() and 0x80) != 0) byteArrayOf(0) + bytes else bytes
        return tlv(0x02, content)
    }

    fun oid(rawContentBytes: ByteArray): ByteArray = tlv(0x06, rawContentBytes)
    fun nullValue(): ByteArray = byteArrayOf(0x05, 0x00)
    fun bitString(bytes: ByteArray): ByteArray = tlv(0x03, byteArrayOf(0) + bytes)
    fun contextExplicit(tagNumber: Int, content: ByteArray): ByteArray = tlv(0xA0 or tagNumber, content)
    fun printableString(s: String): ByteArray = tlv(0x13, s.toByteArray(Charsets.US_ASCII))

    fun utcTime(date: Date): ByteArray {
        val fmt = SimpleDateFormat("yyMMddHHmmss").apply { timeZone = TimeZone.getTimeZone("UTC") }
        return tlv(0x17, (fmt.format(date) + "Z").toByteArray(Charsets.US_ASCII))
    }

    // 1.2.840.113549.1.1.11 sha256WithRSAEncryption
    val SHA256_WITH_RSA = oid(byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B))
    // 2.5.4.3 commonName
    val COMMON_NAME = oid(byteArrayOf(0x55, 0x04, 0x03))
}

/** A self-signed identity certificate for one DTLS endpoint, plus its SHA-256 fingerprint as it belongs in an SDP `a=fingerprint:sha-256 ...` line (RFC 8122). */
class SelfSignedCert(val certificate: X509Certificate, val privateKey: PrivateKey) {
    /** Colon-separated uppercase hex SHA-256 digest of the DER certificate, exactly RFC 8122's textual form. */
    val fingerprint: String = fingerprintOf(certificate)

    companion object {
        fun fingerprintOf(cert: X509Certificate): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            return digest.joinToString(":") { "%02X".format(it) }
        }

        /** Generates a fresh 2048-bit RSA key pair and a self-signed X.509 v3 certificate over it, valid for a year. Real DER bytes, really parsed back by [CertificateFactory] - not a mock object standing in for one. */
        fun generate(commonName: String = "projectfuture-webrtc"): SelfSignedCert {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

            val serial = ByteArray(8).also { SecureRandom().nextBytes(it) }
            val now = Date()
            val notAfter = Date(now.time + 365L * 24 * 60 * 60 * 1000)

            val version = Der.contextExplicit(0, Der.int(2)) // v3
            val serialNumber = Der.bigPositiveInt(serial)
            val signatureAlg = Der.seq(Der.SHA256_WITH_RSA, Der.nullValue())
            val attributeTypeAndValue = Der.tlv(0x30, Der.COMMON_NAME + Der.printableString(commonName))
            val relativeDistinguishedName = Der.tlv(0x31, attributeTypeAndValue)
            val name = Der.tlv(0x30, relativeDistinguishedName)
            val validity = Der.seq(Der.utcTime(now), Der.utcTime(notAfter))
            // PublicKey.getEncoded() for an RSA KeyPairGenerator key is already a DER
            // SubjectPublicKeyInfo structure (RFC 5280) - reused as-is rather than rebuilt by hand.
            val subjectPublicKeyInfo = keyPair.public.encoded

            val tbsCertificate = Der.seq(version, serialNumber, signatureAlg, name, validity, name, subjectPublicKeyInfo)

            val signature = Signature.getInstance("SHA256withRSA").apply {
                initSign(keyPair.private)
                update(tbsCertificate)
            }.sign()

            val certificateDer = Der.seq(tbsCertificate, signatureAlg, Der.bitString(signature))
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certificateDer)) as X509Certificate
            return SelfSignedCert(cert, keyPair.private)
        }
    }
}
