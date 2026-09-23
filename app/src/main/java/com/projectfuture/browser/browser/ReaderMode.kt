package com.projectfuture.browser.browser

import com.projectfuture.browser.html.ElementNode
import com.projectfuture.browser.html.Node
import com.projectfuture.browser.html.TextNode
import com.projectfuture.browser.html.walkElements

private val REMOVE_TAGS = setOf(
    "script", "style", "nav", "header", "footer", "aside", "iframe",
    "form", "button", "input", "select", "textarea", "svg", "canvas", "noscript"
)
private val CONTENT_TAGS = setOf(
    "p", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li",
    "blockquote", "img", "figure", "figcaption", "pre", "a", "span", "b", "strong", "i", "em", "br"
)
private val CANDIDATE_TAGS = setOf("div", "article", "section", "main", "td", "body")

/**
 * A bounded, from-scratch approximation of a Readability-style content
 * extractor: scores block-level candidates by their own text length minus
 * (double-weighted) link text - penalizing nav/sidebar blocks that are
 * mostly anchors - and picks the highest scorer. This is nowhere near
 * Mozilla's actual Readability algorithm (no paragraph density scoring, no
 * ancestor score propagation, no byline/lede heuristics) - a real, working
 * subset that does reasonably on simple article pages, not a faithful
 * port. Returns null if nothing looked like substantial article content,
 * so the caller can fall back to leaving the normal page alone.
 */
fun extractReaderContent(root: ElementNode): ElementNode? {
    var best: ElementNode? = null
    var bestScore = 0
    root.walkElements { el ->
        if (el.tag !in CANDIDATE_TAGS) return@walkElements
        val score = textLength(el) - linkTextLength(el) * 2
        if (score > bestScore) {
            bestScore = score
            best = el
        }
    }
    val winner = best ?: return null
    if (bestScore < 200) return null // too little real content to be worth showing as an "article"

    val html = ElementNode("html")
    val body = ElementNode("body", parent = html)
    html.children.add(body)
    body.children.add(cleanClone(winner, body))
    return html
}

private fun textLength(el: ElementNode): Int {
    var total = 0
    fun walk(n: Node) {
        when (n) {
            is TextNode -> total += n.text.trim().length
            is ElementNode -> if (n.tag !in REMOVE_TAGS) n.children.forEach { walk(it) }
        }
    }
    walk(el)
    return total
}

private fun linkTextLength(el: ElementNode): Int {
    var total = 0
    el.walkElements { if (it.tag == "a") total += textLength(it) }
    return total
}

/** Rebuilds [node] using only content-shaped tags (see CONTENT_TAGS/REMOVE_TAGS), dropping every attribute except href/src. */
private fun cleanClone(node: ElementNode, newParent: ElementNode): ElementNode {
    val clone = ElementNode(if (node.tag in CONTENT_TAGS) node.tag else "div", parent = newParent)
    node.attr("href")?.let { clone.attributes["href"] = it }
    node.attr("src")?.let { clone.attributes["src"] = it }
    for (child in node.children) {
        when (child) {
            is TextNode -> clone.children.add(TextNode(child.text, clone))
            is ElementNode -> if (child.tag !in REMOVE_TAGS) clone.children.add(cleanClone(child, clone))
        }
    }
    return clone
}

/** A clean, generously-spaced serif typographic stylesheet - reader mode's whole visual identity. */
const val READER_MODE_CSS = """
    html, body { background-color: #fdf6e3; color: #333333; }
    body { font-family: serif; font-size: 19px; padding: 24px; }
    h1, h2, h3 { font-family: sans-serif; color: #111111; }
    a { color: #0645ad; }
    img { max-width: 100%; }
    blockquote { color: #555555; border-left: 4px solid #cccccc; padding-left: 16px; }
    pre { background-color: #eeeeee; padding: 12px; }
"""
