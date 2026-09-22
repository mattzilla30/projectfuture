package com.projectfuture.browser.css

import com.projectfuture.browser.html.ElementNode

/** CSS selectors, hand-implemented with an approximate specificity score used for cascade order. */
sealed class Selector(val priority: Int) {
    abstract fun matches(node: ElementNode): Boolean
}

object UniversalSelector : Selector(0) {
    override fun matches(node: ElementNode) = true
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
