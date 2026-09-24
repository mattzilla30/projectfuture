package com.projectfuture.browser.rtc

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.zip.CRC32
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A real RFC 5389 STUN message: binary header + TLV attributes, including
 * the two attributes ICE actually depends on for security -
 * MESSAGE-INTEGRITY (HMAC-SHA1, RFC 5389 section 15.4) and FINGERPRINT
 * (CRC-32, section 15.5) - and the ones RFC 8445 connectivity checks use on
 * top of plain STUN binding requests (PRIORITY, USE-CANDIDATE,
 * ICE-CONTROLLING/ICE-CONTROLLED). This is wire-compatible with any real
 * STUN implementation (a public server like `stun.l.google.com:19302`, or
 * another browser's ICE agent) for the subset of attributes implemented
 * here - it is not a reduced "looks like STUN" imitation.
 */
class StunMessage(
    val type: Int,
    val transactionId: ByteArray,
    val attributes: MutableList<Pair<Int, ByteArray>> = ArrayList()
) {
    fun addXorMappedAddress(addr: InetSocketAddress) = apply {
        attributes.add(ATTR_XOR_MAPPED_ADDRESS to encodeXorAddress(addr, transactionId))
    }

    fun addUsername(username: String) = apply {
        attributes.add(ATTR_USERNAME to username.toByteArray(StandardCharsets.UTF_8))
    }

    fun addPriority(priority: Long) = apply {
        attributes.add(ATTR_PRIORITY to ByteBuffer.allocate(4).putInt(priority.toInt()).array())
    }

    fun addUseCandidate() = apply {
        attributes.add(ATTR_USE_CANDIDATE to ByteArray(0))
    }

    fun addIceControlling(tieBreaker: Long) = apply {
        attributes.add(ATTR_ICE_CONTROLLING to ByteBuffer.allocate(8).putLong(tieBreaker).array())
    }

    fun addIceControlled(tieBreaker: Long) = apply {
        attributes.add(ATTR_ICE_CONTROLLED to ByteBuffer.allocate(8).putLong(tieBreaker).array())
    }

    fun attribute(type: Int): ByteArray? = attributes.firstOrNull { it.first == type }?.second

    fun xorMappedAddress(): InetSocketAddress? =
        attribute(ATTR_XOR_MAPPED_ADDRESS)?.let { decodeXorAddress(it, transactionId) }

    /**
     * Serializes this message. When [integrityKey] is non-null a
     * MESSAGE-INTEGRITY attribute (HMAC-SHA1 over everything before it,
     * with the STUN length field temporarily set as if MESSAGE-INTEGRITY
     * were already the last attribute, per RFC 5389 section 15.4) is
     * appended, then a FINGERPRINT attribute is always appended last.
     */
    fun encode(integrityKey: ByteArray? = null): ByteArray {
        val attrsBuf = ByteBuffer.allocate(4096)
        for ((attrType, value) in attributes) writeAttr(attrsBuf, attrType, value)

        if (integrityKey != null) {
            // Message-integrity covers the header + attributes so far, with length set to
            // include the 24-byte MESSAGE-INTEGRITY attribute (4 header + 20 HMAC) itself.
            val lengthWithIntegrity = attrsBuf.position() + 24
            val header = buildHeader(lengthWithIntegrity)
            val toSign = ByteBuffer.allocate(header.size + attrsBuf.position())
            toSign.put(header)
            toSign.put(attrsBuf.array(), 0, attrsBuf.position())
            val hmac = hmacSha1(integrityKey, toSign.array())
            writeAttr(attrsBuf, ATTR_MESSAGE_INTEGRITY, hmac)
        }

        // Fingerprint covers everything before it, with length set to include the
        // 8-byte FINGERPRINT attribute itself.
        val lengthWithFingerprint = attrsBuf.position() + 8
        val headerForCrc = buildHeader(lengthWithFingerprint)
        val forCrc = ByteBuffer.allocate(headerForCrc.size + attrsBuf.position())
        forCrc.put(headerForCrc)
        forCrc.put(attrsBuf.array(), 0, attrsBuf.position())
        val crc = CRC32()
        crc.update(forCrc.array())
        val fingerprint = (crc.value.toInt() xor FINGERPRINT_XOR)
        writeAttr(attrsBuf, ATTR_FINGERPRINT, ByteBuffer.allocate(4).putInt(fingerprint).array())

        val out = ByteBuffer.allocate(20 + attrsBuf.position())
        out.put(buildHeader(attrsBuf.position()))
        out.put(attrsBuf.array(), 0, attrsBuf.position())
        return out.array()
    }

    private fun buildHeader(attrsLength: Int): ByteArray {
        val h = ByteBuffer.allocate(20)
        h.putShort(type.toShort())
        h.putShort(attrsLength.toShort())
        h.putInt(MAGIC_COOKIE)
        h.put(transactionId)
        return h.array()
    }

    private fun writeAttr(buf: ByteBuffer, type: Int, value: ByteArray) {
        buf.putShort(type.toShort())
        buf.putShort(value.size.toShort())
        buf.put(value)
        val pad = (4 - value.size % 4) % 4
        repeat(pad) { buf.put(0) }
    }

    companion object {
        const val BINDING_REQUEST = 0x0001
        const val BINDING_SUCCESS_RESPONSE = 0x0101
        const val BINDING_ERROR_RESPONSE = 0x0111

        const val ATTR_MAPPED_ADDRESS = 0x0001
        const val ATTR_USERNAME = 0x0006
        const val ATTR_MESSAGE_INTEGRITY = 0x0008
        const val ATTR_ERROR_CODE = 0x0009
        const val ATTR_XOR_MAPPED_ADDRESS = 0x0020
        const val ATTR_PRIORITY = 0x0024
        const val ATTR_USE_CANDIDATE = 0x0025
        const val ATTR_FINGERPRINT = 0x8028
        const val ATTR_ICE_CONTROLLED = 0x8029
        const val ATTR_ICE_CONTROLLING = 0x802A

        const val MAGIC_COOKIE = 0x2112A442.toInt()
        private const val FINGERPRINT_XOR = 0x5354554E

        private val random = SecureRandom()

        fun newTransactionId(): ByteArray = ByteArray(12).also { random.nextBytes(it) }

        fun bindingRequest(): StunMessage = StunMessage(BINDING_REQUEST, newTransactionId())

        fun bindingSuccessResponse(request: StunMessage): StunMessage =
            StunMessage(BINDING_SUCCESS_RESPONSE, request.transactionId)

        /** Parses a datagram's bytes as a STUN message, or returns null if it isn't one (RFC 5389 section 7.3: the magic cookie is how we tell STUN traffic apart from anything else sharing the socket). */
        fun decode(data: ByteArray, length: Int = data.size): StunMessage? {
            if (length < 20) return null
            val buf = ByteBuffer.wrap(data, 0, length)
            val type = buf.short.toInt() and 0xFFFF
            val attrsLength = buf.short.toInt() and 0xFFFF
            val cookie = buf.int
            if (cookie != MAGIC_COOKIE) return null
            val txId = ByteArray(12)
            buf.get(txId)
            if (20 + attrsLength > length) return null
            val attrs = ArrayList<Pair<Int, ByteArray>>()
            var remaining = attrsLength
            while (remaining >= 4) {
                val attrType = buf.short.toInt() and 0xFFFF
                val attrLen = buf.short.toInt() and 0xFFFF
                if (attrLen > remaining - 4) break
                val value = ByteArray(attrLen)
                buf.get(value)
                attrs.add(attrType to value)
                val pad = (4 - attrLen % 4) % 4
                if (pad > 0) buf.position(buf.position() + pad)
                remaining -= 4 + attrLen + pad
            }
            val message = StunMessage(type, txId, attrs)
            // RFC 5389 section 15.5: an agent that adds FINGERPRINT to messages it sends (this one
            // always does, see encode()) uses it to discard bit-corrupted/tampered traffic on
            // receipt too - a message whose FINGERPRINT is present but doesn't match its own
            // covered bytes is not real STUN traffic that survived the wire intact and must not be
            // trusted just because it happens to decode structurally.
            if (!verifyFingerprint(message, data, length)) return null
            return message
        }

        /** Verifies a decoded message's FINGERPRINT attribute against [rawPacket], per RFC 5389 section 15.5. A message with no FINGERPRINT attribute at all passes (not every STUN message is required to carry one) - only a *present but wrong* FINGERPRINT is rejected. */
        fun verifyFingerprint(message: StunMessage, rawPacket: ByteArray, rawLength: Int): Boolean {
            val fp = message.attribute(ATTR_FINGERPRINT) ?: return true
            if (fp.size != 4) return false
            val fpOffset = indexOfAttribute(rawPacket, rawLength, ATTR_FINGERPRINT) ?: return false
            // Recompute over the header+attributes as they were when the fingerprint was computed:
            // the raw bytes up to (not including) the FINGERPRINT attribute's TLV, with the length
            // field patched to what it was at computation time (this attribute present, nothing
            // after it - FINGERPRINT is always the last attribute per encode()).
            val forCrc = rawPacket.copyOf(fpOffset)
            val lengthAtComputation = (fpOffset - 20) + 8
            forCrc[2] = (lengthAtComputation shr 8).toByte()
            forCrc[3] = (lengthAtComputation and 0xFF).toByte()
            val crc = CRC32()
            crc.update(forCrc)
            val expected = crc.value.toInt() xor FINGERPRINT_XOR
            val actual = ((fp[0].toInt() and 0xFF) shl 24) or ((fp[1].toInt() and 0xFF) shl 16) or
                ((fp[2].toInt() and 0xFF) shl 8) or (fp[3].toInt() and 0xFF)
            return expected == actual
        }

        /** Verifies a decoded message's MESSAGE-INTEGRITY attribute against [rawPacket] and [key], per RFC 5389 section 15.4. */
        fun verifyIntegrity(message: StunMessage, rawPacket: ByteArray, rawLength: Int, key: ByteArray): Boolean {
            val mi = message.attribute(ATTR_MESSAGE_INTEGRITY) ?: return false
            // Recompute over the header+attributes as they were when signed: the raw bytes up to
            // (not including) the MESSAGE-INTEGRITY attribute's TLV, with the length field patched
            // to what it was at signing time (this attribute present, nothing after it).
            val miOffset = indexOfAttribute(rawPacket, rawLength, ATTR_MESSAGE_INTEGRITY) ?: return false
            val patched = rawPacket.copyOf(miOffset)
            val lengthAtSigning = (miOffset - 20) + 24
            patched[2] = (lengthAtSigning shr 8).toByte()
            patched[3] = (lengthAtSigning and 0xFF).toByte()
            val expected = hmacSha1(key, patched)
            return expected.contentEquals(mi)
        }

        private fun indexOfAttribute(data: ByteArray, length: Int, wantType: Int): Int? {
            var pos = 20
            while (pos + 4 <= length) {
                val t = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                val l = ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
                if (t == wantType) return pos
                val pad = (4 - l % 4) % 4
                pos += 4 + l + pad
            }
            return null
        }

        private fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(key, "HmacSHA1"))
            return mac.doFinal(data)
        }

        private fun encodeXorAddress(addr: InetSocketAddress, transactionId: ByteArray): ByteArray {
            val ip = addr.address.address // 4 bytes, IPv4 only (this engine's candidates are always IPv4)
            val buf = ByteBuffer.allocate(8)
            buf.put(0)
            buf.put(1) // family: IPv4
            val xport = (addr.port xor (MAGIC_COOKIE ushr 16)) and 0xFFFF
            buf.putShort(xport.toShort())
            val cookieBytes = ByteBuffer.allocate(4).putInt(MAGIC_COOKIE).array()
            for (i in 0 until 4) buf.put((ip[i].toInt() xor cookieBytes[i].toInt()).toByte())
            return buf.array()
        }

        private fun decodeXorAddress(value: ByteArray, transactionId: ByteArray): InetSocketAddress? {
            if (value.size < 8) return null
            val family = value[1].toInt() and 0xFF
            if (family != 1) return null // only IPv4 server-reflexive addresses are handled
            val xport = ((value[2].toInt() and 0xFF) shl 8) or (value[3].toInt() and 0xFF)
            val port = xport xor (MAGIC_COOKIE ushr 16)
            val cookieBytes = ByteBuffer.allocate(4).putInt(MAGIC_COOKIE).array()
            val ipBytes = ByteArray(4) { i -> (value[4 + i].toInt() xor cookieBytes[i].toInt()).toByte() }
            return InetSocketAddress(InetAddress.getByAddress(ipBytes), port)
        }
    }
}

/**
 * A minimal but real STUN client (RFC 5389 section 7.2): sends a Binding
 * Request over an existing `DatagramSocket`, retransmits with exponential
 * backoff on loss, and returns the server-reflexive `XOR-MAPPED-ADDRESS`
 * from the response. Works against any conformant STUN server, including
 * public ones such as `stun.l.google.com:19302`, and doubles as the
 * transport for ICE connectivity checks in [IceAgent], which are also
 * plain STUN binding request/response exchanges per RFC 8445.
 */
object StunClient {
    /**
     * Performs a single STUN binding transaction and blocks (with a
     * bounded number of retransmits) until a matching response arrives or
     * every attempt is exhausted. [respondTo], if given, is invoked with
     * every non-matching STUN message received on the socket meanwhile
     * (used by [IceAgent] so a socket doing connectivity checks can also
     * service the peer's own checks arriving on the same port).
     */
    fun bindingRequest(
        socket: DatagramSocket,
        target: InetSocketAddress,
        request: StunMessage,
        integrityKey: ByteArray? = null,
        timeoutMs: Long = 300L,
        retries: Int = 4,
        respondTo: ((StunMessage, InetSocketAddress) -> Unit)? = null
    ): StunMessage? {
        val payload = request.encode(integrityKey)
        val originalTimeout = socket.soTimeout
        try {
            var timeout = timeoutMs
            repeat(retries) {
                socket.send(DatagramPacket(payload, payload.size, target.address, target.port))
                val deadline = System.currentTimeMillis() + timeout
                while (true) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    socket.soTimeout = remaining.toInt().coerceAtLeast(1)
                    val buf = ByteArray(2048)
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (_: java.net.SocketTimeoutException) {
                        break
                    }
                    val msg = StunMessage.decode(packet.data, packet.length) ?: continue
                    if (msg.transactionId.contentEquals(request.transactionId) &&
                        (msg.type == StunMessage.BINDING_SUCCESS_RESPONSE || msg.type == StunMessage.BINDING_ERROR_RESPONSE)
                    ) {
                        return msg
                    } else {
                        respondTo?.invoke(msg, InetSocketAddress(packet.address, packet.port))
                    }
                }
                timeout *= 2
            }
            return null
        } finally {
            socket.soTimeout = originalTimeout
        }
    }

    /** Server-reflexive candidate gathering against a public STUN server (RFC 5389 usage 4.3 / RFC 8445 section 5.1.1). Returns null on any failure (no network, server unreachable, timeout) rather than throwing - candidate gathering is best-effort by design. */
    fun discoverServerReflexiveAddress(socket: DatagramSocket, stunServer: InetSocketAddress): InetSocketAddress? {
        return try {
            val response = bindingRequest(socket, stunServer, StunMessage.bindingRequest()) ?: return null
            if (response.type != StunMessage.BINDING_SUCCESS_RESPONSE) return null
            response.xorMappedAddress()
        } catch (_: Exception) {
            null
        }
    }
}
