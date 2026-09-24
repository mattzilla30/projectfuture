package com.projectfuture.browser.css

import com.projectfuture.browser.html.ElementNode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression test for a real selector-parsing bug: `CssParser.parseSelectorChain` filters the
 * `>`, `+` and `~` combinator tokens out of the whitespace-split token list entirely, so
 * `div > p`, `div + p` and `div ~ p` were all silently parsed as plain descendant selectors
 * (`div p`) - the same "unsupported CSS syntax silently does the wrong thing instead of failing
 * safe" failure mode as the pseudo-class bug. This engine only implements the descendant
 * combinator (see README: "tag/class/id/descendant selectors"), so per this file's own
 * NeverMatchSelector precedent for unsupported pseudo-classes, an unsupported combinator should
 * make the selector never match - not silently reinterpret it as a (much broader) descendant
 * selector, which would apply author styling to elements the author never intended to target
 * (e.g. `.nav > li{...}` incorrectly styling deeply nested `li`s inside submenus too).
 */
class CssCombinatorSelectorTest {

    private fun matches(selectorText: String, node: ElementNode): Boolean {
        val rules = CssParser("$selectorText{color:red}").parseRules()
        assertEquals(1, rules.size)
        return rules[0].selector.matches(node)
    }

    @Test
    fun childCombinatorDoesNotMatchAGrandchild() {
        val root = ElementNode("div")
        val mid = ElementNode("ul", parent = root)
        root.children.add(mid)
        val grandchild = ElementNode("li", parent = mid)
        mid.children.add(grandchild)

        // "div > li" should NOT match an <li> that is a grandchild of <div>, only a direct child.
        assertEquals(false, matches("div > li", grandchild))
    }

    @Test
    fun childCombinatorDoesMatchADirectChild() {
        val root = ElementNode("div")
        val child = ElementNode("li", parent = root)
        root.children.add(child)
        // Unsupported combinators are documented out of scope (descendant-only selectors), so the
        // safe behavior is "never matches" - even for what would be a direct child in real CSS.
        assertEquals(false, matches("div > li", child))
    }

    @Test
    fun adjacentSiblingCombinatorNeverMatches() {
        val root = ElementNode("div")
        val h2 = ElementNode("h2", parent = root)
        val p = ElementNode("p", parent = root)
        root.children.add(h2)
        root.children.add(p)
        assertEquals(false, matches("h2 + p", p))
    }

    @Test
    fun generalSiblingCombinatorNeverMatches() {
        val root = ElementNode("div")
        val h2 = ElementNode("h2", parent = root)
        val p = ElementNode("p", parent = root)
        root.children.add(h2)
        root.children.add(p)
        assertEquals(false, matches("h2 ~ p", p))
    }
}
