package com.projectfuture.browser.html

/** Base of the hand-rolled DOM: either an element or a text run. */
sealed class Node {
    var parent: ElementNode? = null
}

class TextNode(var text: String, parent: ElementNode? = null) : Node() {
    init { this.parent = parent }
    override fun toString() = "Text(${text.take(20)})"
}

class ElementNode(
    val tag: String,
    val attributes: MutableMap<String, String> = LinkedHashMap(),
    parent: ElementNode? = null
) : Node() {
    init { this.parent = parent }

    val children: MutableList<Node> = ArrayList()

    /** Filled in by the CSS cascade step; inheritable + own properties merged. */
    val style: MutableMap<String, String> = LinkedHashMap()

    fun attr(name: String): String? = attributes[name]

    fun classList(): List<String> = (attributes["class"] ?: "").split(Regex("\\s+")).filter { it.isNotEmpty() }

    override fun toString() = "<$tag>"
}

/** Depth-first walk over an element and its descendants (element nodes only). */
fun ElementNode.walkElements(action: (ElementNode) -> Unit) {
    action(this)
    for (child in children) {
        if (child is ElementNode) child.walkElements(action)
    }
}
