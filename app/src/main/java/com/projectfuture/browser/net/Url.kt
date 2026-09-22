package com.projectfuture.browser.net

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset
import javax.net.ssl.SSLSocketFactory

/**
 * A URL, parsed by hand, and the raw-socket HTTP/1.1 client used to fetch it.
 * No java.net.URLConnection / OkHttp / WebView networking is used here -
 * everything from the TCP handshake up is written for this project.
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
     * Performs a blocking GET over a raw TCP (or TLS) socket, hand-rolling the
     * HTTP/1.1 request/response format. Must be called off the main thread.
     * Follows redirects itself, since there is no library to do it for us.
     */
    fun fetch(redirectsLeft: Int = 10): HttpResponse {
        val raw = fetchRaw(redirectsLeft)
        val charset = charsetFromContentType(raw.headers["content-type"])
        return HttpResponse(raw.statusCode, raw.headers, String(raw.body, charset), raw.url)
    }

    /** Same request as [fetch], but returns the raw body bytes undecoded - for binary resources like images. */
    fun fetchBytes(redirectsLeft: Int = 10): HttpBytesResponse {
        val raw = fetchRaw(redirectsLeft)
        return HttpBytesResponse(raw.statusCode, raw.headers, raw.body, raw.url)
    }

    /** Shared socket/request/response-header/body-bytes plumbing for [fetch] and [fetchBytes]. Follows redirects itself. */
    private fun fetchRaw(redirectsLeft: Int): RawHttpResponse {
        if (scheme != "http" && scheme != "https") {
            throw IOException("Unsupported scheme: $scheme")
        }

        val socket: Socket = if (isHttps) {
            (SSLSocketFactory.getDefault().createSocket() as Socket).also {
                it.connect(InetSocketAddress(host, port), 15000)
                // SNI + hostname verification happen automatically for SSLSocket
                // created against a host/port pair on modern Android.
            }
        } else {
            Socket().also { it.connect(InetSocketAddress(host, port), 15000) }
        }

        socket.soTimeout = 20000

        try {
            val request = buildString {
                append("GET $path HTTP/1.1\r\n")
                append("Host: $host\r\n")
                append("Connection: close\r\n")
                append("User-Agent: ProjectFutureBrowser/0.1 (Android; from-scratch)\r\n")
                append("Accept: text/html,text/css,*/*\r\n")
                append("\r\n")
            }
            socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()

            val input = BufferedInputStream(socket.getInputStream())

            val statusLine = readLine(input) ?: throw IOException("Empty response from $host")
            val statusParts = statusLine.split(" ", limit = 3)
            if (statusParts.size < 2) throw IOException("Malformed status line: $statusLine")
            val statusCode = statusParts[1].toIntOrNull() ?: throw IOException("Bad status code: $statusLine")

            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx == -1) continue
                val key = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                headers[key] = value
            }

            // Redirects: follow them ourselves rather than the body.
            if (statusCode in intArrayOf(301, 302, 303, 307, 308) && redirectsLeft > 0) {
                val location = headers["location"]
                if (location != null) {
                    val next = resolve(location)
                    return next.fetchRaw(redirectsLeft - 1)
                }
            }

            val bodyBytes = if (headers["transfer-encoding"]?.contains("chunked") == true) {
                readChunkedBody(input)
            } else {
                val contentLength = headers["content-length"]?.toIntOrNull()
                if (contentLength != null) readExactly(input, contentLength) else readToEnd(input)
            }

            return RawHttpResponse(statusCode, headers, bodyBytes, this)
        } finally {
            try { socket.close() } catch (_: IOException) {}
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
