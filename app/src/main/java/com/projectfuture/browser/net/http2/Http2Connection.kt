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
private const val REQUEST_TIMEOUT_MS = 20000L

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

    @Volatile private var peerInitialWindowSize = DEFAULT_INITIAL_WINDOW
    @Volatile private var connectionSendWindow = DEFAULT_INITIAL_WINDOW
    @Volatile private var maxFrameSize = DEFAULT_MAX_FRAME_SIZE
    @Volatile private var closed = false
    @Volatile private var closeCause: IOException? = null

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

    val isOpen: Boolean get() = !closed

    /** Holds one request stream's in-flight response state; visible to both the reader thread and the requesting thread. */
    private class StreamState {
        val headersLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(1)
        @Volatile var statusCode: Int = 0
        val responseHeaders = ArrayList<Pair<String, String>>()
        val body = java.io.ByteArrayOutputStream()
        @Volatile var sendWindow = 0
        @Volatile var error: IOException? = null
        var headerBlockInProgress: java.io.ByteArrayOutputStream? = null
    }

    /**
     * Opens a new client-initiated stream, sends its request headers (and body, if any), and
     * blocks the calling thread until the full response is read or an error/timeout occurs.
     * Safe to call from multiple threads concurrently - that's the point of a multiplexed
     * connection.
     */
    fun request(pseudoAndHeaders: List<HpackHeader>, body: ByteArray?): Http2Response {
        if (closed) throw closeCause ?: Http2ConnectionClosedException("HTTP/2 connection is closed")
        val state = StreamState().apply { sendWindow = peerInitialWindowSize }
        val streamId = nextStreamId.getAndAdd(2)
        streams[streamId] = state
        try {
            // Only the HEADERS encode+write is serialized end-to-end (HPACK's dynamic table is
            // shared connection state, and header blocks must reach the peer in the order they
            // were encoded); DATA frames are written by writeBody with their own fine-grained
            // locking per frame, so one stream waiting on flow control never blocks another
            // stream's headers or data from going out.
            synchronized(writeLock) {
                val block = hpackEncoder.encode(pseudoAndHeaders)
                writeHeaderBlock(streamId, block, endStream = body == null)
            }
            if (body != null && body.isNotEmpty()) writeBody(streamId, state, body)
            if (!state.doneLatch.await(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw IOException("HTTP/2 stream $streamId timed out")
            }
            state.error?.let { throw it }
            return Http2Response(state.statusCode, state.responseHeaders, state.body.toByteArray())
        } finally {
            streams.remove(streamId)
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
        val deadline = System.currentTimeMillis() + REQUEST_TIMEOUT_MS
        while (offset < body.size) {
            // Real flow control: never send more than the peer's advertised stream/connection
            // window allows (RFC 7540 6.9), polling for WINDOW_UPDATE frames the reader thread
            // applies concurrently. A short poll loop rather than wait/notify keeps this simple;
            // request bodies in this browser (form POSTs) are small enough that it never matters.
            while (true) {
                val available = minOf(state.sendWindow, connectionSendWindow, maxFrameSize)
                if (available > 0) break
                if (System.currentTimeMillis() > deadline) throw IOException("HTTP/2 flow-control window timed out")
                Thread.sleep(5)
            }
            val chunkSize = minOf(minOf(state.sendWindow, connectionSendWindow, maxFrameSize), body.size - offset)
            val chunk = body.copyOfRange(offset, offset + chunkSize)
            offset += chunkSize
            state.sendWindow -= chunkSize
            connectionSendWindow -= chunkSize
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

    private fun handleFrame(frame: Http2Frame) {
        when (frame.type) {
            FrameType.SETTINGS -> handleSettings(frame)
            FrameType.WINDOW_UPDATE -> handleWindowUpdate(frame)
            FrameType.HEADERS -> handleHeaders(frame)
            FrameType.CONTINUATION -> handleContinuation(frame)
            FrameType.DATA -> handleData(frame)
            FrameType.RST_STREAM -> streams[frame.streamId]?.let {
                it.error = IOException("Stream ${frame.streamId} reset by server")
                it.headersLatch.countDown()
                it.doneLatch.countDown()
            }
            FrameType.PING -> if (!frame.hasFlag(FrameFlag.ACK)) {
                writeFrameLocked(Http2Frame(FrameType.PING, FrameFlag.ACK, CONNECTION_STREAM_ID, frame.payload))
                synchronized(writeLock) { output.flush() }
            }
            FrameType.GOAWAY -> failAll(IOException("HTTP/2 GOAWAY from server"))
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

    private fun handleSettings(frame: Http2Frame) {
        if (frame.hasFlag(FrameFlag.ACK)) return
        for ((id, value) in Http2FrameIO.decodeSettings(frame.payload)) {
            when (id) {
                SettingsId.INITIAL_WINDOW_SIZE -> peerInitialWindowSize = value
                SettingsId.MAX_FRAME_SIZE -> if (value in DEFAULT_MAX_FRAME_SIZE..(1 shl 24) - 1) maxFrameSize = value
                SettingsId.HEADER_TABLE_SIZE -> {} // hpackEncoder's dynamic table already defaults within bounds any real server sets.
            }
        }
        writeFrameLocked(Http2Frame(FrameType.SETTINGS, FrameFlag.ACK, CONNECTION_STREAM_ID, ByteArray(0)))
        synchronized(writeLock) { output.flush() }
    }

    private fun handleWindowUpdate(frame: Http2Frame) {
        val increment = Http2FrameIO.decodeWindowUpdate(frame.payload)
        if (frame.streamId == CONNECTION_STREAM_ID) {
            connectionSendWindow += increment
        } else {
            streams[frame.streamId]?.let { it.sendWindow += increment }
        }
    }

    private fun handleHeaders(frame: Http2Frame) {
        val state = streams[frame.streamId] ?: return // Response for a stream we've stopped tracking (e.g. timed out) - ignore.
        val fragment = Http2FrameIO.extractHeaderBlockFragment(frame.payload, frame.flags)
        if (frame.hasFlag(FrameFlag.END_HEADERS)) {
            applyHeaderBlock(state, fragment)
        } else {
            state.headerBlockInProgress = java.io.ByteArrayOutputStream().apply { write(fragment) }
        }
        if (frame.hasFlag(FrameFlag.END_STREAM)) finishStream(frame.streamId, state)
    }

    private fun handleContinuation(frame: Http2Frame) {
        val state = streams[frame.streamId] ?: return
        val buffer = state.headerBlockInProgress ?: java.io.ByteArrayOutputStream().also { state.headerBlockInProgress = it }
        buffer.write(frame.payload)
        if (frame.hasFlag(FrameFlag.END_HEADERS)) {
            applyHeaderBlock(state, buffer.toByteArray())
            state.headerBlockInProgress = null
        }
    }

    /** The HPACK dynamic table is per-connection shared state, so header blocks must be decoded strictly in the frame order they arrived - true here since only this single reader thread ever calls it. */
    private fun applyHeaderBlock(state: StreamState, block: ByteArray) {
        val decoded = try {
            hpackDecoder.decode(block)
        } catch (e: Exception) {
            state.error = IOException("Malformed HPACK block", e)
            state.headersLatch.countDown()
            state.doneLatch.countDown()
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
        if (state != null) {
            synchronized(state.body) { state.body.write(frame.payload) }
        }
        // Replenish flow control unconditionally (even for an untracked/timed-out stream) so the
        // server's connection-level window doesn't stall out for every other stream sharing it.
        if (frame.payload.isNotEmpty()) {
            writeFrameLocked(Http2Frame(FrameType.WINDOW_UPDATE, 0, CONNECTION_STREAM_ID, Http2FrameIO.encodeWindowUpdate(frame.payload.size)))
            if (state != null) {
                writeFrameLocked(Http2Frame(FrameType.WINDOW_UPDATE, 0, frame.streamId, Http2FrameIO.encodeWindowUpdate(frame.payload.size)))
            }
            synchronized(writeLock) { output.flush() }
        }
        if (state != null && frame.hasFlag(FrameFlag.END_STREAM)) finishStream(frame.streamId, state)
    }

    private fun finishStream(streamId: Int, state: StreamState) {
        state.headersLatch.countDown()
        state.doneLatch.countDown()
    }

    private fun failAll(cause: IOException) {
        if (closed) return
        closed = true
        closeCause = cause
        for (state in streams.values) {
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
