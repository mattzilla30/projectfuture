package com.projectfuture.browser.css

import com.projectfuture.browser.html.ElementNode
import com.projectfuture.browser.html.HtmlParser
import com.projectfuture.browser.html.walkElements
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** [RuleIndex] must return exactly the rules a test-every-rule scan would, in the same cascade order. */
class RuleIndexTest {
    private fun fixture(name: String) = File("src/test/resources/ddg_fixtures/$name").readText()

    private fun assertSameAsFullScan(root: ElementNode, rules: List<CssRule>) {
        val sorted = rules.sortedBy { it.selector.priority }
        val index = RuleIndex(sorted)
        var checked = 0
        root.walkElements { el ->
            val expected = sorted.filter { it.selector.matches(el) }
            assertEquals("rules for <${el.tag} class=${el.attr("class")} id=${el.attr("id")}>", expected, index.matching(el))
            checked++
        }
        assert(checked > 0)
    }

    @Test fun matchesAFullScanOnARealPageAndStylesheets() {
        val root = HtmlParser(fixture("ddg_html.html")).parse()
        val rules = CssParser(fixture("l.css"), 400f).parseRules() + CssParser(fixture("lc.css"), 400f).parseRules() +
            CssParser(DEFAULT_STYLESHEET).parseRules()
        assertSameAsFullScan(root, rules)
    }

    @Test fun matchesAFullScanForEverySelectorShape() {
        val root = HtmlParser(
            "<div id=\"main\" class=\"a b\"><p class=\"b b\">x<span class=\"c\" id=\"s\">y</span></p><ul><li>z</li></ul></div>"
        ).parse()
        val css = """
            * { color: red; }
            div { color: blue; }
            .b { color: green; }
            #main { color: black; }
            div.a.b { margin: 1px; }
            p.b span#s { margin: 2px; }
            div .c { margin: 3px; }
            ul > li { margin: 4px; }
            a:hover { color: pink; }
            .missing { color: gray; }
            li { padding: 1px; }
            .b { padding: 2px; }
        """.trimIndent()
        assertSameAsFullScan(root, CssParser(css).parseRules())
    }
}
