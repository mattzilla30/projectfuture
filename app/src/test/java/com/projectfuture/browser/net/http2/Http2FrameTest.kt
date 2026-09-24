package com.projectfuture.browser.net.http2

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frame-level wire format (RFC 7540 section 4): the 9-byte frame header plus the handful of payload shapes this client actually parses/emits. */
class Http2FrameTest {
    @Test fun roundTripsAFrameThroughTheWire() {
        val frame = Http2Frame(FrameType.HEADERS, FrameFlag.END_HEADERS or FrameFlag.END_STREAM, streamId = 7, payload = byteArrayOf(1, 2, 3, 4, 5))
        val out = ByteArrayOutputStream()
        Http2FrameIO.writeFrame(out, frame)
        val bytes = out.toByteArray()

        // 9-byte header: 3-byte length, 1-byte type, 1-byte flags, 4-byte (31-bit) stream id.
        assertEquals(9 + 5, bytes.size)
        assertEquals(0, bytes[0].toInt()); assertEquals(0, bytes[1].toInt()); assertEquals(5, bytes[2].toInt())
        assertEquals(FrameType.HEADERS, bytes[3].toInt())
        assertEquals(FrameFlag.END_HEADERS or FrameFlag.END_STREAM, bytes[4].toInt())

        val read = Http2FrameIO.readFrame(ByteArrayInputStream(bytes))
        assertEquals(frame.type, read.type)
        assertEquals(frame.flags, read.flags)
        assertEquals(frame.streamId, read.streamId)
        assertArrayEquals(frame.payload, read.payload)
    }

    @Test fun streamIdIgnoresTheReservedTopBit() {
        // RFC 7540 4.1: the top bit of the stream identifier field is reserved and must be ignored on read.
        val out = ByteArrayOutputStream()
        val header = byteArrayOf(0, 0, 0, FrameType.WINDOW_UPDATE.toByte(), 0, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte())
        out.write(header)
        val frame = Http2FrameIO.readFrame(ByteArrayInputStream(out.toByteArray()))
        assertEquals(0x7fffffff, frame.streamId)
    }

    @Test fun rejectsAnOversizedFrame() {
        val header = byteArrayOf(0x10, 0, 0, FrameType.DATA.toByte(), 0, 0, 0, 0, 1) // length = 0x100000, over a 16384 limit
        try {
            Http2FrameIO.readFrame(ByteArrayInputStream(header), maxFrameSize = 16384)
            org.junit.Assert.fail("expected an IOException for an oversized frame")
        } catch (_: java.io.IOException) {
            // Expected.
        }
    }

    @Test fun encodesAndDecodesSettingsPairs() {
        val settings = listOf(SettingsId.ENABLE_PUSH to 0, SettingsId.INITIAL_WINDOW_SIZE to 65535, SettingsId.MAX_FRAME_SIZE to 16384)
        val decoded = Http2FrameIO.decodeSettings(Http2FrameIO.encodeSettings(settings))
        assertEquals(settings, decoded)
    }

    @Test fun encodesAndDecodesWindowUpdate() {
        val increment = 1_000_000
        assertEquals(increment, Http2FrameIO.decodeWindowUpdate(Http2FrameIO.encodeWindowUpdate(increment)))
    }

    @Test fun extractsHeaderBlockFragmentWithNoPaddingOrPriority() {
        val payload = byteArrayOf(9, 8, 7)
        assertArrayEquals(payload, Http2FrameIO.extractHeaderBlockFragment(payload, flags = 0))
    }

    @Test fun extractsHeaderBlockFragmentStrippingPaddingAndPriority() {
        // PADDED (1 pad-length byte) + PRIORITY (4-byte dependency + 1-byte weight) + 3 bytes of
        // real header block + 2 bytes of trailing padding.
        val payload = byteArrayOf(2 /* padLength */, 0, 0, 0, 0, 16 /* weight */, 9, 8, 7, 0, 0)
        val fragment = Http2FrameIO.extractHeaderBlockFragment(payload, flags = FrameFlag.PADDED or FrameFlag.PRIORITY)
        assertArrayEquals(byteArrayOf(9, 8, 7), fragment)
    }

    @Test fun frameHasFlagChecksTheBitCorrectly() {
        val frame = Http2Frame(FrameType.HEADERS, FrameFlag.END_HEADERS, 1, ByteArray(0))
        assertTrue(frame.hasFlag(FrameFlag.END_HEADERS))
        assertTrue(!frame.hasFlag(FrameFlag.END_STREAM))
    }
}
