package com.projectfuture.browser.net

import java.net.Socket
import java.util.ArrayDeque

/**
 * A tiny keep-alive connection pool, keyed by "scheme://host:port". Each
 * page load's networking runs on one background executor thread per Tab
 * (see Tab.kt), so requests within a page are sequential - this doesn't
 * need to support handing out multiple concurrent sockets for the same
 * key, just remembering one or two idle ones between requests (including
 * across different Tabs, hence the `synchronized` guard). A socket is only
 * ever pooled after a response was read to a *determinate* end (a known
 * `Content-Length` or chunked encoding); a response with neither has to be
 * read to EOF, which leaves the socket unusable for a further request -
 * see Url.fetchRaw.
 *
 * Idle sockets can still go stale (server-side keep-alive timeouts close
 * them from under us), so [borrow] only hands back a socket that still
 * looks connected; Url.fetchRaw additionally retries once on a fresh
 * socket if a borrowed one fails on the very first write.
 */
object ConnectionPool {
    private const val MAX_IDLE_PER_KEY = 4
    // Bounds the TOTAL idle sockets kept open across every host, not just per key. Per-key alone
    // doesn't stop unbounded growth: browsing many different sites in one session (or across
    // several Tabs) adds a distinct key per host, and with no global cap the pool would keep
    // accumulating idle sockets (heap + file descriptors) for hosts long since navigated away
    // from - a real resource leak on a memory-constrained mobile device that a per-key limit alone
    // can't catch.
    private const val MAX_IDLE_TOTAL = 20
    // LinkedHashMap so key iteration order is insertion order, which [release] uses to evict the
    // globally-oldest idle socket first when the total cap is hit.
    private val idle = LinkedHashMap<String, ArrayDeque<Socket>>()
    private var totalIdle = 0

    fun borrow(key: String): Socket? {
        synchronized(idle) {
            val deque = idle[key] ?: return null
            while (deque.isNotEmpty()) {
                val socket = deque.removeFirst()
                totalIdle--
                if (socket.isConnected && !socket.isClosed) return socket
                try { socket.close() } catch (_: Exception) {}
            }
            return null
        }
    }

    fun release(key: String, socket: Socket) {
        if (!socket.isConnected || socket.isClosed) return
        synchronized(idle) {
            val deque = idle.getOrPut(key) { ArrayDeque() }
            if (deque.size >= MAX_IDLE_PER_KEY) {
                try { socket.close() } catch (_: Exception) {}
                return
            }
            if (totalIdle >= MAX_IDLE_TOTAL) {
                val oldestKey = idle.entries.firstOrNull { it.value.isNotEmpty() }?.key
                if (oldestKey != null) {
                    val evicted = idle.getValue(oldestKey).removeFirst()
                    totalIdle--
                    try { evicted.close() } catch (_: Exception) {}
                }
            }
            deque.addLast(socket)
            totalIdle++
        }
    }
}
