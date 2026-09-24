package com.projectfuture.browser.browser

import com.projectfuture.browser.html.HtmlParser
import com.projectfuture.browser.js.Interpreter
import com.projectfuture.browser.js.Lexer
import com.projectfuture.browser.js.Parser
import com.projectfuture.browser.js.toJsString
import com.projectfuture.browser.net.Url
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocationTest {
    private val navigations = ArrayList<Pair<String, Boolean>>()

    private fun location(url: String) = JsLocation(
        currentUrl = { Url.parse(url) },
        navigate = { target, replace -> navigations.add(target.toString() to replace) },
        reload = { navigations.add("reload" to false) }
    )

    private fun run(url: String, code: String): Interpreter {
        val interpreter = Interpreter()
        interpreter.globalEnv.declare("location", location(url))
        interpreter.run(Parser(Lexer(code).tokenize()).parseProgram())
        return interpreter
    }

    @Test fun readsUrlParts() {
        val loc = location("https://a.example:8443/path/page?q=1#frag")
        assertEquals("https://a.example:8443/path/page?q=1#frag", toJsString(loc.get("href")))
        assertEquals("https:", toJsString(loc.get("protocol")))
        assertEquals("a.example:8443", toJsString(loc.get("host")))
        assertEquals("a.example", toJsString(loc.get("hostname")))
        assertEquals("8443", toJsString(loc.get("port")))
        assertEquals("/path/page", toJsString(loc.get("pathname")))
        assertEquals("?q=1", toJsString(loc.get("search")))
        assertEquals("#frag", toJsString(loc.get("hash")))
        assertEquals("https://a.example:8443", toJsString(loc.get("origin")))
        assertEquals("", toJsString(location("https://a.example/").get("port")))
    }

    @Test fun replaceNavigatesWithReplacementAndResolvesRelativeTargets() {
        run("https://duckduckgo.com/l/?uddg=x", "location.replace('https://en.wikipedia.org/wiki/Cat')")
        run("https://a.example/dir/page", "location.assign('next')")
        assertEquals(listOf("https://en.wikipedia.org/wiki/Cat" to true, "https://a.example/dir/next" to false), navigations)
    }

    @Test fun assigningHrefNavigates() {
        run("https://a.example/", "location.href = '/login'")
        assertEquals(listOf("https://a.example/login" to false), navigations)
    }

    @Test fun locationConvertsToItsUrlString() {
        val interpreter = run("https://a.example/p", "var s = '' + location; var t = String(location);")
        assertEquals("https://a.example/p", toJsString(interpreter.globalEnv.get("s")))
        assertEquals("https://a.example/p", toJsString(interpreter.globalEnv.get("t")))
    }

    @Test fun nonFetchableTargetIsIgnored() {
        run("https://a.example/", "location.href = 'javascript:void(0)'")
        assertEquals(emptyList<Pair<String, Boolean>>(), navigations)
    }

    @Test fun parsesMetaRefreshForms() {
        assertEquals(MetaRefresh(0.0, "https://x.example/"), parseMetaRefresh("0; url=https://x.example/"))
        assertEquals(MetaRefresh(0.0, "https://x.example/a b"), parseMetaRefresh("0;URL='https://x.example/a b'"))
        assertEquals(MetaRefresh(3.0, "/next"), parseMetaRefresh(" 3 , /next"))
        assertEquals(MetaRefresh(5.0, null), parseMetaRefresh("5"))
        assertNull(parseMetaRefresh("soon"))
    }

    @Test fun findsMetaRefreshInTheDocument() {
        val root = HtmlParser(
            "<html><head><META HTTP-EQUIV=\"Refresh\" CONTENT=\"0; URL=https://en.wikipedia.org/wiki/Cat\"></head><body></body></html>"
        ).parse()
        assertEquals(MetaRefresh(0.0, "https://en.wikipedia.org/wiki/Cat"), findMetaRefresh(root))
    }
}
