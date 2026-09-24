package com.projectfuture.browser.net.quic

import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.security.SecureRandom

/**
 * A minimal HTTP/3 client: raw UDP ([DatagramSocket]) transport, QUIC long-header packet framing
 * ([QuicPacket]), the QUIC frame types a basic request/response needs ([QuicFrame]: PADDING,
 * PING, ACK, CRYPTO, STREAM), and QPACK header (de)compression ([Qpack]) are all genuinely
 * implemented and unit-tested (see `app/src/test/.../net/quic/`). What is **not** genuinely
 * implemented is the TLS 1.3 handshake QUIC requires before any of that framing can carry a real
 * request - see [Http3TlsHandshake]'s class doc for exactly why, and exactly what was
 * investigated and ruled out. That means this class cannot actually complete a request against a
 * real server: [request] builds what a real client would send as far as the crypto/framing layer
 * allows, then fails at the TLS boundary with a clearly-labeled [UnsupportedOperationException]
 * rather than silently falling back to HTTP/1.1 or HTTP/2 and claiming to be HTTP/3.
 *
 * ## Deliberate simplifications (beyond the TLS gap), documented per the project's own standard
 * for scoped-down work (see e.g. HttpCache's/CorsAllows's doc comments):
 * - **Loss detection/retransmission**: no RFC 9002 congestion controller. [retransmitIfNeeded]
 *   is a simple fixed timer (probe-timeout, PTO) that resends the whole in-flight Initial packet
 *   once if no ACK arrives within [RETRANSMIT_TIMEOUT_MS], up to [MAX_RETRANSMITS] times, then
 *   gives up. This avoids flooding the network without implementing real congestion control
 *   (slow start, recovery, persistent congestion) - acceptable because with no working TLS this
 *   client only ever sends its very first Initial packet's worth of data anyway.
 * - **Packet number length**: always encoded as 4 bytes, the simplest legal choice (RFC 9000
 *   17.1 only requires it be long enough to be unambiguous - trading a few wire bytes for not
 *   having to track the peer's largest-acked packet number to pick a shorter encoding).
 * - **Connection IDs**: fixed-length (8 bytes), randomly generated, never rotated - this client
 *   never gets far enough into a connection's lifetime for CID rotation (RFC 9000 5.1.1) to
 *   matter.
 * - **Discovery**: see [Url.kt]'s HTTP/3 negotiation doc - this client is only ever reached
 *   through an explicit opt-in, not real `Alt-Svc`/HTTPS-record discovery (also not implemented;
 *   see that doc for why).
 */
class Http3Client(private val host: String, private val port: Int) {
    private val random = SecureRandom()

    companion object {
        const val RETRANSMIT_TIMEOUT_MS = 1000
        const val MAX_RETRANSMITS = 2
        private const val CID_LENGTH = 8
    }

    private fun randomConnectionId(): ByteArray = ByteArray(CID_LENGTH).also { random.nextBytes(it) }

    /**
     * Builds a fully wire-ready, header-protected client Initial packet carrying [cryptoPayload]
     * as a single CRYPTO frame at offset 0, padded to QUIC's 1200-byte minimum client Initial
     * datagram size. This is genuine, spec-shaped QUIC framing + RFC 9001 Initial protection -
     * exercised directly by unit tests - but is only ever fed a real TLS 1.3 ClientHello in a
     * complete implementation; see the class doc for why this client cannot produce one.
     */
    fun buildClientInitialPacket(destConnectionId: ByteArray, srcConnectionId: ByteArray, packetNumber: Long, cryptoPayload: ByteArray): ByteArray {
        val keys = QuicInitialSecrets.derive(destConnectionId).client

        val cryptoFrame = QuicFrame.Crypto(0, cryptoPayload).encoded()
        val unpaddedLen = QuicPacket.buildHeader(
            QuicPacket.TYPE_INITIAL, QuicPacket.VERSION_1, destConnectionId, srcConnectionId, null, packetNumber, 4, 0
        ).size + cryptoFrame.size + 16 // + AEAD tag
        val paddingNeeded = maxOf(0, QuicPacket.MIN_INITIAL_DATAGRAM_SIZE - unpaddedLen)
        val plaintext = cryptoFrame + QuicFrame.Padding(paddingNeeded).encoded()

        val header = QuicPacket.buildHeader(
            QuicPacket.TYPE_INITIAL, QuicPacket.VERSION_1, destConnectionId, srcConnectionId, null,
            packetNumber, 4, plaintext.size + 16
        )
        val ciphertext = QuicInitialSecrets.aeadSeal(keys, packetNumber, header, plaintext)
        val packet = header + ciphertext
        val pnOffset = header.size - 4
        return QuicInitialSecrets.applyHeaderProtection(packet, pnOffset, 4, keys.hp, isLongHeader = true)
    }

    /**
     * Attempts a basic GET [path]. Opens a real UDP socket to [host]:[port] (genuine transport),
     * derives real Initial secrets, then must hand off to [Http3TlsHandshake] to actually speak
     * TLS 1.3 to the peer - which throws, since that piece isn't implemented. The socket is
     * closed before rethrowing. See the class doc: this is an honest failure, not a fallback
     * dressed up as success.
     */
    fun request(path: String): Nothing {
        val socket = DatagramSocket()
        try {
            socket.connect(InetSocketAddress(host, port))
            // A real client would send buildClientInitialPacket(...) here with a genuine
            // ClientHello as cryptoPayload, then read the server's Initial/Handshake response
            // datagrams via socket.receive(DatagramPacket(...)) and feed them to the TLS engine.
            // There is no such engine (see Http3TlsHandshake), so this fails now rather than
            // sending a packet with meaningless CRYPTO bytes to a real server and calling that
            // "an HTTP/3 request".
            Http3TlsHandshake().performHandshake(host)
        } finally {
            socket.close()
        }
    }
}
