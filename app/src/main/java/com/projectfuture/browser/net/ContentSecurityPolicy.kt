package com.projectfuture.browser.net

/**
 * A bounded subset of Content-Security-Policy: `default-src`/`script-src`/
 * `style-src`/`img-src`/`font-src`/`connect-src`, each understanding
 * `'self'`, `'none'`, `*`, `'unsafe-inline'` (script-src/style-src only,
 * for inline `<script>`/`<style>` blocks - not nonces or hashes), and
 * scheme sources (`https:`, `data:`), and explicit `scheme://host` or bare
 * `host` sources with a leading `*.` wildcard for subdomains. Not implemented: `nonce-`/`sha256-` sources,
 * `report-uri`/`report-to`, `frame-ancestors` and other non-fetch
 * directives, multiple policies combined (only the first
 * `Content-Security-Policy` response header is used). A directive that
 * isn't present falls back to `default-src`; if neither is present,
 * everything is allowed (no policy = no restriction, matching the spec).
 */
class ContentSecurityPolicy(private val directives: Map<String, List<String>>) {

    val hasPolicy: Boolean get() = directives.isNotEmpty()

    fun allowsInlineScript(): Boolean = allowsUnsafeInline("script-src")
    fun allowsInlineStyle(): Boolean = allowsUnsafeInline("style-src")

    private fun allowsUnsafeInline(directive: String): Boolean {
        val sources = directives[directive] ?: directives["default-src"] ?: return true
        return "'unsafe-inline'" in sources
    }

    fun allowsScriptSrc(pageUrl: Url, target: Url) = allows("script-src", pageUrl, target)
    fun allowsStyleSrc(pageUrl: Url, target: Url) = allows("style-src", pageUrl, target)
    fun allowsImgSrc(pageUrl: Url, target: Url) = allows("img-src", pageUrl, target)
    fun allowsFontSrc(pageUrl: Url, target: Url) = allows("font-src", pageUrl, target)
    fun allowsConnectSrc(pageUrl: Url, target: Url) = allows("connect-src", pageUrl, target)

    private fun allows(directive: String, pageUrl: Url, target: Url): Boolean {
        val sources = directives[directive] ?: directives["default-src"] ?: return true
        if (sources.isEmpty()) return false
        for (source in sources) {
            when {
                source == "'none'" -> {}
                source == "'self'" -> if (isSameOrigin(pageUrl, target)) return true
                source == "*" -> return true
                source == "'unsafe-inline'" -> {} // governs inline blocks, not network sources
                else -> if (matchesHostSource(source, target)) return true
            }
        }
        return false
    }

    companion object {
        fun parse(header: String?): ContentSecurityPolicy {
            if (header.isNullOrBlank()) return ContentSecurityPolicy(emptyMap())
            val map = HashMap<String, List<String>>()
            for (part in header.split(";")) {
                val tokens = part.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (tokens.isEmpty()) continue
                map[tokens[0].lowercase()] = tokens.drop(1).map { it.lowercase() }
            }
            return ContentSecurityPolicy(map)
        }
    }
}

private fun matchesHostSource(source: String, target: Url): Boolean {
    // A scheme source such as `https:` allows every URL with that scheme. Real sites rely on it:
    // hellomagazine.com sends `style-src https: 'unsafe-inline'`, and reading `https:` as a host
    // name blocked both of its stylesheets.
    if (source.endsWith(":") && !source.contains("/")) return source.dropLast(1).equals(target.scheme, ignoreCase = true)
    var s = source
    var scheme: String? = null
    if (s.contains("://")) {
        scheme = s.substringBefore("://")
        s = s.substringAfter("://")
    }
    // Ports aren't compared: `host:443` or `host:*` matches the host on any port.
    val host = s.substringBefore("/").substringBefore(":")
    if (scheme != null && !scheme.equals(target.scheme, ignoreCase = true)) return false
    return if (host.startsWith("*.")) {
        // CSP Level 3: a leading "*." wildcard source matches proper subdomains only -
        // never the bare apex itself (`*.trusted.com` must not match `trusted.com`).
        val suffix = host.removePrefix("*.")
        target.host.endsWith(".$suffix", ignoreCase = true)
    } else {
        target.host.equals(host, ignoreCase = true)
    }
}
