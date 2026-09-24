package com.projectfuture.browser.browser

import com.projectfuture.browser.net.Url
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchRedirectTest {
    @Test fun unwrapsADuckDuckGoResultLink() {
        val base = Url.parse("https://html.duckduckgo.com/html/?q=cat")
        val link = base.resolve("//duckduckgo.com/l/?uddg=https%3A%2F%2Fen.wikipedia.org%2Fwiki%2FCat%3Fa%3D1%26b%3D2&rut=abc123")
        assertEquals("https://en.wikipedia.org/wiki/Cat?a=1&b=2", unwrapSearchRedirect(link).toString())
    }

    @Test fun leavesOtherUrlsAlone() {
        for (raw in listOf(
            "https://duckduckgo.com/html/?q=x",
            "https://duckduckgo.com/l/?rut=abc",
            "https://example.com/l/?uddg=https%3A%2F%2Fevil.example%2F",
            "https://duckduckgo.com/l/?uddg=javascript%3Aalert(1)",
        )) {
            val url = Url.parse(raw)
            assertEquals(url, unwrapSearchRedirect(url))
        }
    }
}
