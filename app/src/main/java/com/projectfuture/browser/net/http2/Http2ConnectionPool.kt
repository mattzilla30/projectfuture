package com.projectfuture.browser.net.http2

/**
 * Keyed (by "host:port") pool of live [Http2Connection]s - the HTTP/2 analogue of
 * `ConnectionPool` for HTTP/1.1 keep-alive sockets, except a single entry here is reused
 * *concurrently* by any number of requests (that's what multiplexing means), rather than being
 * checked out by one request at a time.
 */
object Http2ConnectionPool {
    private val connections = HashMap<String, Http2Connection>()

    /** Returns a still-open pooled connection for [key], if there is one. */
    fun get(key: String): Http2Connection? {
        synchronized(connections) {
            val existing = connections[key] ?: return null
            if (existing.isOpen) return existing
            connections.remove(key)
            return null
        }
    }

    /** Registers a newly ALPN-negotiated HTTP/2 connection for future requests to [key] to reuse. */
    fun put(key: String, connection: Http2Connection) {
        synchronized(connections) {
            connections[key]?.takeIf { it.isOpen }?.close()
            connections[key] = connection
        }
    }
}
