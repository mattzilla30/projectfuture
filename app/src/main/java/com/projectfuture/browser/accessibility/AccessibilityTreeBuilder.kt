package com.projectfuture.browser.accessibility

import com.projectfuture.browser.html.ElementNode
import com.projectfuture.browser.html.Node
import com.projectfuture.browser.html.TextNode
import com.projectfuture.browser.layout.DisplayCommand

private val HEADING_LEVELS = mapOf("h1" to 1, "h2" to 2, "h3" to 3, "h4" to 4, "h5" to 5, "h6" to 6)

private val LANDMARK_LABELS = mapOf(
    "nav" to "navigation",
    "main" to "main content",
    "header" to "banner",
    "footer" to "content information",
    "aside" to "complementary"
)

private val ARIA_ROLE_MAP = mapOf(
    "button" to AccessibilityRole.BUTTON,
    "link" to AccessibilityRole.LINK,
    "checkbox" to AccessibilityRole.CHECKBOX,
    "radio" to AccessibilityRole.RADIO,
    "heading" to AccessibilityRole.HEADING,
    "img" to AccessibilityRole.IMAGE,
    "image" to AccessibilityRole.IMAGE,
    "navigation" to AccessibilityRole.LANDMARK,
    "main" to AccessibilityRole.LANDMARK,
    "banner" to AccessibilityRole.LANDMARK,
    "contentinfo" to AccessibilityRole.LANDMARK,
    "complementary" to AccessibilityRole.LANDMARK,
    "list" to AccessibilityRole.LIST,
    "listitem" to AccessibilityRole.LIST_ITEM,
    "combobox" to AccessibilityRole.COMBO_BOX,
    "textbox" to AccessibilityRole.TEXT_FIELD
)

private val CLICKABLE_ROLES = setOf(
    AccessibilityRole.LINK, AccessibilityRole.BUTTON, AccessibilityRole.CHECKBOX, AccessibilityRole.RADIO
)

/**
 * Builds a flat, traversal-ordered accessibility tree from a laid-out
 * document: the real DOM (`ElementNode`) plus the exact `DisplayCommand`
 * list that layout produced for it (see DisplayCommand.kt/LayoutBox.kt).
 * This feeds BrowserAccessibilityNodeProvider, which exposes it to TalkBack
 * as a virtual-view hierarchy on BrowserView - see that class's doc.
 *
 * This function is deliberately pure Kotlin with zero Android framework
 * dependency (no `android.graphics.Rect`, no accessibility APIs), precisely
 * so it can be exercised by ordinary JVM unit tests
 * (AccessibilityTreeBuilderTest) with no device, emulator, or Robolectric
 * involved. That matters here specifically: this project has no way to
 * verify real TalkBack behavior in this environment, and the existing
 * caution elsewhere in this codebase about that risk (see BrowserView's
 * AccessibilityNodeProvider doc) is legitimate - a wrong accessibility tree
 * can hang or crash TalkBack for the exact users depending on it. Splitting
 * "what the tree should look like" (this file, unit-tested) from "how to
 * hand that to Android" (BrowserAccessibilityNodeProvider, not unit-
 * testable without a device) is how this gets automated coverage at all.
 *
 * Safety-motivated design choices, not just simplicity ones:
 *
 * - The result is FLAT, not a mirror of DOM nesting: every meaningful
 *   element (heading, link, form control, image with alt text, landmark,
 *   list/list-item) becomes one node, in document order, and the consumer
 *   treats all of them as direct children of the page. Swipe-based linear
 *   navigation only needs a correctly ordered list, not real nesting, and a
 *   flat list has far fewer ways to be wrong - no risk of a node with the
 *   wrong parent, an orphaned subtree, or a parent/child cycle, which are
 *   exactly the kinds of bugs that can hang or crash a screen reader's
 *   traversal.
 * - Document order (pre-order DOM traversal) is used as the accessibility
 *   traversal order. This matches how this engine's own layout works for
 *   the common case (block content lays out sequentially top-to-bottom in
 *   document order - see LayoutBox.kt's class doc), but it is an
 *   approximation, not a true visual-geometry sort: a page that uses
 *   `position`/floats/flex `order` to visually reorder content will not get
 *   a re-ordered traversal to match. That's a known, bounded limitation,
 *   not a bug to unit-test around.
 * - A node's bounds are the union of every DisplayCommand painted for it OR
 *   any of its descendants (computed bottom-up over the real DOM subtree),
 *   never invented. An element with no painted content at all (e.g.
 *   `display:none`, or something the layout engine skipped) is left out of
 *   the tree entirely rather than exposed with a zero-size or stale rect -
 *   a bad bounds rect is a known way to confuse a screen reader's focus
 *   highlight and gesture targeting.
 * - An `<img>` with no non-empty `alt` (missing OR `alt=""`) is treated as
 *   decorative and left out, rather than exposed with a generic "image"
 *   label - an unlabeled image node is pure noise for a screen-reader user,
 *   arguably worse than not mentioning it at all.
 */
fun buildAccessibilityTree(root: ElementNode, displayCommands: List<DisplayCommand>): AccessibilityTree {
    val direct = HashMap<ElementNode, NodeBounds>()
    for (cmd in displayCommands) {
        val element = cmd.sourceElement ?: continue
        val bounds = NodeBounds(cmd.left, cmd.top, cmd.right, cmd.bottom)
        if (bounds.isEmpty) continue
        direct[element] = direct[element]?.union(bounds) ?: bounds
    }

    val subtreeBounds = HashMap<ElementNode, NodeBounds>()
    fun computeSubtreeBounds(element: ElementNode): NodeBounds? {
        var acc = direct[element]
        for (child in element.children) {
            if (child is ElementNode) {
                val childBounds = computeSubtreeBounds(child)
                if (childBounds != null) acc = acc?.union(childBounds) ?: childBounds
            }
        }
        if (acc != null) subtreeBounds[element] = acc
        return acc
    }
    computeSubtreeBounds(root)

    val nodes = ArrayList<AccessibleNode>()
    fun visit(element: ElementNode) {
        if (!isHidden(element)) {
            val role = classify(element)
            if (role != null) {
                val bounds = subtreeBounds[element]
                if (bounds != null && !bounds.isEmpty) {
                    val label = labelFor(element, role)
                    // See class doc: an unlabeled image is treated as decorative and skipped.
                    if (role != AccessibilityRole.IMAGE || label.isNotBlank()) {
                        nodes.add(
                            AccessibleNode(
                                id = nodes.size,
                                sourceElement = element,
                                role = role,
                                label = label,
                                bounds = bounds,
                                headingLevel = headingLevelFor(element, role),
                                checked = checkedStateFor(element, role),
                                clickable = role in CLICKABLE_ROLES
                            )
                        )
                    }
                }
            }
            for (child in element.children) if (child is ElementNode) visit(child)
        }
    }
    visit(root)
    return AccessibilityTree(nodes)
}

private fun isHidden(element: ElementNode): Boolean =
    element.style["display"] == "none" || element.attr("aria-hidden")?.equals("true", ignoreCase = true) == true

private fun classify(element: ElementNode): AccessibilityRole? {
    element.attr("role")?.lowercase()?.let { roleAttr -> ARIA_ROLE_MAP[roleAttr]?.let { return it } }
    if (element.tag in HEADING_LEVELS) return AccessibilityRole.HEADING
    if (element.tag == "a" && !element.attr("href").isNullOrEmpty()) return AccessibilityRole.LINK
    if (element.tag == "img") return AccessibilityRole.IMAGE
    if (element.tag == "button") return AccessibilityRole.BUTTON
    if (element.tag == "select") return AccessibilityRole.COMBO_BOX
    if (element.tag == "textarea") return AccessibilityRole.TEXT_FIELD
    if (element.tag == "input") {
        return when ((element.attr("type") ?: "text").lowercase()) {
            "hidden" -> null
            "checkbox" -> AccessibilityRole.CHECKBOX
            "radio" -> AccessibilityRole.RADIO
            "submit", "button", "reset", "image" -> AccessibilityRole.BUTTON
            else -> AccessibilityRole.TEXT_FIELD
        }
    }
    if (element.tag in LANDMARK_LABELS) return AccessibilityRole.LANDMARK
    if (element.tag == "ul" || element.tag == "ol") return AccessibilityRole.LIST
    if (element.tag == "li") return AccessibilityRole.LIST_ITEM
    return null
}

private fun headingLevelFor(element: ElementNode, role: AccessibilityRole): Int? {
    if (role != AccessibilityRole.HEADING) return null
    HEADING_LEVELS[element.tag]?.let { return it }
    return element.attr("aria-level")?.toIntOrNull()?.coerceIn(1, 6) ?: 2
}

private fun checkedStateFor(element: ElementNode, role: AccessibilityRole): Boolean? {
    if (role != AccessibilityRole.CHECKBOX && role != AccessibilityRole.RADIO) return null
    element.attr("aria-checked")?.let { return it.equals("true", ignoreCase = true) }
    return element.attr("checked") != null
}

private fun textContent(element: ElementNode): String {
    val sb = StringBuilder()
    fun walk(node: Node) {
        when (node) {
            is TextNode -> {
                val trimmed = node.text.trim()
                if (trimmed.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(trimmed)
                }
            }
            is ElementNode -> for (child in node.children) walk(child)
        }
    }
    walk(element)
    return sb.toString().replace(Regex("\\s+"), " ").trim()
}

private fun labelFor(element: ElementNode, role: AccessibilityRole): String {
    element.attr("aria-label")?.takeIf { it.isNotBlank() }?.let { return it }
    return when (role) {
        AccessibilityRole.IMAGE -> element.attr("alt")?.trim() ?: ""
        AccessibilityRole.TEXT_FIELD, AccessibilityRole.COMBO_BOX -> textContent(element).ifBlank {
            element.attr("placeholder") ?: element.attr("value") ?: element.attr("name") ?: ""
        }
        AccessibilityRole.LANDMARK -> LANDMARK_LABELS[element.tag] ?: element.tag
        AccessibilityRole.CHECKBOX, AccessibilityRole.RADIO -> textContent(element).ifBlank {
            element.attr("value") ?: element.attr("name") ?: role.name.lowercase()
        }
        else -> textContent(element).ifBlank { element.attr("value") ?: "" }
    }
}
