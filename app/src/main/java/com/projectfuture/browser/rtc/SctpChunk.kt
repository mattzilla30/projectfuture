package com.projectfuture.browser.rtc

import java.nio.ByteBuffer

/** RFC 4960 Appendix B: SCTP's checksum is CRC-32C (Castagnoli), not zlib's CRC-32 - a different polynomial, so `java.util.zip.CRC32` (used for STUN's FINGERPRINT in Stun.kt, which really is zlib CRC-32) cannot be reused here. */
object Crc32C {
    private val TABLE = LongArray(256).also { table ->
        val poly = 0x82F63B78L // reversed 0x1EDC6F41
        for (i in 0 until 256) {
            var crc = i.toLong()
            repeat(8) { crc = if (crc and 1L != 0L) (crc ushr 1) xor poly else crc ushr 1 }
            table[i] = crc
        }
    }

    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Long {
        var crc = 0xFFFFFFFFL
        for (i in offset until offset + length) {
            val idx = ((crc xor data[i].toLong()) and 0xFF).toInt()
            crc = (crc ushr 8) xor TABLE[idx]
        }
        return crc.inv() and 0xFFFFFFFFL
    }
}

/** One decoded SCTP chunk (RFC 4960 section 3.3) - the subset this engine's association setup and reliable-ordered data transfer needs. Unrecognized chunk types round-trip as [Unknown] rather than being rejected, matching RFC 4960 section 3.2's "unrecognized chunk" handling for the common case (upper two bits of an unknown type both 0, meaning "skip and continue"). */
sealed class SctpChunk {
    abstract fun encode(): ByteArray

    data class Init(val initiateTag: Long, val aRwnd: Long, val outboundStreams: Int, val inboundStreams: Int, val initialTsn: Long) : SctpChunk() {
        override fun encode() = buildFixedFieldsChunk(TYPE_INIT, initiateTag, aRwnd, outboundStreams, inboundStreams, initialTsn)
    }

    data class InitAck(val initiateTag: Long, val aRwnd: Long, val outboundStreams: Int, val inboundStreams: Int, val initialTsn: Long, val cookie: ByteArray) : SctpChunk() {
        override fun encode(): ByteArray {
            val fixed = buildFixedFieldsChunkValue(initiateTag, aRwnd, outboundStreams, inboundStreams, initialTsn)
            val cookieParam = ByteBuffer.allocate(4 + cookie.size).apply {
                putShort(PARAM_STATE_COOKIE.toShort())
                putShort((4 + cookie.size).toShort())
                put(cookie)
            }.array()
            return wrapChunk(TYPE_INIT_ACK, 0, fixed + pad4(cookieParam))
        }
    }

    data class CookieEcho(val cookie: ByteArray) : SctpChunk() {
        override fun encode() = wrapChunk(TYPE_COOKIE_ECHO, 0, cookie)
    }

    object CookieAck : SctpChunk() {
        override fun encode() = wrapChunk(TYPE_COOKIE_ACK, 0, ByteArray(0))
    }

    data class Data(val tsn: Long, val streamId: Int, val streamSeq: Int, val ppid: Long, val unordered: Boolean, val beginning: Boolean, val ending: Boolean, val payload: ByteArray) : SctpChunk() {
        override fun encode(): ByteArray {
            var flags = 0
            if (unordered) flags = flags or FLAG_UNORDERED
            if (beginning) flags = flags or FLAG_BEGIN
            if (ending) flags = flags or FLAG_END
            val value = ByteBuffer.allocate(12 + payload.size).apply {
                putInt(tsn.toInt())
                putShort(streamId.toShort())
                putShort(streamSeq.toShort())
                putInt(ppid.toInt())
                put(payload)
            }.array()
            return wrapChunk(TYPE_DATA, flags, value)
        }
    }

    data class Sack(val cumulativeTsnAck: Long, val aRwnd: Long, val gapBlocks: List<Pair<Int, Int>> = emptyList(), val duplicateTsns: List<Long> = emptyList()) : SctpChunk() {
        override fun encode(): ByteArray {
            val buf = ByteBuffer.allocate(12 + gapBlocks.size * 4 + duplicateTsns.size * 4)
            buf.putInt(cumulativeTsnAck.toInt())
            buf.putInt(aRwnd.toInt())
            buf.putShort(gapBlocks.size.toShort())
            buf.putShort(duplicateTsns.size.toShort())
            for ((start, end) in gapBlocks) { buf.putShort(start.toShort()); buf.putShort(end.toShort()) }
            for (d in duplicateTsns) buf.putInt(d.toInt())
            return wrapChunk(TYPE_SACK, 0, buf.array())
        }
    }

    data class Unknown(val type: Int, val flags: Int, val value: ByteArray) : SctpChunk() {
        override fun encode() = wrapChunk(type, flags, value)
    }

    companion object {
        const val TYPE_DATA = 0
        const val TYPE_INIT = 1
        const val TYPE_INIT_ACK = 2
        const val TYPE_SACK = 3
        const val TYPE_COOKIE_ECHO = 10
        const val TYPE_COOKIE_ACK = 11

        const val FLAG_UNORDERED = 0x04
        const val FLAG_BEGIN = 0x02
        const val FLAG_END = 0x01

        private const val PARAM_STATE_COOKIE = 7

        private fun pad4(bytes: ByteArray): ByteArray {
            val pad = (4 - bytes.size % 4) % 4
            return if (pad == 0) bytes else bytes + ByteArray(pad)
        }

        private fun wrapChunk(type: Int, flags: Int, value: ByteArray): ByteArray {
            val header = ByteBuffer.allocate(4).apply {
                put(type.toByte())
                put(flags.toByte())
                putShort((4 + value.size).toShort())
            }.array()
            return pad4(header + value)
        }

        private fun buildFixedFieldsChunkValue(initiateTag: Long, aRwnd: Long, outboundStreams: Int, inboundStreams: Int, initialTsn: Long): ByteArray =
            ByteBuffer.allocate(16).apply {
                putInt(initiateTag.toInt())
                putInt(aRwnd.toInt())
                putShort(outboundStreams.toShort())
                putShort(inboundStreams.toShort())
                putInt(initialTsn.toInt())
            }.array()

        private fun buildFixedFieldsChunk(type: Int, initiateTag: Long, aRwnd: Long, outboundStreams: Int, inboundStreams: Int, initialTsn: Long): ByteArray =
            wrapChunk(type, 0, buildFixedFieldsChunkValue(initiateTag, aRwnd, outboundStreams, inboundStreams, initialTsn))

        /** Decodes one chunk starting at [offset] in [data], returning the chunk and the offset of the next one (chunks are individually padded to a 4-byte boundary per RFC 4960 section 3.2), or null if truncated/malformed. */
        fun decodeAt(data: ByteArray, offset: Int): Pair<SctpChunk, Int>? {
            if (offset + 4 > data.size) return null
            val type = data[offset].toInt() and 0xFF
            val flags = data[offset + 1].toInt() and 0xFF
            val length = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
            if (length < 4 || offset + length > data.size) return null
            val value = data.copyOfRange(offset + 4, offset + length)
            val padded = length + (4 - length % 4) % 4
            val next = offset + padded

            val chunk: SctpChunk = when (type) {
                TYPE_INIT -> parseFixedFields(value)?.let { (tag, rwnd, out, inn, tsn) -> Init(tag, rwnd, out, inn, tsn) } ?: Unknown(type, flags, value)
                TYPE_INIT_ACK -> {
                    val fixed = parseFixedFields(value)
                    if (fixed == null) Unknown(type, flags, value) else {
                        val (tag, rwnd, out, inn, tsn) = fixed
                        val cookie = findParam(value, 16, PARAM_STATE_COOKIE) ?: ByteArray(0)
                        InitAck(tag, rwnd, out, inn, tsn, cookie)
                    }
                }
                TYPE_COOKIE_ECHO -> CookieEcho(value)
                TYPE_COOKIE_ACK -> CookieAck
                TYPE_DATA -> {
                    if (value.size < 12) Unknown(type, flags, value) else {
                        val buf = ByteBuffer.wrap(value)
                        val tsn = buf.int.toLong() and 0xFFFFFFFFL
                        val streamId = buf.short.toInt() and 0xFFFF
                        val streamSeq = buf.short.toInt() and 0xFFFF
                        val ppid = buf.int.toLong() and 0xFFFFFFFFL
                        val payload = ByteArray(buf.remaining()).also { buf.get(it) }
                        Data(tsn, streamId, streamSeq, ppid, flags and FLAG_UNORDERED != 0, flags and FLAG_BEGIN != 0, flags and FLAG_END != 0, payload)
                    }
                }
                TYPE_SACK -> {
                    if (value.size < 12) Unknown(type, flags, value) else {
                        val buf = ByteBuffer.wrap(value)
                        val cumTsn = buf.int.toLong() and 0xFFFFFFFFL
                        val rwnd = buf.int.toLong() and 0xFFFFFFFFL
                        val numGaps = buf.short.toInt() and 0xFFFF
                        val numDup = buf.short.toInt() and 0xFFFF
                        val gaps = ArrayList<Pair<Int, Int>>()
                        repeat(numGaps) { if (buf.remaining() >= 4) gaps.add((buf.short.toInt() and 0xFFFF) to (buf.short.toInt() and 0xFFFF)) }
                        val dups = ArrayList<Long>()
                        repeat(numDup) { if (buf.remaining() >= 4) dups.add(buf.int.toLong() and 0xFFFFFFFFL) }
                        Sack(cumTsn, rwnd, gaps, dups)
                    }
                }
                else -> Unknown(type, flags, value)
            }
            return chunk to next
        }

        private fun parseFixedFields(value: ByteArray): FixedFields? {
            if (value.size < 16) return null
            val buf = ByteBuffer.wrap(value)
            val tag = buf.int.toLong() and 0xFFFFFFFFL
            val rwnd = buf.int.toLong() and 0xFFFFFFFFL
            val out = buf.short.toInt() and 0xFFFF
            val inn = buf.short.toInt() and 0xFFFF
            val tsn = buf.int.toLong() and 0xFFFFFFFFL
            return FixedFields(tag, rwnd, out, inn, tsn)
        }

        private data class FixedFields(val tag: Long, val rwnd: Long, val out: Int, val inn: Int, val tsn: Long)

        private fun findParam(value: ByteArray, startOffset: Int, wantType: Int): ByteArray? {
            var pos = startOffset
            while (pos + 4 <= value.size) {
                val type = ((value[pos].toInt() and 0xFF) shl 8) or (value[pos + 1].toInt() and 0xFF)
                val len = ((value[pos + 2].toInt() and 0xFF) shl 8) or (value[pos + 3].toInt() and 0xFF)
                if (len < 4 || pos + len > value.size) return null
                if (type == wantType) return value.copyOfRange(pos + 4, pos + len)
                val pad = (4 - len % 4) % 4
                pos += len + pad
            }
            return null
        }
    }
}

/** An RFC 4960 SCTP packet: a 12-byte common header (source/destination port, verification tag, CRC-32C checksum) followed by one or more chunks. */
class SctpPacket(val sourcePort: Int, val destPort: Int, val verificationTag: Long, val chunks: List<SctpChunk>) {
    fun encode(): ByteArray {
        val chunkBytes = chunks.map { it.encode() }
        val totalChunkLen = chunkBytes.sumOf { it.size }
        val buf = ByteBuffer.allocate(12 + totalChunkLen)
        buf.putShort(sourcePort.toShort())
        buf.putShort(destPort.toShort())
        buf.putInt(verificationTag.toInt())
        buf.putInt(0) // checksum placeholder
        for (c in chunkBytes) buf.put(c)
        val bytes = buf.array()
        val checksum = Crc32C.compute(bytes)
        bytes[8] = (checksum and 0xFF).toByte()
        bytes[9] = ((checksum shr 8) and 0xFF).toByte()
        bytes[10] = ((checksum shr 16) and 0xFF).toByte()
        bytes[11] = ((checksum shr 24) and 0xFF).toByte()
        return bytes
    }

    companion object {
        /** Decodes a packet and verifies its checksum; returns null for anything truncated or with a bad checksum (RFC 4960 section 6.8: such a packet is silently discarded). */
        fun decode(data: ByteArray): SctpPacket? {
            if (data.size < 12) return null
            val receivedChecksum = (data[8].toLong() and 0xFF) or
                ((data[9].toLong() and 0xFF) shl 8) or
                ((data[10].toLong() and 0xFF) shl 16) or
                ((data[11].toLong() and 0xFF) shl 24)
            val forCrc = data.copyOf()
            forCrc[8] = 0; forCrc[9] = 0; forCrc[10] = 0; forCrc[11] = 0
            if (Crc32C.compute(forCrc) != receivedChecksum) return null

            val buf = ByteBuffer.wrap(data)
            val srcPort = buf.short.toInt() and 0xFFFF
            val dstPort = buf.short.toInt() and 0xFFFF
            val tag = buf.int.toLong() and 0xFFFFFFFFL
            buf.int // checksum, already verified above

            val chunks = ArrayList<SctpChunk>()
            var offset = 12
            while (offset < data.size) {
                val result = SctpChunk.decodeAt(data, offset) ?: break
                chunks.add(result.first)
                offset = result.second
            }
            return SctpPacket(srcPort, dstPort, tag, chunks)
        }
    }
}
