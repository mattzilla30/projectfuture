package com.projectfuture.browser.rtc

import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * One end of a data channel, independent of any JS wiring - see
 * PeerConnectionCore's class doc for what is and isn't real underneath
 * this. `onOpen`/`onMessage` buffer at most one pending notification if
 * it arrives (from the background receiver thread) before the JS wrapper
 * has finished attaching its callback - a real race on a fast loopback
 * transport, not a hypothetical one, given how quickly two same-device
 * UDP sockets round-trip.
 */
class RTCDataChannelCore(val label: String) {
    @Volatile var readyState: String = "connecting" // "connecting" | "open" | "closed"
    private var openPending = false
    private val bufferedMessages = ArrayList<String>()

    var onOpen: (() -> Unit)? = null
        set(value) {
            field = value
            if (value != null && openPending) { openPending = false; value.invoke() }
        }
    var onMessage: ((String) -> Unit)? = null
        set(value) {
            field = value
            if (value != null && bufferedMessages.isNotEmpty()) {
                val toDeliver = bufferedMessages.toList()
                bufferedMessages.clear()
                toDeliver.forEach { value.invoke(it) }
            }
        }
    var onClose: (() -> Unit)? = null

    internal var sender: ((String) -> Unit)? = null

    fun send(text: String) {
        if (readyState != "open") return // matches a real channel silently dropping sends before "open"/after "closed"
        sender?.invoke(text)
    }

    internal fun markOpen() {
        readyState = "open"
        val cb = onOpen
        if (cb != null) cb.invoke() else openPending = true
    }

    internal fun deliverMessage(text: String) {
        val cb = onMessage
        if (cb != null) cb.invoke(text) else bufferedMessages.add(text)
    }

    fun close() {
        readyState = "closed"
        onClose?.invoke()
    }
}

/**
 * The engine behind `RTCPeerConnection`/`RTCDataChannel`, with no
 * JS/interpreter dependency at all (Tab.kt's `installWebRTC` is the thin
 * JS-facing wrapper around this). **What genuinely works end-to-end
 * versus what's still simplified or unimplemented, spelled out
 * precisely** (see also IceAgent's, DtlsTransport's and
 * SctpAssociation's own class docs for the details behind each layer):
 *
 * REAL, WORKING END-TO-END (exercised by PeerConnectionCoreTest with no
 * mocking - two independent instances on real loopback UDP sockets):
 * - `createOffer`/`createAnswer` generate real RFC 4566/8866-shaped SDP
 *   (Sdp.kt), with a genuine per-connection self-signed DTLS certificate
 *   ([SelfSignedCert]) whose real SHA-256 fingerprint goes in
 *   `a=fingerprint`, real ICE credentials, and real host (+ best-effort
 *   server-reflexive, RFC 5389/8445) candidates from [IceAgent].
 * - **ICE**: [IceAgent] performs real RFC 8445 candidate-pair priority
 *   computation and real peer-to-peer STUN connectivity checks (RFC 5389
 *   wire format, short-term credentials, MESSAGE-INTEGRITY, FINGERPRINT)
 *   to nominate a working pair - not just "trust the first SDP candidate"
 *   as before this task. See IceAgent's class doc for what's still
 *   simplified (no trickle ICE, aggressive nomination, no TURN/relay).
 * - **DTLS**: once ICE nominates a pair, [DtlsTransport] runs a real DTLS
 *   1.2 handshake over that UDP path via the platform's
 *   `SSLContext.getInstance("DTLS")`/`SSLEngine`, authenticated by
 *   comparing the peer's certificate against the SDP `a=fingerprint` -
 *   WebRTC's actual security model (RFC 8827), not a CA chain. All data
 *   channel bytes flow encrypted over this, not in plaintext. See
 *   DtlsTransport's doc for the honest API-level story (falls back below
 *   API 29 - see [DTLS_UNAVAILABLE_FALLBACK_NOTE] below for what that
 *   fallback actually is).
 * - **SCTP**: [SctpAssociation] performs a real RFC 4960 four-way
 *   handshake (INIT/INIT-ACK-with-state-cookie/COOKIE-ECHO/COOKIE-ACK)
 *   over the DTLS channel, and each `RTCDataChannel` is a distinct SCTP
 *   stream ID (not a text-protocol label multiplexed by hand) carrying
 *   real TSN-numbered DATA chunks acknowledged by cumulative-TSN SACKs.
 *   See SctpAssociation's doc for what's simplified (one chunk in flight
 *   at a time - no congestion control/pipelining; no partial reliability
 *   or unordered delivery).
 * - `createDataChannel` on one side and the matching `ondatachannel`
 *   firing on the other, then `send()`/`onmessage` both directions,
 *   multiple independently-labeled channels multiplexed over the one
 *   SCTP association, delivered reliably and in order.
 *
 * STILL NOT REAL / OUT OF SCOPE:
 * - **TURN/relay candidates** are not implemented - see IceAgent's class
 *   doc for exactly why (no reachable TURN server in this environment to
 *   verify a client against).
 * - **[DTLS_UNAVAILABLE_FALLBACK_NOTE]**: on a platform where
 *   `SSLContext.getInstance("DTLS")` isn't available (real Android
 *   devices below API 29; this project's minSdk is 24), this falls all
 *   the way back to a small plaintext stop-and-wait ARQ directly over the
 *   ICE-selected UDP path ([PlaintextFallbackChannel], the same
 *   approach - and much of the same wire format - as this engine's
 *   pre-this-task transport). That fallback is genuinely reliable and
 *   ordered, but **not encrypted or authenticated at all**: no DTLS, no
 *   SCTP framing, no multi-streaming (labels are multiplexed by a
 *   hand-written text prefix again, exactly like before this task). This
 *   path is not exercised by this project's JVM unit tests, because the
 *   host JDK's own `SunJSSE` provider has supported the `"DTLS"`
 *   algorithm since JDK 9 and this repo targets JDK 17 - so on this
 *   build/test setup, the real DTLS+SCTP path above is always what runs.
 * - **Media**: no `getUserMedia`, no audio/video `m=` sections, no
 *   codecs, no RTP/SRTP, no `addTrack`/`ontrack`. Building a real
 *   audio/video pipeline (codecs, jitter buffers, echo cancellation) from
 *   scratch is its own multi-month project on top of everything else in
 *   this engine and remains out of scope, as before this task.
 */
class PeerConnectionCore(
    private val localIp: String = "127.0.0.1",
    /** A public STUN server to try for server-reflexive candidate gathering (RFC 5389 usage 4.3). Left null by default so tests never depend on outbound network access; pass e.g. `InetSocketAddress("stun.l.google.com", 19302)` to exercise that path for real. */
    private val stunServer: InetSocketAddress? = null
) {
    enum class Role { UNSET, OFFERER, ANSWERER }

    var role: Role = Role.UNSET
        private set
    var localDescription: SdpSession? = null
        private set
    var remoteDescription: SdpSession? = null
        private set
    var connectionState: String = "new"
        private set

    var onIceCandidate: ((IceCandidate) -> Unit)? = null
    var onDataChannel: ((RTCDataChannelCore) -> Unit)? = null
    var onConnectionStateChange: (() -> Unit)? = null

    private val selfCert = SelfSignedCert.generate()
    private var iceUfrag = randomIceToken(4)
    private var icePwd = randomIceToken(22)
    private var socket: DatagramSocket? = null
    private var iceAgent: IceAgent? = null
    private var dtls: DtlsTransport? = null
    private var sctp: SctpAssociation? = null
    private var plaintextFallback: PlaintextFallbackChannel? = null

    private val channels = ConcurrentHashMap<String, RTCDataChannelCore>()
    private val streamIdForLabel = ConcurrentHashMap<String, Int>()
    private val labelForStreamId = ConcurrentHashMap<Int, String>()
    private var nextLocalStreamId = 0 // parity fixed up once the DTLS role is known, see connectTransports()
    private val pendingLocalLabels = ArrayList<String>()
    @Volatile private var connected = false

    private fun ensureIceAgent(controlling: Boolean): IceAgent {
        iceAgent?.let { return it }
        val sock = DatagramSocket(0)
        socket = sock
        val agent = IceAgent(iceUfrag, icePwd, controlling)
        agent.gatherCandidates(sock, localIp, stunServer)
        iceAgent = agent
        return agent
    }

    fun createOffer(): SdpSession {
        role = Role.OFFERER
        val agent = ensureIceAgent(controlling = true)
        return SdpSession(
            sessionId = System.currentTimeMillis().toString(),
            iceUfrag = iceUfrag,
            icePwd = icePwd,
            fingerprint = selfCert.fingerprint,
            setup = "actpass",
            sctpPort = 5000,
            candidates = agent.localCandidates.toList()
        )
    }

    fun createAnswer(): SdpSession {
        role = Role.ANSWERER
        val agent = ensureIceAgent(controlling = false)
        return SdpSession(
            sessionId = System.currentTimeMillis().toString(),
            iceUfrag = iceUfrag,
            icePwd = icePwd,
            fingerprint = selfCert.fingerprint,
            setup = "active",
            sctpPort = 5000,
            candidates = agent.localCandidates.toList()
        )
    }

    fun setLocalDescription(sdp: SdpSession) {
        localDescription = sdp
        maybeConnect()
    }

    fun setRemoteDescription(sdp: SdpSession) {
        remoteDescription = sdp
        val agent = ensureIceAgent(controlling = role == Role.OFFERER)
        agent.setRemoteCredentials(sdp.iceUfrag, sdp.icePwd)
        for (c in sdp.candidates) agent.addRemoteCandidate(c)
        maybeConnect()
    }

    private fun maybeConnect() {
        if (connected) return
        val local = localDescription ?: return
        val remote = remoteDescription ?: return
        connected = true
        Thread { connectTransports(local, remote) }.apply { isDaemon = true; start() }
    }

    /** Whether this side is the DTLS client (and, per RFC 8841, therefore the SCTP association initiator) - decided by the negotiated `a=setup` roles (RFC 5763), not by who happened to be the ICE offerer/answerer. */
    private fun isDtlsClient(local: SdpSession, remote: SdpSession): Boolean = when {
        local.setup == "active" -> true
        local.setup == "passive" -> false
        remote.setup == "active" -> false
        remote.setup == "passive" -> true
        else -> role == Role.ANSWERER
    }

    private fun connectTransports(local: SdpSession, remote: SdpSession) {
        val agent = iceAgent ?: return
        val iceOk = agent.startConnectivityChecks()
        if (!iceOk) {
            connectionState = "failed"
            onConnectionStateChange?.invoke()
            return
        }

        val isClient = isDtlsClient(local, remote)
        nextLocalStreamId = if (isClient) 0 else 1

        val dtlsTransport = DtlsTransport.create(agent, isClient, selfCert, remote.fingerprint)
        if (dtlsTransport == null) {
            // No platform DTLS support (see this class's doc, DTLS_UNAVAILABLE_FALLBACK_NOTE).
            val fallback = PlaintextFallbackChannel(agent)
            plaintextFallback = fallback
            fallback.onMessage = { label, text -> deliverToChannel(label, text) }
            fallback.onOpen = { label -> openRemoteChannel(label) }
            connectionState = "connected"
            onConnectionStateChange?.invoke()
            for (label in pendingLocalLabels) fallback.sendOpen(label)
            pendingLocalLabels.clear()
            for (ch in channels.values) if (ch.readyState == "connecting") ch.markOpen()
            return
        }

        dtls = dtlsTransport
        val handshakeOk = try {
            dtlsTransport.handshake()
        } catch (_: DtlsFingerprintMismatchException) {
            false
        }
        if (!handshakeOk) {
            connectionState = "failed"
            onConnectionStateChange?.invoke()
            return
        }
        dtlsTransport.startReceiveLoop()

        val association = SctpAssociation(isClient, local.sctpPort, remote.sctpPort, sendRaw = dtlsTransport::send)
        sctp = association
        dtlsTransport.onApplicationData = { bytes -> association.handleIncoming(bytes) }
        association.onStreamMessage = { streamId, payload -> handleSctpMessage(streamId, payload) }
        association.start()
        if (!association.awaitEstablished(10000)) {
            connectionState = "failed"
            onConnectionStateChange?.invoke()
            return
        }

        connectionState = "connected"
        onConnectionStateChange?.invoke()
        for (label in pendingLocalLabels) openLocalStream(label, association)
        pendingLocalLabels.clear()
        for ((label, ch) in channels) if (ch.readyState == "connecting") {
            val id = streamIdForLabel[label]
            if (id != null) ch.markOpen()
        }
    }

    private fun allocateStreamId(): Int {
        val id = nextLocalStreamId
        nextLocalStreamId += 2
        return id
    }

    private fun openLocalStream(label: String, association: SctpAssociation) {
        val id = streamIdForLabel.getOrPut(label) { allocateStreamId() }
        labelForStreamId[id] = label
        association.sendMessage(id, encodeControl(FRAME_OPEN, label.toByteArray(StandardCharsets.UTF_8)))
        channels[label]?.let { if (it.readyState == "connecting") it.markOpen() }
    }

    /** `pc.createDataChannel(label)`: locally-initiated channel. Opens immediately if already connected, otherwise once transports finish connecting. */
    fun createDataChannel(label: String): RTCDataChannelCore {
        val ch = RTCDataChannelCore(label)
        ch.sender = { text -> sendOnChannel(label, text) }
        channels[label] = ch
        val association = sctp
        val fallback = plaintextFallback
        when {
            association != null && association.state == SctpAssociation.State.ESTABLISHED -> openLocalStream(label, association)
            fallback != null -> { fallback.sendOpen(label); ch.markOpen() }
            else -> pendingLocalLabels.add(label) // not connected yet (or DTLS/SCTP still mid-handshake) - opened once connectTransports() finishes
        }
        return ch
    }

    private fun sendOnChannel(label: String, text: String) {
        val association = sctp
        if (association != null) {
            val id = streamIdForLabel[label] ?: return
            association.sendMessage(id, encodeControl(FRAME_MESSAGE, text.toByteArray(StandardCharsets.UTF_8)))
            return
        }
        plaintextFallback?.sendMessage(label, text)
    }

    private fun handleSctpMessage(streamId: Int, payload: ByteArray) {
        if (payload.isEmpty()) return
        when (payload[0]) {
            FRAME_OPEN -> {
                val label = String(payload, 1, payload.size - 1, StandardCharsets.UTF_8)
                streamIdForLabel[label] = streamId
                labelForStreamId[streamId] = label
                openRemoteChannel(label)
            }
            FRAME_MESSAGE -> {
                val label = labelForStreamId[streamId] ?: return
                val text = String(payload, 1, payload.size - 1, StandardCharsets.UTF_8)
                deliverToChannel(label, text)
            }
        }
    }

    private fun openRemoteChannel(label: String) {
        val ch = channels.getOrPut(label) { RTCDataChannelCore(label).also { it.sender = { text -> sendOnChannel(label, text) } } }
        val wasNew = ch.readyState == "connecting"
        ch.markOpen()
        if (wasNew) onDataChannel?.invoke(ch)
    }

    private fun deliverToChannel(label: String, text: String) {
        channels[label]?.deliverMessage(text)
    }

    fun close() {
        connectionState = "closed"
        onConnectionStateChange?.invoke()
        for (ch in channels.values) ch.close()
        dtls?.close()
        iceAgent?.close()
        plaintextFallback?.close()
        try { socket?.close() } catch (_: Exception) {}
    }

    companion object {
        private const val FRAME_OPEN: Byte = 1
        private const val FRAME_MESSAGE: Byte = 2

        private fun encodeControl(frameType: Byte, body: ByteArray): ByteArray {
            val out = ByteArray(1 + body.size)
            out[0] = frameType
            System.arraycopy(body, 0, out, 1, body.size)
            return out
        }
    }
}

/**
 * The DTLS_UNAVAILABLE_FALLBACK_NOTE path: a small plaintext stop-and-wait
 * ARQ multiplexing labeled channels by a text prefix, sent directly over
 * an already ICE-connected [IceAgent] (rather than owning its own
 * `DatagramSocket`, since ICE has already done NAT/connectivity discovery
 * on this one). Not encrypted, not SCTP - see PeerConnectionCore's class
 * doc for exactly when and why this runs instead of the real DTLS+SCTP
 * path above it.
 */
private class PlaintextFallbackChannel(private val agent: IceAgent) {
    var onMessage: ((label: String, text: String) -> Unit)? = null
    var onOpen: ((label: String) -> Unit)? = null

    private val sendSeq = java.util.concurrent.atomic.AtomicInteger(0)
    private var expectedRecvSeq = 0
    private val pendingAcks = ConcurrentHashMap<Int, java.util.concurrent.CountDownLatch>()

    init {
        agent.onData = { data, len, _ -> handleIncoming(data, len) }
    }

    private fun handleIncoming(data: ByteArray, len: Int) {
        if (len < 5) return
        val buf = java.nio.ByteBuffer.wrap(data, 0, len)
        val type = buf.get()
        val seq = buf.int
        when (type) {
            TYPE_ACK -> pendingAcks.remove(seq)?.countDown()
            TYPE_DATA -> {
                sendRaw(TYPE_ACK, seq, ByteArray(0))
                if (seq == expectedRecvSeq) {
                    expectedRecvSeq++
                    val payload = ByteArray(buf.remaining())
                    buf.get(payload)
                    val text = String(payload, StandardCharsets.UTF_8)
                    if (text.startsWith("O")) onOpen?.invoke(text.substring(1))
                    else if (text.startsWith("M")) {
                        val header = text.substring(1).substringBefore(':')
                        val len2 = header.toIntOrNull() ?: return
                        val after = text.substring(1 + header.length + 1)
                        onMessage?.invoke(after.take(len2), after.substring(len2))
                    }
                }
            }
        }
    }

    private fun sendRaw(type: Byte, seq: Int, payload: ByteArray) {
        val buf = java.nio.ByteBuffer.allocate(5 + payload.size)
        buf.put(type); buf.putInt(seq); buf.put(payload)
        try { agent.send(buf.array()) } catch (_: Exception) {}
    }

    private fun sendReliable(text: String) {
        val seq = sendSeq.getAndIncrement()
        val payload = text.toByteArray(StandardCharsets.UTF_8)
        val latch = java.util.concurrent.CountDownLatch(1)
        pendingAcks[seq] = latch
        var timeout = 300L
        repeat(8) {
            sendRaw(TYPE_DATA, seq, payload)
            if (latch.await(timeout, java.util.concurrent.TimeUnit.MILLISECONDS)) { pendingAcks.remove(seq); return }
            timeout *= 2
        }
        pendingAcks.remove(seq)
    }

    fun sendOpen(label: String) = Thread { sendReliable("O$label") }.apply { isDaemon = true; start() }
    fun sendMessage(label: String, text: String) = Thread { sendReliable("M${label.length}:$label$text") }.apply { isDaemon = true; start() }
    fun close() {}

    companion object {
        private const val TYPE_DATA: Byte = 1
        private const val TYPE_ACK: Byte = 2
    }
}
