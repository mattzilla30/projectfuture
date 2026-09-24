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

    /**
     * Registers a newly ALPN-negotiated HTTP/2 connection for future requests to [key] to reuse -
     * unless another thread already raced ahead and registered a still-open one first, in which
     * case *that* connection is returned instead and [connection] is left untouched for the caller
     * to close.
     *
     * This has to be a check-and-set, not a blind overwrite: two threads can each miss [get] (e.g.
     * two Tabs' requests to the same host, or a `preconnect()` racing the real request - both
     * legitimate per this project's design, see their docs) and then both finish their own TCP+TLS
     * handshake and call this. A blind `connections[key] = connection` would let the second caller
     * close out the first caller's connection - the exact connection a concurrent `request()` call
     * on it (or one about to be dispatched via [get]) may already be relying on - breaking an
     * in-flight multiplexed request and defeating the whole point of pooling one connection per
     * host. Converging both callers onto a single winner instead is what actually delivers the
     * multiplexing this class exists for.
     */
    fun putIfAbsent(key: String, connection: Http2Connection): Http2Connection {
        synchronized(connections) {
            val existing = connections[key]
            if (existing != null && existing.isOpen) return existing
            connections[key] = connection
            return connection
        }
    }
}
