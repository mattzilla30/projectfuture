package com.projectfuture.browser.net.quic

import java.io.ByteArrayOutputStream

/**
 * The variable-length integer encoding used everywhere in QUIC wire format
 * (packet numbers' lengths aside - those are separate), per RFC 9000
 * section 16. The two most-significant bits of the first byte pick the
 * encoded length (1/2/4/8 bytes), and the remaining 6/14/30/62 bits hold
 * the value, big-endian.
 */
object QuicVarInt {
    const val MAX_VALUE: Long = (1L shl 62) - 1

    /** Encodes [value] into its shortest legal varint form. */
    fun encode(value: Long): ByteArray {
        require(value in 0..MAX_VALUE) { "QUIC varint out of range: $value" }
        return when {
            value <= 0x3F -> byteArrayOf(value.toByte())
            value <= 0x3FFF -> {
                val v = value or (0x01L shl 14)
                byteArrayOf((v shr 8).toByte(), v.toByte())
            }
            value <= 0x3FFFFFFFL -> {
                val v = value or (0x02L shl 30)
                byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
            }
            else -> {
                val v = value or (0x03L shl 62)
                ByteArray(8) { i -> (v shr (56 - 8 * i)).toByte() }
            }
        }
    }

    fun encodeTo(out: ByteArrayOutputStream, value: Long) {
        out.write(encode(value))
    }

    /** The number of bytes [encode] would produce for [value], without allocating. */
    fun encodedLength(value: Long): Int = when {
        value <= 0x3F -> 1
        value <= 0x3FFF -> 2
        value <= 0x3FFFFFFFL -> 4
        else -> 8
    }

    /** Result of [decode]: the value and how many bytes of input it consumed. */
    data class Decoded(val value: Long, val bytesConsumed: Int)

    /** Decodes a varint starting at [offset] in [data]. */
    fun decode(data: ByteArray, offset: Int): Decoded {
        require(offset < data.size) { "QUIC varint: nothing to read at offset $offset" }
        val first = data[offset].toInt() and 0xFF
        val lengthLog2 = first shr 6
        val length = 1 shl lengthLog2
        require(offset + length <= data.size) { "QUIC varint: truncated (need $length bytes at $offset)" }
        var value = (first and 0x3F).toLong()
        for (i in 1 until length) {
            value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
        }
        return Decoded(value, length)
    }

    /** Reads a varint from a [QuicByteReader] cursor. */
    fun read(reader: QuicByteReader): Long {
        val decoded = decode(reader.data, reader.position)
        reader.position += decoded.bytesConsumed
        return decoded.value
    }
}

/** A tiny mutable read cursor over a byte array - used across the QUIC decoders. */
class QuicByteReader(val data: ByteArray, var position: Int = 0) {
    val remaining: Int get() = data.size - position

    fun readByte(): Int {
        require(remaining >= 1) { "QUIC reader: out of bytes" }
        return data[position++].toInt() and 0xFF
    }

    fun readBytes(count: Int): ByteArray {
        require(remaining >= count) { "QUIC reader: need $count bytes, have $remaining" }
        val out = data.copyOfRange(position, position + count)
        position += count
        return out
    }

    fun readVarInt(): Long = QuicVarInt.read(this)

    fun hasRemaining(): Boolean = remaining > 0
}
