package com.projectfuture.browser.accessibility

import com.projectfuture.browser.html.ElementNode

/**
 * A painted region in the same absolute page-space coordinates as
 * [com.projectfuture.browser.layout.DisplayCommand] (see that class's doc).
 * Deliberately a plain data class, not `android.graphics.Rect`/`RectF`, so
 * this whole package stays free of any Android framework type and can be
 * exercised by ordinary JVM unit tests with no device/emulator/Robolectric
 * involved - see AccessibilityTreeBuilder.kt's class doc for why that matters.
 */
data class NodeBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val isEmpty: Boolean get() = right <= left || bottom <= top

    fun union(other: NodeBounds): NodeBounds = NodeBounds(
        minOf(left, other.left),
        minOf(top, other.top),
        maxOf(right, other.right),
        maxOf(bottom, other.bottom)
    )
}

/**
 * The accessibility-relevant roles this engine currently recognizes when
 * walking a laid-out DOM. Deliberately a small, closed set matching what
 * [com.projectfuture.browser.accessibility.buildAccessibilityTree] actually
 * classifies - see that function's doc for the mapping from HTML tags/ARIA
 * roles to each of these.
 */
enum class AccessibilityRole {
    HEADING,
    LINK,
    BUTTON,
    CHECKBOX,
    RADIO,
    TEXT_FIELD,
    COMBO_BOX,
    IMAGE,
    LANDMARK,
    LIST,
    LIST_ITEM,
    GENERIC
}

/**
 * One exposed accessibility node: a DOM element that is meaningful enough
 * (a heading, link, form control, image with alt text, or landmark) to
 * surface to a screen reader, with real page-space bounds derived from the
 * document's own layout - never invented ones. See
 * [buildAccessibilityTree]'s class doc for the tree-shape and safety
 * reasoning behind [id] being a flat index rather than a real tree position.
 */
data class AccessibleNode(
    /** Index into the owning [AccessibilityTree.nodes] list - also the virtualViewId used by BrowserAccessibilityNodeProvider. */
    val id: Int,
    val sourceElement: ElementNode,
    val role: AccessibilityRole,
    val label: String,
    val bounds: NodeBounds,
    /** 1-6 for `<h1>`-`<h6>` (or `role="heading"` + `aria-level`), null otherwise. */
    val headingLevel: Int? = null,
    /** Only meaningful for CHECKBOX/RADIO; null for every other role. */
    val checked: Boolean? = null,
    /** Whether double-tap activation (ACTION_CLICK) should be offered for this node. */
    val clickable: Boolean = false
)

/**
 * The full set of accessible nodes for one laid-out page, in document/
 * traversal order (see [buildAccessibilityTree]). Flat by design - there is
 * no parent/child nesting beyond "every node is a child of the page" - so a
 * consumer (BrowserAccessibilityNodeProvider) just needs [nodes] in order to
 * drive swipe-based linear navigation.
 */
data class AccessibilityTree(val nodes: List<AccessibleNode>) {
    val isEmpty: Boolean get() = nodes.isEmpty()
    fun nodeAt(id: Int): AccessibleNode? = nodes.getOrNull(id)
}
