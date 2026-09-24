package com.projectfuture.browser.css

import com.projectfuture.browser.html.ElementNode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression test for the real cascade bug behind DuckDuckGo's `/html/` links rendering with
 * default/unstyled (underlined, UA-default blue) appearance instead of the site's own
 * `a{text-decoration:none;color:#1168cc}`.
 *
 * `CssParser.parseToken` used to drop any pseudo-class/pseudo-element suffix wholesale
 * ("unsupported") and keep parsing the rest of the selector as if the pseudo were always
 * satisfied. That's wrong: DuckDuckGo's own stylesheet also declares
 * `a:hover{text-decoration:underline}` and `a:visited{color:#6830bb}` - both textually AFTER
 * the plain `a{...}` rule. With the pseudo silently dropped, both became unconditional `a{...}`
 * rules that, being later in the same file (and CssParser + computeStyles' cascade is a stable
 * sort on equal specificity, so later same-specificity rules win), clobbered the real
 * `text-decoration:none` and `color:#1168cc` right back to underline/a different color - on
 * EVERY link, not just visited/hovered ones. Fixed by making an unsupported pseudo make that
 * selector never match (the spec-correct behavior for an unrecognized pseudo), instead of
 * silently matching everything.
 */
class CssPseudoClassCascadeTest {

    private fun anchorStyledBy(authorCss: String): Map<String, String> {
        val rules = CssParser(authorCss).parseRules()
        val root = ElementNode("html")
        val a = ElementNode("a", parent = root)
        root.children.add(a)
        computeStyles(root, rules)
        return a.style
    }

    @Test
    fun plainAnchorRuleWinsOverUaDefault() {
        val style = anchorStyledBy("a{text-decoration:none;color:#1168cc}")
        assertEquals("#1168cc", style["color"])
        assertEquals("none", style["text-decoration"])
    }

    @Test
    fun laterPseudoClassRulesDoNotClobberThePlainRule() {
        // Same declaration order as DuckDuckGo's real l.css.
        val style = anchorStyledBy(
            "a{text-decoration:none;color:#1168cc}" +
                "a:hover{text-decoration:underline}" +
                "a:visited{color:#6830bb}"
        )
        assertEquals("#1168cc", style["color"])
        assertEquals("none", style["text-decoration"])
    }

    @Test
    fun unsupportedPseudoClassSelectorNeverMatchesAnyElement() {
        val rules = CssParser("a:visited{color:#6830bb}").parseRules()
        assertEquals(1, rules.size)
        val root = ElementNode("html")
        val a = ElementNode("a", parent = root)
        assertEquals(false, rules[0].selector.matches(a))
    }

    @Test
    fun pseudoElementSelectorAlsoNeverMatches() {
        val rules = CssParser("a::before{content:'x'}").parseRules()
        assertEquals(1, rules.size)
        val root = ElementNode("html")
        val a = ElementNode("a", parent = root)
        assertEquals(false, rules[0].selector.matches(a))
    }
}
