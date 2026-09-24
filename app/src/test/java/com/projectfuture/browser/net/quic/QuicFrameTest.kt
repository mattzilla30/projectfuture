package com.projectfuture.browser.net.quic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuicFrameTest {

    @Test fun paddingRoundTrips() {
        val frame = QuicFrame.Padding(10)
        val decoded = QuicFrame.decodeAll(frame.encoded())
        assertEquals(1, decoded.size)
        assertTrue(decoded[0] is QuicFrame.Padding)
        assertEquals(10, (decoded[0] as QuicFrame.Padding).length)
    }

    @Test fun pingRoundTrips() {
        val decoded = QuicFrame.decodeAll(QuicFrame.Ping.encoded())
        assertEquals(listOf(QuicFrame.Ping), decoded)
    }

    @Test fun cryptoFrameRoundTrips() {
        val payload = "client hello bytes go here".toByteArray()
        val frame = QuicFrame.Crypto(42, payload)
        val decoded = QuicFrame.decodeAll(frame.encoded())
        assertEquals(1, decoded.size)
        val c = decoded[0] as QuicFrame.Crypto
        assertEquals(42L, c.offset)
        assertEquals(payload.toList(), c.data.toList())
    }

    @Test fun streamFrameRoundTripsWithFin() {
        val payload = "GET / HTTP/3 request bytes".toByteArray()
        val frame = QuicFrame.Stream(streamId = 0, offset = 0, data = payload, fin = true)
        val decoded = QuicFrame.decodeAll(frame.encoded())
        val s = decoded[0] as QuicFrame.Stream
        assertEquals(0L, s.streamId)
        assertEquals(0L, s.offset)
        assertTrue(s.fin)
        assertEquals(payload.toList(), s.data.toList())
    }

    @Test fun ackFrameWithSingleRangeRoundTrips() {
        val frame = QuicFrame.ack(largestAcknowledged = 5, ackDelay = 100)
        val decoded = QuicFrame.decodeAll(frame.encoded())
        val ack = decoded[0] as QuicFrame.Ack
        assertEquals(5L, ack.largestAcknowledged)
        assertEquals(100L, ack.ackDelay)
        assertEquals(1, ack.ackRanges.size)
        assertEquals(5L, ack.ackRanges[0].last)
        assertEquals(5L, ack.ackRanges[0].first) // rangeLength 0 -> just packet 5
    }

    @Test fun ackFrameWithMultipleRangesRoundTrips() {
        // Acknowledges packets 0-2 and 5-9 (a gap at 3-4).
        val frame = QuicFrame.Ack(
            largestAcknowledged = 9,
            ackDelay = 0,
            ackRanges = listOf(5L..9L, 0L..2L)
        )
        val decoded = QuicFrame.decodeAll(frame.encoded())
        val ack = decoded[0] as QuicFrame.Ack
        assertEquals(9L, ack.largestAcknowledged)
        assertEquals(2, ack.ackRanges.size)
        assertEquals(5L..9L, ack.ackRanges[0])
        assertEquals(0L..2L, ack.ackRanges[1])
    }

    @Test fun multipleFramesInOnePayloadDecodeInOrder() {
        val payload = QuicFrame.Ping.encoded() + QuicFrame.Padding(3).encoded() + QuicFrame.Crypto(0, byteArrayOf(1, 2, 3)).encoded()
        val decoded = QuicFrame.decodeAll(payload)
        assertEquals(3, decoded.size)
        assertTrue(decoded[0] is QuicFrame.Ping)
        assertTrue(decoded[1] is QuicFrame.Padding)
        assertTrue(decoded[2] is QuicFrame.Crypto)
    }
}
