package com.projectfuture.browser.net.quic

import java.io.ByteArrayOutputStream

/**
 * QUIC long-header packet framing (RFC 9000 section 17.2), scoped to the
 * three long-header types a client needs before it has 1-RTT keys:
 * Initial, Handshake, and (structurally, though this client never sends
 * one) Retry. Short-header (1-RTT) packets are handled by
 * [QuicShortHeaderPacket] below.
 *
 * This class only knows the *cleartext* header layout - i.e. the bytes as
 * they look once QUIC's mandatory header protection has already been
 * removed (or, on the encode side, before it is applied). Applying/removing
 * header protection and the AEAD packet payload protection itself is
 * [QuicInitialSecrets]'s job (Initial packets; that's the only packet-
 * number space whose keys are derivable without a completed TLS
 * handshake). Callers combine the two: build the cleartext header here,
 * hand it to the crypto layer to encrypt+protect for sending, or take
 * bytes off the wire, remove header protection first, then parse here.
 */
object QuicPacket {
    const val TYPE_INITIAL = 0x00
    const val TYPE_ZERO_RTT = 0x01
    const val TYPE_HANDSHAKE = 0x02
    const val TYPE_RETRY = 0x03

    /** QUIC version 1 (RFC 9000), the only version this client speaks. */
    const val VERSION_1 = 0x00000001

    /** The smallest a client Initial UDP datagram is allowed to be (RFC 9000 14.1) - padded up to this with PADDING frames. */
    const val MIN_INITIAL_DATAGRAM_SIZE = 1200

    /**
     * Encodes the number of bytes in [packetNumber] that the given length
     * needs (1-4, chosen by the caller based on how far behind the peer's
     * last acknowledgment is - this client always uses a fixed length for
     * simplicity, see [Http3Client]).
     */
    fun encodePacketNumber(packetNumber: Long, length: Int): ByteArray {
        require(length in 1..4)
        return ByteArray(length) { i -> (packetNumber shr (8 * (length - 1 - i))).toByte() }
    }

    /**
     * Builds the cleartext long-header bytes (everything up to and including the packet number),
     * ready to be concatenated with the plaintext payload for AEAD encryption (as associated
     * data covering the header) or, after encryption, with the ciphertext for sending.
     *
     * [payloadLength] is the length of the *protected* payload (ciphertext + AEAD tag) that will
     * follow, needed up front because the RFC 9000 `Length` field covers packet-number + payload
     * and the header protection sample depends on where the packet number field ends.
     */
    fun buildHeader(
        type: Int,
        version: Int,
        destConnectionId: ByteArray,
        srcConnectionId: ByteArray,
        token: ByteArray?,
        packetNumber: Long,
        packetNumberLength: Int,
        payloadLength: Int
    ): ByteArray {
        require(destConnectionId.size <= 20 && srcConnectionId.size <= 20)
        val out = ByteArrayOutputStream()
        // Header form=1 (long), fixed bit=1, type in bits 4-5, reserved bits=0, pn-length-1 in low 2 bits.
        val firstByte = 0xC0 or (type shl 4) or (packetNumberLength - 1)
        out.write(firstByte)
        out.write((version ushr 24) and 0xFF); out.write((version ushr 16) and 0xFF)
        out.write((version ushr 8) and 0xFF); out.write(version and 0xFF)
        out.write(destConnectionId.size)
        out.write(destConnectionId)
        out.write(srcConnectionId.size)
        out.write(srcConnectionId)
        if (type == TYPE_INITIAL) {
            val tok = token ?: ByteArray(0)
            QuicVarInt.encodeTo(out, tok.size.toLong())
            out.write(tok)
        }
        QuicVarInt.encodeTo(out, (packetNumberLength + payloadLength).toLong())
        out.write(encodePacketNumber(packetNumber, packetNumberLength))
        return out.toByteArray()
    }

    /** A parsed long-header packet whose header protection has already been removed. */
    data class ParsedLongHeader(
        val type: Int,
        val version: Int,
        val destConnectionId: ByteArray,
        val srcConnectionId: ByteArray,
        val token: ByteArray,
        val packetNumberLength: Int,
        val packetNumber: Long,
        /** Byte offset into the original datagram where the (still-protected-payload) packet payload begins. */
        val payloadOffset: Int,
        /** Total length of this packet within the datagram (it may be one of several coalesced packets). */
        val packetLength: Int,
        /** The cleartext header bytes (through the packet number) - used as AEAD associated data. */
        val headerBytes: ByteArray
    )

    /**
     * Parses the *cleartext* portion of a long header at [offset] in [datagram], given that
     * header protection has already been removed in place (so the packet-number-length bits
     * and packet number bytes are genuine). Does not touch the payload.
     */
    fun parseLongHeader(datagram: ByteArray, offset: Int): ParsedLongHeader {
        val reader = QuicByteReader(datagram, offset)
        val first = reader.readByte()
        require(first and 0x80 != 0) { "not a long header packet" }
        val type = (first shr 4) and 0x03
        val pnLength = (first and 0x03) + 1
        val version = (reader.readByte() shl 24) or (reader.readByte() shl 16) or
            (reader.readByte() shl 8) or reader.readByte()
        val dcidLen = reader.readByte()
        val dcid = reader.readBytes(dcidLen)
        val scidLen = reader.readByte()
        val scid = reader.readBytes(scidLen)
        val token = if (type == TYPE_INITIAL) {
            val tokenLen = reader.readVarInt().toInt()
            reader.readBytes(tokenLen)
        } else {
            ByteArray(0)
        }
        val length = reader.readVarInt().toInt() // packet-number length + payload length
        val headerEndBeforePn = reader.position
        val pnBytes = reader.readBytes(pnLength)
        var pn = 0L
        for (b in pnBytes) pn = (pn shl 8) or (b.toLong() and 0xFF)
        val headerBytes = datagram.copyOfRange(offset, reader.position)
        return ParsedLongHeader(
            type = type,
            version = version,
            destConnectionId = dcid,
            srcConnectionId = scid,
            token = token,
            packetNumberLength = pnLength,
            packetNumber = pn,
            payloadOffset = reader.position,
            packetLength = (reader.position - offset) + (length - pnLength),
            headerBytes = headerBytes
        ).also { require(headerEndBeforePn <= reader.position) }
    }
}

/**
 * Short-header (1-RTT) packet framing (RFC 9000 17.3) - the format used once the handshake is
 * complete. This client's scope never gets that far without real TLS keys (see
 * [Http3TlsHandshake]), so this is a structural placeholder documenting the target shape rather
 * than a load-bearing part of the current request path.
 */
object QuicShortHeaderPacket {
    /** Builds the cleartext short header: form=0, fixed=1, spin bit=0, key phase=0, pn-length-1. */
    fun buildHeader(destConnectionId: ByteArray, packetNumber: Long, packetNumberLength: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x40 or (packetNumberLength - 1))
        out.write(destConnectionId)
        out.write(QuicPacket.encodePacketNumber(packetNumber, packetNumberLength))
        return out.toByteArray()
    }
}
