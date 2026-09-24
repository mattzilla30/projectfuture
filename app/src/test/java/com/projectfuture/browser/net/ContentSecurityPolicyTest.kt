package com.projectfuture.browser.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentSecurityPolicyTest {
    private val page = Url.parse("https://www.hellomagazine.com/us/")

    @Test
    fun schemeSourceAllowsEveryUrlWithThatScheme() {
        // hellomagazine.com's real header, trimmed.
        val csp = ContentSecurityPolicy.parse("default-src https:; style-src https: 'unsafe-inline'; img-src https: data: blob:; font-src https: data:")
        assertTrue(csp.allowsStyleSrc(page, Url.parse("https://www.hellomagazine.com/_next/static/css/441b97eb3ce444be.css")))
        assertTrue(csp.allowsFontSrc(page, Url.parse("https://fonts.gstatic.com/s/roboto/latin.woff2")))
        assertTrue(csp.allowsImgSrc(page, Url.parse("https://images.hellomagazine.com/a.jpg")))
        assertFalse(csp.allowsStyleSrc(page, Url.parse("http://insecure.example/a.css")))
        assertTrue(csp.allowsInlineStyle())
    }

    @Test
    fun hostSourcesMatchWithPortsAndWildcards() {
        val csp = ContentSecurityPolicy.parse("style-src 'self' cdn.example.com:443 *.static.example")
        assertTrue(csp.allowsStyleSrc(page, Url.parse("https://www.hellomagazine.com/a.css")))
        assertTrue(csp.allowsStyleSrc(page, Url.parse("https://cdn.example.com/a.css")))
        assertTrue(csp.allowsStyleSrc(page, Url.parse("https://a.static.example/a.css")))
        assertFalse(csp.allowsStyleSrc(page, Url.parse("https://static.example/a.css")))
        assertFalse(csp.allowsStyleSrc(page, Url.parse("https://evil.example/a.css")))
        assertFalse(csp.allowsInlineStyle())
    }

    @Test
    fun noneBlocksEverything() {
        val csp = ContentSecurityPolicy.parse("default-src 'none'")
        assertFalse(csp.allowsScriptSrc(page, Url.parse("https://www.hellomagazine.com/a.js")))
    }
}
