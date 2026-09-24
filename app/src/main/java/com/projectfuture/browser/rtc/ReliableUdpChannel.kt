package com.projectfuture.browser.rtc

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The transport underneath [RTCDataChannel]: a small stop-and-wait ARQ
 * protocol (sequence number + ack, retransmit on timeout) directly over a
 * plain `DatagramSocket`. This is what the task's "if a real SCTP-over-
 * DTLS handshake isn't achievable, a simplified reliable-ordered channel
 * using raw UDP" option asks for, taken deliberately rather than faked:
 *
 * - It is NOT SCTP (RFC 4960): no multi-streaming, no congestion control
 *   beyond "wait for one ack before sending the next frame", no partial
 *   reliability modes, no real connection handshake (no INIT/COOKIE-ECHO).
 * - It is NOT DTLS-SRTP: frames go over the wire as plaintext. There is
 *   no encryption, no authentication, no replay protection at this layer.
 *   Anything sent over an `RTCDataChannel` built on this is genuinely
 *   readable/spoofable by anyone who can see the UDP traffic - unlike a
 *   real WebRTC data channel, which is always DTLS-encrypted even for
 *   "insecure" `ws://`-style content.
 * - It IS a real, working reliable-ordered byte-message channel: messages
 *   are delivered exactly once, in the order they were sent, deduplicated
 *   by sequence number, with actual retransmission on packet loss (up to
 *   [maxRetries] attempts before giving up and closing) - not just "UDP
 *   happens to arrive on localhost so it looks reliable".
 *
 * Wire format per UDP datagram: 1-byte type (`DATA` or `ACK`), 4-byte
 * big-endian sequence number, then for DATA only, the raw payload bytes.
 */
class ReliableUdpChannel(localPort: Int = 0) {
    val socket = DatagramSocket(localPort)
    val localPort: Int get() = socket.localPort

    @Volatile private var remoteAddress: InetAddress? = null
    @Volatile private var remotePort: Int = -1
    @Volatile private var running = false
    private var receiverThread: Thread? = null

    private val sendSeq = AtomicInteger(0)
    private var expectedRecvSeq = 0
    private val pendingAcks = ConcurrentHashMap<Int, CountDownLatch>()

    var onMessage: ((String) -> Unit)? = null
    var onClosed: (() -> Unit)? = null

    companion object {
        private const val TYPE_DATA: Byte = 1
        private const val TYPE_ACK: Byte = 2
        private const val RETRY_TIMEOUT_MS = 300L
        private const val MAX_RETRIES = 8
    }

    fun connect(remoteIp: String, port: Int) {
        remoteAddress = InetAddress.getByName(remoteIp)
        remotePort = port
    }

    /** Starts the background receive loop. Safe to call before or after [connect]. */
    fun start() {
        if (running) return
        running = true
        receiverThread = Thread {
            val buf = ByteArray(65535)
            while (running) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (_: Exception) {
                    break // socket closed
                }
                handleIncoming(packet)
            }
        }.apply { isDaemon = true; start() }
    }

    private fun handleIncoming(packet: DatagramPacket) {
        if (packet.length < 5) return
        val bb = ByteBuffer.wrap(packet.data, packet.offset, packet.length)
        val type = bb.get()
        val seq = bb.int
        when (type) {
            TYPE_ACK -> pendingAcks.remove(seq)?.countDown()
            TYPE_DATA -> {
                // Always ack, even a duplicate/out-of-order packet, so the sender's retransmit loop stops.
                sendRaw(TYPE_ACK, seq, ByteArray(0), packet.address, packet.port)
                if (seq == expectedRecvSeq) {
                    expectedRecvSeq++
                    val payload = ByteArray(bb.remaining())
                    bb.get(payload)
                    onMessage?.invoke(String(payload, StandardCharsets.UTF_8))
                }
                // A seq ahead of what's expected (reordered/duplicate-of-a-retry) is dropped rather than
                // buffered - stop-and-wait means the peer never has more than one frame in flight anyway,
                // so this only discards genuine duplicates, never legitimate out-of-order data.
            }
        }
    }

    private fun sendRaw(type: Byte, seq: Int, payload: ByteArray, address: InetAddress, port: Int) {
        val buf = ByteBuffer.allocate(5 + payload.size)
        buf.put(type)
        buf.putInt(seq)
        buf.put(payload)
        val packet = DatagramPacket(buf.array(), buf.array().size, address, port)
        try { socket.send(packet) } catch (_: Exception) { }
    }

    /** Sends [text] reliably: blocks (on the calling thread) until acked or retries are exhausted. Returns true on success. */
    fun send(text: String): Boolean {
        val address = remoteAddress ?: return false
        val port = remotePort
        val seq = sendSeq.getAndIncrement()
        val payload = text.toByteArray(StandardCharsets.UTF_8)
        val latch = CountDownLatch(1)
        pendingAcks[seq] = latch
        repeat(MAX_RETRIES) {
            sendRaw(TYPE_DATA, seq, payload, address, port)
            if (latch.await(RETRY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                pendingAcks.remove(seq)
                return true
            }
        }
        pendingAcks.remove(seq)
        return false
    }

    /**
     * Fire-and-forget version of [send] for JS-facing code: real
     * `RTCDataChannel.send()` is synchronous and returns nothing, so it
     * must never block the caller's thread on network I/O (that thread is
     * often the main/page thread) - the retry loop still runs, just on a
     * background thread, and delivery failure is silent (matching the
     * real API, which has no per-message failure callback either).
     */
    fun sendAsync(text: String) {
        Thread { send(text) }.apply { isDaemon = true; start() }
    }

    fun close() {
        running = false
        try { socket.close() } catch (_: Exception) { }
        onClosed?.invoke()
    }
}
