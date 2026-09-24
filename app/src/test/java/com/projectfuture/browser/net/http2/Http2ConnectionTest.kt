package com.projectfuture.browser.net.http2

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end exercise of [Http2Connection] against a tiny hand-rolled HTTP/2 "server" speaking
 * real frames/HPACK over a plain [Socket] (no TLS - [Http2Connection] only needs a connected
 * socket, and ALPN/TLS is a separate, already-covered concern - see `Alpn`). This is the same
 * "test the real client against a real, if minimal, protocol implementation" approach
 * `UrlConnectionPoolTest` uses for the HTTP/1.1 path, extended to prove genuine multiplexing:
 * two concurrent requests share one connection and each gets back its own response.
 */
class Http2ConnectionTest {
    /** A minimal HTTP/2 server loop: reads the preface, then answers every HEADERS it sees with a 200 response whose body echoes the request's `:path`. */
    private fun startFakeServer(): ServerSocket {
        val server = ServerSocket(0)
        Thread {
            try {
                val socket = server.accept()
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())
                val writeLock = Any()
                val preface = ByteArray(HTTP2_CONNECTION_PREFACE.size)
                var read = 0
                while (read < preface.size) read += input.read(preface, read, preface.size - read)

                val decoder = HpackDecoder()
                val encoder = HpackEncoder()
                while (!socket.isClosed) {
                    val frame = try { Http2FrameIO.readFrame(input) } catch (_: Exception) { break }
                    when (frame.type) {
                        FrameType.SETTINGS -> if (!frame.hasFlag(FrameFlag.ACK)) {
                            synchronized(writeLock) {
                                Http2FrameIO.writeFrame(output, Http2Frame(FrameType.SETTINGS, FrameFlag.ACK, 0, ByteArray(0)))
                                output.flush()
                            }
                        }
                        FrameType.HEADERS -> {
                            val fragment = Http2FrameIO.extractHeaderBlockFragment(frame.payload, frame.flags)
                            val requestHeaders = decoder.decode(fragment)
                            val path = requestHeaders.firstOrNull { it.name == ":path" }?.value ?: "/"
                            val body = "you asked for $path".toByteArray(Charsets.UTF_8)
                            val responseBlock = encoder.encode(listOf(HpackHeader(":status", "200"), HpackHeader("content-type", "text/plain")))
                            synchronized(writeLock) {
                                Http2FrameIO.writeFrame(output, Http2Frame(FrameType.HEADERS, FrameFlag.END_HEADERS, frame.streamId, responseBlock))
                                Http2FrameIO.writeFrame(output, Http2Frame(FrameType.DATA, FrameFlag.END_STREAM, frame.streamId, body))
                                output.flush()
                            }
                        }
                        else -> {} // WINDOW_UPDATE/DATA/etc. from the client aren't needed for this fixture.
                    }
                }
            } catch (_: Exception) {
                // A closed-server-mid-test failure surfaces via the client-side assertions below.
            }
        }.apply { isDaemon = true }.start()
        return server
    }

    @Test fun performsASingleRequestOverHttp2() {
        val server = startFakeServer()
        try {
            val socket = Socket("127.0.0.1", server.localPort)
            val connection = Http2Connection(socket)
            val response = connection.request(
                listOf(
                    HpackHeader(":method", "GET"),
                    HpackHeader(":scheme", "http"),
                    HpackHeader(":authority", "example.test"),
                    HpackHeader(":path", "/hello")
                ),
                body = null
            )
            assertEquals(200, response.statusCode)
            assertEquals("you asked for /hello", String(response.body, Charsets.UTF_8))
            assertEquals("text/plain", response.headers.toMap()["content-type"])
        } finally {
            server.close()
        }
    }

    @Test fun multiplexesTwoConcurrentRequestsOverOneConnection() {
        val server = startFakeServer()
        try {
            val socket = Socket("127.0.0.1", server.localPort)
            val connection = Http2Connection(socket)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val first = pool.submit<Http2Response> {
                    connection.request(listOf(HpackHeader(":method", "GET"), HpackHeader(":scheme", "http"), HpackHeader(":authority", "example.test"), HpackHeader(":path", "/first")), null)
                }
                val second = pool.submit<Http2Response> {
                    connection.request(listOf(HpackHeader(":method", "GET"), HpackHeader(":scheme", "http"), HpackHeader(":authority", "example.test"), HpackHeader(":path", "/second")), null)
                }
                val firstResponse = first.get(5, TimeUnit.SECONDS)
                val secondResponse = second.get(5, TimeUnit.SECONDS)
                assertEquals("you asked for /first", String(firstResponse.body, Charsets.UTF_8))
                assertEquals("you asked for /second", String(secondResponse.body, Charsets.UTF_8))
            } finally {
                pool.shutdownNow()
            }
        } finally {
            server.close()
        }
    }

    /**
     * RFC 7540 6.2/6.10: END_HEADERS and END_STREAM are independent flags on a HEADERS frame - a
     * response can declare "no DATA frames follow" (END_STREAM) while its own header block still
     * isn't finished (no END_HEADERS) and needs one or more CONTINUATION frames to complete. A
     * response with a body large/plentiful enough in headers to need CONTINUATION but with no body
     * at all (e.g. a 204, or a HEAD response with a huge Set-Cookie fan-out) is exactly this shape.
     */
    @Test fun headersWithEndStreamButSplitAcrossContinuationStillYieldsTheFullResponse() {
        val server = ServerSocket(0)
        val serverDone = CountDownLatch(1)
        Thread {
            try {
                val socket = server.accept()
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())
                val preface = ByteArray(HTTP2_CONNECTION_PREFACE.size)
                var read = 0
                while (read < preface.size) read += input.read(preface, read, preface.size - read)
                // Consume the client's initial SETTINGS frame (and anything else) until its HEADERS arrives.
                var clientStreamId = 1
                while (true) {
                    val frame = Http2FrameIO.readFrame(input)
                    if (frame.type == FrameType.HEADERS) {
                        clientStreamId = frame.streamId
                        break
                    }
                }

                val encoder = HpackEncoder()
                val responseBlock = encoder.encode(
                    listOf(
                        HpackHeader(":status", "204"),
                        HpackHeader("x-part-a", "a".repeat(50)),
                        HpackHeader("x-part-b", "b".repeat(50))
                    )
                )
                val splitAt = responseBlock.size / 2
                val first = responseBlock.copyOfRange(0, splitAt)
                val second = responseBlock.copyOfRange(splitAt, responseBlock.size)
                // HEADERS: END_STREAM set (no body coming), END_HEADERS NOT set (block continues).
                Http2FrameIO.writeFrame(output, Http2Frame(FrameType.HEADERS, FrameFlag.END_STREAM, clientStreamId, first))
                output.flush()
                // Give the client's reader thread a chance to (incorrectly, pre-fix) finish the
                // stream here if it's going to - the CONTINUATION below arrives a moment later,
                // the way a real network interleaving would, not atomically with the HEADERS frame.
                Thread.sleep(50)
                Http2FrameIO.writeFrame(output, Http2Frame(FrameType.CONTINUATION, FrameFlag.END_HEADERS, clientStreamId, second))
                output.flush()
            } catch (_: Exception) {
                // Surfaced via the client-side assertions below.
            } finally {
                serverDone.countDown()
            }
        }.apply { isDaemon = true }.start()

        try {
            val socket = Socket("127.0.0.1", server.localPort)
            val connection = Http2Connection(socket)
            val response = connection.request(
                listOf(
                    HpackHeader(":method", "GET"), HpackHeader(":scheme", "http"),
                    HpackHeader(":authority", "example.test"), HpackHeader(":path", "/no-body")
                ),
                body = null
            )
            assertEquals(204, response.statusCode)
            assertEquals("a".repeat(50), response.headers.toMap()["x-part-a"])
            assertEquals("b".repeat(50), response.headers.toMap()["x-part-b"])
            assertEquals(0, response.body.size)
        } finally {
            serverDone.await(5, TimeUnit.SECONDS)
            server.close()
        }
    }

    /**
     * RFC 7540 6.9.2: a SETTINGS frame changing SETTINGS_INITIAL_WINDOW_SIZE must adjust the
     * flow-control window of every stream that's already open by the same delta, not just streams
     * opened after the SETTINGS frame - it's a retroactive per-connection change, not a value only
     * used the next time a stream starts.
     *
     * The stream here opens with the RFC-default window (65535) and immediately tries to send a
     * 70000-byte body, so it necessarily stalls partway through, still open, with its per-stream
     * window driven down near/at zero. A SETTINGS frame then raises SETTINGS_INITIAL_WINDOW_SIZE
     * well above 65535 (plus a WINDOW_UPDATE to lift the separate connection-level window, which
     * SETTINGS never touches per 6.9.2) - the request can only complete if that already-open
     * stream's own window actually receives the (new - old) delta, not just future streams.
     */
    @Test fun settingsInitialWindowSizeChangeAdjustsAlreadyOpenStreams() {
        val server = ServerSocket(0)
        val bytesReceived = AtomicInteger(0)
        val bodySize = 70000
        Thread {
            try {
                val socket = server.accept()
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())
                val preface = ByteArray(HTTP2_CONNECTION_PREFACE.size)
                var read = 0
                while (read < preface.size) read += input.read(preface, read, preface.size - read)
                Http2FrameIO.readFrame(input) // client's initial SETTINGS - left at the RFC default (65535).

                val decoder = HpackDecoder()
                var clientStreamId = 1
                while (true) {
                    val frame = Http2FrameIO.readFrame(input)
                    when (frame.type) {
                        FrameType.SETTINGS -> {}
                        FrameType.HEADERS -> {
                            clientStreamId = frame.streamId
                            decoder.decode(Http2FrameIO.extractHeaderBlockFragment(frame.payload, frame.flags))
                        }
                        FrameType.DATA -> {
                            bytesReceived.addAndGet(frame.payload.size)
                            // Once the client has spent its whole initial 65535-byte window (the
                            // stream is now genuinely stalled, mid-body, waiting), raise the window
                            // for that already-open stream (SETTINGS) and the connection (WINDOW_UPDATE).
                            if (bytesReceived.get() >= 65535 && bytesReceived.get() < bodySize) {
                                Http2FrameIO.writeFrame(output, Http2Frame(FrameType.SETTINGS, 0, 0, Http2FrameIO.encodeSettings(listOf(SettingsId.INITIAL_WINDOW_SIZE to 200000))))
                                Http2FrameIO.writeFrame(output, Http2Frame(FrameType.WINDOW_UPDATE, 0, 0, Http2FrameIO.encodeWindowUpdate(bodySize)))
                                output.flush()
                            }
                        }
                        else -> {}
                    }
                    if (bytesReceived.get() >= bodySize) break
                }

                val encoder = HpackEncoder()
                val responseBlock = encoder.encode(listOf(HpackHeader(":status", "200")))
                Http2FrameIO.writeFrame(output, Http2Frame(FrameType.HEADERS, FrameFlag.END_HEADERS or FrameFlag.END_STREAM, clientStreamId, responseBlock))
                output.flush()
            } catch (_: Exception) {
                // Surfaced via the client-side assertions/timeout below.
            }
        }.apply { isDaemon = true }.start()

        try {
            val socket = Socket("127.0.0.1", server.localPort)
            val connection = Http2Connection(socket)
            val body = ByteArray(bodySize) { it.toByte() }
            val response = connection.request(
                listOf(
                    HpackHeader(":method", "POST"), HpackHeader(":scheme", "http"),
                    HpackHeader(":authority", "example.test"), HpackHeader(":path", "/upload")
                ),
                body = body
            )
            assertEquals(200, response.statusCode)
            assertTrue("server should have received the full $bodySize-byte body once its already-open stream's window grew", bytesReceived.get() >= bodySize)
        } finally {
            server.close()
        }
    }

    /**
     * Reproduces the unsynchronized flow-control race directly: many threads concurrently spend a
     * small, shared connection-level send window at once. Without windowLock serializing the
     * "check room, then spend it" step, two threads can both see the same leftover window and each
     * take a full share of it - so this asserts that repeated concurrent draws off the SAME shared
     * budget never let the total drawn exceed what was actually available, however many times it's
     * run back to back.
     */
    @Test fun concurrentBodyWritesNeverExceedTheSharedConnectionWindow() {
        val server = ServerSocket(0)
        val maxObservedOverGrant = AtomicInteger(0)
        val threadCount = 8
        Thread {
            try {
                val socket = server.accept()
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())
                val preface = ByteArray(HTTP2_CONNECTION_PREFACE.size)
                var read = 0
                while (read < preface.size) read += input.read(preface, read, preface.size - read)
                Http2FrameIO.readFrame(input) // client's initial SETTINGS

                val decoder = HpackDecoder()
                // The connection-level send window starts at the RFC default, 65535, and this
                // fixture never sends a single WINDOW_UPDATE - so no matter how many streams are
                // multiplexed, total DATA bytes across all of them must never exceed 65535. Keep
                // reading for a fixed window (rather than stopping once all `threadCount` HEADERS
                // frames are seen) - DATA frames legitimately arrive staggered, some well after the
                // last HEADERS, and stopping early would silently skip inspecting them.
                var granted = 65535
                socket.soTimeout = 3000
                try {
                    while (true) {
                        val frame = Http2FrameIO.readFrame(input)
                        when (frame.type) {
                            FrameType.HEADERS -> decoder.decode(Http2FrameIO.extractHeaderBlockFragment(frame.payload, frame.flags))
                            FrameType.DATA -> {
                                granted -= frame.payload.size
                                if (granted < 0) maxObservedOverGrant.set(maxOf(maxObservedOverGrant.get(), -granted))
                            }
                            else -> {}
                        }
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // Expected: no WINDOW_UPDATE is ever sent, so once every stream has stalled on
                    // its share (or lack thereof) of the connection window, no more frames arrive.
                }
            } catch (_: Exception) {
                // Surfaced via the client-side assertion below.
            }
        }.apply { isDaemon = true }.start()

        try {
            val socket = Socket("127.0.0.1", server.localPort)
            val connection = Http2Connection(socket)
            // A CyclicBarrier (rather than a thread pool's submit-and-hope) lines every thread up
            // to call request() at effectively the same instant, maximizing the odds two threads'
            // unsynchronized "check the shared window, then spend it" sequences actually overlap -
            // the same way real concurrent multiplexed uploads (e.g. several form POSTs firing at
            // once) would contend for the one connection-level window.
            val barrier = java.util.concurrent.CyclicBarrier(threadCount)
            val threads = (0 until threadCount).map { i ->
                Thread {
                    try {
                        barrier.await(5, TimeUnit.SECONDS)
                        connection.request(
                            listOf(
                                HpackHeader(":method", "POST"), HpackHeader(":scheme", "http"),
                                HpackHeader(":authority", "example.test"), HpackHeader(":path", "/upload$i")
                            ),
                            ByteArray(16384) { it.toByte() }
                        )
                    } catch (_: Exception) {
                        // The fixture never ACKs enough window for every stream's whole body, and
                        // deliberately never grants more via WINDOW_UPDATE - a timeout here is
                        // expected. Only the server-side accounting above is under test.
                    }
                }.apply { isDaemon = true }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join(15000) }
        } finally {
            server.close()
        }
        assertEquals("client sent more DATA bytes than the connection-level window ever granted", 0, maxObservedOverGrant.get())
    }

    private fun request(connection: Http2Connection, path: String) = connection.request(
        listOf(HpackHeader(":method", "GET"), HpackHeader(":scheme", "http"), HpackHeader(":authority", "example.test"), HpackHeader(":path", path)),
        null
    )

    /** Accepts one client, skips its preface, then hands every client frame to [onFrame] until the socket closes. */
    private fun scriptedServer(onFrame: (Http2Frame, BufferedOutputStream) -> Unit): ServerSocket {
        val server = ServerSocket(0)
        Thread {
            try {
                val socket = server.accept()
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())
                val preface = ByteArray(HTTP2_CONNECTION_PREFACE.size)
                var read = 0
                while (read < preface.size) read += input.read(preface, read, preface.size - read)
                while (!socket.isClosed) {
                    val frame = try { Http2FrameIO.readFrame(input) } catch (_: Exception) { break }
                    onFrame(frame, output)
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true }.start()
        return server
    }

    private fun okHeaders(encoder: HpackEncoder) = encoder.encode(listOf(HpackHeader(":status", "200")))

    @Test fun paddedDataFrameContributesOnlyItsDataToTheBody() {
        val encoder = HpackEncoder()
        val server = scriptedServer { frame, out ->
            if (frame.type == FrameType.HEADERS) {
                val data = "hello".toByteArray()
                val padded = byteArrayOf(4) + data + ByteArray(4)
                Http2FrameIO.writeFrame(out, Http2Frame(FrameType.HEADERS, FrameFlag.END_HEADERS, frame.streamId, okHeaders(encoder)))
                Http2FrameIO.writeFrame(out, Http2Frame(FrameType.DATA, FrameFlag.PADDED or FrameFlag.END_STREAM, frame.streamId, padded))
                out.flush()
            }
        }
        try {
            val response = request(Http2Connection(Socket("127.0.0.1", server.localPort)), "/padded")
            assertEquals("hello", String(response.body, Charsets.UTF_8))
        } finally {
            server.close()
        }
    }

    @Test fun resetWithNoErrorAfterACompleteResponseDoesNotFailTheRequest() {
        val encoder = HpackEncoder()
        val server = scriptedServer { frame, out ->
            if (frame.type == FrameType.HEADERS) {
                // RFC 7540 8.1 lets a server follow a complete response with RST_STREAM(NO_ERROR).
                Http2FrameIO.writeFrame(out, Http2Frame(FrameType.HEADERS, FrameFlag.END_HEADERS, frame.streamId, okHeaders(encoder)))
                Http2FrameIO.writeFrame(out, Http2Frame(FrameType.DATA, FrameFlag.END_STREAM, frame.streamId, "done".toByteArray()))
                Http2FrameIO.writeFrame(out, Http2Frame(FrameType.RST_STREAM, 0, frame.streamId, Http2FrameIO.encodeRstStream(ErrorCode.NO_ERROR)))
                out.flush()
            }
        }
        try {
            val connection = Http2Connection(Socket("127.0.0.1", server.localPort))
            repeat(20) { assertEquals("done", String(request(connection, "/r$it").body, Charsets.UTF_8)) }
        } finally {
            server.close()
        }
    }

    @Test fun concurrentRequestsOpenStreamIdsInIncreasingOrder() {
        val seen = java.util.Collections.synchronizedList(ArrayList<Int>())
        val encoder = HpackEncoder()
        val decoder = HpackDecoder()
        val server = scriptedServer { frame, out ->
            if (frame.type == FrameType.HEADERS) {
                seen.add(frame.streamId)
                decoder.decode(Http2FrameIO.extractHeaderBlockFragment(frame.payload, frame.flags))
                Http2FrameIO.writeFrame(out, Http2Frame(FrameType.HEADERS, FrameFlag.END_HEADERS or FrameFlag.END_STREAM, frame.streamId, okHeaders(encoder)))
                out.flush()
            }
        }
        val threads = 32
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val connection = Http2Connection(Socket("127.0.0.1", server.localPort))
            val start = CountDownLatch(1)
            val futures = (0 until threads).map { i -> pool.submit<Int> { start.await(); request(connection, "/c$i").statusCode } }
            start.countDown()
            for (f in futures) assertEquals(200, f.get(10, TimeUnit.SECONDS))
            // RFC 7540 5.1.1: a server that sees a lower id after a higher one treats it as closed and never answers it.
            assertEquals(seen.sorted(), seen.toList())
        } finally {
            pool.shutdownNow()
            server.close()
        }
    }

    @Test fun gracefulGoAwayLetsStreamsAtOrBelowTheLastStreamIdFinish() {
        val encoder = HpackEncoder()
        val server = scriptedServer { frame, out ->
            if (frame.type == FrameType.HEADERS) {
                val goAway = Http2FrameIO.encodeRstStream(frame.streamId) + Http2FrameIO.encodeRstStream(ErrorCode.NO_ERROR)
                Http2FrameIO.writeFrame(out, Http2Frame(FrameType.GOAWAY, 0, 0, goAway))
                Http2FrameIO.writeFrame(out, Http2Frame(FrameType.HEADERS, FrameFlag.END_HEADERS, frame.streamId, okHeaders(encoder)))
                Http2FrameIO.writeFrame(out, Http2Frame(FrameType.DATA, FrameFlag.END_STREAM, frame.streamId, "finished".toByteArray()))
                out.flush()
            }
        }
        try {
            val connection = Http2Connection(Socket("127.0.0.1", server.localPort))
            assertEquals("finished", String(request(connection, "/last").body, Charsets.UTF_8))
            assertTrue("a connection that received GOAWAY must not be reused for new requests", !connection.isOpen)
        } finally {
            server.close()
        }
    }
}
