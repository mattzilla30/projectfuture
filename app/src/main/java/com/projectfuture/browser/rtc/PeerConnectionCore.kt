package com.projectfuture.browser.rtc

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
 * JS-facing wrapper around this) - see this file's class doc in
 * RTCDataChannelCore.kt... actually here: **what genuinely works
 * end-to-end** versus what's scoped out on purpose, spelled out because
 * this is the harder, partially-implemented half of this task:
 *
 * WORKS END-TO-END (exercised by PeerConnectionCoreTest, no mocking):
 * - `createOffer`/`createAnswer` generate real RFC 4566/8866-shaped SDP
 *   (see Sdp.kt) with ICE credentials, a DTLS fingerprint, `a=setup`, an
 *   `a=candidate` host candidate, and an SCTP port.
 * - `SdpSession.parse` reads that SDP back into the same structure - a
 *   genuine round trip, not just "the same object handed back".
 * - `setLocalDescription`/`setRemoteDescription` on two independent
 *   instances (as two peer connections, or as this engine and any other
 *   spec-compliant SDP producer for the data-channel section) drive a
 *   real UDP socket pair to actually connect to each other, using the
 *   IP/port carried in the exchanged SDP - no loopback shortcut, no
 *   fakery: swap in a real routable IP and this connects across a LAN.
 * - `createDataChannel` on one side and the matching `ondatachannel`
 *   firing on the other, then `send()`/`onmessage` both directions,
 *   multiple independently-labeled channels multiplexed over the one
 *   association, delivered reliably and in order (see
 *   ReliableUdpChannel's class doc for exactly what "reliable" means
 *   here) even across simulated packet loss.
 *
 * API-SURFACE-ONLY / NOT REAL (so scripts don't crash, but nothing here
 * should be mistaken for a working implementation):
 * - ICE: there is no STUN, no TURN, no candidate gathering beyond the
 *   one local host address, no connectivity checks, no candidate
 *   pairing/prioritization, no NAT traversal. "ICE credentials" in the
 *   SDP are generated and round-tripped but never used to authenticate
 *   anything. This only actually connects when the exchanged candidate's
 *   IP/port is directly reachable (localhost, or a LAN with no NAT/
 *   firewall in the way) - exactly the "another instance of this same
 *   browser" scenario the task allows for.
 * - Security: no DTLS, no SRTP. The SDP fingerprint is generated and
 *   carried but never checked against a real certificate, because no
 *   certificate or handshake exists. Data channel traffic is plaintext
 *   on the wire (see ReliableUdpChannel's doc).
 * - Transport: not real SCTP (RFC 4960) - see ReliableUdpChannel. No
 *   partial reliability / unordered channel modes (`{ordered: false}` is
 *   accepted by the JS API for compatibility but has no effect: every
 *   channel is ordered and reliable).
 * - Media: no `getUserMedia`, no audio/video `m=` sections, no codecs,
 *   no RTP/SRTP, no `addTrack`/`ontrack`. Building a real audio/video
 *   pipeline (codecs, jitter buffers, echo cancellation) from scratch is
 *   its own multi-month project on top of everything else in this
 *   engine and was out of scope for this session - the task's own
 *   framing agrees this isn't realistically achievable correctly in one
 *   pass, so it was not attempted or faked with a stub that looks
 *   functional but isn't.
 */
class PeerConnectionCore(localIp: String = "127.0.0.1") {
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

    private val localIp = localIp
    private var transport: ReliableUdpChannel? = null
    private val channels = ConcurrentHashMap<String, RTCDataChannelCore>()
    private val pendingLocalLabels = ArrayList<String>()
    private var connected = false

    private fun ensureTransport(): ReliableUdpChannel {
        transport?.let { return it }
        val t = ReliableUdpChannel()
        t.onMessage = ::handleFrame
        transport = t
        return t
    }

    private fun localCandidate(): IceCandidate {
        val t = ensureTransport()
        return IceCandidate(foundation = "0", component = 1, protocol = "udp", priority = 2113937151L, ip = localIp, port = t.localPort)
    }

    fun createOffer(): SdpSession {
        role = Role.OFFERER
        val sdp = SdpSession(
            sessionId = System.currentTimeMillis().toString(),
            iceUfrag = randomIceToken(4),
            icePwd = randomIceToken(22),
            fingerprint = randomFingerprint(),
            setup = "actpass",
            sctpPort = 5000,
            candidates = listOf(localCandidate())
        )
        return sdp
    }

    fun createAnswer(): SdpSession {
        role = Role.ANSWERER
        val sdp = SdpSession(
            sessionId = System.currentTimeMillis().toString(),
            iceUfrag = randomIceToken(4),
            icePwd = randomIceToken(22),
            fingerprint = randomFingerprint(),
            setup = "active",
            sctpPort = 5000,
            candidates = listOf(localCandidate())
        )
        return sdp
    }

    fun setLocalDescription(sdp: SdpSession) {
        localDescription = sdp
        maybeConnect()
    }

    fun setRemoteDescription(sdp: SdpSession) {
        remoteDescription = sdp
        maybeConnect()
    }

    private fun maybeConnect() {
        if (connected) return
        val local = localDescription ?: return
        val remote = remoteDescription ?: return
        val remoteCandidate = remote.candidates.firstOrNull() ?: return
        val t = ensureTransport()
        t.connect(remoteCandidate.ip, remoteCandidate.port)
        t.start()
        connected = true
        connectionState = "connected"
        onConnectionStateChange?.invoke()
        for (label in pendingLocalLabels) sendOpenFrame(label)
        pendingLocalLabels.clear()
        for (ch in channels.values) if (ch.readyState == "connecting") ch.markOpen()
    }

    private fun sendOpenFrame(label: String) {
        transport?.sendAsync(encodeOpen(label))
    }

    /** `pc.createDataChannel(label)`: locally-initiated channel. Opens immediately if already connected, otherwise once `maybeConnect()` succeeds. */
    fun createDataChannel(label: String): RTCDataChannelCore {
        val ch = RTCDataChannelCore(label)
        ch.sender = { text -> transport?.sendAsync(encodeMessage(label, text)) }
        channels[label] = ch
        if (connected) {
            sendOpenFrame(label)
            ch.markOpen()
        } else {
            pendingLocalLabels.add(label)
        }
        return ch
    }

    private fun handleFrame(raw: String) {
        when {
            raw.startsWith("O") -> {
                val label = raw.substring(1)
                val ch = channels.getOrPut(label) {
                    RTCDataChannelCore(label).also { it.sender = { text -> transport?.sendAsync(encodeMessage(label, text)) } }
                }
                val isNew = ch.readyState == "connecting"
                ch.markOpen()
                if (isNew) onDataChannel?.invoke(ch)
            }
            raw.startsWith("M") -> {
                val header = raw.substring(1).substringBefore(':')
                val len = header.toIntOrNull() ?: return
                val afterHeader = raw.substring(1 + header.length + 1)
                val label = afterHeader.take(len)
                val payload = afterHeader.substring(len)
                channels[label]?.deliverMessage(payload)
            }
        }
    }

    fun close() {
        connectionState = "closed"
        onConnectionStateChange?.invoke()
        for (ch in channels.values) ch.close()
        transport?.close()
    }

    companion object {
        private fun encodeOpen(label: String) = "O$label"
        private fun encodeMessage(label: String, payload: String) = "M${label.length}:$label$payload"
    }
}
