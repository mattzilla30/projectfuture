package com.projectfuture.browser.net

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlResolveTest {
    private fun at(url: String) = Url.parse(url)

    @Test fun schemeSeparatorInsideAQueryStringIsStillARelativeReference() {
        val resolved = at("https://news.example/l/").resolve("/redirect?to=https://other.example/a")
        assertEquals("https://news.example/redirect?to=https://other.example/a", resolved.toString())
    }

    @Test fun queryOnlyReferenceKeepsTheCurrentPath() {
        assertEquals("https://news.ycombinator.com/news?p=2", at("https://news.ycombinator.com/news?p=1").resolve("?p=2").toString())
    }

    @Test fun relativeReferenceResolvesAgainstThePathNotTheQuery() {
        assertEquals("https://a.example/x/page2", at("https://a.example/x/page1?next=/y/z").resolve("page2").toString())
    }

    @Test fun trailingSlashIsKept() {
        assertEquals("https://a.example/docs/", at("https://a.example/").resolve("docs/").toString())
        assertEquals("https://a.example/docs/intro.html", at("https://a.example/docs/").resolve("intro.html").toString())
    }

    @Test fun dotSegmentsAreRemovedFromThePathOnly() {
        assertEquals("https://a.example/a/c", at("https://a.example/a/b/page").resolve("../c").toString())
        assertEquals("https://a.example/a/", at("https://a.example/a/b/page").resolve("..").toString())
        assertEquals("https://a.example/", at("https://a.example/a").resolve("../../..").toString())
        assertEquals(
            "https://a.example/go?u=http://b.example//x/../y",
            at("https://a.example/").resolve("go?u=http://b.example//x/../y").toString()
        )
    }

    @Test fun fragmentReferenceKeepsPathAndQuery() {
        assertEquals("https://a.example/p?q=1#top", at("https://a.example/p?q=1#old").resolve("#top").toString())
    }

    @Test fun protocolRelativeAndAbsoluteReferences() {
        assertEquals("https://cdn.example/x.css", at("https://a.example/").resolve("//cdn.example/x.css").toString())
        assertEquals("http://b.example/", at("https://a.example/").resolve("http://b.example").toString())
    }

    @Test fun nonFetchableSchemesResolveToNull() {
        val base = at("https://a.example/")
        assertNull(base.resolveOrNull("javascript:void(0)"))
        assertNull(base.resolveOrNull("mailto:someone@example.com"))
        assertNull(base.resolveOrNull("data:image/png;base64,AAAA"))
        assertNull(base.resolveOrNull("blob:https://a.example/123"))
    }

    @Test fun hostEndsAtQueryOrFragment() {
        val url = at("example.com?x=1")
        assertEquals("example.com", url.host)
        assertEquals("/?x=1", url.path)
        assertEquals("a.example", at("https://a.example#frag").host)
    }

    @Test fun schemeIsCaseInsensitiveAndPortStillParses() {
        val url = at("HTTPS://Example.com:8443/x")
        assertEquals("https", url.scheme)
        assertEquals(8443, url.port)
        assertEquals(8080, at("localhost:8080/x").port)
    }

    @Test fun fragmentIsNeverSentToTheServer() {
        val server = ServerSocket(0)
        var requestLine: String? = null
        val done = CountDownLatch(1)
        Thread {
            try {
                server.accept().use { socket ->
                    val input = BufferedReader(InputStreamReader(socket.getInputStream()))
                    requestLine = input.readLine()
                    var line = input.readLine()
                    while (line != null && line.isNotEmpty()) line = input.readLine()
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                    socket.getOutputStream().flush()
                }
            } catch (_: Exception) {
            } finally {
                done.countDown()
            }
        }.apply { isDaemon = true }.start()
        try {
            assertEquals("ok", Url.parse("http://127.0.0.1:${server.localPort}/wiki/Page?x=1#Section").fetch().body)
            done.await(5, TimeUnit.SECONDS)
            assertEquals("GET /wiki/Page?x=1 HTTP/1.1", requestLine)
        } finally {
            server.close()
        }
    }
}
