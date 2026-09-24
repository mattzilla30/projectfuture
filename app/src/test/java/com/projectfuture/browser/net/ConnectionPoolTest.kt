package com.projectfuture.browser.net

import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [ConnectionPool] directly against real connected sockets (a local loopback server
 * just needs to accept and hold connections open - keep-alive semantics for a *single* host are
 * already covered end-to-end through `Url.fetch` in [UrlConnectionPoolTest]). This targets the
 * pool's behavior across MANY DIFFERENT hosts/keys at once, which a per-key cap alone can't bound.
 */
class ConnectionPoolTest {
    /** A local server that accepts up to [count] connections and holds each open (no response needed - the pool never reads from an idle socket). */
    private fun acceptSockets(count: Int): Pair<ServerSocket, List<Socket>> {
        val server = ServerSocket(0)
        val serverSideSockets = ArrayList<Socket>()
        val accepted = Thread {
            try {
                repeat(count) { serverSideSockets.add(server.accept()) }
            } catch (_: Exception) {
            }
        }
        accepted.start()
        val clientSockets = (0 until count).map { Socket("127.0.0.1", server.localPort) }
        accepted.join(5000)
        return server to clientSockets
    }

    /**
     * Before the fix, [ConnectionPool] only bounded idle sockets *per key* (`MAX_IDLE_PER_KEY`);
     * nothing bounded the total across all the distinct keys a browsing session accumulates
     * (one host per site visited, kept forever). This releases 30 real connected sockets under 30
     * distinct keys - one idle socket per host, well under any single key's own limit - and checks
     * the pool doesn't just keep all 30 open forever.
     */
    @Test fun boundsTotalIdleSocketsAcrossManyDifferentHosts() {
        val hostCount = 30
        val (server, sockets) = acceptSockets(hostCount)
        try {
            sockets.forEachIndexed { i, socket ->
                ConnectionPool.release("https://host-$i.example:443", socket)
            }

            val stillOpen = sockets.count { it.isConnected && !it.isClosed }
            val closed = sockets.count { it.isClosed }

            assertTrue(
                "pool should have evicted (closed) some idle sockets once its total cap was exceeded, instead all $hostCount stayed open",
                closed > 0
            )
            assertTrue(
                "pool should still be holding on to at least one idle socket, not have evicted everything",
                stillOpen > 0
            )
            // The oldest ones (released first, i.e. lowest index) should be the ones evicted -
            // confirms eviction order, not just that *something* got closed.
            assertTrue("the earliest-released socket should have been evicted first", sockets[0].isClosed)
            assertFalse("the most-recently-released socket should still be pooled", sockets[hostCount - 1].isClosed)
        } finally {
            server.close()
            sockets.forEach { try { it.close() } catch (_: Exception) {} }
        }
    }
}
