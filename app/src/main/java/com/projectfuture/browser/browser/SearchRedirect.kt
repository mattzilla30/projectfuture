package com.projectfuture.browser.browser

import com.projectfuture.browser.net.Url
import java.net.URLDecoder

/**
 * DuckDuckGo's HTML results (this browser's default search) link every result through
 * `duckduckgo.com/l/?uddg=<percent-encoded target>&rut=...`. That endpoint forwards with a page
 * built for full browsers, and this engine landed on it as a blank page instead of the result.
 * Returns the `uddg` target when [url] is one of those links, otherwise [url] unchanged.
 */
fun unwrapSearchRedirect(url: Url): Url {
    val host = url.host.lowercase()
    if (host != "duckduckgo.com" && !host.endsWith(".duckduckgo.com")) return url
    val beforeHash = url.path.substringBefore('#')
    if (!beforeHash.startsWith("/l/")) return url
    val encoded = beforeHash.substringAfter('?', "").split('&')
        .firstOrNull { it.startsWith("uddg=") }?.substringAfter("uddg=") ?: return url
    val target = try { URLDecoder.decode(encoded, "UTF-8") } catch (_: Exception) { return url }
    val parsed = try { Url.parse(target) } catch (_: Exception) { return url }
    return if (parsed.scheme == "http" || parsed.scheme == "https") parsed else url
}
