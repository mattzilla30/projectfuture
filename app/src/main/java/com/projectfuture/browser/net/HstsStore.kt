package com.projectfuture.browser.net

/**
 * A minimal in-memory HSTS (HTTP Strict Transport Security) store: once a
 * host has sent a `Strict-Transport-Security: max-age=N` header over a
 * *verified HTTPS connection* (see the `isHttps` guard at the call site in
 * Url.kt - a plain-HTTP response setting this header must be ignored, or
 * anyone on the network could forge it), every subsequent plain-HTTP
 * request to that host is transparently upgraded to HTTPS instead of ever
 * going out in the clear. Session-only (not persisted across app
 * restarts, unlike a real browser's HSTS preload/dynamic list) - a
 * documented, bounded simplification, same spirit as HttpCache and
 * CookieJar's own scope cuts. `includeSubDomains`/preload lists aren't
 * implemented.
 */
object HstsStore {
    private data class Entry(val expiresAtMillis: Long)

    private val hosts = HashMap<String, Entry>()

    @Synchronized
    fun isEnforced(host: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val key = host.lowercase()
        val entry = hosts[key] ?: return false
        if (nowMillis >= entry.expiresAtMillis) {
            hosts.remove(key)
            return false
        }
        return true
    }

    @Synchronized
    fun record(host: String, headerValue: String, nowMillis: Long = System.currentTimeMillis()) {
        val maxAge = parseMaxAge(headerValue) ?: return
        val key = host.lowercase()
        if (maxAge <= 0) {
            hosts.remove(key) // max-age=0 means "forget this host was ever HSTS-enforced"
        } else {
            hosts[key] = Entry(nowMillis + maxAge * 1000)
        }
    }

    @Synchronized
    fun clear() = hosts.clear()
}

/** Pulled out as a free function so the header parsing is directly unit-testable. */
fun parseMaxAge(headerValue: String): Long? =
    Regex("max-age=(\\d+)").find(headerValue.lowercase())?.groupValues?.get(1)?.toLongOrNull()
