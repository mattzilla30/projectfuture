package com.projectfuture.browser.rtc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real RFC 4960 chunk/packet encode-decode round trips, including CRC-32C checksum verification - no association logic here, just the wire format. */
class SctpChunkTest {
    @Test fun initChunkRoundTrips() {
        val chunk = SctpChunk.Init(initiateTag = 123456789L, aRwnd = 131072, outboundStreams = 10, inboundStreams = 12, initialTsn = 555)
        val (decoded, _) = SctpChunk.decodeAt(chunk.encode(), 0)!!
        decoded as SctpChunk.Init
        assertEquals(123456789L, decoded.initiateTag)
        assertEquals(131072L, decoded.aRwnd)
        assertEquals(10, decoded.outboundStreams)
        assertEquals(12, decoded.inboundStreams)
        assertEquals(555L, decoded.initialTsn)
    }

    @Test fun initAckCarriesTheStateCookieUnchanged() {
        val cookie = "opaque-state-cookie-bytes".toByteArray()
        val chunk = SctpChunk.InitAck(9L, 131072, 5, 5, 1000, cookie)
        val (decoded, _) = SctpChunk.decodeAt(chunk.encode(), 0)!!
        decoded as SctpChunk.InitAck
        assertArrayEquals(cookie, decoded.cookie)
        assertEquals(9L, decoded.initiateTag)
    }

    @Test fun cookieEchoAndAckRoundTrip() {
        val cookie = byteArrayOf(1, 2, 3, 4, 5)
        val (echoDecoded, _) = SctpChunk.decodeAt(SctpChunk.CookieEcho(cookie).encode(), 0)!!
        assertArrayEquals(cookie, (echoDecoded as SctpChunk.CookieEcho).cookie)

        val (ackDecoded, _) = SctpChunk.decodeAt(SctpChunk.CookieAck.encode(), 0)!!
        assertTrue(ackDecoded is SctpChunk.CookieAck)
    }

    @Test fun dataChunkRoundTripsWithFlagsAndStreamId() {
        val payload = "hello over a real sctp stream".toByteArray()
        val chunk = SctpChunk.Data(tsn = 42, streamId = 7, streamSeq = 3, ppid = 51, unordered = false, beginning = true, ending = true, payload = payload)
        val (decoded, next) = SctpChunk.decodeAt(chunk.encode(), 0)!!
        decoded as SctpChunk.Data
        assertEquals(42L, decoded.tsn)
        assertEquals(7, decoded.streamId)
        assertEquals(3, decoded.streamSeq)
        assertEquals(51L, decoded.ppid)
        assertTrue(decoded.beginning)
        assertTrue(decoded.ending)
        assertArrayEquals(payload, decoded.payload)
        assertEquals(chunk.encode().size, next) // exactly one (padded) chunk consumed
    }

    @Test fun sackChunkRoundTripsWithGapBlocks() {
        val chunk = SctpChunk.Sack(cumulativeTsnAck = 100, aRwnd = 65536, gapBlocks = listOf(2 to 4, 10 to 10), duplicateTsns = listOf(101L, 105L))
        val (decoded, _) = SctpChunk.decodeAt(chunk.encode(), 0)!!
        decoded as SctpChunk.Sack
        assertEquals(100L, decoded.cumulativeTsnAck)
        assertEquals(listOf(2 to 4, 10 to 10), decoded.gapBlocks)
        assertEquals(listOf(101L, 105L), decoded.duplicateTsns)
    }

    @Test fun packetChecksumRoundTripsAndVerifies() {
        val packet = SctpPacket(5000, 5000, 987654321L, listOf(SctpChunk.CookieAck))
        val decoded = SctpPacket.decode(packet.encode())
        assertEquals(5000, decoded!!.sourcePort)
        assertEquals(987654321L, decoded.verificationTag)
        assertTrue(decoded.chunks[0] is SctpChunk.CookieAck)
    }

    @Test fun corruptedPacketFailsChecksumAndIsRejected() {
        val bytes = SctpPacket(5000, 5000, 1L, listOf(SctpChunk.CookieAck)).encode()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()
        assertNull(SctpPacket.decode(bytes))
    }

    @Test fun multipleChunksInOnePacketAllDecode() {
        val packet = SctpPacket(1, 2, 42L, listOf(
            SctpChunk.Data(1, 0, 0, 51, false, true, true, "a".toByteArray()),
            SctpChunk.Data(2, 0, 1, 51, false, true, true, "bb".toByteArray())
        ))
        val decoded = SctpPacket.decode(packet.encode())!!
        assertEquals(2, decoded.chunks.size)
        assertEquals("a", String((decoded.chunks[0] as SctpChunk.Data).payload))
        assertEquals("bb", String((decoded.chunks[1] as SctpChunk.Data).payload))
    }
}
