package com.projectfuture.browser.net

import android.content.Context

data class Cookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    val expiresAt: Long? = null,
    val secure: Boolean = false,
    // True for a cookie set with no explicit `Domain` attribute at all - per RFC 6265 6.1 that
    // makes it "host-only": it must be sent back only to the exact host that set it, never a
    // subdomain. Only a cookie that *does* carry a `Domain` attribute (with or without the
    // leading dot RFC 6265 says to strip) is a "domain cookie" that also matches subdomains.
    val hostOnly: Boolean = true
)

/**
 * A minimal cookie jar: domain-suffix matching (no public-suffix-list, so
 * `example.co.uk` and a hypothetical sibling site under the same public
 * suffix would incorrectly be treated as domain-related - a real gap, just
 * a rare one), path-prefix matching, persisted via SharedPreferences with
 * the same hand-written tab/newline serialization as HistoryStore/
 * BookmarkStore rather than a JSON library. `Expires` (an HTTP-date) isn't
 * parsed, only `Max-Age` - HTTP-date parsing correctly is its own small
 * time-sink and `Max-Age` covers the common case modern servers send.
 * `HttpOnly`/`SameSite` aren't enforced, since nothing in this engine
 * currently exposes `document.cookie` to scripts for them to guard
 * against - there's no theft surface yet to defend.
 */
class CookieJar(context: Context) {
    private val prefs = context.getSharedPreferences("cookies", Context.MODE_PRIVATE)
    private val cookies = ArrayList<Cookie>()

    init {
        load()
    }

    @Synchronized
    fun cookieHeaderFor(url: Url): String? {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt != null && it.expiresAt < now }
        val matching = cookies.filter {
            val domainOk = if (it.hostOnly) url.host == it.domain else domainMatches(it.domain, url.host)
            domainOk && url.path.startsWith(it.path) && (!it.secure || url.isHttps)
        }
        if (matching.isEmpty()) return null
        return matching.joinToString("; ") { "${it.name}=${it.value}" }
    }

    @Synchronized
    fun store(url: Url, setCookieHeaders: List<String>) {
        if (setCookieHeaders.isEmpty()) return
        for (header in setCookieHeaders) {
            val cookie = parseSetCookie(header, url.host) ?: continue
            cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            cookies.add(cookie)
        }
        save()
    }

    private fun load() {
        val raw = prefs.getString(KEY, null) ?: return
        for (line in raw.split("\n")) {
            if (line.isBlank()) continue
            val parts = line.split("\t")
            // A 7th (hostOnly) field was added later; entries written by an older version of this
            // store only have 6, and are treated as domain cookies (this store's old behavior) for
            // backward compatibility rather than dropped.
            if (parts.size != 6 && parts.size != 7) continue
            cookies.add(
                Cookie(
                    name = parts[0],
                    value = parts[1],
                    domain = parts[2],
                    path = parts[3],
                    expiresAt = parts[4].toLongOrNull().takeIf { parts[4] != "-" },
                    secure = parts[5] == "1",
                    hostOnly = if (parts.size == 7) parts[6] == "1" else false
                )
            )
        }
    }

    private fun save() {
        val raw = cookies.joinToString("\n") {
            "${it.name}\t${it.value}\t${it.domain}\t${it.path}\t${it.expiresAt ?: "-"}\t${if (it.secure) "1" else "0"}\t${if (it.hostOnly) "1" else "0"}"
        }
        prefs.edit().putString(KEY, raw).apply()
    }

    companion object {
        private const val KEY = "entries"
    }
}

/**
 * A module-level holder rather than a constructor parameter threaded through
 * every Url.fetch() call site (there are many, across Tab/DomBridge), set
 * once by MainActivity at startup. Url.kt otherwise has no Android
 * dependency and works fine with this left null (e.g. in tests) - cookies
 * are then simply not sent or stored.
 */
var sharedCookieJar: CookieJar? = null

/**
 * Pulled out as a free function (rather than a CookieJar method) so it's
 * directly unit-testable: CookieJar itself needs an Android Context for
 * SharedPreferences, which local JVM unit tests can't exercise (the
 * android.jar they run against is a behaviorless stub), but the actual
 * `Set-Cookie` parsing logic has no Android dependency at all.
 */
fun parseSetCookie(header: String, requestHost: String): Cookie? {
    val parts = header.split(";").map { it.trim() }
    val nameValue = parts.firstOrNull()?.split("=", limit = 2) ?: return null
    if (nameValue.size != 2 || nameValue[0].isBlank()) return null

    var domain = requestHost
    var path = "/"
    var expiresAt: Long? = null
    var secure = false
    var hostOnly = true
    for (attr in parts.drop(1)) {
        val kv = attr.split("=", limit = 2)
        when (kv[0].trim().lowercase()) {
            // Any explicit Domain attribute - leading dot or not - makes this a domain cookie
            // (RFC 6265 5.2.3/5.3): it's no longer host-only, and should also match subdomains.
            "domain" -> kv.getOrNull(1)?.trim()?.removePrefix(".")?.let { if (it.isNotEmpty()) { domain = it; hostOnly = false } }
            "path" -> kv.getOrNull(1)?.trim()?.let { if (it.isNotEmpty()) path = it }
            "max-age" -> kv.getOrNull(1)?.trim()?.toLongOrNull()?.let { expiresAt = System.currentTimeMillis() + it * 1000 }
            "secure" -> secure = true
        }
    }
    return Cookie(nameValue[0].trim(), nameValue[1].trim(), domain, path, expiresAt, secure, hostOnly)
}

fun domainMatches(cookieDomain: String, host: String): Boolean =
    host == cookieDomain || host.endsWith(".$cookieDomain")
