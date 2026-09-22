package com.projectfuture.browser.css

import com.projectfuture.browser.html.ElementNode
import com.projectfuture.browser.html.Node

/** The browser's built-in (user-agent) stylesheet, applied before any page CSS. */
const val DEFAULT_STYLESHEET = """
a { color: #1a0dab; text-decoration: underline; }
b, strong { font-weight: bold; }
i, em { font-style: italic; }
h1 { font-size: 30px; font-weight: bold; }
h2 { font-size: 24px; font-weight: bold; }
h3 { font-size: 20px; font-weight: bold; }
h4 { font-size: 17px; font-weight: bold; }
h5 { font-size: 15px; font-weight: bold; }
h6 { font-size: 13px; font-weight: bold; }
small { font-size: 13px; }
code, pre, tt, kbd, samp { font-family: monospace; }
mark { background-color: #ffff00; }
del, s, strike { text-decoration: line-through; }
u, ins { text-decoration: underline; }
"""

private val INHERITED_PROPS = listOf("color", "font-size", "font-weight", "font-style", "text-align")

private val BASE_STYLE = mapOf(
    "color" to "#202020",
    "font-size" to "16px",
    "font-weight" to "normal",
    "font-style" to "normal",
    "text-align" to "left"
)

/**
 * Runs the CSS cascade over the DOM: user-agent rules, then author rules
 * (sorted by our approximate specificity), then the inline `style`
 * attribute, with the usual property inheritance in between.
 */
fun computeStyles(root: ElementNode, authorRules: List<CssRule>) {
    val rules = (CssParser(DEFAULT_STYLESHEET).parseRules() + authorRules).sortedBy { it.selector.priority }
    styleNode(root, rules, BASE_STYLE)
}

private fun styleNode(node: Node, rules: List<CssRule>, parentStyle: Map<String, String>) {
    if (node !is ElementNode) return

    node.style.clear()
    for (key in INHERITED_PROPS) {
        parentStyle[key]?.let { node.style[key] = it }
    }

    for (rule in rules) {
        if (rule.selector.matches(node)) node.style.putAll(rule.properties)
    }

    val inline = node.attr("style")
    if (!inline.isNullOrBlank()) {
        node.style.putAll(CssParser.parseInlineDeclarations(inline))
    }

    resolveFontSize(node, parentStyle)

    for (child in node.children) styleNode(child, rules, node.style)
}

private fun resolveFontSize(node: ElementNode, parentStyle: Map<String, String>) {
    val parentPx = parsePx(parentStyle["font-size"]) ?: 16f
    val raw = node.style["font-size"] ?: return
    val resolved = when {
        raw.endsWith("%") -> raw.removeSuffix("%").toFloatOrNull()?.let { parentPx * it / 100f }
        raw.endsWith("em") -> raw.removeSuffix("em").toFloatOrNull()?.let { parentPx * it }
        raw.endsWith("px") -> raw.removeSuffix("px").toFloatOrNull()
        else -> raw.toFloatOrNull()
    } ?: parentPx
    node.style["font-size"] = "${resolved}px"
}

fun parsePx(value: String?): Float? {
    if (value == null) return null
    return value.removeSuffix("px").toFloatOrNull()
}
