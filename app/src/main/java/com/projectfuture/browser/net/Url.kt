package com.projectfuture.browser.net

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLSocketFactory

/**
 * A URL, parsed by hand, and the raw-socket HTTP/1.1 client used to fetch it.
 * No java.net.URLConnection / OkHttp / WebView networking is used here -
 * everything from the TCP handshake up is written for this project.
 *
 * Cookies are sent/stored via [sharedCookieJar] (see CookieJar.kt for what
 * it does and doesn't cover). `Accept-Encoding: gzip` is sent, and a gzip
 * `Content-Encoding` response is decompressed via the standard
 * `java.util.zip.GZIPInputStream` - a platform compression utility, the
 * same tier as `Inflater` (used for WOFF font decompression) elsewhere in
 * this project, not "browser engine" logic. Brotli isn't supported (no
 * standard-library equivalent, and writing a Brotli decoder is its own
 * substantial project - same reasoning as the WOFF2 gap in FontDecoder.kt),
 * so a server that only offers `br` encoding won't get it requested.
 */
data class Url(
    val scheme: String,
    val host: String,
    val port: Int,
    val path: String
) {
    val isHttps: Boolean get() = scheme == "https"

    override fun toString(): String {
        val defaultPort = if (isHttps) 443 else 80
        val portPart = if (port == defaultPort) "" else ":$port"
        return "$scheme://$host$portPart$path"
    }

    /** Resolve a possibly-relative URL found in this page against this URL. */
    fun resolve(other: String): Url {
        val trimmed = other.trim()
        if (trimmed.contains("://")) return parse(trimmed)
        if (trimmed.startsWith("//")) return parse("$scheme:$trimmed")
        if (trimmed.startsWith("#")) return copy() // same-document fragment; treated as same page
        if (trimmed.startsWith("/")) return copy(path = trimmed)

        // Relative path: resolve against the directory of the current path.
        val dir = if (path.endsWith("/")) path else path.substringBeforeLast('/', "") + "/"
        val combined = normalizePath(dir + trimmed)
        return copy(path = combined)
    }

    private fun normalizePath(rawPath: String): String {
        val segments = ArrayList<String>()
        for (segment in rawPath.split("/")) {
            when (segment) {
                "", "." -> {}
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
                else -> segments.add(segment)
            }
        }
        return "/" + segments.joinToString("/")
    }

    /**
     * Performs a blocking request over a raw TCP (or TLS) socket, hand-rolling
     * the HTTP/1.1 request/response format. Must be called off the main
     * thread. Follows redirects itself, since there is no library to do it
     * for us. [method]/[body]/[extraHeaders] back `fetch()`'s options object
     * and `XMLHttpRequest`, in addition to the plain GETs used elsewhere
     * (page loads, images, stylesheets).
     */
    /**
     * [allowCookies] gates both sending the `Cookie` header and storing any
     * `Set-Cookie` response (also skips the shared HttpCache) - Tab passes
     * `false` for private/incognito tabs, giving them real cookie
     * isolation rather than just skipping history recording.
     */
    fun fetch(method: String = "GET", body: ByteArray? = null, extraHeaders: Map<String, String> = emptyMap(), redirectsLeft: Int = 10, allowCookies: Boolean = true): HttpResponse {
        hstsUpgrade()?.let { return it.fetch(method, body, extraHeaders, redirectsLeft, allowCookies) }
        // Only cacheable requests (see HttpCache's class doc) even attempt a cache lookup - a
        // GET with no body, matching this project's bounded RFC 7234 subset.
        val cacheable = allowCookies && method.equals("GET", ignoreCase = true) && body == null
        if (cacheable) HttpCache.get(this)?.let { return it }
        val raw = fetchRaw(method, body, extraHeaders, redirectsLeft, allowCookies)
        val charset = charsetFromContentType(raw.headers["content-type"])
        val response = HttpResponse(raw.statusCode, raw.headers, String(raw.body, charset), raw.url)
        if (cacheable) HttpCache.store(this, response)
        return response
    }

    /** Same request as [fetch], but returns the raw body bytes undecoded - for binary resources like images. */
    fun fetchBytes(redirectsLeft: Int = 10, allowCookies: Boolean = true): HttpBytesResponse {
        hstsUpgrade()?.let { return it.fetchBytes(redirectsLeft, allowCookies) }
        val raw = fetchRaw("GET", null, emptyMap(), redirectsLeft, allowCookies)
        return HttpBytesResponse(raw.statusCode, raw.headers, raw.body, raw.url)
    }

    /** Non-null (the HTTPS-upgraded URL to use instead) when this is a plain-HTTP request to an HSTS-enforced host. */
    private fun hstsUpgrade(): Url? {
        if (scheme != "http" || !HstsStore.isEnforced(host)) return null
        return copy(scheme = "https", port = if (port == 80) 443 else port)
    }

    /** Shared socket/request/response-header/body-bytes plumbing for [fetch] and [fetchBytes]. Follows redirects itself. */
    private fun openSocket(): Socket = if (isHttps) {
        (SSLSocketFactory.getDefault().createSocket() as Socket).also {
            it.connect(InetSocketAddress(host, port), 15000)
            // SNI + hostname verification happen automatically for SSLSocket
            // created against a host/port pair on modern Android.
        }
    } else {
        Socket().also { it.connect(InetSocketAddress(host, port), 15000) }
    }

    private fun buildRequestHead(method: String, body: ByteArray?, extraHeaders: Map<String, String>, allowCookies: Boolean): String {
        val cookieHeader = if (allowCookies) sharedCookieJar?.cookieHeaderFor(this) else null
        // A caller (Tab's "desktop site" toggle) can override the User-Agent via extraHeaders;
        // it's handled specially here rather than just appended, so there's never a duplicate header.
        val userAgent = extraHeaders.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
            ?: "ProjectFutureBrowser/0.1 (Android; from-scratch)"
        return buildString {
            append("${method.uppercase()} $path HTTP/1.1\r\n")
            append("Host: $host\r\n")
            append("Connection: keep-alive\r\n")
            append("User-Agent: $userAgent\r\n")
            append("Accept: text/html,text/css,*/*\r\n")
            append("Accept-Encoding: gzip\r\n")
            if (cookieHeader != null) append("Cookie: $cookieHeader\r\n")
            if (body != null) append("Content-Length: ${body.size}\r\n")
            for ((k, v) in extraHeaders) if (!k.equals("User-Agent", ignoreCase = true)) append("$k: $v\r\n")
            append("\r\n")
        }
    }

    private fun writeRequest(socket: Socket, requestHead: String, body: ByteArray?) {
        val out = socket.getOutputStream()
        out.write(requestHead.toByteArray(Charsets.US_ASCII))
        if (body != null) out.write(body)
        out.flush()
    }

    /**
     * Reuses a pooled keep-alive socket when one is available for this
     * host, falling back to (and retrying once on) a fresh connection if
     * the pooled one turns out to be stale - a server-side keep-alive
     * timeout closing an idle socket from under us is routine, not
     * exceptional. A socket is only ever offered back to [ConnectionPool]
     * after a response with a determinate length (`Content-Length` or
     * chunked) was fully read and the server didn't send `Connection:
     * close`; anything else (indeterminate-length body, a redirect whose
     * body we don't consume) closes the socket instead, since reusing it
     * would hand the next request a stream with stale bytes still in it.
     */
    private fun fetchRaw(method: String, body: ByteArray?, extraHeaders: Map<String, String>, redirectsLeft: Int, allowCookies: Boolean = true): RawHttpResponse {
        if (scheme != "http" && scheme != "https") {
            throw IOException("Unsupported scheme: $scheme")
        }

        val poolKey = "$scheme://$host:$port"
        var socket = ConnectionPool.borrow(poolKey)
        var reused = socket != null
        if (socket == null) socket = openSocket()
        socket.soTimeout = 20000

        val requestHead = buildRequestHead(method, body, extraHeaders, allowCookies)
        try {
            try {
                writeRequest(socket, requestHead, body)
            } catch (e: IOException) {
                if (!reused) throw e
                try { socket.close() } catch (_: Exception) {}
                socket = openSocket()
                socket.soTimeout = 20000
                reused = false
                writeRequest(socket, requestHead, body)
            }

            val input = BufferedInputStream(socket.getInputStream())

            val statusLine = readLine(input) ?: run {
                if (!reused) throw IOException("Empty response from $host")
                // The pooled socket was closed by the server between requests with nothing
                // written back yet; retry once on a fresh connection rather than failing outright.
                try { socket.close() } catch (_: Exception) {}
                return fetchRaw(method, body, extraHeaders, redirectsLeft, allowCookies)
            }
            val statusParts = statusLine.split(" ", limit = 3)
            if (statusParts.size < 2) throw IOException("Malformed status line: $statusLine")
            val statusCode = statusParts[1].toIntOrNull() ?: throw IOException("Bad status code: $statusLine")

            // Multiple Set-Cookie response headers are legal and common (one per cookie),
            // so they're tracked separately rather than folded into the single-value headers map.
            val headers = LinkedHashMap<String, String>()
            val setCookieHeaders = ArrayList<String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx == -1) continue
                val key = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                if (key == "set-cookie") setCookieHeaders.add(value) else headers[key] = value
            }
            if (allowCookies && setCookieHeaders.isNotEmpty()) sharedCookieJar?.store(this, setCookieHeaders)
            // Only trust Strict-Transport-Security when it arrives over a connection we've actually
            // verified is HTTPS - honoring it over plain HTTP would let a network attacker forge it.
            if (isHttps) headers["strict-transport-security"]?.let { HstsStore.record(host, it) }

            // Redirects: follow them ourselves rather than the body. The redirect response's own
            // body (if any) is left unread, so this socket can't safely be pooled - just close it.
            if (statusCode in intArrayOf(301, 302, 303, 307, 308) && redirectsLeft > 0) {
                val location = headers["location"]
                if (location != null) {
                    try { socket.close() } catch (_: Exception) {}
                    val next = resolve(location)
                    // 303 (and, in practice, 301/302 for non-GET/HEAD) always redirects as a GET with no body;
                    // 307/308 preserve the original method and body, per spec.
                    val (nextMethod, nextBody) = if (statusCode == 303 || (statusCode in intArrayOf(301, 302) && method != "GET" && method != "HEAD")) {
                        "GET" to null
                    } else {
                        method to body
                    }
                    return next.fetchRaw(nextMethod, nextBody, extraHeaders, redirectsLeft - 1, allowCookies)
                }
            }

            val chunked = headers["transfer-encoding"]?.contains("chunked") == true
            val contentLength = headers["content-length"]?.toIntOrNull()
            val rawBody: ByteArray
            val determinateLength: Boolean
            if (chunked) {
                rawBody = readChunkedBody(input)
                determinateLength = true
            } else if (contentLength != null) {
                rawBody = readExactly(input, contentLength)
                determinateLength = true
            } else {
                rawBody = readToEnd(input)
                determinateLength = false
            }

            if (determinateLength && headers["connection"]?.lowercase() != "close") {
                ConnectionPool.release(poolKey, socket)
            } else {
                try { socket.close() } catch (_: Exception) {}
            }

            val bodyBytes = if (headers["content-encoding"]?.contains("gzip") == true) {
                try { GZIPInputStream(rawBody.inputStream()).use { it.readBytes() } } catch (_: Exception) { rawBody }
            } else {
                rawBody
            }

            return RawHttpResponse(statusCode, headers, bodyBytes, this)
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
            throw e
        }
    }

    private fun charsetFromContentType(contentType: String?): Charset {
        if (contentType == null) return Charsets.UTF_8
        val idx = contentType.indexOf("charset=")
        if (idx == -1) return Charsets.UTF_8
        val name = contentType.substring(idx + 8).trim().trim('"', '\'')
        return try { Charset.forName(name) } catch (_: Exception) { Charsets.UTF_8 }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val buffer = ByteArrayOutputStream()
        var prevWasCr = false
        var readAny = false
        while (true) {
            val b = input.read()
            if (b == -1) return if (readAny) buffer.toString("ISO-8859-1") else null
            readAny = true
            if (b == '\n'.code) {
                val bytes = buffer.toByteArray()
                val len = if (prevWasCr && bytes.isNotEmpty()) bytes.size - 1 else bytes.size
                return String(bytes, 0, len, Charsets.ISO_8859_1)
            }
            prevWasCr = b == '\r'.code
            buffer.write(b)
        }
    }

    private fun readExactly(input: BufferedInputStream, count: Int): ByteArray {
        val out = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(out, read, count - read)
            if (n == -1) break
            read += n
        }
        return if (read == count) out else out.copyOf(read)
    }

    private fun readToEnd(input: BufferedInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n == -1) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun readChunkedBody(input: BufferedInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: break
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) {
                // Consume trailing headers/blank line, if any.
                while (true) {
                    val trailer = readLine(input) ?: break
                    if (trailer.isEmpty()) break
                }
                break
            }
            out.write(readExactly(input, size))
            readLine(input) // trailing CRLF after chunk data
        }
        return out.toByteArray()
    }

    companion object {
        fun parse(raw: String): Url {
            var url = raw.trim()
            val scheme: String
            if (url.contains("://")) {
                scheme = url.substringBefore("://")
                url = url.substringAfter("://")
            } else {
                scheme = "http"
            }
            require(scheme == "http" || scheme == "https") { "Unsupported scheme: $scheme" }

            if (!url.contains("/")) url += "/"
            val host = url.substringBefore("/")
            var path = "/" + url.substringAfter("/")

            var hostname = host
            var port = if (scheme == "https") 443 else 80
            if (hostname.contains(":")) {
                port = hostname.substringAfterLast(":").toIntOrNull() ?: port
                hostname = hostname.substringBeforeLast(":")
            }
            if (path.isEmpty()) path = "/"
            return Url(scheme, hostname, port, path)
        }

        /** Best-effort: treat bare input as a URL if it looks like a host, else a search query. */
        fun fromAddressBar(input: String, searchTemplate: String = "https://duckduckgo.com/html/?q=%s"): Url {
            val trimmed = input.trim()
            val looksLikeUrl = trimmed.contains("://") ||
                (trimmed.contains(".") && !trimmed.contains(" ") && !trimmed.contains("\t"))
            return if (looksLikeUrl) {
                parse(trimmed)
            } else {
                val encoded = java.net.URLEncoder.encode(trimmed, "UTF-8")
                parse(searchTemplate.replace("%s", encoded))
            }
        }
    }
}

data class HttpResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: String,
    val url: Url
)

fun isSameOrigin(a: Url, b: Url): Boolean = a.scheme == b.scheme && a.host == b.host && a.port == b.port

/**
 * True when loading [resourceUrl] from a page at [pageUrl] would be mixed
 * content: an HTTPS page pulling in a plain-HTTP subresource (script,
 * stylesheet, image, font, or `fetch()`/XHR target), which a real browser
 * blocks (for active content like scripts/stylesheets) or warns about
 * (for passive content like images) since it lets a network attacker
 * tamper with or snoop on part of an otherwise-secure page. This project
 * blocks all of it uniformly rather than distinguishing active/passive -
 * simpler, and erring toward blocking is the safer default.
 */
fun isMixedContent(pageUrl: Url, resourceUrl: Url): Boolean = pageUrl.isHttps && !resourceUrl.isHttps

/**
 * Simple-request CORS only (no preflight `OPTIONS` for non-"simple"
 * requests, no `Access-Control-Allow-Credentials` handling) - the core
 * cross-origin check from the Fetch spec: a response is exposed to script
 * only if its `Access-Control-Allow-Origin` header is `*` or matches the
 * page's own origin exactly. Pulled out as a free function (see
 * CookieJar.kt's parseSetCookie for the same reasoning) so it's directly
 * unit-testable without a real network round-trip.
 */
fun corsAllows(pageOrigin: Url, responseHeaders: Map<String, String>): Boolean {
    val allow = responseHeaders["access-control-allow-origin"] ?: return false
    if (allow.trim() == "*") return true
    val allowedOrigin = try { Url.parse(allow.trim()) } catch (_: Exception) { return false }
    return isSameOrigin(pageOrigin, allowedOrigin)
}

/** Same shape as [HttpResponse] but for binary resources (images) - see [Url.fetchBytes]. */
data class HttpBytesResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
    val url: Url
)

private data class RawHttpResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
    val url: Url
)
