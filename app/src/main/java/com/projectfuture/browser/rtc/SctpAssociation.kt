package com.projectfuture.browser.rtc

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * A real (if deliberately scoped-down) RFC 4960 SCTP association: the
 * genuine four-way handshake (INIT / INIT-ACK-with-state-cookie /
 * COOKIE-ECHO / COOKIE-ACK, section 5.1), real per-stream stream IDs so
 * multiple `RTCDataChannel`s are actually distinct SCTP streams
 * multiplexed on one association (section 1.3's whole point), and
 * TSN-numbered DATA chunks acknowledged by cumulative-TSN SACKs (sections
 * 3.3.4/6.2) providing genuinely reliable, in-order delivery.
 *
 * **What's simplified, precisely:**
 * - **One DATA chunk in flight at a time**, association-wide (a
 *   send blocks until that chunk's TSN is cumulatively SACKed or
 *   retransmit attempts are exhausted) - there is no congestion window,
 *   no slow start/congestion avoidance (RFC 4960 section 7), and no
 *   multiple-streams-in-parallel pipelining. This keeps every stream's
 *   delivery trivially ordered (the receiver never has to buffer
 *   out-of-order TSNs) without implementing RFC 4960's full reordering
 *   queue, at the cost of every data channel on the association
 *   serializing through one global send queue - a real correctness/
 *   throughput trade the task's own framing explicitly allows ("get
 *   correctness right for the reliable-ordered case at minimum").
 * - **No partial reliability (PR-SCTP, RFC 3758)** and no unordered
 *   delivery mode - every message is sent reliable-and-ordered
 *   regardless of what `RTCDataChannelInit` asked for, same limitation
 *   PeerConnectionCore already documented before this task for its ARQ
 *   layer, now true of a real SCTP layer instead of a bespoke one.
 * - **No SCTP-level heartbeats/path MTU discovery/multi-homing** -
 *   this association has exactly one path (the single DTLS-wrapped UDP
 *   socket ICE selected).
 * - Retransmission is timeout-based with exponential backoff, not
 *   RTT-estimated (RFC 4960 section 6.3.1's RTO calculation).
 */
class SctpAssociation(
    private val isInitiator: Boolean,
    private val sourcePort: Int,
    private val destPort: Int,
    private val sendRaw: (ByteArray) -> Unit
) {
    enum class State { CLOSED, COOKIE_WAIT, COOKIE_ECHOED, ESTABLISHED }

    @Volatile var state: State = State.CLOSED
        private set

    var onStreamMessage: ((Int, ByteArray) -> Unit)? = null
    var onEstablished: (() -> Unit)? = null

    private val random = SecureRandom()
    private val localVerificationTag = (random.nextInt(Int.MAX_VALUE) + 1).toLong()
    @Volatile private var peerVerificationTag = 0L
    private val localInitialTsn = (random.nextInt(1 shl 20) + 1).toLong()
    private val nextTsn = AtomicLong(localInitialTsn)
    @Volatile private var expectedTsn = 0L
    @Volatile private var expectedCookie: ByteArray? = null

    private val streamSendSeq = ConcurrentHashMap<Int, Int>()
    private val sendLock = Any()
    @Volatile private var pendingAckTsn: Long = -1
    @Volatile private var pendingAckLatch: CountDownLatch? = null

    private val establishLatch = CountDownLatch(1)

    fun start() {
        if (isInitiator) {
            state = State.COOKIE_WAIT
            send(SctpPacket(sourcePort, destPort, 0, listOf(
                SctpChunk.Init(localVerificationTag, 131072, 65535, 65535, localInitialTsn)
            )))
        }
    }

    fun awaitEstablished(timeoutMs: Long): Boolean = establishLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    /** Feeds one decrypted (post-DTLS) datagram's bytes into the association. */
    fun handleIncoming(bytes: ByteArray) {
        val packet = SctpPacket.decode(bytes) ?: return
        for (chunk in packet.chunks) {
            when (chunk) {
                is SctpChunk.Init -> handleInit(chunk)
                is SctpChunk.InitAck -> handleInitAck(chunk)
                is SctpChunk.CookieEcho -> handleCookieEcho(chunk)
                is SctpChunk.CookieAck -> handleCookieAck()
                is SctpChunk.Data -> handleData(chunk)
                is SctpChunk.Sack -> handleSack(chunk)
                is SctpChunk.Unknown -> {} // RFC 4960 section 3.2: unknown chunk type 00 => skip
            }
        }
    }

    private fun handleInit(chunk: SctpChunk.Init) {
        peerVerificationTag = chunk.initiateTag
        peerInitialTsnReceived(chunk.initialTsn)
        val cookie = ByteArray(16).also { random.nextBytes(it) }
        expectedCookie = cookie
        // RFC 4960 section 8.5.1: the INIT ACK is sent with the Initiate Tag from the INIT as its
        // verification tag, since we have no association (and thus no shared tag) yet.
        send(SctpPacket(sourcePort, destPort, chunk.initiateTag, listOf(
            SctpChunk.InitAck(localVerificationTag, 131072, 65535, 65535, localInitialTsn, cookie)
        )))
    }

    private fun handleInitAck(chunk: SctpChunk.InitAck) {
        if (state != State.COOKIE_WAIT) return
        peerVerificationTag = chunk.initiateTag
        peerInitialTsnReceived(chunk.initialTsn)
        state = State.COOKIE_ECHOED
        send(SctpPacket(sourcePort, destPort, peerVerificationTag, listOf(SctpChunk.CookieEcho(chunk.cookie))))
    }

    private fun handleCookieEcho(chunk: SctpChunk.CookieEcho) {
        val expected = expectedCookie
        if (expected != null && !expected.contentEquals(chunk.cookie)) return
        send(SctpPacket(sourcePort, destPort, peerVerificationTag, listOf(SctpChunk.CookieAck)))
        markEstablished()
    }

    private fun handleCookieAck() {
        if (state != State.COOKIE_ECHOED) return
        markEstablished()
    }

    private fun markEstablished() {
        if (state == State.ESTABLISHED) return
        state = State.ESTABLISHED
        onEstablished?.invoke()
        establishLatch.countDown()
    }

    private fun peerInitialTsnReceived(tsn: Long) {
        expectedTsn = tsn
    }

    private fun handleData(chunk: SctpChunk.Data) {
        when {
            chunk.tsn == expectedTsn -> {
                expectedTsn++
                onStreamMessage?.invoke(chunk.streamId, chunk.payload)
                sendSack()
            }
            chunk.tsn < expectedTsn -> sendSack() // duplicate of an already-delivered chunk: re-ack so the sender's retransmit loop stops
            else -> sendSack() // a TSN ahead of what's expected can't happen with one chunk in flight at a time; ack what we do have
        }
    }

    private fun sendSack() {
        send(SctpPacket(sourcePort, destPort, peerVerificationTag, listOf(
            SctpChunk.Sack(cumulativeTsnAck = expectedTsn - 1, aRwnd = 131072)
        )))
    }

    private fun handleSack(chunk: SctpChunk.Sack) {
        if (pendingAckTsn != -1L && chunk.cumulativeTsnAck >= pendingAckTsn) {
            pendingAckLatch?.countDown()
        }
    }

    /** Sends [payload] reliably and in order as one SCTP DATA chunk on SCTP stream [streamId] (a real per-channel stream, per this class's doc). Blocks the caller until acknowledged or retries are exhausted. */
    fun sendMessage(streamId: Int, payload: ByteArray, timeoutMs: Long = 300L, retries: Int = 8): Boolean {
        if (!establishLatch.await(5, TimeUnit.SECONDS)) return false
        synchronized(sendLock) {
            val seq = streamSendSeq.getOrDefault(streamId, 0)
            val tsn = nextTsn.getAndIncrement()
            val chunk = SctpChunk.Data(tsn, streamId, seq, ppid = 51 /* WebRTC String PPID, RFC 8831 */, unordered = false, beginning = true, ending = true, payload = payload)
            val packetBytes = SctpPacket(sourcePort, destPort, peerVerificationTag, listOf(chunk)).encode()

            pendingAckTsn = tsn
            val latch = CountDownLatch(1)
            pendingAckLatch = latch
            var timeout = timeoutMs
            try {
                repeat(retries) {
                    sendRaw(packetBytes)
                    if (latch.await(timeout, TimeUnit.MILLISECONDS)) {
                        streamSendSeq[streamId] = seq + 1
                        return true
                    }
                    timeout = (timeout * 2).coerceAtMost(4000)
                }
                return false
            } finally {
                pendingAckTsn = -1
                pendingAckLatch = null
            }
        }
    }

    private fun send(packet: SctpPacket) {
        try {
            sendRaw(packet.encode())
        } catch (_: Exception) {
        }
    }
}
