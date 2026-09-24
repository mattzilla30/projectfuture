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

    private fun ElementNode.walkElementsCollect(tag: String): List<ElementNode> {
        val out = ArrayList<ElementNode>()
        walkElements { if (it.tag == tag) out.add(it) }
        return out
    }
}
