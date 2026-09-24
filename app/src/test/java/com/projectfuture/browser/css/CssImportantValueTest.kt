package com.projectfuture.browser.css

import com.projectfuture.browser.html.ElementNode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression test for a real cascade bug: `CssParser.parseDeclarations` (and
 * `parseInlineDeclarations`) stored a declaration's value verbatim, including any trailing
 * `!important` annotation - extremely common on real sites (utility classes, third-party
 * overrides, ad/cookie-banner fixes, etc). That left the stored value as e.g.
 * `"red !important"` or `"14px !important"` instead of `"red"` / `"14px"`. Since nothing
 * downstream (parseCssColor, lengthValue, resolveFontSize, ...) strips that suffix, an
 * `!important` declaration silently failed to parse as its actual type and the property fell
 * back to whatever default/inherited value would otherwise apply - the exact same "malformed
 * input silently does the wrong thing instead of failing loud or applying correctly" failure
 * mode as the pseudo-class cascade bug, just triggered by a different kind of unsupported-looking
 * syntax.
 */
class CssImportantValueTest {

    private fun styledBy(authorCss: String): Map<String, String> {
        val rules = CssParser(authorCss).parseRules()
        val root = ElementNode("html")
        val div = ElementNode("div", parent = root)
        root.children.add(div)
        computeStyles(root, rules)
        return div.style
    }

    @Test
    fun importantColorDeclarationStillParsesAsAColor() {
        // Before the fix this stored "red !important" verbatim - not a key in NAMED_COLORS, not a
        // "#..." or "rgb(...)" value either - so parseCssColor(...) would silently return null and
        // the color would fall back to the default black instead of red.
        val style = styledBy("div{color:red !important}")
        assertEquals("red", style["color"])
    }

    @Test
    fun importantFontSizeDeclarationIsNotIgnored() {
        // Parent (root <html>) inherits the 16px UA base; the div's own !important rule must win,
        // not silently fall back to the inherited 16px because "20px !important" failed to parse.
        val style = styledBy("div{font-size:20px !important}")
        assertEquals("20.0px", style["font-size"])
    }

    @Test
    fun importantDeclarationWithoutSpaceBeforeBangAlsoParses() {
        val style = styledBy("div{width:100px!important}")
        assertEquals("100px", style["width"])
    }

    @Test
    fun parseInlineDeclarationsStripsImportant() {
        val props = CssParser.parseInlineDeclarations("color: blue !important; width: 50px")
        assertEquals("blue", props["color"])
        assertEquals("50px", props["width"])
    }
}
