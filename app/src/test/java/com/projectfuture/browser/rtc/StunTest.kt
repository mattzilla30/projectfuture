package com.projectfuture.browser.rtc

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Direct tests of the RFC 5389 STUN message codec and client - real wire format, real HMAC-SHA1/CRC-32, no mocking. */
class StunTest {
    @Test fun bindingRequestRoundTripsThroughEncodeDecode() {
        val request = StunMessage.bindingRequest()
        val encoded = request.encode()
        val decoded = StunMessage.decode(encoded)
        assertNotNull(decoded)
        assertEquals(StunMessage.BINDING_REQUEST, decoded!!.type)
        assertArrayEquals(request.transactionId, decoded.transactionId)
    }

    @Test fun xorMappedAddressRoundTrips() {
        val addr = InetSocketAddress(InetAddress.getByName("203.0.113.7"), 54321)
        val request = StunMessage.bindingRequest()
        val response = StunMessage.bindingSuccessResponse(request).addXorMappedAddress(addr)
        val decoded = StunMessage.decode(response.encode())!!
        val recovered = decoded.xorMappedAddress()
        assertNotNull(recovered)
        assertEquals(addr.port, recovered!!.port)
        assertEquals("203.0.113.7", recovered.address.hostAddress)
    }

    @Test fun messageIntegrityIsVerifiable() {
        val key = "the-remote-agents-ice-password".toByteArray()
        val request = StunMessage.bindingRequest().addUsername("frag2:frag1")
        val encoded = request.encode(integrityKey = key)
        val decoded = StunMessage.decode(encoded)!!
        assertTrue(StunMessage.verifyIntegrity(decoded, encoded, encoded.size, key))
    }

    @Test fun messageIntegrityFailsWithWrongKey() {
        val request = StunMessage.bindingRequest().addUsername("frag2:frag1")
        val encoded = request.encode(integrityKey = "correct-password".toByteArray())
        val decoded = StunMessage.decode(encoded)!!
        assertFalse(StunMessage.verifyIntegrity(decoded, encoded, encoded.size, "wrong-password".toByteArray()))
    }

    @Test fun validFingerprintIsAccepted() {
        val encoded = StunMessage.bindingRequest().addUseCandidate().encode()
        val decoded = StunMessage.decode(encoded)
        assertNotNull("an untampered message with a correct FINGERPRINT must decode", decoded)
    }

    @Test fun fingerprintRejectsATamperedMessage() {
        val encoded = StunMessage.bindingRequest().addUseCandidate().encode()
        val tampered = encoded.copyOf()
        // Corrupt a byte inside USE-CANDIDATE's TLV, which FINGERPRINT covers but is itself
        // untouched - the sort of single-bit wire corruption/tampering FINGERPRINT exists to catch
        // (RFC 5389 section 15.5). decode() must refuse to hand back a message whose payload no
        // longer matches its own checksum, not silently accept corrupted/tampered STUN traffic.
        tampered[21] = (tampered[21] + 1).toByte()
        assertNull("decode() must reject a message whose FINGERPRINT no longer matches its covered bytes", StunMessage.decode(tampered))
    }

    @Test fun fingerprintVerificationDirectlyRejectsTamperedBytes() {
        val encoded = StunMessage.bindingRequest().addUseCandidate().addPriority(12345L).encode()
        val message = StunMessage.decode(encoded)!!
        assertTrue(StunMessage.verifyFingerprint(message, encoded, encoded.size))
        val tampered = encoded.copyOf()
        tampered[24] = (tampered[24] + 1).toByte()
        // verifyFingerprint is handed the decoded message (still carrying the original, un-tampered
        // FINGERPRINT attribute value) alongside the tampered raw bytes - it must recompute against
        // the raw bytes and disagree, not trust the attribute it already parsed out.
        val decodedFromTampered = StunMessage(message.type, message.transactionId, message.attributes)
        assertFalse(StunMessage.verifyFingerprint(decodedFromTampered, tampered, tampered.size))
    }

    @Test fun garbageIsNotMisparsedAsStun() {
        val garbage = ByteArray(30) { it.toByte() }
        assertNull(StunMessage.decode(garbage))
    }

    @Test fun priorityAndUseCandidateAndControlAttributesRoundTrip() {
        val msg = StunMessage.bindingRequest()
            .addUsername("a:b")
            .addPriority(2113937151L)
            .addUseCandidate()
            .addIceControlling(123456789L)
        val decoded = StunMessage.decode(msg.encode(integrityKey = "pwd".toByteArray()))!!
        assertNotNull(decoded.attribute(StunMessage.ATTR_USE_CANDIDATE))
        val priorityBytes = decoded.attribute(StunMessage.ATTR_PRIORITY)!!
        val priority = ((priorityBytes[0].toInt() and 0xFF) shl 24) or ((priorityBytes[1].toInt() and 0xFF) shl 16) or
            ((priorityBytes[2].toInt() and 0xFF) shl 8) or (priorityBytes[3].toInt() and 0xFF)
        assertEquals(2113937151L, priority.toLong() and 0xFFFFFFFFL)
    }

    @Test fun clientGetsServerReflexiveAddressFromARealStunServerOverLoopback() {
        // A tiny in-process STUN server standing in for a public one (stun.l.google.com is not
        // reachable from this sandboxed test environment - see IceAgent's class doc on TURN for
        // the same reasoning) - it speaks real RFC 5389 wire format on both ends, so this proves
        // StunClient/StunMessage genuinely interoperate, just not across the open internet here.
        val serverSocket = DatagramSocket(0)
        val serverThread = Thread {
            val buf = ByteArray(2048)
            val packet = DatagramPacket(buf, buf.size)
            serverSocket.receive(packet)
            val request = StunMessage.decode(packet.data, packet.length)!!
            val response = StunMessage.bindingSuccessResponse(request)
                .addXorMappedAddress(InetSocketAddress(packet.address, packet.port))
            val out = response.encode()
            serverSocket.send(DatagramPacket(out, out.size, packet.address, packet.port))
        }.apply { isDaemon = true; start() }

        val clientSocket = DatagramSocket(0)
        val clientPort = clientSocket.localPort
        val reflexive = StunClient.discoverServerReflexiveAddress(clientSocket, InetSocketAddress(InetAddress.getByName("127.0.0.1"), serverSocket.localPort))
        serverThread.join(2000)
        clientSocket.close()
        serverSocket.close()

        assertNotNull("a real STUN transaction against a loopback server should yield a reflexive address", reflexive)
        assertEquals(clientPort, reflexive!!.port)
    }
}
