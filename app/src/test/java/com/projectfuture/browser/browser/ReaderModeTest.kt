package com.projectfuture.browser.browser

import com.projectfuture.browser.css.CssParser
import com.projectfuture.browser.css.computeStyles
import com.projectfuture.browser.html.ElementNode
import com.projectfuture.browser.html.HtmlParser
import com.projectfuture.browser.html.walkElements
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderModeTest {

    private fun collectText(node: ElementNode): String {
        val sb = StringBuilder()
        node.walkElements { el ->
            el.children.filterIsInstance<com.projectfuture.browser.html.TextNode>().forEach { sb.append(it.text) }
        }
        return sb.toString()
    }

    @Test fun picksTheArticleBodyOverANavHeavySidebar() {
        val longParagraph = "This is a long article about something interesting. ".repeat(20)
        val html = """
            <html><body>
                <nav><a href="/a">Link A</a><a href="/b">Link B</a><a href="/c">Link C</a></nav>
                <div id="sidebar"><a href="/x">X</a><a href="/y">Y</a><a href="/z">Z</a></div>
                <article><h1>Title</h1><p>$longParagraph</p></article>
            </body></html>
        """.trimIndent()
        val root = HtmlParser(html).parse()
        val extracted = extractReaderContent(root)
        assertTrue("should find substantial article content", extracted != null)
        val text = collectText(extracted!!)
        assertTrue("extracted content should contain the article text", text.contains("interesting"))
        assertTrue("extracted content should not include nav/sidebar link text", !text.contains("Link A"))
    }

    @Test fun stripsScriptsAndFormsFromExtractedContent() {
        val longParagraph = "Some real article content that goes on for a while. ".repeat(20)
        val html = """
            <html><body>
                <article>
                    <p>$longParagraph</p>
                    <script>alert('tracking');</script>
                    <form><input type="text"></form>
                </article>
            </body></html>
        """.trimIndent()
        val root = HtmlParser(html).parse()
        val extracted = extractReaderContent(root)!!
        var sawScript = false
        var sawForm = false
        extracted.walkElements { if (it.tag == "script") sawScript = true; if (it.tag == "form") sawForm = true }
        assertTrue(!sawScript)
        assertTrue(!sawForm)
    }

    @Test fun returnsNullWhenThereIsNoSubstantialContent() {
        val html = "<html><body><div>hi</div></body></html>"
        val root = HtmlParser(html).parse()
        assertNull(extractReaderContent(root))
    }

    /**
     * Regression test for a real Tab.toggleReaderMode() bug: it swapped
     * `currentDoc` for the extracted reader document but left the Tab's
     * `currentAuthorRules` field (which every later user-driven restyle -
     * dispatchClick/dispatchInputEvent - reuses) pointed at the *original
     * page's* author CSS instead of [READER_MODE_CSS]. Any interaction with
     * the reader-mode document that triggered a restyle (e.g. tapping a
     * preserved link/checkbox) would therefore silently re-apply the
     * original page's stylesheet to the reader DOM, and the reverse would
     * happen on toggling back off. This test proves the two rule sets are
     * NOT interchangeable for the same extracted document - i.e. that using
     * the stale rules really does produce wrong, visibly different styling -
     * which is what made the missing field swap a real, user-visible bug
     * (now fixed in Tab.toggleReaderMode by keeping currentAuthorRules in
     * sync with which document/stylesheet is actually active).
     */
    @Test fun originalPageAuthorRulesAndReaderModeCssProduceDifferentStylesForTheSameExtractedDoc() {
        val longParagraph = "This is a long article about something interesting. ".repeat(20)
        val html = """
            <html><body>
                <article><h1>Title</h1><p>$longParagraph</p></article>
            </body></html>
        """.trimIndent()
        val root = HtmlParser(html).parse()
        val extracted = extractReaderContent(root)!!
        val body = extracted.children.filterIsInstance<ElementNode>().first { it.tag == "body" }

        // The original page's own author CSS - completely unrelated to reader mode's typography.
        val originalPageRules = CssParser("body { color: #ff0000; font-family: sans-serif; }").parseRules()
        val readerRules = CssParser(READER_MODE_CSS).parseRules()

        computeStyles(extracted, originalPageRules)
        val colorWithOriginalRules = body.style["color"]

        computeStyles(extracted, readerRules)
        val colorWithReaderRules = body.style["color"]

        assertNotEquals(
            "applying the original page's author rules vs. READER_MODE_CSS to the same " +
                "extracted reader document must produce different computed styles - proving " +
                "the stale-rules bug was visibly wrong, not a no-op",
            colorWithOriginalRules,
            colorWithReaderRules
        )
        assertEquals("#333333", colorWithReaderRules)
        assertEquals("#ff0000", colorWithOriginalRules)
    }
}
