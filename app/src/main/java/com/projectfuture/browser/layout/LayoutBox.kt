package com.projectfuture.browser.layout

import android.graphics.Color
import com.projectfuture.browser.css.AlignItems
import com.projectfuture.browser.css.BoxMetrics
import com.projectfuture.browser.css.FlexContainerProps
import com.projectfuture.browser.css.FlexDirection
import com.projectfuture.browser.css.FlexItemProps
import com.projectfuture.browser.css.FlexWrapMode
import com.projectfuture.browser.css.GridContainerProps
import com.projectfuture.browser.css.JustifyContent
import com.projectfuture.browser.css.lengthValue
import com.projectfuture.browser.css.parseCssColor
import com.projectfuture.browser.css.parsePx
import com.projectfuture.browser.css.resolveBoxMetrics
import com.projectfuture.browser.css.resolveFlexContainerProps
import com.projectfuture.browser.css.resolveFlexItemProps
import com.projectfuture.browser.css.resolveFlexMainSizes
import com.projectfuture.browser.css.resolveGridColumnWidths
import com.projectfuture.browser.css.resolveGridContainerProps
import com.projectfuture.browser.css.resolvePositionOffsets
import com.projectfuture.browser.html.ElementNode
import com.projectfuture.browser.html.HtmlParser
import com.projectfuture.browser.html.Node
import com.projectfuture.browser.html.TextNode

/**
 * The box tree + line-breaking layout engine. Three layout modes, chosen
 * per box the way real browsers approximate it for a simple engine: BLOCK
 * (children stack vertically, each full-width), INLINE (children are text
 * flowed into wrapped, baseline-aligned lines), or FLEX (`display: flex`).
 * This produces the absolute page-coordinate DisplayCommand list that
 * BrowserView paints.
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
 *
 * Flexbox support and its known limits:
 * - `flex-direction: row`/`row-reverse` gets the full treatment: wrapping,
 *   grow/shrink, justify-content, align-items/align-self (incl. stretch).
 * - `flex-direction: column`/`column-reverse` supports grow/shrink,
 *   justify-content and align, but NOT wrapping (multi-column flex-wrap is
 *   rare in practice versus row-wrap, e.g. card grids, so it was left out
 *   to bound scope) - items always form a single column.
 * - A flex item's basis, when `flex-basis`/`width`(row)/`height`(column)
 *   aren't set, is approximated: for row direction from its unwrapped text
 *   width (no true intrinsic-sizing pass for nested block content); for
 *   column direction via a one-off probe layout at full width. Both are
 *   reasonable approximations, not spec-exact shrink-to-fit.
 * - `align-content` (multi-line cross-axis distribution) isn't
 *   implemented - wrapped lines always stack from the start.
 *
 * Grid support (`display: grid`) is deliberately narrow: see Grid.kt's
 * class doc. In short, `grid-template-columns` (with `fr`/`repeat()`) plus
 * row-major auto-placement, not explicit item placement or row tracks.
 */
private const val LINE_LEADING = 1.25f

private val SKIPPED_TAGS = setOf("script", "style", "head", "title", "meta", "link", "noscript")

private enum class LayoutMode { BLOCK, INLINE, FLEX, GRID }

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
    private val inheritedFixed: Boolean,
    private val forcedWidth: Float? = null,
    private val forcedHeight: Float? = null
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
        width = forcedWidth ?: (metrics.explicitContentWidth ?: availableContentWidth.coerceAtLeast(0f))

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
        when (mode) {
            LayoutMode.BLOCK -> {
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
                height = forcedHeight ?: (metrics.explicitContentHeight ?: (cursor - y).coerceAtLeast(0f))
            }
            LayoutMode.FLEX -> {
                val flexItemNodes = ArrayList<ElementNode>()
                for (childNode in node.children) {
                    if (childNode !is ElementNode || isSkipped(childNode)) continue
                    val childPosition = childNode.style["position"] ?: "static"
                    if (childPosition == "absolute" || childPosition == "fixed") {
                        val bl = BlockLayout(childNode, x, y, width, childPosContext, fixedContext)
                        bl.layout()
                        positionedChildren.add(bl)
                    } else {
                        flexItemNodes.add(childNode)
                    }
                }
                val containerProps = resolveFlexContainerProps(node.style, width, fontSizePx)
                val naturalMain = layoutFlexChildren(containerProps, flexItemNodes, childPosContext, metrics.explicitContentHeight)
                height = forcedHeight ?: (metrics.explicitContentHeight ?: naturalMain)
            }
            LayoutMode.GRID -> {
                val gridItemNodes = ArrayList<ElementNode>()
                for (childNode in node.children) {
                    if (childNode !is ElementNode || isSkipped(childNode)) continue
                    val childPosition = childNode.style["position"] ?: "static"
                    if (childPosition == "absolute" || childPosition == "fixed") {
                        val bl = BlockLayout(childNode, x, y, width, childPosContext, fixedContext)
                        bl.layout()
                        positionedChildren.add(bl)
                    } else {
                        gridItemNodes.add(childNode)
                    }
                }
                val gridProps = resolveGridContainerProps(node.style, width, fontSizePx)
                val naturalHeight = layoutGridChildren(gridProps, gridItemNodes, childPosContext)
                height = forcedHeight ?: (metrics.explicitContentHeight ?: naturalHeight)
            }
            LayoutMode.INLINE -> {
                cursorX = 0f
                cursorY = 0f
                lineBuffer.clear()
                inlineDisplay.clear()
                recurse(node, currentLinkHref = null)
                flushLine()
                height = forcedHeight ?: (metrics.explicitContentHeight ?: cursorY)
            }
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
        val display = node.style["display"]
        if (display == "flex" || display == "inline-flex") return LayoutMode.FLEX
        if (display == "grid" || display == "inline-grid") return LayoutMode.GRID
        val elementChildren = node.children.filterIsInstance<ElementNode>().filterNot { isSkipped(it) }
        if (elementChildren.isEmpty()) return LayoutMode.INLINE
        return if (elementChildren.any { it.tag in HtmlParser.BLOCK_ELEMENTS }) LayoutMode.BLOCK else LayoutMode.INLINE
    }

    // ---- Flexbox ----

    private class FlexItem(
        val node: ElementNode,
        val props: FlexItemProps,
        val metrics: BoxMetrics,
        val basis: Float
    )

    private fun layoutFlexChildren(
        containerProps: FlexContainerProps,
        itemNodes: List<ElementNode>,
        childPosContext: PositionContext,
        ownExplicitHeight: Float?
    ): Float {
        if (itemNodes.isEmpty()) return 0f
        val isRow = containerProps.direction == FlexDirection.ROW || containerProps.direction == FlexDirection.ROW_REVERSE
        val reverse = containerProps.direction == FlexDirection.ROW_REVERSE || containerProps.direction == FlexDirection.COLUMN_REVERSE
        val ordered = if (reverse) itemNodes.asReversed() else itemNodes
        return if (isRow) layoutFlexRow(containerProps, ordered, childPosContext) else layoutFlexColumn(containerProps, ordered, childPosContext, ownExplicitHeight)
    }

    private fun buildFlexItem(child: ElementNode, mainAxisBasisFallback: () -> Float): FlexItem {
        val childColor = parseCssColor(child.style["color"]) ?: Color.BLACK
        val childFontSize = parsePx(child.style["font-size"]) ?: 16f
        val m = resolveBoxMetrics(child.style, width, childColor, childFontSize)
        val props = resolveFlexItemProps(child.style)
        return FlexItem(child, props, m, mainAxisBasisFallback())
    }

    private fun layoutFlexRow(
        containerProps: FlexContainerProps,
        itemNodes: List<ElementNode>,
        childPosContext: PositionContext
    ): Float {
        val items = itemNodes.map { child ->
            buildFlexItem(child) {
                val childFontSize = parsePx(child.style["font-size"]) ?: 16f
                val props = resolveFlexItemProps(child.style)
                val m = resolveBoxMetrics(child.style, width, Color.BLACK, childFontSize)
                when {
                    props.basis != "auto" -> lengthValue(props.basis, width, childFontSize) ?: 0f
                    m.explicitContentWidth != null -> m.explicitContentWidth
                    else -> measureNaturalWidth(child)
                }
            }
        }
        val orderedIndices = items.indices.sortedBy { items[it].props.order }

        val lines = ArrayList<MutableList<Int>>()
        if (containerProps.wrap == FlexWrapMode.NOWRAP) {
            lines.add(orderedIndices.toMutableList())
        } else {
            var current = ArrayList<Int>()
            var currentMain = 0f
            for (idx in orderedIndices) {
                val item = items[idx]
                val extra = item.metrics.marginLeft + item.metrics.marginRight + item.metrics.borderLeft +
                    item.metrics.borderRight + item.metrics.paddingLeft + item.metrics.paddingRight
                val outer = item.basis + extra
                val withGap = if (current.isEmpty()) outer else outer + containerProps.columnGap
                if (current.isNotEmpty() && currentMain + withGap > width) {
                    lines.add(current)
                    current = arrayListOf(idx)
                    currentMain = outer
                } else {
                    current.add(idx)
                    currentMain += withGap
                }
            }
            if (current.isNotEmpty()) lines.add(current)
        }

        var lineTop = y
        for (lineIndices in lines) {
            val n = lineIndices.size
            val basisList = lineIndices.map { items[it].basis }
            val growList = lineIndices.map { items[it].props.grow }
            val shrinkList = lineIndices.map { items[it].props.shrink }
            val extraList = lineIndices.map { i ->
                val m = items[i].metrics
                m.marginLeft + m.marginRight + m.borderLeft + m.borderRight + m.paddingLeft + m.paddingRight
            }
            val finalMain = resolveFlexMainSizes(basisList, growList, shrinkList, extraList, width, containerProps.columnGap)

            val totalOuterMain = finalMain.indices.sumOf { (finalMain[it] + extraList[it]).toDouble() }.toFloat() +
                containerProps.columnGap * (n - 1).coerceAtLeast(0)
            val remaining = (width - totalOuterMain).coerceAtLeast(0f)
            var cursorMainX = x
            var betweenExtra = 0f
            when (containerProps.justifyContent) {
                JustifyContent.FLEX_START -> {}
                JustifyContent.FLEX_END -> cursorMainX += remaining
                JustifyContent.CENTER -> cursorMainX += remaining / 2
                JustifyContent.SPACE_BETWEEN -> if (n > 1) betweenExtra = remaining / (n - 1) else cursorMainX += remaining / 2
                JustifyContent.SPACE_AROUND -> {
                    val each = if (n > 0) remaining / n else 0f
                    cursorMainX += each / 2
                    betweenExtra = each
                }
            }

            val laidOut = arrayOfNulls<BlockLayout>(n)
            val itemX = FloatArray(n)
            for ((pos, idx) in lineIndices.withIndex()) {
                itemX[pos] = cursorMainX
                val bl = BlockLayout(items[idx].node, cursorMainX, lineTop, width, childPosContext, fixedContext, forcedWidth = finalMain[pos])
                bl.layout()
                laidOut[pos] = bl
                cursorMainX += finalMain[pos] + extraList[pos] + containerProps.columnGap + betweenExtra
            }

            val lineCrossSize = laidOut.filterNotNull().maxOfOrNull { it.outerHeight } ?: 0f

            for ((pos, idx) in lineIndices.withIndex()) {
                val item = items[idx]
                val alignSelf = item.props.alignSelf ?: containerProps.alignItems
                var finalBl = laidOut[pos]!!
                if (alignSelf == AlignItems.STRETCH && item.metrics.explicitContentHeight == null) {
                    val m = item.metrics
                    val forcedContentHeight = (lineCrossSize - m.marginTop - m.marginBottom - m.borderTop - m.borderBottom - m.paddingTop - m.paddingBottom).coerceAtLeast(0f)
                    finalBl = BlockLayout(item.node, itemX[pos], lineTop, width, childPosContext, fixedContext, forcedWidth = finalMain[pos], forcedHeight = forcedContentHeight)
                    finalBl.layout()
                } else if (alignSelf != AlignItems.FLEX_START) {
                    val extra = lineCrossSize - finalBl.outerHeight
                    val dy = if (alignSelf == AlignItems.CENTER) extra / 2 else extra
                    if (dy > 0f) {
                        finalBl = BlockLayout(item.node, itemX[pos], lineTop + dy, width, childPosContext, fixedContext, forcedWidth = finalMain[pos])
                        finalBl.layout()
                    }
                }
                normalChildren.add(finalBl)
            }

            lineTop += lineCrossSize + containerProps.rowGap
        }

        return (lineTop - y - containerProps.rowGap).coerceAtLeast(0f)
    }

    private fun layoutFlexColumn(
        containerProps: FlexContainerProps,
        itemNodes: List<ElementNode>,
        childPosContext: PositionContext,
        ownExplicitHeight: Float?
    ): Float {
        val items = itemNodes.map { child ->
            buildFlexItem(child) {
                val childFontSize = parsePx(child.style["font-size"]) ?: 16f
                val props = resolveFlexItemProps(child.style)
                val m = resolveBoxMetrics(child.style, width, Color.BLACK, childFontSize)
                when {
                    props.basis != "auto" -> lengthValue(props.basis, ownExplicitHeight ?: 0f, childFontSize) ?: 0f
                    m.explicitContentHeight != null -> m.explicitContentHeight
                    else -> {
                        val probe = BlockLayout(child, x, y, width, childPosContext, fixedContext)
                        probe.layout()
                        (probe.outerHeight - m.marginTop - m.marginBottom - m.borderTop - m.borderBottom - m.paddingTop - m.paddingBottom).coerceAtLeast(0f)
                    }
                }
            }
        }
        val orderedIndices = items.indices.sortedBy { items[it].props.order }

        val basisList = orderedIndices.map { items[it].basis }
        val growList = orderedIndices.map { items[it].props.grow }
        val shrinkList = orderedIndices.map { items[it].props.shrink }
        val extraList = orderedIndices.map { i ->
            val m = items[i].metrics
            m.marginTop + m.marginBottom + m.borderTop + m.borderBottom + m.paddingTop + m.paddingBottom
        }

        val n = orderedIndices.size
        val availableMain = ownExplicitHeight ?: (basisList.sum() + extraList.sum() + containerProps.rowGap * (n - 1).coerceAtLeast(0))
        val finalMain = resolveFlexMainSizes(basisList, growList, shrinkList, extraList, availableMain, containerProps.rowGap)

        val totalOuterMain = finalMain.indices.sumOf { (finalMain[it] + extraList[it]).toDouble() }.toFloat() +
            containerProps.rowGap * (n - 1).coerceAtLeast(0)
        val remaining = (availableMain - totalOuterMain).coerceAtLeast(0f)
        var cursorMainY = y
        var betweenExtra = 0f
        when (containerProps.justifyContent) {
            JustifyContent.FLEX_START -> {}
            JustifyContent.FLEX_END -> cursorMainY += remaining
            JustifyContent.CENTER -> cursorMainY += remaining / 2
            JustifyContent.SPACE_BETWEEN -> if (n > 1) betweenExtra = remaining / (n - 1) else cursorMainY += remaining / 2
            JustifyContent.SPACE_AROUND -> {
                val each = if (n > 0) remaining / n else 0f
                cursorMainY += each / 2
                betweenExtra = each
            }
        }

        for ((pos, idx) in orderedIndices.withIndex()) {
            val item = items[idx]
            val alignSelf = item.props.alignSelf ?: containerProps.alignItems
            val m = item.metrics
            val stretch = alignSelf == AlignItems.STRETCH && m.explicitContentWidth == null
            val forcedW = if (stretch) width else null
            var itemX = x
            if (!stretch) {
                val naturalWidthGuess = m.explicitContentWidth ?: width
                val extraSpace = (width - naturalWidthGuess).coerceAtLeast(0f)
                itemX = when (alignSelf) {
                    AlignItems.CENTER -> x + extraSpace / 2
                    AlignItems.FLEX_END -> x + extraSpace
                    else -> x
                }
            }
            val bl = BlockLayout(item.node, itemX, cursorMainY, width, childPosContext, fixedContext, forcedWidth = forcedW, forcedHeight = finalMain[pos])
            bl.layout()
            normalChildren.add(bl)
            cursorMainY += finalMain[pos] + extraList[pos] + containerProps.rowGap + betweenExtra
        }

        return (cursorMainY - y - containerProps.rowGap).coerceAtLeast(0f)
    }

    // ---- Grid ----

    /** Simple row-major auto-placement: fill columns left-to-right, wrap to a new row when full. */
    private fun layoutGridChildren(
        props: GridContainerProps,
        itemNodes: List<ElementNode>,
        childPosContext: PositionContext
    ): Float {
        if (itemNodes.isEmpty()) return 0f
        val columnWidths = resolveGridColumnWidths(props.columns, width, props.columnGap)
        val columnX = FloatArray(columnWidths.size)
        var acc = x
        for (i in columnWidths.indices) {
            columnX[i] = acc
            acc += columnWidths[i] + props.columnGap
        }

        var rowTop = y
        var col = 0
        val rowItems = ArrayList<BlockLayout>()
        fun flushRow() {
            if (rowItems.isEmpty()) return
            val rowHeight = rowItems.maxOf { it.outerHeight }
            normalChildren.addAll(rowItems)
            rowTop += rowHeight + props.rowGap
            rowItems.clear()
        }
        for (child in itemNodes) {
            if (col >= columnWidths.size) {
                flushRow()
                col = 0
            }
            // No forcedWidth: the column width is just this item's containing
            // width, so its own margin/border/padding/explicit-width are
            // honored by the normal box model instead of overflowing the cell.
            val bl = BlockLayout(child, columnX[col], rowTop, columnWidths[col], childPosContext, fixedContext)
            bl.layout()
            rowItems.add(bl)
            col++
        }
        flushRow()
        return (rowTop - y - props.rowGap).coerceAtLeast(0f)
    }

    /** Unwrapped natural text width, used as a flex-basis fallback for inline-content items. */
    private fun measureNaturalWidth(node: ElementNode): Float {
        if (isSkipped(node)) return 0f
        val elementChildren = node.children.filterIsInstance<ElementNode>().filterNot { isSkipped(it) }
        val isInlineContent = elementChildren.isEmpty() || elementChildren.none { it.tag in HtmlParser.BLOCK_ELEMENTS }
        if (!isInlineContent) return 0f
        var total = 0f
        fun walk(n: Node, linkHref: String?) {
            when (n) {
                is TextNode -> {
                    val style = textStyleForElement(n.parent ?: node, linkHref)
                    val paint = FontCache.paintFor(style)
                    for (word in n.text.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                        total += paint.measureText(word) + paint.measureText(" ")
                    }
                }
                is ElementNode -> {
                    if (isSkipped(n) || n.tag == "br" || n.tag == "img") return
                    val href = if (n.tag == "a") n.attr("href") else linkHref
                    for (c in n.children) walk(c, href)
                }
            }
        }
        walk(node, null)
        return total
    }

    // ---- Inline text flow ----

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

        if (mode == LayoutMode.BLOCK || mode == LayoutMode.FLEX || mode == LayoutMode.GRID) {
            for (c in normalChildren) c.paint(cmds)
            for (c in positionedChildren.sortedBy { it.zIndex }) c.paint(cmds)
        } else {
            cmds.addAll(inlineDisplay)
        }
    }
}
