package com.projectfuture.browser.browser

import com.projectfuture.browser.html.ElementNode
import com.projectfuture.browser.html.HtmlParser
import com.projectfuture.browser.html.walkElements
import org.junit.Assert.assertEquals
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
}
