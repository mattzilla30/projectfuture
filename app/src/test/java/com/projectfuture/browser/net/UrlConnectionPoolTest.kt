package com.projectfuture.browser.net

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Url.kt has no Android dependency (plain java.net/java.io), so its
 * keep-alive connection reuse (ConnectionPool) can be exercised for real
 * against a tiny hand-rolled local HTTP server, rather than only ever
 * being checked by "it compiles" - the same reasoning as the JS
 * interpreter and DomBridge tests.
 */
class UrlConnectionPoolTest {

    /** Accepts exactly one connection and serves [responseCount] keep-alive responses over it, then closes. */
    private fun startKeepAliveServer(responseCount: Int, acceptCount: AtomicInteger): ServerSocket {
        val server = ServerSocket(0)
        Thread {
            try {
                val socket = server.accept()
                acceptCount.incrementAndGet()
                val input = BufferedReader(InputStreamReader(socket.getInputStream()))
                val output = socket.getOutputStream()
                repeat(responseCount) {
                    // Consume the request line + headers up to the blank line.
                    var line = input.readLine()
                    while (line != null && line.isNotEmpty()) line = input.readLine()
                    val body = "response-$it"
                    val response = "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: keep-alive\r\n\r\n$body"
                    output.write(response.toByteArray(Charsets.US_ASCII))
                    output.flush()
                }
                socket.close()
            } catch (_: Exception) {
                // Test failure surfaces via the assertions below timing out/failing to match, not here.
            }
        }.apply { isDaemon = true }.start()
        return server
    }

    @Test fun reusesOneSocketAcrossSequentialRequests() {
        val acceptCount = AtomicInteger(0)
        val server = startKeepAliveServer(responseCount = 2, acceptCount = acceptCount)
        try {
            val port = server.localPort
            val first = Url.parse("http://127.0.0.1:$port/a").fetch()
            val second = Url.parse("http://127.0.0.1:$port/b").fetch()

            assertEquals("response-0", first.body)
            assertEquals("response-1", second.body)
            // Both requests were served by the single accepted connection - proof the second
            // request reused a pooled socket instead of opening (and the server accepting) a new one.
            assertEquals(1, acceptCount.get())
        } finally {
            server.close()
        }
    }

    @Test fun fallsBackToFreshConnectionWhenServerClosesTheOnlyOne() {
        val acceptCount = AtomicInteger(0)
        // Server only answers one request per accepted connection, then closes - simulating a
        // keep-alive timeout that already happened by the time the second request tries to reuse it.
        val server = ServerSocket(0)
        Thread {
            try {
                repeat(2) {
                    val socket = server.accept()
                    acceptCount.incrementAndGet()
                    val input = BufferedReader(InputStreamReader(socket.getInputStream()))
                    var line = input.readLine()
                    while (line != null && line.isNotEmpty()) line = input.readLine()
                    val body = "response-$it"
                    val response = "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                    socket.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
                    socket.getOutputStream().flush()
                    socket.close()
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true }.start()
        try {
            val port = server.localPort
            val first = Url.parse("http://127.0.0.1:$port/a").fetch()
            val second = Url.parse("http://127.0.0.1:$port/b").fetch()
            assertEquals("response-0", first.body)
            assertEquals("response-1", second.body)
            assertTrue("server should see two separate connections since 'Connection: close' prevents pooling", acceptCount.get() == 2)
        } finally {
            server.close()
        }
    }

    /**
     * Real servers send `charset=` in any position within `Content-Type`, not only as the last
     * parameter (e.g. `Content-Type: text/html; charset=iso-8859-1; boundary=x`, a legal RFC 2045
     * parameter list). Before the fix, `charsetFromContentType` handed everything from `charset=`
     * to the end of the header - including any later parameters - to `Charset.forName`, which threw
     * for a value like `iso-8859-1; boundary=x` and silently fell back to UTF-8, corrupting the
     * decoded body of every response whose declared charset wasn't ASCII-compatible with UTF-8
     * (the actual bug this reproduces: a single non-ASCII Latin-1 byte, 0xE9 = 'é' in ISO-8859-1,
     * decodes as mojibake under UTF-8).
     */
    @Test fun charsetIsParsedWhenFollowedByAnotherContentTypeParameter() {
        val server = ServerSocket(0)
        Thread {
            try {
                val socket = server.accept()
                val input = BufferedReader(InputStreamReader(socket.getInputStream()))
                var line = input.readLine()
                while (line != null && line.isNotEmpty()) line = input.readLine()
                // 0xE9 is 'é' in ISO-8859-1 but a lone continuation byte (invalid/mojibake) in UTF-8.
                val bodyBytes = byteArrayOf('h'.code.toByte(), 0xE9.toByte(), 'x'.code.toByte())
                val head = "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=iso-8859-1; boundary=x\r\n" +
                    "Content-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n"
                val output = socket.getOutputStream()
                output.write(head.toByteArray(Charsets.US_ASCII))
                output.write(bodyBytes)
                output.flush()
                socket.close()
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true }.start()
        try {
            val port = server.localPort
            val response = Url.parse("http://127.0.0.1:$port/x").fetch()
            assertEquals("héx", response.body)
        } finally {
            server.close()
        }
    }
}
