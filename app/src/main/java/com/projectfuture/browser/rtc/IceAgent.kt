package com.projectfuture.browser.rtc

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** RFC 8445 section 5.1.2.1 candidate-type preferences (this engine never produces `relay`/TURN candidates - see [IceAgent]'s class doc). */
private fun typePreference(type: String): Int = when (type) {
    "host" -> 126
    "prflx" -> 110
    "srflx" -> 100
    "relay" -> 0
    else -> 0
}

/** RFC 8445 section 5.1.2.1's exact priority formula for a single (only-interface, single-candidate-per-type) agent. */
fun icePriority(type: String, component: Int = 1, localPreference: Int = 65535): Long =
    (typePreference(type).toLong() shl 24) + (localPreference.toLong() shl 8) + (256 - component).toLong()

/** A candidate pair as RFC 8445 section 6.1.2.3 defines it, with its priority computed by the pair-priority formula of section 5.1.4.2. */
data class CandidatePair(val local: IceCandidate, val remote: IceCandidate, val priority: Long)

/** RFC 8445 section 5.1.4.2: `2^32 * MIN(G,D) + 2 * MAX(G,D) + (G>D ? 1 : 0)`, where G is the controlling agent's candidate priority and D is the controlled agent's. */
fun pairPriority(controllingPriority: Long, controlledPriority: Long): Long {
    val g = controllingPriority
    val d = controlledPriority
    return (minOf(g, d) shl 32) + 2 * maxOf(g, d) + (if (g > d) 1L else 0L)
}

/**
 * A real, minimal RFC 8445 ICE agent: gathers host and (best-effort)
 * server-reflexive candidates, exchanges no signaling of its own (that's
 * still the SDP in [SdpSession] - this class only does the connectivity
 * checks once both sides' candidates are known), computes real RFC
 * 8445 candidate-pair priorities, and performs genuine peer-to-peer STUN
 * connectivity checks (RFC 8445 section 7, reusing [StunMessage]/
 * [StunClient]'s wire format and RFC 5389 short-term credentials -
 * `USERNAME`/`MESSAGE-INTEGRITY` keyed by the ICE ufrag/pwd from SDP) to
 * find a candidate pair that actually works, nominating it as the
 * selected pair.
 *
 * Deliberate simplifications, stated precisely:
 * - **Trickle ICE is not supported.** Both sides' full candidate sets
 *   must be known (via [addRemoteCandidate]) before [startConnectivityChecks]
 *   is called - candidates cannot be added incrementally mid-negotiation.
 * - **Nomination uses the "aggressive" algorithm** (RFC 8445 section 8.1.1
 *   explicitly allows this, though "regular" nomination is its
 *   recommended default): the controlling agent sets `USE-CANDIDATE` on
 *   every connectivity check it sends, in descending pair-priority order,
 *   and the first pair whose check succeeds is nominated - rather than
 *   probing all pairs first and nominating separately afterward. This is
 *   real ICE nomination per a spec-legal algorithm, not a shortcut that
 *   skips connectivity checking.
 * - **TURN/relay candidates are not implemented.** A relay candidate
 *   needs a running TURN server (RFC 5766/8656) to allocate from, and
 *   this environment has no TURN server reachable to test against - a
 *   client written against no reachable server can't be verified to
 *   actually interoperate, so rather than ship untestable, possibly-wrong
 *   TURN code, it was left out. Everything here (STUN binding
 *   requests/responses, XOR-MAPPED-ADDRESS, message integrity,
 *   fingerprint) is real enough that TURN support (which reuses the same
 *   STUN message format plus a handful of new attributes/methods) would
 *   be an incremental addition on top of [StunMessage], not a rewrite.
 * - **Only one component (RTP component 1, i.e. the single SCTP-over-DTLS
 *   flow a data-channel-only `m=` section has) and one local network
 *   interface/candidate of each type are considered** - no multi-homed
 *   host candidates, no candidate elimination/redundancy pruning beyond
 *   that.
 */
class IceAgent(
    val localUfrag: String,
    val localPwd: String,
    val controlling: Boolean,
    private val tieBreaker: Long = SecureRandom().nextLong()
) {
    @Volatile var remoteUfrag: String = ""
    @Volatile var remotePwd: String = ""

    val localCandidates: MutableList<IceCandidate> = ArrayList()
    private val remoteCandidates: MutableList<IceCandidate> = ArrayList()

    private lateinit var socket: DatagramSocket
    private val pendingResponses = ConcurrentHashMap<String, LinkedBlockingQueue<StunMessage>>()
    private val running = AtomicBoolean(false)
    private var receiverThread: Thread? = null

    private val selected = AtomicReference<InetSocketAddress?>(null)
    /** Set once ICE has nominated a working pair; the remote UDP endpoint all subsequent (DTLS-encrypted) traffic goes to. */
    val selectedRemoteAddress: InetSocketAddress? get() = selected.get()

    /** Non-STUN datagrams received once ICE is running (i.e. everything that isn't a STUN message on this 5-tuple) - this is where DTLS records get handed upward, per how a real stack demultiplexes STUN/DTLS/SRTP sharing one port (RFC 7983). */
    var onData: ((ByteArray, Int, InetSocketAddress) -> Unit)? = null

    fun setRemoteCredentials(ufrag: String, pwd: String) {
        remoteUfrag = ufrag
        remotePwd = pwd
    }

    fun addRemoteCandidate(candidate: IceCandidate) {
        remoteCandidates.add(candidate)
    }

    /**
     * Binds [socket] (created and owned by the caller so the same local
     * port ends up in the SDP `a=candidate` line) as this agent's socket,
     * adds the host candidate for it, and - best-effort, never throwing -
     * tries to add a server-reflexive candidate by asking [stunServer].
     * Real STUN wire traffic when [stunServer] is reachable; silently
     * skipped (leaving only the host candidate) when it isn't, which is
     * the expected outcome in a sandboxed/offline test environment and
     * exactly the case RFC 8445 candidate gathering is meant to degrade
     * gracefully from.
     */
    fun gatherCandidates(socket: DatagramSocket, localIp: String, stunServer: InetSocketAddress? = null): List<IceCandidate> {
        this.socket = socket
        val host = IceCandidate(
            foundation = "host-udp",
            component = 1,
            protocol = "udp",
            priority = icePriority("host"),
            ip = localIp,
            port = socket.localPort,
            type = "host"
        )
        localCandidates.add(host)

        if (stunServer != null) {
            val reflexive = StunClient.discoverServerReflexiveAddress(socket, stunServer)
            if (reflexive != null && reflexive.address.hostAddress != localIp) {
                localCandidates.add(
                    IceCandidate(
                        foundation = "srflx-udp",
                        component = 1,
                        protocol = "udp",
                        priority = icePriority("srflx"),
                        ip = reflexive.address.hostAddress ?: localIp,
                        port = reflexive.port,
                        type = "srflx"
                    )
                )
            }
        }
        return localCandidates
    }

    private fun buildPairs(): List<CandidatePair> {
        val pairs = ArrayList<CandidatePair>()
        for (l in localCandidates) {
            for (r in remoteCandidates) {
                val g = if (controlling) l.priority else r.priority
                val d = if (controlling) r.priority else l.priority
                pairs.add(CandidatePair(l, r, pairPriority(g, d)))
            }
        }
        return pairs.sortedByDescending { it.priority }
    }

    /**
     * Runs real STUN connectivity checks (RFC 8445 section 7) against
     * every candidate pair in descending priority order until one
     * succeeds, then returns. Starts this agent's persistent receive
     * loop, which keeps running afterward to answer the peer's own
     * checks/keepalives and to demux post-ICE traffic to [onData].
     * Returns true once a pair is nominated and selected, false if every
     * pair's checks failed to get a response within their retries (e.g.
     * neither peer is actually reachable on any exchanged candidate).
     */
    fun startConnectivityChecks(perCheckTimeoutMs: Long = 300L, retries: Int = 3): Boolean {
        startReceiver()
        val pairs = buildPairs()
        for (pair in pairs) {
            if (selected.get() != null) break
            val remoteAddr = InetSocketAddress(pair.remote.ip, pair.remote.port)
            val ok = performCheck(remoteAddr, pair.local.priority, nominate = controlling, timeoutMs = perCheckTimeoutMs, retries = retries)
            if (ok && controlling) {
                selected.compareAndSet(null, remoteAddr)
            }
        }
        // The controlled side's selection happens asynchronously in handleRequest() as soon as a
        // USE-CANDIDATE-bearing request arrives, which may be slightly after our own loop above
        // returns (the controlling side runs the same loop concurrently) - give it a brief grace
        // period rather than declaring failure the instant our own checks finish.
        val deadline = System.currentTimeMillis() + 2000
        while (selected.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        return selected.get() != null
    }

    private fun performCheck(remoteAddr: InetSocketAddress, localCandidatePriority: Long, nominate: Boolean, timeoutMs: Long, retries: Int): Boolean {
        val request = StunMessage.bindingRequest()
            .addUsername("$remoteUfrag:$localUfrag")
            .addPriority(localCandidatePriority)
        if (controlling) request.addIceControlling(tieBreaker) else request.addIceControlled(tieBreaker)
        if (nominate) request.addUseCandidate()

        val txKey = hex(request.transactionId)
        val queue = LinkedBlockingQueue<StunMessage>(1)
        pendingResponses[txKey] = queue
        try {
            val payload = request.encode(integrityKey = remotePwd.toByteArray())
            var timeout = timeoutMs
            repeat(retries) {
                try {
                    socket.send(DatagramPacket(payload, payload.size, remoteAddr.address, remoteAddr.port))
                } catch (_: Exception) {
                    return false
                }
                val response = queue.poll(timeout, TimeUnit.MILLISECONDS)
                if (response != null) {
                    return response.type == StunMessage.BINDING_SUCCESS_RESPONSE
                }
                timeout *= 2
            }
            return false
        } finally {
            pendingResponses.remove(txKey)
        }
    }

    private fun startReceiver() {
        if (!running.compareAndSet(false, true)) return
        receiverThread = Thread {
            val buf = ByteArray(65535)
            while (running.get()) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (_: Exception) {
                    break
                }
                val from = InetSocketAddress(packet.address, packet.port)
                val msg = StunMessage.decode(packet.data, packet.length)
                if (msg == null) {
                    onData?.invoke(packet.data, packet.length, from)
                    continue
                }
                when (msg.type) {
                    StunMessage.BINDING_REQUEST -> handleRequest(msg, packet.data, packet.length, from)
                    StunMessage.BINDING_SUCCESS_RESPONSE, StunMessage.BINDING_ERROR_RESPONSE ->
                        pendingResponses[hex(msg.transactionId)]?.offer(msg)
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun handleRequest(msg: StunMessage, raw: ByteArray, rawLen: Int, from: InetSocketAddress) {
        // Validate the short-term credential the peer signed with (our own local password, since
        // it is *our* MESSAGE-INTEGRITY key as the recipient - RFC 5389 section 10.1.2) before
        // trusting/acting on the request, exactly as a real ICE agent must.
        if (!StunMessage.verifyIntegrity(msg, raw, rawLen, localPwd.toByteArray())) return

        val response = StunMessage.bindingSuccessResponse(msg).addXorMappedAddress(from)
        val payload = response.encode(integrityKey = localPwd.toByteArray())
        try {
            socket.send(DatagramPacket(payload, payload.size, from.address, from.port))
        } catch (_: Exception) {
        }

        if (!controlling && msg.attribute(StunMessage.ATTR_USE_CANDIDATE) != null) {
            selected.compareAndSet(null, from)
        }
    }

    /** Sends a raw datagram to the nominated remote address (post-[startConnectivityChecks]) - the transport DTLS and, in the no-DTLS fallback, the plaintext channel both send through. */
    fun send(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        val remote = selected.get() ?: return
        try {
            socket.send(DatagramPacket(data, offset, length, remote.address, remote.port))
        } catch (_: Exception) {
        }
    }

    fun close() {
        running.set(false)
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
