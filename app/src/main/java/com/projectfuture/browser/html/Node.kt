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

    // Style matching asks for this once per class selector per element, so it's cached against the
    // attribute string it was split from (scripts can change `class` at any time).
    private var classListSource: String? = null
    private var classListCache: List<String> = emptyList()

    fun classList(): List<String> {
        val raw = attributes["class"] ?: ""
        if (raw != classListSource) {
            classListCache = raw.split(WHITESPACE).filter { it.isNotEmpty() }
            classListSource = raw
        }
        return classListCache
    }

    override fun toString() = "<$tag>"
}

private val WHITESPACE = Regex("\\s+")

/** Depth-first walk over an element and its descendants (element nodes only). */
fun ElementNode.walkElements(action: (ElementNode) -> Unit) {
    action(this)
    for (child in children) {
        if (child is ElementNode) child.walkElements(action)
    }
}
