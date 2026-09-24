package com.projectfuture.browser.net.http2

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Thrown for any request made once the connection has gone away (GOAWAY, a read error, or a close) - the caller falls back to opening a new connection. */
class Http2ConnectionClosedException(message: String) : IOException(message)

/** A response to one HTTP/2 request-stream, assembled once its stream reaches END_STREAM. Header order/duplicates are preserved (e.g. multiple `set-cookie`), same as the HTTP/1.1 path. */
data class Http2Response(val statusCode: Int, val headers: List<Pair<String, String>>, val body: ByteArray)

private const val DEFAULT_INITIAL_WINDOW = 65535
private const val CONNECTION_STREAM_ID = 0
/** How long a stream may go without receiving any frame before it's considered dead. Measured from the last frame, not from the start, so a large response on a slow network isn't cut off. */
private const val STREAM_INACTIVITY_TIMEOUT_MS = 20000L
/** A connection that has carried no traffic for this long isn't handed out again: on mobile networks an idle TCP connection often dies silently (network switch, NAT timeout, device sleep) with no FIN ever arriving. */
private const val MAX_IDLE_BEFORE_REUSE_MS = 60000L

/**
 * One real HTTP/2 connection (RFC 7540): the client connection preface and SETTINGS exchange,
 * then genuine multiplexed request streams sharing a single TCP/TLS socket, each with its own
 * HPACK-compressed HEADERS (+CONTINUATION) frame and DATA frames, governed by real per-stream and
 * per-connection flow-control windows. One background thread reads frames off the socket and
 * dispatches them by stream ID; any number of caller threads can have `request()` calls in flight
 * at once - that's the actual multiplexing HTTP/2 is for, not a single-stream-at-a-time facade.
 *
 * Server push (PUSH_PROMISE) is disabled up front via `SETTINGS_ENABLE_PUSH: 0` in the client's
 * initial SETTINGS frame (RFC 7540 8.2 makes this a normal, spec-legal way to opt out); if a
 * server ignores that and sends one anyway, the promised stream is refused with RST_STREAM rather
 * than silently mishandled.
 */
class Http2Connection(private val socket: Socket) {
    private val input = BufferedInputStream(socket.getInputStream(), 16384)
    private val output = BufferedOutputStream(socket.getOutputStream(), 16384)
    private val writeLock = Any()

    private val hpackEncoder = HpackEncoder()
    private val hpackDecoder = HpackDecoder()

    private val nextStreamId = AtomicInteger(1)
    private val streams = ConcurrentHashMap<Int, StreamState>()

    // Guards every read-modify-write of a send-window field below (peerInitialWindowSize,
    // connectionSendWindow, and each StreamState.sendWindow). Both the reader thread (WINDOW_UPDATE/
    // SETTINGS frames granting more window) and any number of concurrent requesting threads (writeBody
    // spending window on DATA frames) touch these, and `x += y`/`x -= y` is a read-then-write, not an
    // atomic op - without a shared lock two threads spending the same connection-level budget at once
    // can each observe the same "window has room" snapshot and together send more than the peer
    // granted, which a conformant server treats as a FLOW_CONTROL_ERROR and tears the connection down for.
    private val windowLock = Any()
    private var peerInitialWindowSize = DEFAULT_INITIAL_WINDOW
    private var connectionSendWindow = DEFAULT_INITIAL_WINDOW
    @Volatile private var maxFrameSize = DEFAULT_MAX_FRAME_SIZE
    @Volatile private var closed = false
    @Volatile private var closeCause: IOException? = null
    // Set by a GOAWAY: streams at or below its last-stream-id still complete normally, but no new
    // stream may be opened, so the pool must stop handing this connection out.
    @Volatile private var goingAway = false
    @Volatile private var lastTrafficMs = System.currentTimeMillis()

    private val readerThread = Thread({ readLoop() }, "http2-reader").apply { isDaemon = true }

    init {
        output.write(HTTP2_CONNECTION_PREFACE)
        // A generous MAX_CONCURRENT_STREAMS isn't needed since we only advertise our own limits
        // (peer's are tracked passively); ENABLE_PUSH: 0 declines server push (see class doc).
        writeFrameLocked(Http2Frame(FrameType.SETTINGS, 0, CONNECTION_STREAM_ID, Http2FrameIO.encodeSettings(
            listOf(SettingsId.ENABLE_PUSH to 0, SettingsId.INITIAL_WINDOW_SIZE to DEFAULT_INITIAL_WINDOW)
        )))
        synchronized(writeLock) { output.flush() }
        readerThread.start()
    }

    /** Whether a new request may be started on this connection. See [MAX_IDLE_BEFORE_REUSE_MS] for the idle check. */
    val isOpen: Boolean get() {
        if (closed || goingAway) return false
        if (streams.isEmpty() && System.currentTimeMillis() - lastTrafficMs > MAX_IDLE_BEFORE_REUSE_MS) {
            close()
            return false
        }
        return true
    }

    /** Holds one request stream's in-flight response state; visible to both the reader thread and the requesting thread. */
    private class StreamState {
        val headersLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(1)
        @Volatile var statusCode: Int = 0
        val responseHeaders = ArrayList<Pair<String, String>>()
        val body = java.io.ByteArrayOutputStream()
        // Guarded by the owning Http2Connection's windowLock, not @Volatile alone - see windowLock's doc.
        var sendWindow = 0
        @Volatile var error: IOException? = null
        var headerBlockInProgress: java.io.ByteArrayOutputStream? = null
        // Set when a HEADERS frame arrives with END_STREAM but *without* END_HEADERS (the header
        // block continues in one or more CONTINUATION frames): the stream must not be considered
        // finished until those CONTINUATION frames complete the header block, even though
        // END_STREAM already told us no DATA frames are coming (see handleHeaders/handleContinuation).
        @Volatile var endStreamPending = false
        @Volatile var finished = false
        @Volatile var lastActivityMs = System.currentTimeMillis()
    }

    /**
     * Opens a new client-initiated stream, sends its request headers (and body, if any), and
     * blocks the calling thread until the full response is read or an error/timeout occurs.
     * Safe to call from multiple threads concurrently - that's the point of a multiplexed
     * connection.
     */
    fun request(pseudoAndHeaders: List<HpackHeader>, body: ByteArray?): Http2Response {
        // Nothing has been sent for this request yet, so the caller can safely retry it on a new connection.
        if (closed || goingAway) throw Http2ConnectionClosedException(closeCause?.message ?: "HTTP/2 connection is closed")
        val state = StreamState()
        var streamId = -1
        try {
            // The stream id is allocated inside the same critical section that writes its HEADERS:
            // RFC 7540 5.1.1 requires new stream ids to reach the peer in increasing order, and a
            // server that sees stream 17 open before stream 15 treats 15 as already closed and never
            // answers it. HPACK's dynamic table is shared connection state too, so header blocks must
            // also go out in encode order. DATA frames are written by writeBody with their own
            // per-frame locking, so one stream waiting on flow control never blocks another.
            streamId = synchronized(writeLock) {
                if (closed || goingAway) throw Http2ConnectionClosedException(closeCause?.message ?: "HTTP/2 connection is closed")
                val id = nextStreamId.getAndAdd(2)
                state.sendWindow = synchronized(windowLock) { peerInitialWindowSize }
                streams[id] = state
                writeHeaderBlock(id, hpackEncoder.encode(pseudoAndHeaders), endStream = body == null)
                id
            }
            lastTrafficMs = System.currentTimeMillis()
            if (body != null && body.isNotEmpty()) writeBody(streamId, state, body)
            while (!state.doneLatch.await(500, TimeUnit.MILLISECONDS)) {
                if (System.currentTimeMillis() - state.lastActivityMs > STREAM_INACTIVITY_TIMEOUT_MS) {
                    // Nothing at all has arrived for this stream in that long: the connection is almost
                    // certainly dead. Tear it down so the pool stops handing it out and the next
                    // request opens a fresh one, instead of every later request timing out on it too.
                    failAll(IOException("HTTP/2 connection stopped responding"))
                    throw IOException("HTTP/2 stream $streamId timed out")
                }
            }
            state.error?.let { throw it }
            return Http2Response(state.statusCode, state.responseHeaders, state.body.toByteArray())
        } finally {
            if (streamId != -1) streams.remove(streamId)
            if (goingAway && streams.isEmpty()) close()
        }
    }

    private fun writeHeaderBlock(streamId: Int, block: ByteArray, endStream: Boolean) {
        var offset = 0
        var first = true
        while (offset < block.size || first) {
            val chunkSize = minOf(maxFrameSize, block.size - offset)
            val chunk = block.copyOfRange(offset, offset + chunkSize)
            offset += chunkSize
            val isLast = offset >= block.size
            var flags = 0
            if (isLast) flags = flags or FrameFlag.END_HEADERS
            if (first && endStream) flags = flags or FrameFlag.END_STREAM
            val type = if (first) FrameType.HEADERS else FrameType.CONTINUATION
            writeFrameLocked(Http2Frame(type, flags, streamId, chunk))
            first = false
        }
        synchronized(writeLock) { output.flush() }
    }

    private fun writeBody(streamId: Int, state: StreamState, body: ByteArray) {
        var offset = 0
        val deadline = System.currentTimeMillis() + STREAM_INACTIVITY_TIMEOUT_MS
        while (offset < body.size) {
            // Real flow control: never send more than the peer's advertised stream/connection
            // window allows (RFC 7540 6.9), polling for WINDOW_UPDATE frames the reader thread
            // applies concurrently. A short poll loop rather than wait/notify keeps this simple;
            // request bodies in this browser (form POSTs) are small enough that it never matters.
            // The "is there room, and if so how much" check and the spend of that room must happen
            // as one atomic step under windowLock - checking then separately decrementing (the two
            // used to be split across two unsynchronized statements) lets two concurrent streams
            // both see the same leftover window and each spend it in full, sending more bytes than
            // the peer ever granted.
            var chunkSize: Int
            while (true) {
                chunkSize = synchronized(windowLock) {
                    val available = minOf(state.sendWindow, connectionSendWindow, maxFrameSize)
                    val take = minOf(available, body.size - offset)
                    if (take > 0) {
                        state.sendWindow -= take
                        connectionSendWindow -= take
                    }
                    take
                }
                if (chunkSize > 0) break
                if (System.currentTimeMillis() > deadline) throw IOException("HTTP/2 flow-control window timed out")
                Thread.sleep(5)
            }
            val chunk = body.copyOfRange(offset, offset + chunkSize)
            offset += chunkSize
            val flags = if (offset >= body.size) FrameFlag.END_STREAM else 0
            writeFrameLocked(Http2Frame(FrameType.DATA, flags, streamId, chunk), flush = true)
        }
    }

    private fun writeFrameLocked(frame: Http2Frame, flush: Boolean = false) {
        synchronized(writeLock) {
            Http2FrameIO.writeFrame(output, frame)
            if (flush) output.flush()
        }
    }

    private fun readLoop() {
        try {
            while (!closed) {
                val frame = Http2FrameIO.readFrame(input, 1 shl 24)
                handleFrame(frame)
            }
        } catch (e: IOException) {
            failAll(e)
        } catch (e: Exception) {
            failAll(IOException("HTTP/2 connection failed", e))
        }
    }

    private fun readInt32(payload: ByteArray, offset: Int): Int =
        (payload[offset].toInt() and 0xff shl 24) or (payload[offset + 1].toInt() and 0xff shl 16) or
            (payload[offset + 2].toInt() and 0xff shl 8) or (payload[offset + 3].toInt() and 0xff)

    private fun handleFrame(frame: Http2Frame) {
        lastTrafficMs = System.currentTimeMillis()
        when (frame.type) {
            FrameType.SETTINGS -> handleSettings(frame)
            FrameType.WINDOW_UPDATE -> handleWindowUpdate(frame)
            FrameType.HEADERS -> handleHeaders(frame)
            FrameType.CONTINUATION -> handleContinuation(frame)
            FrameType.DATA -> handleData(frame)
            FrameType.RST_STREAM -> streams[frame.streamId]?.let {
                // A server may legally send RST_STREAM(NO_ERROR) right after a complete response
                // (RFC 7540 8.1). Frames are handled in order on this one thread, so a stream that
                // already saw END_STREAM here is complete and the reset must not turn it into a failure.
                if (it.finished) return@let
                val code = if (frame.payload.size >= 4) readInt32(frame.payload, 0) else -1
                it.error = IOException("Stream ${frame.streamId} reset by server (error code $code)")
                it.headersLatch.countDown()
                it.doneLatch.countDown()
            }
            FrameType.PING -> if (!frame.hasFlag(FrameFlag.ACK)) {
                writeFrameLocked(Http2Frame(FrameType.PING, FrameFlag.ACK, CONNECTION_STREAM_ID, frame.payload))
                synchronized(writeLock) { output.flush() }
            }
            FrameType.GOAWAY -> handleGoAway(frame)
            FrameType.PUSH_PROMISE -> {
                // We advertised SETTINGS_ENABLE_PUSH: 0; a server that pushes anyway gets refused.
                val promisedStreamId = (frame.payload[0].toInt() and 0x7f shl 24) or
                    (frame.payload[1].toInt() and 0xff shl 16) or
                    (frame.payload[2].toInt() and 0xff shl 8) or (frame.payload[3].toInt() and 0xff)
                writeFrameLocked(Http2Frame(FrameType.RST_STREAM, 0, promisedStreamId, Http2FrameIO.encodeRstStream(ErrorCode.REFUSED_STREAM)))
                synchronized(writeLock) { output.flush() }
            }
            FrameType.PRIORITY -> {} // Not used for request scheduling here; safe to ignore.
            else -> {} // Unknown frame types must be ignored per RFC 7540 4.1.
        }
    }

    /**
     * GOAWAY (RFC 7540 6.8) is usually a graceful shutdown: the server promises to finish every
     * stream up to its last-stream-id and refuses anything above it. Servers send it routinely
     * (idle timeouts, request-count limits, deploys), so failing every in-flight stream on it threw
     * away responses the server was still delivering. Refused streams fail with
     * [Http2ConnectionClosedException], which the caller retries on a new connection.
     */
    private fun handleGoAway(frame: Http2Frame) {
        goingAway = true
        val lastStreamId = if (frame.payload.size >= 4) readInt32(frame.payload, 0) and 0x7fffffff else 0
        for ((id, state) in streams) {
            if (id > lastStreamId && !state.finished) {
                state.error = Http2ConnectionClosedException("Stream $id refused by GOAWAY")
                state.headersLatch.countDown()
                state.doneLatch.countDown()
            }
        }
        if (streams.values.all { it.finished || it.error != null }) close()
    }

    private fun handleSettings(frame: Http2Frame) {
        if (frame.hasFlag(FrameFlag.ACK)) return
        for ((id, value) in Http2FrameIO.decodeSettings(frame.payload)) {
            when (id) {
                SettingsId.INITIAL_WINDOW_SIZE -> synchronized(windowLock) {
                    // RFC 7540 6.9.2: changing SETTINGS_INITIAL_WINDOW_SIZE retroactively adjusts
                    // the flow-control window of every currently open stream by the same delta (new
                    // minus old) - it's not just the initial value for streams opened from now on.
                    // Without this, a server that lowers/raises the setting mid-connection leaves
                    // already-open streams' windows silently wrong: too large (a protocol violation
                    // if we then send past what the server actually still allows) or too small
                    // (a needless stall, since we'd never use window room the server just granted).
                    val delta = value - peerInitialWindowSize
                    peerInitialWindowSize = value
                    for (state in streams.values) state.sendWindow += delta
                }
                SettingsId.MAX_FRAME_SIZE -> if (value in DEFAULT_MAX_FRAME_SIZE..(1 shl 24) - 1) maxFrameSize = value
                SettingsId.HEADER_TABLE_SIZE -> {} // hpackEncoder's dynamic table already defaults within bounds any real server sets.
            }
        }
        writeFrameLocked(Http2Frame(FrameType.SETTINGS, FrameFlag.ACK, CONNECTION_STREAM_ID, ByteArray(0)))
        synchronized(writeLock) { output.flush() }
    }

    private fun handleWindowUpdate(frame: Http2Frame) {
        val increment = Http2FrameIO.decodeWindowUpdate(frame.payload)
        synchronized(windowLock) {
            if (frame.streamId == CONNECTION_STREAM_ID) {
                connectionSendWindow += increment
            } else {
                streams[frame.streamId]?.let { it.sendWindow += increment }
            }
        }
    }

    private fun handleHeaders(frame: Http2Frame) {
        // A header block for a stream we've stopped tracking (e.g. timed out) must still be decoded:
        // HPACK's dynamic table is shared connection state, and skipping a block desynchronizes it
        // for every later response on this connection.
        val state = streams[frame.streamId] ?: orphanHeaderState(frame.streamId)
        state.lastActivityMs = System.currentTimeMillis()
        val fragment = Http2FrameIO.extractHeaderBlockFragment(frame.payload, frame.flags)
        val endStream = frame.hasFlag(FrameFlag.END_STREAM)
        if (frame.hasFlag(FrameFlag.END_HEADERS)) {
            orphanHeaderStates.remove(frame.streamId)
            applyHeaderBlock(state, fragment)
            // Only safe to signal "done" once the header block itself has actually been decoded
            // into state.statusCode/responseHeaders - see the CONTINUATION branch below for why
            // this can't just unconditionally fire on END_STREAM.
            if (endStream) finishStream(frame.streamId, state)
        } else {
            state.headerBlockInProgress = java.io.ByteArrayOutputStream().apply { write(fragment) }
            // A HEADERS frame can carry END_STREAM (no DATA frames follow) while still needing
            // CONTINUATION frame(s) to complete its own header block (RFC 7540 6.2/6.10 - END_HEADERS
            // and END_STREAM are independent flags). Finishing the stream now, before the header
            // block is fully decoded, would hand the caller a response with no status/headers even
            // though a decodable HEADERS+CONTINUATION sequence is still in flight - defer instead.
            state.endStreamPending = endStream
        }
    }

    private fun handleContinuation(frame: Http2Frame) {
        val state = streams[frame.streamId] ?: orphanHeaderState(frame.streamId)
        state.lastActivityMs = System.currentTimeMillis()
        val buffer = state.headerBlockInProgress ?: java.io.ByteArrayOutputStream().also { state.headerBlockInProgress = it }
        buffer.write(frame.payload)
        if (frame.hasFlag(FrameFlag.END_HEADERS)) {
            orphanHeaderStates.remove(frame.streamId)
            applyHeaderBlock(state, buffer.toByteArray())
            state.headerBlockInProgress = null
            if (state.endStreamPending) finishStream(frame.streamId, state)
        }
    }

    // Reader-thread only: holds a header block in progress for a stream no longer in [streams].
    private val orphanHeaderStates = HashMap<Int, StreamState>()

    private fun orphanHeaderState(streamId: Int): StreamState = orphanHeaderStates.getOrPut(streamId) { StreamState() }

    /** The HPACK dynamic table is per-connection shared state, so header blocks must be decoded strictly in the frame order they arrived - true here since only this single reader thread ever calls it. */
    private fun applyHeaderBlock(state: StreamState, block: ByteArray) {
        val decoded = try {
            hpackDecoder.decode(block)
        } catch (e: Exception) {
            // A failed decode leaves the shared dynamic table in an unknown state, so every later
            // header block on this connection would decode wrong too (RFC 7540 4.3: a connection error).
            failAll(IOException("Malformed HPACK block", e))
            return
        }
        for (header in decoded) {
            if (header.name == ":status") state.statusCode = header.value.toIntOrNull() ?: state.statusCode
            else if (!header.name.startsWith(":")) state.responseHeaders.add(header.name to header.value)
        }
        state.headersLatch.countDown()
    }

    private fun handleData(frame: Http2Frame) {
        val state = streams[frame.streamId]
        // A PADDED frame's pad-length byte and trailing padding are not response bytes; writing them
        // into the body corrupted it, which then failed gzip/Brotli decoding.
        val data = Http2FrameIO.extractDataPayload(frame.payload, frame.flags)
        if (state != null) {
            state.lastActivityMs = System.currentTimeMillis()
            synchronized(state.body) { state.body.write(data) }
        }
        // Replenish flow control unconditionally (even for an untracked/timed-out stream) so the
        // server's connection-level window doesn't stall out for every other stream sharing it.
        // Flow control counts the whole frame payload, padding included (RFC 7540 6.9.1).
        if (frame.payload.isNotEmpty()) {
            writeFrameLocked(Http2Frame(FrameType.WINDOW_UPDATE, 0, CONNECTION_STREAM_ID, Http2FrameIO.encodeWindowUpdate(frame.payload.size)))
            if (state != null && !frame.hasFlag(FrameFlag.END_STREAM)) {
                writeFrameLocked(Http2Frame(FrameType.WINDOW_UPDATE, 0, frame.streamId, Http2FrameIO.encodeWindowUpdate(frame.payload.size)))
            }
            synchronized(writeLock) { output.flush() }
        }
        if (state != null && frame.hasFlag(FrameFlag.END_STREAM)) finishStream(frame.streamId, state)
    }

    private fun finishStream(streamId: Int, state: StreamState) {
        state.finished = true
        state.headersLatch.countDown()
        state.doneLatch.countDown()
    }

    private fun failAll(cause: IOException) {
        if (closed) return
        closed = true
        closeCause = cause
        for (state in streams.values) {
            // A stream that already received END_STREAM has its complete response; its caller may just
            // not have picked it up yet.
            if (state.finished) continue
            state.error = cause
            state.headersLatch.countDown()
            state.doneLatch.countDown()
        }
        try { socket.close() } catch (_: Exception) {}
    }

    fun close() {
        failAll(IOException("HTTP/2 connection closed"))
    }
}
