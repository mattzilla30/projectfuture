package com.projectfuture.browser.net.quic

import org.junit.Assert.assertEquals
import org.junit.Test

class QuicVarIntTest {

    @Test fun encodesOneByteForSmallValues() {
        assertEquals(1, QuicVarInt.encode(0).size)
        assertEquals(1, QuicVarInt.encode(63).size)
    }

    @Test fun picksShortestLengthAtEachBoundary() {
        assertEquals(2, QuicVarInt.encode(64).size)
        assertEquals(2, QuicVarInt.encode(16383).size)
        assertEquals(4, QuicVarInt.encode(16384).size)
        assertEquals(4, QuicVarInt.encode(1073741823).size)
        assertEquals(8, QuicVarInt.encode(1073741824).size)
    }

    // RFC 9000 Appendix A.1 worked example: 0xc2197c5eff14e88c decodes to 151288809941952652,
    // consuming all 8 bytes (the top two bits of the first byte, 0b11, select the 8-byte form).
    @Test fun decodesRfc9000WorkedExample() {
        val bytes = byteArrayOf(
            0xc2.toByte(), 0x19, 0x7c, 0x5e, 0xff.toByte(), 0x14, 0xe8.toByte(), 0x8c.toByte()
        )
        val decoded = QuicVarInt.decode(bytes, 0)
        assertEquals(151288809941952652L, decoded.value)
        assertEquals(8, decoded.bytesConsumed)
    }

    @Test fun roundTripsAcrossAllLengthBoundaries() {
        val values = longArrayOf(0, 1, 63, 64, 16383, 16384, 1073741823L, 1073741824L, QuicVarInt.MAX_VALUE)
        for (v in values) {
            val encoded = QuicVarInt.encode(v)
            assertEquals(encoded.size, QuicVarInt.encodedLength(v))
            val decoded = QuicVarInt.decode(encoded, 0)
            assertEquals(v, decoded.value)
            assertEquals(encoded.size, decoded.bytesConsumed)
        }
    }

    @Test fun readerAdvancesPastConsumedBytes() {
        val reader = QuicByteReader(QuicVarInt.encode(300) + QuicVarInt.encode(5))
        assertEquals(300L, reader.readVarInt())
        assertEquals(5L, reader.readVarInt())
        assertEquals(0, reader.remaining)
    }
}
