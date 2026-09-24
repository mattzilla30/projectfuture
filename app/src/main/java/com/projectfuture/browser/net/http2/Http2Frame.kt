package com.projectfuture.browser.net.http2

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** HTTP/2 frame type codes (RFC 7540 6.*). */
object FrameType {
    const val DATA = 0x0
    const val HEADERS = 0x1
    const val PRIORITY = 0x2
    const val RST_STREAM = 0x3
    const val SETTINGS = 0x4
    const val PUSH_PROMISE = 0x5
    const val PING = 0x6
    const val GOAWAY = 0x7
    const val WINDOW_UPDATE = 0x8
    const val CONTINUATION = 0x9
}

/** Flag bits, shared bit positions reused per RFC 7540 across frame types that define them. */
object FrameFlag {
    const val END_STREAM = 0x1
    const val ACK = 0x1 // Same bit as END_STREAM, used on SETTINGS/PING - context tells them apart.
    const val END_HEADERS = 0x4
    const val PADDED = 0x8
    const val PRIORITY = 0x20
}

/** Standard HTTP/2 SETTINGS identifiers this client sends or reads (RFC 7540 6.5.2). */
object SettingsId {
    const val HEADER_TABLE_SIZE = 0x1
    const val ENABLE_PUSH = 0x2
    const val MAX_CONCURRENT_STREAMS = 0x3
    const val INITIAL_WINDOW_SIZE = 0x4
    const val MAX_FRAME_SIZE = 0x5
    const val MAX_HEADER_LIST_SIZE = 0x6
}

object ErrorCode {
    const val NO_ERROR = 0x0
    const val PROTOCOL_ERROR = 0x1
    const val FLOW_CONTROL_ERROR = 0x3
    const val REFUSED_STREAM = 0x7
    const val CANCEL = 0x8
}

/** The 9-byte frame header (RFC 7540 4.1) plus its payload, decoded but otherwise unparsed. */
data class Http2Frame(val type: Int, val flags: Int, val streamId: Int, val payload: ByteArray) {
    fun hasFlag(flag: Int) = flags and flag != 0
}

/** Client connection preface (RFC 7540 3.5) - sent once, before any frame, on every HTTP/2 connection. */
val HTTP2_CONNECTION_PREFACE: ByteArray =
    "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".toByteArray(Charsets.US_ASCII)

const val DEFAULT_MAX_FRAME_SIZE = 16384

/**
 * Frame-level binary I/O. Pulled out as free functions over plain [InputStream]/[OutputStream]
 * (rather than baked into the socket-owning connection class) so the wire format itself - the part
 * RFC 7540 section 4 actually specifies bit-for-bit - is directly unit-testable against in-memory
 * byte arrays, the same reasoning [Hpack] and [Huffman] are standalone.
 */
object Http2FrameIO {
    fun readFrame(input: InputStream, maxFrameSize: Int = 1 shl 24): Http2Frame {
        val header = readFully(input, 9)
        val length = (header[0].toInt() and 0xff shl 16) or
            (header[1].toInt() and 0xff shl 8) or
            (header[2].toInt() and 0xff)
        if (length > maxFrameSize) throw java.io.IOException("Frame too large: $length")
        val type = header[3].toInt() and 0xff
        val flags = header[4].toInt() and 0xff
        val streamId = (header[5].toInt() and 0x7f shl 24) or
            (header[6].toInt() and 0xff shl 16) or
            (header[7].toInt() and 0xff shl 8) or
            (header[8].toInt() and 0xff)
        val payload = if (length == 0) ByteArray(0) else readFully(input, length)
        return Http2Frame(type, flags, streamId, payload)
    }

    fun writeFrame(output: OutputStream, frame: Http2Frame) {
        val length = frame.payload.size
        val header = ByteArray(9)
        header[0] = ((length ushr 16) and 0xff).toByte()
        header[1] = ((length ushr 8) and 0xff).toByte()
        header[2] = (length and 0xff).toByte()
        header[3] = frame.type.toByte()
        header[4] = frame.flags.toByte()
        header[5] = ((frame.streamId ushr 24) and 0x7f).toByte()
        header[6] = ((frame.streamId ushr 16) and 0xff).toByte()
        header[7] = ((frame.streamId ushr 8) and 0xff).toByte()
        header[8] = (frame.streamId and 0xff).toByte()
        output.write(header)
        if (length > 0) output.write(frame.payload)
    }

    private fun readFully(input: InputStream, count: Int): ByteArray {
        val buf = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(buf, read, count - read)
            if (n == -1) throw EOFException("Connection closed after $read/$count bytes")
            read += n
        }
        return buf
    }

    /** Encodes a SETTINGS frame payload from (id, value) pairs (RFC 7540 6.5). */
    fun encodeSettings(settings: List<Pair<Int, Int>>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for ((id, value) in settings) {
            out.write((id ushr 8) and 0xff)
            out.write(id and 0xff)
            out.write((value ushr 24) and 0xff)
            out.write((value ushr 16) and 0xff)
            out.write((value ushr 8) and 0xff)
            out.write(value and 0xff)
        }
        return out.toByteArray()
    }

    /** Decodes a SETTINGS frame payload into (id, value) pairs. */
    fun decodeSettings(payload: ByteArray): List<Pair<Int, Int>> {
        val result = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i + 6 <= payload.size) {
            val id = (payload[i].toInt() and 0xff shl 8) or (payload[i + 1].toInt() and 0xff)
            val value = (payload[i + 2].toInt() and 0xff shl 24) or
                (payload[i + 3].toInt() and 0xff shl 16) or
                (payload[i + 4].toInt() and 0xff shl 8) or
                (payload[i + 5].toInt() and 0xff)
            result.add(id to value)
            i += 6
        }
        return result
    }

    /** Encodes a 31-bit window-size-increment WINDOW_UPDATE payload (RFC 7540 6.9). */
    fun encodeWindowUpdate(increment: Int): ByteArray {
        val out = ByteArray(4)
        out[0] = ((increment ushr 24) and 0x7f).toByte()
        out[1] = ((increment ushr 16) and 0xff).toByte()
        out[2] = ((increment ushr 8) and 0xff).toByte()
        out[3] = (increment and 0xff).toByte()
        return out
    }

    fun decodeWindowUpdate(payload: ByteArray): Int =
        (payload[0].toInt() and 0x7f shl 24) or
            (payload[1].toInt() and 0xff shl 16) or
            (payload[2].toInt() and 0xff shl 8) or
            (payload[3].toInt() and 0xff)

    /** Encodes an 8-byte RST_STREAM/GOAWAY-style error code payload used by RST_STREAM (RFC 7540 6.4). */
    fun encodeRstStream(errorCode: Int): ByteArray {
        val out = ByteArray(4)
        out[0] = ((errorCode ushr 24) and 0xff).toByte()
        out[1] = ((errorCode ushr 16) and 0xff).toByte()
        out[2] = ((errorCode ushr 8) and 0xff).toByte()
        out[3] = (errorCode and 0xff).toByte()
        return out
    }

    /** Strips DATA-frame padding (RFC 7540 6.1): a leading pad-length byte plus that many trailing pad bytes. */
    fun extractDataPayload(payload: ByteArray, flags: Int): ByteArray {
        if (flags and FrameFlag.PADDED == 0) return payload
        if (payload.isEmpty()) throw java.io.IOException("PADDED DATA frame with no pad length")
        val padLength = payload[0].toInt() and 0xff
        val end = payload.size - padLength
        if (end < 1) throw java.io.IOException("DATA frame padding exceeds frame length")
        return payload.copyOfRange(1, end)
    }

    /** Strips HEADERS-frame padding/priority prefix fields, returning just the HPACK block bytes. */
    fun extractHeaderBlockFragment(payload: ByteArray, flags: Int): ByteArray {
        var offset = 0
        var padLength = 0
        if (flags and FrameFlag.PADDED != 0) {
            padLength = payload[0].toInt() and 0xff
            offset += 1
        }
        if (flags and FrameFlag.PRIORITY != 0) {
            offset += 5 // 31-bit stream dependency + 1-bit exclusive, then 1-byte weight.
        }
        val end = payload.size - padLength
        return payload.copyOfRange(offset, end)
    }
}
