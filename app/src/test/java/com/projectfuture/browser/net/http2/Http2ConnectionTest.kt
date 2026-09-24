package com.projectfuture.browser.net.http2

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
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
}
