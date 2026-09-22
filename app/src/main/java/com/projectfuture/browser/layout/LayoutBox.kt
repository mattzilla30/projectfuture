package com.projectfuture.browser.layout

import android.graphics.Color
import com.projectfuture.browser.css.parseCssColor
import com.projectfuture.browser.css.parsePx
import com.projectfuture.browser.css.resolveBoxMetrics
import com.projectfuture.browser.css.resolvePositionOffsets
import com.projectfuture.browser.html.ElementNode
import com.projectfuture.browser.html.HtmlParser
import com.projectfuture.browser.html.Node
import com.projectfuture.browser.html.TextNode

/**
 * The box tree + line-breaking layout engine. Two layout modes, chosen per
 * box the way real browsers approximate it for a simple engine: BLOCK
 * (children stack vertically, each full-width) or INLINE (children are text
 * flowed into wrapped, baseline-aligned lines). This produces the absolute
 * page-coordinate DisplayCommand list that BrowserView paints.
 *
 * Positioning support and its known limits:
 * - `relative` stays in normal flow; top/right/bottom/left just shift its
 *   painted position (and its subtree's), matching spec.
 * - `absolute`/`fixed` are removed from flow and positioned against the
 *   nearest positioned ancestor's padding box (the viewport for `fixed`,
 *   or for `absolute` with no positioned ancestor). `left`/`top` are fully
 *   supported; `right`/`bottom` resolve correctly when paired with an
 *   explicit width/height, but fall back to the normal-flow "static
 *   position" when the box's own size is auto - true CSS shrink-to-fit and
 *   a second layout pass for that case aren't implemented.
 * - `z-index`: positioned descendants paint after normal-flow ones,
 *   sorted by z-index. This is a flat approximation of real stacking
 *   contexts (which nest), not a full implementation.
 * - `sticky` is not implemented - it needs layout to react to scroll
 *   position, which this single-pass-then-just-translate renderer doesn't
 *   support yet.
 */
private const val LINE_LEADING = 1.25f

private val SKIPPED_TAGS = setOf("script", "style", "head", "title", "meta", "link", "noscript")

private enum class LayoutMode { BLOCK, INLINE }

/** The containing block used to resolve absolute/fixed descendants' geometry. */
data class PositionContext(
    val containerX: Float,
    val containerY: Float,
    val containerWidth: Float,
    val containerHeight: Float
)

class DocumentLayout(private val root: ElementNode) {
    var width = 0f
        private set
    var height = 0f
        private set

    fun layout(availableWidth: Float, availableHeight: Float): List<DisplayCommand> {
        width = availableWidth
        val initialContext = PositionContext(0f, 0f, availableWidth, availableHeight)
        val child = BlockLayout(root, 0f, 0f, availableWidth, initialContext, inheritedFixed = false)
        child.layout()
        height = child.outerHeight
        val cmds = ArrayList<DisplayCommand>()
        child.paint(cmds)
        return cmds
    }
}

class BlockLayout(
    val node: ElementNode,
    private val containingX: Float,
    private val containingY: Float,
    private val containingWidth: Float,
    private val posContext: PositionContext,
    private val inheritedFixed: Boolean
) {
    /** Content-box geometry, set once [layout] has run. */
    var x = 0f
        private set
    var y = 0f
        private set
    var width = 0f
        private set
    var height = 0f
        private set

    /** Full margin-box height, i.e. what a parent advances its cursor by. */
    var outerHeight = 0f
        private set

    private var marginTop = 0f
    private var marginBottom = 0f
    private var borderTop = 0f
    private var borderRight = 0f
    private var borderBottom = 0f
    private var borderLeft = 0f
    private var paddingTop = 0f
    private var paddingRight = 0f
    private var paddingBottom = 0f
    private var paddingLeft = 0f
    private var borderColorTop = Color.BLACK
    private var borderColorRight = Color.BLACK
    private var borderColorBottom = Color.BLACK
    private var borderColorLeft = Color.BLACK
    private var fixedContext = false
    private var zIndex = 0

    private val normalChildren = ArrayList<BlockLayout>()
    private val positionedChildren = ArrayList<BlockLayout>()
    private val inlineDisplay = ArrayList<DisplayCommand>()
    private var mode = LayoutMode.INLINE

    private var cursorX = 0f
    private var cursorY = 0f
    private val lineBuffer = ArrayList<WordFragment>()

    private class WordFragment(val text: String, val xOffset: Float, val style: TextStyle)

    fun layout() {
        val currentColor = parseCssColor(node.style["color"]) ?: Color.BLACK
        val fontSizePx = parsePx(node.style["font-size"]) ?: 16f
        val ownPosition = node.style["position"] ?: "static"
        fixedContext = inheritedFixed || ownPosition == "fixed"
        zIndex = node.style["z-index"]?.toIntOrNull() ?: 0

        val removedFromFlow = ownPosition == "absolute" || ownPosition == "fixed"
        val establishesContainingBlock = ownPosition != "static"

        val effContainingX = if (removedFromFlow) posContext.containerX else containingX
        val effContainingY = if (removedFromFlow) posContext.containerY else containingY
        val effContainingWidth = if (removedFromFlow) posContext.containerWidth else containingWidth

        val metrics = resolveBoxMetrics(node.style, effContainingWidth, currentColor, fontSizePx)
        marginTop = metrics.marginTop
        marginBottom = metrics.marginBottom
        borderTop = metrics.borderTop
        borderRight = metrics.borderRight
        borderBottom = metrics.borderBottom
        borderLeft = metrics.borderLeft
        paddingTop = metrics.paddingTop
        paddingRight = metrics.paddingRight
        paddingBottom = metrics.paddingBottom
        paddingLeft = metrics.paddingLeft
        borderColorTop = metrics.borderColorTop
        borderColorRight = metrics.borderColorRight
        borderColorBottom = metrics.borderColorBottom
        borderColorLeft = metrics.borderColorLeft

        val availableContentWidth = effContainingWidth - metrics.marginLeft - metrics.marginRight -
            borderLeft - borderRight - paddingLeft - paddingRight
        width = metrics.explicitContentWidth ?: availableContentWidth.coerceAtLeast(0f)

        if (removedFromFlow) {
            val offsets = resolvePositionOffsets(node.style, posContext.containerWidth, posContext.containerHeight, fontSizePx)
            val staticX = containingX + metrics.marginLeft + borderLeft + paddingLeft
            val staticY = containingY + marginTop + borderTop + paddingTop
            x = when {
                offsets.left != null -> posContext.containerX + offsets.left + borderLeft + paddingLeft
                offsets.right != null -> (posContext.containerX + posContext.containerWidth) - offsets.right - borderRight - paddingRight - width
                else -> staticX
            }
            y = when {
                offsets.top != null -> posContext.containerY + offsets.top + borderTop + paddingTop
                offsets.bottom != null && metrics.explicitContentHeight != null ->
                    (posContext.containerY + posContext.containerHeight) - offsets.bottom - borderBottom - paddingBottom - metrics.explicitContentHeight
                else -> staticY
            }
        } else {
            x = effContainingX + metrics.marginLeft + borderLeft + paddingLeft
            y = effContainingY + marginTop + borderTop + paddingTop
            if (ownPosition == "relative") {
                val offsets = resolvePositionOffsets(node.style, effContainingWidth, posContext.containerHeight, fontSizePx)
                x += offsets.left ?: offsets.right?.let { -it } ?: 0f
                y += offsets.top ?: offsets.bottom?.let { -it } ?: 0f
            }
        }

        // Containing block passed to descendants when this box establishes one.
        // Height is approximated from the nearest known height when this box's
        // own height is auto (a real second layout pass isn't implemented -
        // see the class doc's note on right/bottom + auto-size).
        val childPosContext = if (establishesContainingBlock) {
            val approxHeight = metrics.explicitContentHeight ?: posContext.containerHeight
            PositionContext(
                containerX = x - paddingLeft,
                containerY = y - paddingTop,
                containerWidth = width + paddingLeft + paddingRight,
                containerHeight = approxHeight + paddingTop + paddingBottom
            )
        } else {
            posContext
        }

        mode = layoutMode()
        if (mode == LayoutMode.BLOCK) {
            var cursor = y
            for (childNode in node.children) {
                if (childNode !is ElementNode || isSkipped(childNode)) continue
                val childPosition = childNode.style["position"] ?: "static"
                val childRemoved = childPosition == "absolute" || childPosition == "fixed"
                val bl = BlockLayout(childNode, x, cursor, width, childPosContext, fixedContext)
                bl.layout()
                if (childRemoved) {
                    positionedChildren.add(bl)
                } else {
                    normalChildren.add(bl)
                    cursor += bl.outerHeight
                }
            }
            height = metrics.explicitContentHeight ?: (cursor - y).coerceAtLeast(0f)
        } else {
            cursorX = 0f
            cursorY = 0f
            lineBuffer.clear()
            inlineDisplay.clear()
            recurse(node, currentLinkHref = null)
            flushLine()
            height = metrics.explicitContentHeight ?: cursorY
        }

        outerHeight = if (removedFromFlow) {
            0f // takes no space in the flow it was removed from
        } else {
            marginTop + borderTop + paddingTop + height + paddingBottom + borderBottom + marginBottom
        }
    }

    private fun isSkipped(el: ElementNode): Boolean =
        el.tag in SKIPPED_TAGS || el.style["display"] == "none"

    private fun layoutMode(): LayoutMode {
        val elementChildren = node.children.filterIsInstance<ElementNode>().filterNot { isSkipped(it) }
        if (elementChildren.isEmpty()) return LayoutMode.INLINE
        return if (elementChildren.any { it.tag in HtmlParser.BLOCK_ELEMENTS }) LayoutMode.BLOCK else LayoutMode.INLINE
    }

    private fun recurse(n: Node, currentLinkHref: String?) {
        when (n) {
            is TextNode -> {
                val style = textStyleForElement(n.parent ?: node, currentLinkHref)
                for (word in n.text.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                    addWord(word, style)
                }
            }
            is ElementNode -> {
                if (isSkipped(n)) return
                if (n.tag == "br") {
                    flushLine()
                    return
                }
                if (n.tag == "img") return
                val linkHref = if (n.tag == "a") n.attr("href") else currentLinkHref
                for (child in n.children) recurse(child, linkHref)
            }
        }
    }

    private fun addWord(word: String, style: TextStyle) {
        val paint = FontCache.paintFor(style)
        val wordWidth = paint.measureText(word)
        val spaceWidth = paint.measureText(" ")
        if (cursorX > 0f && cursorX + wordWidth > width) flushLine()
        lineBuffer.add(WordFragment(word, cursorX, style))
        cursorX += wordWidth + spaceWidth
    }

    private fun flushLine() {
        if (lineBuffer.isEmpty()) return
        var maxAscent = 0f
        var maxDescent = 0f
        for (w in lineBuffer) {
            val fm = FontCache.paintFor(w.style).fontMetrics
            maxAscent = maxOf(maxAscent, -fm.ascent)
            maxDescent = maxOf(maxDescent, fm.descent)
        }
        val baseline = cursorY + maxAscent
        for (w in lineBuffer) {
            val paint = FontCache.paintFor(w.style)
            val wordWidth = paint.measureText(w.text)
            val fm = paint.fontMetrics
            inlineDisplay.add(
                DrawText(
                    x = x + w.xOffset,
                    baselineY = y + baseline,
                    text = w.text,
                    style = w.style,
                    left = x + w.xOffset,
                    right = x + w.xOffset + wordWidth,
                    boxTop = y + baseline + fm.ascent,
                    boxBottom = y + baseline + fm.descent,
                    fixed = fixedContext
                )
            )
        }
        cursorY = (baseline + maxDescent) + (maxAscent + maxDescent) * (LINE_LEADING - 1f)
        lineBuffer.clear()
        cursorX = 0f
    }

    fun paint(cmds: MutableList<DisplayCommand>) {
        val borderBoxLeft = x - paddingLeft - borderLeft
        val borderBoxTop = y - paddingTop - borderTop
        val borderBoxRight = x + width + paddingRight + borderRight
        val borderBoxBottom = y + height + paddingBottom + borderBottom

        parseCssColor(node.style["background-color"])?.let { bg ->
            cmds.add(DrawRect(borderBoxLeft, borderBoxTop, borderBoxRight, borderBoxBottom, bg, fixed = fixedContext))
        }
        if (borderTop > 0f) cmds.add(DrawRect(borderBoxLeft, borderBoxTop, borderBoxRight, borderBoxTop + borderTop, borderColorTop, fixed = fixedContext))
        if (borderBottom > 0f) cmds.add(DrawRect(borderBoxLeft, borderBoxBottom - borderBottom, borderBoxRight, borderBoxBottom, borderColorBottom, fixed = fixedContext))
        if (borderLeft > 0f) cmds.add(DrawRect(borderBoxLeft, borderBoxTop, borderBoxLeft + borderLeft, borderBoxBottom, borderColorLeft, fixed = fixedContext))
        if (borderRight > 0f) cmds.add(DrawRect(borderBoxRight - borderRight, borderBoxTop, borderBoxRight, borderBoxBottom, borderColorRight, fixed = fixedContext))

        if (mode == LayoutMode.BLOCK) {
            for (c in normalChildren) c.paint(cmds)
            for (c in positionedChildren.sortedBy { it.zIndex }) c.paint(cmds)
        } else {
            cmds.addAll(inlineDisplay)
        }
    }
}
