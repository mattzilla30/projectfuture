package com.projectfuture.browser.net.quic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuicInitialSecretsTest {

    @Test fun clientAndServerKeysDifferButAreDeterministicForTheSameDcid() {
        val dcid = byteArrayOf(0x83.toByte(), 0x94.toByte(), 0xc8.toByte(), 0xf0.toByte(), 0x3e, 0x51, 0x57, 0x08)
        val a = QuicInitialSecrets.derive(dcid)
        val b = QuicInitialSecrets.derive(dcid)
        assertArrayEquals(a.client.key, b.client.key)
        assertArrayEquals(a.server.key, b.server.key)
        assertFalse(a.client.key.contentEquals(a.server.key))
        assertEquals(16, a.client.key.size)
        assertEquals(12, a.client.iv.size)
        assertEquals(16, a.client.hp.size)
    }

    @Test fun differentDcidsProduceDifferentKeys() {
        val a = QuicInitialSecrets.derive(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        val b = QuicInitialSecrets.derive(byteArrayOf(8, 7, 6, 5, 4, 3, 2, 1))
        assertFalse(a.client.key.contentEquals(b.client.key))
    }

    @Test fun aeadSealThenOpenRecoversPlaintext() {
        val keys = QuicInitialSecrets.derive(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)).client
        val header = "fake-quic-header".toByteArray()
        val plaintext = "CRYPTO frame payload pretending to be a ClientHello".toByteArray()
        val sealed = QuicInitialSecrets.aeadSeal(keys, packetNumber = 2, header = header, plaintext = plaintext)
        assertEquals(plaintext.size + 16, sealed.size) // GCM appends a 16-byte tag
        val opened = QuicInitialSecrets.aeadOpen(keys, packetNumber = 2, header = header, ciphertext = sealed)
        assertArrayEquals(plaintext, opened)
    }

    @Test fun aeadOpenFailsIfHeaderTampered() {
        val keys = QuicInitialSecrets.derive(byteArrayOf(9, 9, 9, 9)).client
        val sealed = QuicInitialSecrets.aeadSeal(keys, 0, "header-a".toByteArray(), "hello".toByteArray())
        try {
            QuicInitialSecrets.aeadOpen(keys, 0, "header-b".toByteArray(), sealed)
            throw AssertionError("expected AEAD authentication failure")
        } catch (_: javax.crypto.AEADBadTagException) {
            // expected: tampering with the associated data must be detected
        }
    }

    @Test fun headerProtectionRoundTripsAndRecoversOriginalBytes() {
        val keys = QuicInitialSecrets.derive(byteArrayOf(1, 1, 1, 1)).client
        // A packet needs at least pnOffset + 4 (assumed max pn length) + 16 (sample) bytes.
        val original = ByteArray(64) { it.toByte() }
        original[0] = 0xC3.toByte() // long header, fixed bit set, pn length nibble = 3 (4 bytes)
        val pnOffset = 20
        val protected = QuicInitialSecrets.applyHeaderProtection(original, pnOffset, 4, keys.hp, isLongHeader = true)
        assertFalse(protected.contentEquals(original)) // header protection actually changed something
        val unprotected = QuicInitialSecrets.applyHeaderProtection(protected, pnOffset, 4, keys.hp, isLongHeader = true)
        assertArrayEquals(original, unprotected) // XOR mask is its own inverse
    }

    @Test fun http3ClientBuildsAWellFormedProtectedInitialPacket() {
        val client = Http3Client("example.com", 443)
        val dcid = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val scid = byteArrayOf(9, 9, 9, 9, 9, 9, 9, 9)
        val packet = client.buildClientInitialPacket(dcid, scid, packetNumber = 0, cryptoPayload = byteArrayOf(1, 2, 3, 4))
        // A client Initial datagram must be padded to at least 1200 bytes (RFC 9000 14.1).
        assertTrue(packet.size >= QuicPacket.MIN_INITIAL_DATAGRAM_SIZE)
        // First byte: long header + fixed bit set (top two bits 11).
        assertEquals(0xC0, packet[0].toInt() and 0xC0)
    }
}
