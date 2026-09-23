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

    private fun response(status: Int = 200, cacheControl: String? = null) =
        HttpResponse(status, cacheControl?.let { mapOf("cache-control" to it) } ?: emptyMap(), "body", Url.parse("https://example.com/x"))

    @Test fun cacheableExpiryHonorsMaxAge() {
        val now = 1_000_000L
        assertEquals(now + 60_000L, cacheableExpiryMillis(response(cacheControl = "max-age=60"), now))
    }

    @Test fun cacheableExpiryRejectsNoStoreNoCacheAndPrivate() {
        val now = 1_000_000L
        assertEquals(null, cacheableExpiryMillis(response(cacheControl = "no-store, max-age=60"), now))
        assertEquals(null, cacheableExpiryMillis(response(cacheControl = "no-cache, max-age=60"), now))
        assertEquals(null, cacheableExpiryMillis(response(cacheControl = "private, max-age=60"), now))
    }

    @Test fun cacheableExpiryRejectsNonOkAndMissingMaxAge() {
        val now = 1_000_000L
        assertEquals(null, cacheableExpiryMillis(response(status = 404, cacheControl = "max-age=60"), now))
        assertEquals(null, cacheableExpiryMillis(response(cacheControl = null), now))
    }

    @Test fun mixedContentOnlyBlocksHttpsPageLoadingHttpResource() {
        assertTrue(isMixedContent(Url.parse("https://example.com/"), Url.parse("http://cdn.example.com/x.js")))
        assertTrue(!isMixedContent(Url.parse("https://example.com/"), Url.parse("https://cdn.example.com/x.js")))
        assertTrue(!isMixedContent(Url.parse("http://example.com/"), Url.parse("http://cdn.example.com/x.js")))
        assertTrue(!isMixedContent(Url.parse("http://example.com/"), Url.parse("https://cdn.example.com/x.js")))
    }

    @Test fun parseMaxAgeExtractsSecondsFromHeaderValue() {
        assertEquals(31536000L, parseMaxAge("max-age=31536000; includeSubDomains"))
        assertEquals(0L, parseMaxAge("max-age=0"))
        assertEquals(null, parseMaxAge("includeSubDomains"))
    }

    @Test fun hstsStoreEnforcesThenExpires() {
        HstsStore.clear()
        val now = 1_000_000L
        HstsStore.record("example.com", "max-age=100", now)
        assertTrue(HstsStore.isEnforced("example.com", now + 50_000L))
        assertTrue(!HstsStore.isEnforced("example.com", now + 150_000L)) // past max-age, in seconds*1000
        assertTrue(!HstsStore.isEnforced("never-seen.com", now))
    }

    @Test fun hstsMaxAgeZeroForgetsTheHost() {
        HstsStore.clear()
        val now = 1_000_000L
        HstsStore.record("example.com", "max-age=100", now)
        HstsStore.record("example.com", "max-age=0", now)
        assertTrue(!HstsStore.isEnforced("example.com", now))
    }

    @Test fun cspAllowsEverythingWhenNoPolicyPresent() {
        val csp = ContentSecurityPolicy.parse(null)
        assertTrue(csp.allowsInlineScript())
        assertTrue(csp.allowsScriptSrc(Url.parse("https://example.com/"), Url.parse("https://cdn.example.com/x.js")))
    }

    @Test fun cspSelfAllowsSameOriginOnly() {
        val csp = ContentSecurityPolicy.parse("default-src 'self'")
        val page = Url.parse("https://example.com/")
        assertTrue(csp.allowsScriptSrc(page, Url.parse("https://example.com/a.js")))
        assertTrue(!csp.allowsScriptSrc(page, Url.parse("https://other.com/a.js")))
        assertTrue(!csp.allowsInlineScript()) // no 'unsafe-inline'
    }

    @Test fun cspAllowsExplicitHostAndWildcardSubdomain() {
        val csp = ContentSecurityPolicy.parse("script-src https://cdn.example.com *.trusted.com")
        val page = Url.parse("https://example.com/")
        assertTrue(csp.allowsScriptSrc(page, Url.parse("https://cdn.example.com/lib.js")))
        assertTrue(csp.allowsScriptSrc(page, Url.parse("https://sub.trusted.com/lib.js")))
        assertTrue(!csp.allowsScriptSrc(page, Url.parse("https://untrusted.com/lib.js")))
    }

    @Test fun cspUnsafeInlineAllowsInlineScriptsAndStyles() {
        val csp = ContentSecurityPolicy.parse("script-src 'unsafe-inline'; style-src 'unsafe-inline'")
        assertTrue(csp.allowsInlineScript())
        assertTrue(csp.allowsInlineStyle())
    }

    @Test fun cspDirectiveFallsBackToDefaultSrc() {
        val csp = ContentSecurityPolicy.parse("default-src 'none'")
        val page = Url.parse("https://example.com/")
        assertTrue(!csp.allowsImgSrc(page, Url.parse("https://example.com/x.png"))) // no img-src, falls back to default-src 'none'
    }

    @Test fun httpCacheServesFreshEntryAndDropsExpiredOne() {
        HttpCache.clear()
        val url = Url.parse("https://example.com/cached")
        val resp = HttpResponse(200, mapOf("cache-control" to "max-age=60"), "cached-body", url)
        val storedAt = 1_000_000L
        HttpCache.store(url, resp, storedAt)

        assertEquals("cached-body", HttpCache.get(url, storedAt + 30_000L)?.body)
        assertEquals(null, HttpCache.get(url, storedAt + 61_000L)) // past max-age
    }
}
