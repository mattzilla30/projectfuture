package com.projectfuture.browser.net.http2

import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Http2ConnectionPool] is meant to hand out ONE shared [Http2Connection] per host for any number
 * of concurrent requests to multiplex over (see its class doc and `Http2ConnectionTest`'s
 * multiplexing test). But `Url.fetchRaw`'s "check the pool, and if empty do our own handshake and
 * register the result" sequence is a classic check-then-act race: two callers can both miss the
 * pool (two Tabs requesting the same host at once, or `preconnect()` racing the real request - both
 * legitimate per this project's own docs) and each finish their own handshake before either
 * registers. Whichever registers second must not blindly evict the first: something may already be
 * relying on that first connection (another thread that got it via `get()`, or a `request()` call
 * already in flight on it), and closing it out from under that use breaks an in-flight multiplexed
 * request - exactly the failure multiplexing exists to avoid.
 */
class Http2ConnectionPoolTest {
    /** Accepts one connection per call and holds it open (reads the client preface, then just idles) - enough for an [Http2Connection] to consider itself open. */
    private fun acceptAndHoldOpen(server: ServerSocket): Socket {
        val socket = server.accept()
        Thread {
            try {
                val input = socket.getInputStream()
                val buf = ByteArray(4096)
                while (input.read(buf) != -1) { /* idle: just drain whatever the client sends */ }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true }.start()
        return socket
    }

    @Test fun secondRegistrationForTheSameKeyDoesNotCloseTheFirstStillOpenConnection() {
        val server = ServerSocket(0)
        try {
            val key = "https://example.test:443"

            val clientSocketA = Socket("127.0.0.1", server.localPort)
            val serverSocketA = acceptAndHoldOpen(server)
            val connectionA = Http2Connection(clientSocketA)

            // Simulates the first of two racing callers registering its freshly-handshaked
            // connection - e.g. the winner of two concurrent Url.fetch() calls to the same host.
            val firstWinner = Http2ConnectionPool.putIfAbsent(key, connectionA)
            assertSame("the first registration should win and be returned as-is", connectionA, firstWinner)

            // Anything using connectionA at this point (another thread that already called get(),
            // or a request() already in flight on it) is now depending on it staying open.
            val someOtherThreadIsUsingConnectionA = connectionA
            assertTrue(someOtherThreadIsUsingConnectionA.isOpen)

            // The second of the two racing callers finishes its own (redundant) handshake and also
            // tries to register - simulating the loser of that same race.
            val clientSocketB = Socket("127.0.0.1", server.localPort)
            val serverSocketB = acceptAndHoldOpen(server)
            val connectionB = Http2Connection(clientSocketB)
            val secondWinner = Http2ConnectionPool.putIfAbsent(key, connectionB)

            // The pool must converge both callers on the SAME connection (the first one) rather
            // than replacing it - that's what actually delivers multiplexing across concurrent
            // requests to the same host.
            assertSame("a second registration for the same key must not replace an existing, still-open connection", connectionA, secondWinner)
            assertTrue(
                "the connection an earlier caller is already relying on must not be closed out from under it by a losing, redundant registration",
                someOtherThreadIsUsingConnectionA.isOpen
            )

            connectionA.close()
            connectionB.close()
            serverSocketA.close()
            serverSocketB.close()
        } finally {
            server.close()
        }
    }
}
