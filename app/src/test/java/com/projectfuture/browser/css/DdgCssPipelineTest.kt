package com.projectfuture.browser.css

import com.projectfuture.browser.html.ElementNode
import com.projectfuture.browser.html.HtmlParser
import com.projectfuture.browser.html.walkElements
import com.projectfuture.browser.net.Url
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * End-to-end regression test for the "DuckDuckGo /html/ links render with default/unstyled
 * appearance" bug: real HTML/CSS fetched from the live site (see the fixtures under
 * src/test/resources/ddg_fixtures) run through the real HtmlParser, CssParser, `Url.resolve`
 * and `computeStyles` - the same pipeline `Tab.collectAuthorCss` drives - minus only the actual
 * network fetch, which is stubbed with the fixture bytes read straight off disk (including
 * lc.css's real leading UTF-8 BOM). Two real, independent bugs were found and fixed this way:
 *
 *  1. `lc.css` starts with a UTF-8 BOM immediately before `body{...}`, decoded (the way
 *     Url.kt's `fetch()` used to, via plain `String(bytes, charset)`) as a leading U+FEFF
 *     character that doesn't get magically stripped by the JVM. That corrupted the tag name of
 *     the file's first selector into the unmatchable `"﻿body"` - see CssParserBomTest.
 *  2. `l.css`'s `a:hover{...}` / `a:visited{...}` rules had their pseudo-classes silently
 *     dropped by CssParser and, appearing after the plain `a{...}` rule in the same file, won
 *     the (stable-sorted, equal-specificity) cascade and clobbered its `color`/`text-decoration`
 *     right back toward the UA default look - see CssPseudoClassCascadeTest.
 */
class DdgCssPipelineTest {

    private fun fixture(name: String) = File("src/test/resources/ddg_fixtures/$name")

    @Test
    fun ddgLinksGetAuthorCssNotUaDefaults() {
        val baseUrl = Url.parse("https://duckduckgo.com/html/")
        val html = fixture("ddg_html.html").readText(Charsets.UTF_8)
        val root = HtmlParser(html).parse()

        // Mirrors Tab.collectAuthorCss: walk the DOM, find <link rel=stylesheet> tags, resolve
        // their (possibly protocol-relative) href against the page URL, "fetch" each one (here:
        // read the matching fixture file's real bytes instead of hitting the network) and
        // harvest its rules in document order.
        val authorRules = ArrayList<CssRule>()
        var stylesheetLinksFound = 0
        root.walkElements { el ->
            if (el.tag == "link" && el.attr("rel")?.lowercase()?.contains("stylesheet") == true) {
                val href = el.attr("href") ?: return@walkElements
                val resolved = baseUrl.resolve(href)
                assertEquals("https", resolved.scheme)
                assertEquals("duckduckgo.com", resolved.host)
                val bytes = when {
                    resolved.path.contains("/dist/l.") -> fixture("l.css").readBytes()
                    resolved.path.contains("/dist/lc.") -> fixture("lc.css").readBytes()
                    else -> return@walkElements
                }
                stylesheetLinksFound++
                // Real UTF-8 decode of the real bytes, BOM and all, exactly as Url.fetch() does.
                val text = String(bytes, Charsets.UTF_8).let {
                    if (it.isNotEmpty() && it[0] == '﻿') it.substring(1) else it
                }
                authorRules.addAll(CssParser(text).parseRules())
            }
        }
        assertEquals("expected both DuckDuckGo stylesheet <link> tags to be found", 2, stylesheetLinksFound)

        computeStyles(root, authorRules)

        var anchor: ElementNode? = null
        root.walkElements { el -> if (el.tag == "a" && el.attr("class") == "header-url") anchor = el }
        val a = anchor ?: error("fixture's <a class=\"header-url\"> not found")

        // The real bug: these used to come out as the UA defaults (#1a0dab / underline) or, once
        // the BOM bug alone was fixed, as the wrong color from the mis-parsed a:visited rule
        // (#6830bb) - never the site's real a{color:#1168cc;text-decoration:none}.
        assertEquals("#1168cc", a!!.style["color"])
        assertEquals("none", a!!.style["text-decoration"])

        // lc.css's first rule - body{margin:0;...}, sitting right after the BOM - must also
        // have applied (the UA default is `margin: 8px`). Confirms the BOM fix on its own,
        // independent of the pseudo-class fix checked above.
        var body: ElementNode? = null
        root.walkElements { el -> if (el.tag == "body") body = el }
        assertEquals("0", body!!.style["margin"])
    }
}
