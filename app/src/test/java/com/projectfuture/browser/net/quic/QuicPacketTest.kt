package com.projectfuture.browser.net.quic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class QuicPacketTest {

    @Test fun longHeaderRoundTripsThroughEncodeAndParse() {
        val dcid = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val scid = byteArrayOf(9, 8, 7, 6)
        val payloadLength = 50 // pretend ciphertext+tag length
        val header = QuicPacket.buildHeader(
            QuicPacket.TYPE_INITIAL, QuicPacket.VERSION_1, dcid, scid, token = null,
            packetNumber = 7, packetNumberLength = 2, payloadLength = payloadLength
        )
        // Append a fake payload so parseLongHeader's declared packet length is self-consistent.
        val datagram = header + ByteArray(payloadLength)
        val parsed = QuicPacket.parseLongHeader(datagram, 0)

        assertEquals(QuicPacket.TYPE_INITIAL, parsed.type)
        assertEquals(QuicPacket.VERSION_1, parsed.version)
        assertArrayEquals(dcid, parsed.destConnectionId)
        assertArrayEquals(scid, parsed.srcConnectionId)
        assertEquals(2, parsed.packetNumberLength)
        assertEquals(7L, parsed.packetNumber)
        assertEquals(header.size, parsed.payloadOffset)
        assertEquals(datagram.size, parsed.packetLength)
    }

    @Test fun handshakeTypeRoundTrips() {
        val dcid = byteArrayOf(1, 1, 1, 1)
        val scid = byteArrayOf(2, 2, 2, 2)
        val header = QuicPacket.buildHeader(
            QuicPacket.TYPE_HANDSHAKE, QuicPacket.VERSION_1, dcid, scid, token = null,
            packetNumber = 300, packetNumberLength = 4, payloadLength = 10
        )
        val parsed = QuicPacket.parseLongHeader(header + ByteArray(10), 0)
        assertEquals(QuicPacket.TYPE_HANDSHAKE, parsed.type)
        assertEquals(300L, parsed.packetNumber)
    }

    @Test fun initialPacketTokenRoundTrips() {
        val dcid = byteArrayOf(1)
        val scid = byteArrayOf(2)
        val token = byteArrayOf(0x11, 0x22, 0x33)
        val header = QuicPacket.buildHeader(
            QuicPacket.TYPE_INITIAL, QuicPacket.VERSION_1, dcid, scid, token = token,
            packetNumber = 1, packetNumberLength = 1, payloadLength = 5
        )
        val parsed = QuicPacket.parseLongHeader(header + ByteArray(5), 0)
        assertArrayEquals(token, parsed.token)
    }

    @Test fun packetNumberEncodingIsBigEndianFixedWidth() {
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x01, 0x2c), QuicPacket.encodePacketNumber(300, 4))
        assertArrayEquals(byteArrayOf(0x2c), QuicPacket.encodePacketNumber(300, 1)) // truncated, as real QUIC allows when unambiguous
    }
}
