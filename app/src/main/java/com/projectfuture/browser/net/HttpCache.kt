package com.projectfuture.browser.net

/**
 * A minimal in-memory HTTP cache (a bounded RFC 7234 subset): honors
 * `Cache-Control: max-age`/`no-store`/`no-cache`/`private`, keyed by the
 * exact request URL, for GET responses only. Deliberately narrow, same
 * spirit as the `Expires` gap already documented in CookieJar.kt:
 * - Not persisted across app restarts - session-only, held in memory.
 * - No conditional revalidation (`ETag`/`If-None-Match`/`Last-Modified`) -
 *   an expired entry is simply discarded and the next request re-fetches
 *   the full response, rather than sending a cheap conditional request.
 * - No `Expires` header support, only `max-age` - same reasoning as
 *   CookieJar's Max-Age-only handling: HTTP-date parsing is its own
 *   small time-sink this project isn't taking on right now.
 * - No `Vary` support - a cached entry is reused regardless of what
 *   request headers a later request sends.
 * - Only `fetch()`'s plain-text responses are cached, not `fetchBytes()`
 *   (images) - real value for repeat page/stylesheet/XHR loads within a
 *   session, at a fraction of the complexity of caching binary resources
 *   too.
 */
object HttpCache {
    private data class Entry(val response: HttpResponse, val expiresAtMillis: Long)

    private val entries = HashMap<String, Entry>()

    @Synchronized
    fun get(url: Url, nowMillis: Long = System.currentTimeMillis()): HttpResponse? {
        val key = url.toString()
        val entry = entries[key] ?: return null
        if (nowMillis >= entry.expiresAtMillis) {
            entries.remove(key)
            return null
        }
        return entry.response
    }

    @Synchronized
    fun store(url: Url, response: HttpResponse, nowMillis: Long = System.currentTimeMillis()) {
        val expiresAt = cacheableExpiryMillis(response, nowMillis) ?: return
        entries[url.toString()] = Entry(response, expiresAt)
    }

    @Synchronized
    fun clear() = entries.clear()
}

/**
 * Pulled out as a free function (see CookieJar.kt's parseSetCookie for the
 * same reasoning) so the max-age/no-store/no-cache/private parsing is
 * directly unit-testable. Returns null when the response isn't cacheable
 * at all (non-200, or `Cache-Control` says not to store/reuse it, or there
 * is no `max-age` to bound its freshness lifetime).
 */
fun cacheableExpiryMillis(response: HttpResponse, nowMillis: Long): Long? {
    if (response.statusCode != 200) return null
    val cacheControl = response.headers["cache-control"]?.lowercase() ?: ""
    if (listOf("no-store", "no-cache", "private").any { it in cacheControl }) return null
    val maxAge = Regex("max-age=(\\d+)").find(cacheControl)?.groupValues?.get(1)?.toLongOrNull() ?: return null
    return nowMillis + maxAge * 1000
}
