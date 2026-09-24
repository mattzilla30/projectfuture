package com.projectfuture.browser.net.quic

import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Derives and applies the QUIC Initial packet keys, per RFC 9001 sections 5.1-5.4. Initial
 * packets are the one packet-number space whose keys don't depend on a completed TLS handshake:
 * they're derived from nothing but the client's chosen Destination Connection ID and a salt
 * that's fixed by the QUIC version, using HKDF (RFC 5869) over SHA-256. That makes this the one
 * layer of "TLS 1.3 over QUIC" this project can implement for real without a TLS 1.3 state
 * machine - see [Http3TlsHandshake] for why the *rest* of the handshake (past Initial) is a
 * documented stub rather than faked.
 *
 * Both the AEAD (AES-128-GCM, protecting the packet payload) and header protection (AES-128-ECB,
 * masking the low bits of the first header byte and the packet number field so an on-path
 * observer can't trivially read/tamper with them) are built entirely from `javax.crypto`
 * primitives already used elsewhere in this project's TLS usage - no vendored crypto.
 */
object QuicInitialSecrets {
    // RFC 9001 section 5.2: the salt used to derive Initial secrets for QUIC version 1.
    private val INITIAL_SALT_V1 = hex("38762cf7f55934b34d179ae6a4c80cadccbb7f0a")

    private const val HMAC_SHA256 = "HmacSHA256"
    private const val KEY_LEN = 16 // AES-128
    private const val IV_LEN = 12
    private const val HP_LEN = 16

    data class DirectionalKeys(val key: ByteArray, val iv: ByteArray, val hp: ByteArray)
    data class InitialKeys(val client: DirectionalKeys, val server: DirectionalKeys)

    /** Derives both directions' Initial keys from the client-chosen destination connection ID. */
    fun derive(destConnectionId: ByteArray): InitialKeys {
        val initialSecret = hkdfExtract(INITIAL_SALT_V1, destConnectionId)
        val clientSecret = hkdfExpandLabel(initialSecret, "client in", ByteArray(0), 32)
        val serverSecret = hkdfExpandLabel(initialSecret, "server in", ByteArray(0), 32)
        return InitialKeys(directionalKeys(clientSecret), directionalKeys(serverSecret))
    }

    private fun directionalKeys(secret: ByteArray): DirectionalKeys {
        val key = hkdfExpandLabel(secret, "quic key", ByteArray(0), KEY_LEN)
        val iv = hkdfExpandLabel(secret, "quic iv", ByteArray(0), IV_LEN)
        val hp = hkdfExpandLabel(secret, "quic hp", ByteArray(0), HP_LEN)
        return DirectionalKeys(key, iv, hp)
    }

    /** RFC 5869 HKDF-Extract with HMAC-SHA256. */
    fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(salt, HMAC_SHA256))
        return mac.doFinal(ikm)
    }

    /** RFC 5869 HKDF-Expand with HMAC-SHA256. */
    fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(prk, HMAC_SHA256))
        val out = ByteArray(length)
        var t = ByteArray(0)
        var generated = 0
        var counter = 1
        while (generated < length) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val toCopy = minOf(t.size, length - generated)
            System.arraycopy(t, 0, out, generated, toCopy)
            generated += toCopy
            counter++
        }
        return out
    }

    /** TLS 1.3's HKDF-Expand-Label (RFC 8446 7.1), used by QUIC (RFC 9001 5.1) to name each derived secret. */
    fun hkdfExpandLabel(secret: ByteArray, label: String, context: ByteArray, length: Int): ByteArray {
        val fullLabel = "tls13 $label".toByteArray(StandardCharsets.US_ASCII)
        val info = java.io.ByteArrayOutputStream().apply {
            write((length ushr 8) and 0xFF); write(length and 0xFF)
            write(fullLabel.size)
            write(fullLabel)
            write(context.size)
            write(context)
        }.toByteArray()
        return hkdfExpand(secret, info, length)
    }

    /**
     * Encrypts+authenticates an Initial (or Handshake) packet payload with AES-128-GCM, per
     * RFC 9001 5.3. [nonce] is the packet's IV XORed with its packet number, per RFC 9000 5.3.
     */
    fun aeadSeal(keys: DirectionalKeys, packetNumber: Long, header: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val nonce = nonceFor(keys.iv, packetNumber)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(header)
        return cipher.doFinal(plaintext)
    }

    fun aeadOpen(keys: DirectionalKeys, packetNumber: Long, header: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val nonce = nonceFor(keys.iv, packetNumber)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(header)
        return cipher.doFinal(ciphertext)
    }

    private fun nonceFor(iv: ByteArray, packetNumber: Long): ByteArray {
        val nonce = iv.copyOf()
        for (i in 0 until 8) {
            nonce[nonce.size - 1 - i] = (nonce[nonce.size - 1 - i].toInt() xor ((packetNumber shr (8 * i)) and 0xFF).toInt()).toByte()
        }
        return nonce
    }

    /**
     * Computes the 5-byte header protection mask (RFC 9001 5.4.1) from a 16-byte ciphertext
     * sample, using AES-128-ECB (a single-block AES encryption of the sample under the hp key).
     */
    fun headerProtectionMask(hp: ByteArray, sample: ByteArray): ByteArray {
        require(sample.size == 16)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(hp, "AES"))
        return cipher.doFinal(sample).copyOf(5)
    }

    /**
     * Applies (or removes - XOR is its own inverse) header protection to an already-assembled
     * long-header Initial/Handshake packet, per RFC 9001 5.4.1. [pnOffset] is the byte offset of
     * the packet number field within [packet]; the 4-byte sample is taken starting 4 bytes after
     * that (assuming the largest legal packet-number length of 4, as the spec requires sampling
     * as if it were, before the real length is known on the receive side).
     */
    fun applyHeaderProtection(packet: ByteArray, pnOffset: Int, pnLength: Int, hp: ByteArray, isLongHeader: Boolean): ByteArray {
        val out = packet.copyOf()
        val sampleOffset = pnOffset + 4
        require(sampleOffset + 16 <= out.size) { "packet too short to sample for header protection" }
        val sample = out.copyOfRange(sampleOffset, sampleOffset + 16)
        val mask = headerProtectionMask(hp, sample)
        val firstByteMask = if (isLongHeader) 0x0F else 0x1F
        out[0] = (out[0].toInt() xor (mask[0].toInt() and firstByteMask)).toByte()
        for (i in 0 until pnLength) {
            out[pnOffset + i] = (out[pnOffset + i].toInt() xor mask[1 + i].toInt()).toByte()
        }
        return out
    }

    private fun hex(s: String): ByteArray {
        val clean = if (s.length % 2 == 1) "0$s" else s
        return ByteArray(clean.length / 2) { i -> ((Character.digit(clean[i * 2], 16) shl 4) + Character.digit(clean[i * 2 + 1], 16)).toByte() }
    }
}
