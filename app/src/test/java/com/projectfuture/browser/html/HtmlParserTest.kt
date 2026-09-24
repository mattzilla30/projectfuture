package com.projectfuture.browser.html

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HtmlParserTest {

    private fun tableCellText(root: ElementNode): List<List<String>> {
        val table = ArrayList<List<String>>()
        root.walkElements { tr ->
            if (tr.tag == "tr") {
                table.add(tr.children.filterIsInstance<ElementNode>().filter { it.tag == "td" }.map { collectText(it) })
            }
        }
        return table
    }

    private fun collectText(n: Node): String = when (n) {
        is TextNode -> n.text
        is ElementNode -> n.children.joinToString("") { collectText(it) }
    }

    @Test fun unclosedTdThenTrStartsANewRowInsteadOfNestingInsideTheOpenCell() {
        // Real-world hand-written tables routinely omit </td> and </tr>.
        val root = HtmlParser("<table><tr><td>A<tr><td>B</table>").parse()
        val rows = tableCellText(root)
        assertEquals("expected two separate rows, not the second <tr> nested inside the first <td>", 2, rows.size)
        assertEquals(listOf("A"), rows[0])
        assertEquals(listOf("B"), rows[1])
    }

    @Test fun unclosedLiWithAnUnclosedInlineTagInsideStillClosesOnTheNextLi() {
        // <b> is left open by the page; the next <li> must still close the previous <li>.
        val root = HtmlParser("<ul><li>One<b>bold<li>Two</ul>").parse()
        val items = root.walkElementsCollect("li")
        assertEquals(2, items.size)
        assertEquals(0, items[0].children.filterIsInstance<ElementNode>().count { it.tag == "li" })
        assertEquals("bold", collectText(items[0]).substringAfter("One"))
        assertEquals("Two", collectText(items[1]))
    }

    @Test fun blockElementClosesAnOpenPEvenThroughAnUnclosedInlineTag() {
        val root = HtmlParser("<div><p>Text<b>bold<div>NewBlock</div></div>").parse()
        val divs = root.walkElementsCollect("div")
        // Outer div should directly contain <p> and the new inner <div> as siblings,
        // not have the inner <div> nested inside the still-open <p>/<b>.
        val outer = divs.first()
        val pNode = outer.children.filterIsInstance<ElementNode>().first { it.tag == "p" }
        assertEquals(0, pNode.children.filterIsInstance<ElementNode>().count { it.tag == "div" })
        assertEquals(2, outer.children.filterIsInstance<ElementNode>().size)
    }

    @Test fun outOfRangeNumericCharacterReferenceDoesNotCrashTheParser() {
        // 0x110000 is one past the maximum valid Unicode code point.
        val root = HtmlParser("<p>a&#x110000;b</p>").parse()
        val text = root.walkElementsCollect("p").first().let { collectText(it) }
        assertEquals("a�b", text)
    }

    @Test fun outOfRangeDecimalNumericCharacterReferenceDoesNotCrashTheParser() {
        val root = HtmlParser("<p>a&#1114112;b</p>").parse()
        val text = root.walkElementsCollect("p").first().let { collectText(it) }
        assertEquals("a�b", text)
    }

    @Test fun validNumericCharacterReferencesStillDecode() {
        val root = HtmlParser("<p>&#65;&#x41;</p>").parse()
        val text = collectText(root.walkElementsCollect("p").first())
        assertEquals("AA", text)
    }

    @Test fun scriptContentWithClosingTagLookalikeInsideAStringLiteralIsNotTagParsed() {
        // A </div> inside a JS string must not be treated as a real closing tag
        // or otherwise cause the script's raw text to be split early.
        val root = HtmlParser("<div><script>var x = \"</div>\";</script>After</div>").parse()
        val divs = root.walkElementsCollect("div")
        assertEquals(1, divs.size)
        val script = divs[0].children.filterIsInstance<ElementNode>().first { it.tag == "script" }
        assertEquals("var x = \"</div>\";", collectText(script))
        assertEquals("After", collectText(divs[0]).substringAfter(";"))
    }

    @Test fun scriptClosingTagMustBeFollowedByATagTerminatingCharacter() {
        // "</scriptable>" merely starts with "</script" but is not the real
        // closing tag - the tokenizer must not stop the raw-text run there.
        val root = HtmlParser("<script>var s = \"</scriptable>\";</script>").parse()
        val script = root.walkElementsCollect("script").first()
        assertEquals("var s = \"</scriptable>\";", collectText(script))
    }

    @Test fun scriptContainingAnOldStyleHtmlCommentHidesItAsRawText() {
        val root = HtmlParser("<script><!-- var x = 1; --></script>").parse()
        val script = root.walkElementsCollect("script").first()
        assertEquals("<!-- var x = 1; -->", collectText(script))
    }

    @Test fun styleContentWithAngleBracketishCssValueIsNotTagParsed() {
        val root = HtmlParser("<style>.x::before{content:\"<\"}</style><p>ok</p>").parse()
        val style = root.walkElementsCollect("style").first()
        assertEquals(".x::before{content:\"<\"}", collectText(style))
        assertEquals("ok", collectText(root.walkElementsCollect("p").first()))
    }

    @Test fun scriptContentIsNotEntityDecoded() {
        // Script/style content is raw text per spec - it must reach the JS
        // engine byte-for-byte, not have "&amp;" etc silently decoded.
        val root = HtmlParser("<script>var x = \"a &amp; b\";</script>").parse()
        val script = root.walkElementsCollect("script").first()
        assertEquals("var x = \"a &amp; b\";", collectText(script))
    }

    @Test fun commentContainingAngleBracketsIsSkippedEntirely() {
        val root = HtmlParser("<p>before</p><!-- a < b > c --><p>after</p>").parse()
        val ps = root.walkElementsCollect("p")
        assertEquals(2, ps.size)
        assertEquals("before", collectText(ps[0]))
        assertEquals("after", collectText(ps[1]))
    }

    @Test fun unterminatedCommentConsumesRestOfDocumentWithoutHangingOrCrashing() {
        val root = HtmlParser("<p>before</p><!-- never closed <p>ghost</p>").parse()
        val ps = root.walkElementsCollect("p")
        assertEquals(1, ps.size)
        assertEquals("before", collectText(ps[0]))
    }

    @Test fun emptyCommentImmediatelyClosedDoesNotSwallowRestOfDocument() {
        // "<!-->" is a (weird, but spec-valid) empty comment - parsing must
        // resume normally right after it, not run to the next "-->" anywhere
        // later in the document.
        val root = HtmlParser("<p>a</p><!--><p>b</p>").parse()
        val ps = root.walkElementsCollect("p")
        assertEquals(2, ps.size)
        assertEquals("a", collectText(ps[0]))
        assertEquals("b", collectText(ps[1]))
    }

    @Test fun unescapedGreaterThanInsideQuotedAttributeValueDoesNotEndTagEarly() {
        val root = HtmlParser("<a title=\"click > here\">text</a>").parse()
        val a = root.walkElementsCollect("a").first()
        assertEquals("click > here", a.attr("title"))
        assertEquals("text", collectText(a))
    }

    @Test fun tagAndAttributeNamesAreCaseInsensitive() {
        val root = HtmlParser("<DIV CLASS=\"x\"><SPAN>hi</SPAN></DIV>").parse()
        val divs = root.walkElementsCollect("div")
        assertEquals(1, divs.size)
        assertEquals("x", divs[0].attr("class"))
        assertEquals("hi", collectText(root.walkElementsCollect("span").first()))
    }

    @Test fun commonRealWorldNamedEntitiesDecode() {
        val root = HtmlParser(
            "<p>&trade;&reg;&deg;&plusmn;&times;&divide;&bull;</p>"
        ).parse()
        val text = collectText(root.walkElementsCollect("p").first())
        assertEquals("™®°±×÷•", text)
    }

    @Test fun legacyWindows1252NumericReferenceIsRemappedPerSpec() {
        // &#146; is a common malformed "smart quote" export that real
        // browsers remap to U+2019 rather than emitting literal U+0092.
        val root = HtmlParser("<p>don&#146;t</p>").parse()
        val text = collectText(root.walkElementsCollect("p").first())
        assertEquals("don’t", text)
    }

    private fun ElementNode.walkElementsCollect(tag: String): List<ElementNode> {
        val out = ArrayList<ElementNode>()
        walkElements { if (it.tag == tag) out.add(it) }
        return out
    }
}
