package com.projectfuture.browser.browser

import com.projectfuture.browser.html.ElementNode
import com.projectfuture.browser.html.walkElements
import com.projectfuture.browser.js.JsObject
import com.projectfuture.browser.js.JsString
import com.projectfuture.browser.js.JsStringConvertible
import com.projectfuture.browser.js.JsUndefined
import com.projectfuture.browser.js.JsValue
import com.projectfuture.browser.js.NativeFunction
import com.projectfuture.browser.js.toJsString
import com.projectfuture.browser.net.Url

/**
 * `window.location` / `document.location`. Reading a part (`href`, `hostname`, `search`...) reflects
 * the page's current URL, and assigning `href` (or calling `assign`/`replace`) navigates. Redirect
 * pages depend on this: search-result redirectors, login flows, and "you are being redirected"
 * interstitials run `location.replace(target)` and show nothing else.
 */
class JsLocation(
    private val currentUrl: () -> Url,
    private val navigate: (target: Url, replace: Boolean) -> Unit,
    private val reload: () -> Unit
) : JsObject(), JsStringConvertible {

    init {
        super.set("assign", NativeFunction("assign", 1) { _, _, args -> navigateTo(args.getOrElse(0) { JsUndefined }, replace = false); JsUndefined })
        super.set("replace", NativeFunction("replace", 1) { _, _, args -> navigateTo(args.getOrElse(0) { JsUndefined }, replace = true); JsUndefined })
        super.set("reload", NativeFunction("reload", 0) { _, _, _ -> reload(); JsUndefined })
        super.set("toString", NativeFunction("toString", 0) { _, _, _ -> JsString(currentUrl().toString()) })
    }

    override fun jsString(): String = currentUrl().toString()

    override fun get(name: String): JsValue {
        val url = currentUrl()
        val path = url.path
        val beforeHash = path.substringBefore('#')
        return when (name) {
            "href" -> JsString(url.toString())
            "protocol" -> JsString(url.scheme + ":")
            "host" -> JsString(hostWithPort(url))
            "hostname" -> JsString(url.host)
            "port" -> JsString(if (url.port == defaultPort(url)) "" else url.port.toString())
            "pathname" -> JsString(beforeHash.substringBefore('?'))
            "search" -> JsString(if ('?' in beforeHash) "?" + beforeHash.substringAfter('?') else "")
            "hash" -> JsString(if ('#' in path) "#" + path.substringAfter('#') else "")
            "origin" -> JsString("${url.scheme}://${hostWithPort(url)}")
            else -> super.get(name)
        }
    }

    override fun set(name: String, value: JsValue) {
        when (name) {
            "href" -> navigateTo(value, replace = false)
            "pathname", "search", "hash" -> {
                val url = currentUrl()
                val beforeHash = url.path.substringBefore('#')
                val pathname = beforeHash.substringBefore('?')
                val search = if ('?' in beforeHash) "?" + beforeHash.substringAfter('?') else ""
                val hash = if ('#' in url.path) "#" + url.path.substringAfter('#') else ""
                val v = toJsString(value)
                val newPath = when (name) {
                    "pathname" -> (if (v.startsWith("/")) v else "/$v") + search + hash
                    "search" -> pathname + (if (v.isEmpty() || v.startsWith("?")) v else "?$v") + hash
                    else -> pathname + search + (if (v.isEmpty() || v.startsWith("#")) v else "#$v")
                }
                navigate(url.copy(path = newPath), false)
            }
            else -> super.set(name, value)
        }
    }

    private fun navigateTo(value: JsValue, replace: Boolean) {
        val target = currentUrl().resolveOrNull(toJsString(value)) ?: return
        navigate(target, replace)
    }

    private fun defaultPort(url: Url) = if (url.scheme == "https" || url.scheme == "wss") 443 else 80

    private fun hostWithPort(url: Url) = if (url.port == defaultPort(url)) url.host else "${url.host}:${url.port}"
}

/** A parsed `<meta http-equiv="refresh" content="...">`: wait [delaySeconds], then load [url] (null means reload the same page). */
data class MetaRefresh(val delaySeconds: Double, val url: String?)

/** Parses a refresh `content` value: `5`, `0;url=/next`, `0; URL='https://x'`, `3, https://x`. Null when it isn't a valid refresh. */
fun parseMetaRefresh(content: String): MetaRefresh? {
    val match = Regex("^\\s*(\\d+(?:\\.\\d*)?)\\s*(?:[;,]\\s*(.*))?$", RegexOption.DOT_MATCHES_ALL).find(content) ?: return null
    val delay = match.groupValues[1].toDoubleOrNull() ?: return null
    var rest = match.groupValues[2].trim()
    val urlPrefix = Regex("^url\\s*=\\s*", RegexOption.IGNORE_CASE).find(rest)
    if (urlPrefix != null) rest = rest.substring(urlPrefix.value.length)
    if (rest.startsWith("'") || rest.startsWith("\"")) {
        val quote = rest[0]
        val end = rest.indexOf(quote, 1)
        rest = if (end == -1) rest.substring(1) else rest.substring(1, end)
    }
    rest = rest.trim()
    return MetaRefresh(delay, rest.ifEmpty { null })
}

/** The first `<meta http-equiv="refresh">` in the document, parsed. */
fun findMetaRefresh(root: ElementNode): MetaRefresh? {
    var found: MetaRefresh? = null
    root.walkElements { el ->
        if (found == null && el.tag == "meta" && el.attr("http-equiv")?.trim()?.equals("refresh", ignoreCase = true) == true) {
            found = el.attr("content")?.let { parseMetaRefresh(it) }
        }
    }
    return found
}
