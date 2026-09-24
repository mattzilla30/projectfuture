package com.projectfuture.browser.net.quic

import java.io.ByteArrayOutputStream

/**
 * QUIC frame types (RFC 9000 section 19), scoped to the ones a minimal
 * "send one request, read one response" HTTP/3 client actually needs:
 * PADDING and PING (packet padding for the Initial packet's 1200-byte
 * minimum, and keepalive), ACK (acknowledging what the peer sent), CRYPTO
 * (carries the TLS 1.3 handshake bytes), and STREAM (carries HTTP/3 request/
 * response bytes once the connection is up). Frame types this client never
 * needs to send or is willing to just ignore on receipt (NEW_CONNECTION_ID,
 * RESET_STREAM, MAX_DATA/MAX_STREAM_DATA flow-control frames, etc.) are not
 * modeled - see the class doc on [Http3Client] for the full list of what's
 * deliberately out of scope.
 */
sealed class QuicFrame {

    abstract fun encode(out: ByteArrayOutputStream)

    fun encoded(): ByteArray {
        val out = ByteArrayOutputStream()
        encode(out)
        return out.toByteArray()
    }

    /** Type 0x00. One or more of these are pure zero bytes; [length] just says how many. */
    data class Padding(val length: Int) : QuicFrame() {
        override fun encode(out: ByteArrayOutputStream) {
            repeat(length) { out.write(0x00) }
        }
    }

    /** Type 0x01. No payload - used as a keepalive / to force an ACK-eliciting packet. */
    object Ping : QuicFrame() {
        override fun encode(out: ByteArrayOutputStream) {
            out.write(0x01)
        }
    }

    /**
     * Type 0x02 (0x03 adds ECN counts, not produced here). Acknowledges packet numbers via a
     * largest-acknowledged plus a run of gap/range pairs ("ACK ranges"), per RFC 9000 19.3.
     * This client only ever needs to *send* trivial one-range ACKs (it receives far more than
     * it sends), so [ackRanges] beyond the first are supported for decoding completeness but
     * [ack] below only ever constructs a single range.
     */
    data class Ack(
        val largestAcknowledged: Long,
        val ackDelay: Long,
        val ackRanges: List<LongRange> // each inclusive, descending, first = [largestAcknowledged - firstRangeLen, largestAcknowledged]
    ) : QuicFrame() {
        override fun encode(out: ByteArrayOutputStream) {
            out.write(0x02)
            QuicVarInt.encodeTo(out, largestAcknowledged)
            QuicVarInt.encodeTo(out, ackDelay)
            QuicVarInt.encodeTo(out, (ackRanges.size - 1).toLong())
            val first = ackRanges[0]
            QuicVarInt.encodeTo(out, first.last - first.first) // first ACK range length
            var prevLow = first.first
            for (i in 1 until ackRanges.size) {
                val r = ackRanges[i]
                val gap = prevLow - r.last - 2
                QuicVarInt.encodeTo(out, gap)
                QuicVarInt.encodeTo(out, r.last - r.first)
                prevLow = r.first
            }
        }

        companion object {
            /** Convenience constructor for the common single-range case. */
            fun single(largestAcknowledged: Long, ackDelay: Long, rangeLength: Long): Ack =
                Ack(largestAcknowledged, ackDelay, listOf((largestAcknowledged - rangeLength)..largestAcknowledged))
        }
    }

    /** Type 0x06. Carries a slice of the TLS 1.3 handshake byte stream at [offset]. */
    data class Crypto(val offset: Long, val data: ByteArray) : QuicFrame() {
        override fun encode(out: ByteArrayOutputStream) {
            out.write(0x06)
            QuicVarInt.encodeTo(out, offset)
            QuicVarInt.encodeTo(out, data.size.toLong())
            out.write(data)
        }

        override fun equals(other: Any?): Boolean =
            other is Crypto && offset == other.offset && data.contentEquals(other.data)
        override fun hashCode(): Int = offset.hashCode() * 31 + data.contentHashCode()
    }

    /**
     * Types 0x08-0x0f. The low 3 bits of the type are flags: OFF (explicit offset present),
     * LEN (explicit length present - otherwise the frame runs to the end of the packet), FIN
     * (this is the last data on the stream). This client always sends OFF+LEN explicitly, which
     * keeps encode/decode simple at the cost of a few extra bytes.
     */
    data class Stream(val streamId: Long, val offset: Long, val data: ByteArray, val fin: Boolean) : QuicFrame() {
        override fun encode(out: ByteArrayOutputStream) {
            val type = 0x08 or 0x04 /* OFF */ or 0x02 /* LEN */ or (if (fin) 0x01 else 0x00)
            out.write(type)
            QuicVarInt.encodeTo(out, streamId)
            QuicVarInt.encodeTo(out, offset)
            QuicVarInt.encodeTo(out, data.size.toLong())
            out.write(data)
        }

        override fun equals(other: Any?): Boolean =
            other is Stream && streamId == other.streamId && offset == other.offset &&
                fin == other.fin && data.contentEquals(other.data)
        override fun hashCode(): Int = (streamId.hashCode() * 31 + offset.hashCode()) * 31 + data.contentHashCode()
    }

    /** A frame type this decoder doesn't model, kept as opaque bytes (including its type byte's varint). */
    data class Unknown(val type: Long, val raw: ByteArray) : QuicFrame() {
        override fun encode(out: ByteArrayOutputStream) {
            out.write(raw)
        }
    }

    companion object {
        fun ack(largestAcknowledged: Long, ackDelay: Long = 0): Ack =
            Ack.single(largestAcknowledged, ackDelay, 0)

        /** Decodes every frame in a fully-decrypted packet payload. */
        fun decodeAll(payload: ByteArray): List<QuicFrame> {
            val reader = QuicByteReader(payload)
            val frames = ArrayList<QuicFrame>()
            while (reader.hasRemaining()) {
                frames.add(decodeOne(reader))
            }
            return frames
        }

        private fun decodeOne(reader: QuicByteReader): QuicFrame {
            val startPos = reader.position
            val type = reader.readVarInt()
            return when {
                type == 0x00L -> {
                    var len = 1
                    while (reader.hasRemaining() && reader.data[reader.position].toInt() == 0x00) {
                        reader.position++
                        len++
                    }
                    Padding(len)
                }
                type == 0x01L -> Ping
                type == 0x02L || type == 0x03L -> {
                    val largest = reader.readVarInt()
                    val delay = reader.readVarInt()
                    val rangeCount = reader.readVarInt()
                    val firstRangeLen = reader.readVarInt()
                    val ranges = ArrayList<LongRange>()
                    ranges.add((largest - firstRangeLen)..largest)
                    var prevLow = largest - firstRangeLen
                    repeat(rangeCount.toInt()) {
                        val gap = reader.readVarInt()
                        val rangeLen = reader.readVarInt()
                        val high = prevLow - gap - 2
                        val low = high - rangeLen
                        ranges.add(low..high)
                        prevLow = low
                    }
                    if (type == 0x03L) {
                        // ECN counts (ECT0, ECT1, CE) - read and discard, not modeled.
                        reader.readVarInt(); reader.readVarInt(); reader.readVarInt()
                    }
                    Ack(largest, delay, ranges)
                }
                type == 0x06L -> {
                    val offset = reader.readVarInt()
                    val len = reader.readVarInt()
                    val data = reader.readBytes(len.toInt())
                    Crypto(offset, data)
                }
                type in 0x08L..0x0FL -> {
                    val off = type and 0x04L != 0L
                    val hasLen = type and 0x02L != 0L
                    val fin = type and 0x01L != 0L
                    val streamId = reader.readVarInt()
                    val offset = if (off) reader.readVarInt() else 0L
                    val data = if (hasLen) {
                        val len = reader.readVarInt()
                        reader.readBytes(len.toInt())
                    } else {
                        reader.readBytes(reader.remaining)
                    }
                    Stream(streamId, offset, data, fin)
                }
                else -> {
                    // Unknown frame type: we don't know its shape, so we can't safely skip past it
                    // without risking desyncing the rest of the packet. Consume the rest of the
                    // payload as opaque bytes - acceptable because this client never expects to see
                    // one of these on the packets it actually needs to parse.
                    val raw = reader.data.copyOfRange(startPos, reader.data.size)
                    reader.position = reader.data.size
                    Unknown(type, raw)
                }
            }
        }
    }
}
