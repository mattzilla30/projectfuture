package com.projectfuture.browser.css

import com.projectfuture.browser.net.decodeDataUrl
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FontFaceTest {
    private fun rules(css: String) = CssParser(css).also { it.parseRules() }.fontFaceRules.mapNotNull { parseFontFaceRule(it) }

    @Test
    fun readsGoogleFontsSubsets() {
        val css = """
            /* cyrillic */
            @font-face { font-family: 'Roboto'; font-style: normal; font-weight: 400; font-display: swap;
              src: url(https://fonts.gstatic.com/s/roboto/cyr.woff2) format('woff2');
              unicode-range: U+0301, U+0400-045F, U+0490-0491, U+04B0-04B1, U+2116; }
            /* latin */
            @font-face { font-family: 'Roboto'; font-style: italic; font-weight: 700;
              src: url(https://fonts.gstatic.com/s/roboto/latin.woff2) format('woff2');
              unicode-range: U+0000-00FF, U+0131, U+0152-0153, U+02BB-02BC; }
        """
        val r = rules(css)
        assertEquals(2, r.size)
        assertFalse(r[0].coversLatin)
        assertEquals(400, r[0].weight)
        assertTrue(r[1].coversLatin)
        assertEquals(700, r[1].weight)
        assertTrue(r[1].italic)
        assertEquals("https://fonts.gstatic.com/s/roboto/latin.woff2", r[1].srcUrl)
    }

    @Test
    fun skipsUnsupportedFormatsAndLocalSources() {
        val css = """@font-face { font-family: "Icons"; src: local("Icons"), url("icons.eot?#iefix") format("embedded-opentype"),
            url("icons.svg#icons") format("svg"), url("icons.woff") format("woff"); font-weight: 100 900; }"""
        val r = rules(css).single()
        assertEquals("icons.woff", r.srcUrl)
        assertEquals(400, r.weight)
    }

    @Test
    fun keepsDataUrlSourceIntact() {
        val css = "@font-face { font-family: X; src: url(data:font/woff2;base64,d09GMg==) format('woff2'); font-weight: bold }"
        val r = rules(css).single()
        assertEquals("data:font/woff2;base64,d09GMg==", r.srcUrl)
        assertEquals(700, r.weight)
        assertArrayEquals("wOF2".toByteArray(), decodeDataUrl(r.srcUrl))
    }

    @Test
    fun semicolonsInsideQuotesAndParensDoNotSplitDeclarations() {
        val rules = CssParser("a { content: \"a;b\"; background: url(x;y.png); color: red }").parseRules()
        val decls = rules.single().properties
        assertEquals("\"a;b\"", decls["content"])
        assertEquals("url(x;y.png)", decls["background"])
        assertEquals("red", decls["color"])
    }

    @Test
    fun unicodeRangeForms() {
        assertTrue(unicodeRangeContains("U+0000-00FF", 'a'.code))
        assertTrue(unicodeRangeContains("U+00??", 'a'.code))
        assertTrue(unicodeRangeContains("u+61", 'a'.code))
        assertFalse(unicodeRangeContains("U+0400-045F, U+2116", 'a'.code))
    }

    @Test
    fun dataUrlForms() {
        assertArrayEquals("hi there".toByteArray(), decodeDataUrl("data:text/plain,hi%20there"))
        assertArrayEquals("hello".toByteArray(), decodeDataUrl("data:;base64,aGVs\nbG8="))
        assertNull(decodeDataUrl("https://example.com/"))
        assertNull(decodeDataUrl("data:;base64,@@@"))
    }
}
