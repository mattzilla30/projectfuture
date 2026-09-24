package com.projectfuture.browser.css

import com.projectfuture.browser.html.ElementNode

/** CSS selectors, hand-implemented with an approximate specificity score used for cascade order. */
sealed class Selector(val priority: Int) {
    abstract fun matches(node: ElementNode): Boolean
}

object UniversalSelector : Selector(0) {
    override fun matches(node: ElementNode) = true
}

/**
 * Stands in for a pseudo-class/pseudo-element this engine doesn't implement
 * (`:hover`, `:visited`, `:nth-child()`, `::before`, ...). Per the CSS spec,
 * a selector containing an unsupported pseudo is invalid and matches
 * nothing - the safe default, matching this file's existing fail-safe
 * philosophy for unrecognized `@media` features. The alternative (silently
 * dropping just the pseudo and keeping the rest of the selector, as if the
 * pseudo were always satisfied) is actively wrong: `a:visited{color:...}`
 * would then apply to every plain, unvisited link too, and since it's
 * textually last in a typical stylesheet it would silently win the cascade
 * over the real `a{color:...}` rule.
 */
object NeverMatchSelector : Selector(0) {
    override fun matches(node: ElementNode) = false
}

class TagSelector(val tag: String) : Selector(1) {
    override fun matches(node: ElementNode) = node.tag == tag
}

class ClassSelector(val className: String) : Selector(10) {
    override fun matches(node: ElementNode) = className in node.classList()
}

class IdSelector(val id: String) : Selector(100) {
    override fun matches(node: ElementNode) = node.attr("id") == id
}

class CompoundSelector(val parts: List<Selector>) : Selector(parts.sumOf { it.priority }) {
    override fun matches(node: ElementNode) = parts.all { it.matches(node) }
}

/** Matches [descendant] on a node that also has some ancestor matching [ancestor]. */
class DescendantSelector(val ancestor: Selector, val descendant: Selector) :
    Selector(ancestor.priority + descendant.priority) {
    override fun matches(node: ElementNode): Boolean {
        if (!descendant.matches(node)) return false
        var p = node.parent
        while (p != null) {
            if (ancestor.matches(p)) return true
            p = p.parent
        }
        return false
    }
}
