package com.projectfuture.browser.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieJarTest {

    @Test fun parsesNameValue() {
        val c = parseSetCookie("session=abc123", "example.com")!!
        assertEquals("session", c.name)
        assertEquals("abc123", c.value)
        assertEquals("example.com", c.domain) // defaults to the request host
        assertEquals("/", c.path)
    }

    @Test fun parsesAttributes() {
        val c = parseSetCookie("id=42; Domain=.example.com; Path=/app; Secure", "example.com")!!
        assertEquals("example.com", c.domain) // leading dot stripped
        assertEquals("/app", c.path)
        assertTrue(c.secure)
    }

    @Test fun parsesMaxAgeAsFutureExpiry() {
        val before = System.currentTimeMillis()
        val c = parseSetCookie("id=42; Max-Age=60", "example.com")!!
        assertTrue(c.expiresAt != null && c.expiresAt!! > before)
    }

    @Test fun rejectsMalformedHeader() {
        assertNull(parseSetCookie("", "example.com"))
        assertNull(parseSetCookie("noequalssign", "example.com"))
        assertNull(parseSetCookie("=novalue", "example.com"))
    }

    @Test fun domainMatchExactAndSubdomain() {
        assertTrue(domainMatches("example.com", "example.com"))
        assertTrue(domainMatches("example.com", "www.example.com"))
        assertTrue(!domainMatches("example.com", "notexample.com"))
        assertTrue(!domainMatches("example.com", "example.org"))
    }

    @Test fun sameOriginRequiresSchemeHostAndPort() {
        assertTrue(isSameOrigin(Url.parse("https://example.com/a"), Url.parse("https://example.com/b")))
        assertTrue(!isSameOrigin(Url.parse("https://example.com/"), Url.parse("http://example.com/")))
        assertTrue(!isSameOrigin(Url.parse("https://example.com/"), Url.parse("https://other.com/")))
        assertTrue(!isSameOrigin(Url.parse("https://example.com:8443/"), Url.parse("https://example.com/")))
    }

    @Test fun corsAllowsWildcardOrMatchingOrigin() {
        val page = Url.parse("https://app.example.com/")
        assertTrue(corsAllows(page, mapOf("access-control-allow-origin" to "*")))
        assertTrue(corsAllows(page, mapOf("access-control-allow-origin" to "https://app.example.com")))
        assertTrue(!corsAllows(page, mapOf("access-control-allow-origin" to "https://other.com")))
        assertTrue(!corsAllows(page, emptyMap()))
    }
}
