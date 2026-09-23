package com.projectfuture.browser.layout

import android.graphics.Bitmap
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
import com.projectfuture.browser.css.resolveTranslate
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
 *
 * `<img>` renders as an inline, baseline-aligned replaced element sized
 * from CSS width/height (falling back to the decoded bitmap's own pixel
 * size, aspect-ratio-preserving if only one axis is set). Decoded bitmaps
 * come from Tab's collectAndDecodeImages via the module-level
 * [currentImages] map. `data:` URIs and `srcset`/`sizes` aren't handled;
 * a broken/missing image is just skipped (no alt-text or broken-image icon).
 *
 * `<svg>` is rasterized to a bitmap up front by SvgRenderer (see its class
 * doc for the bounded shape/path support) and then flows through this
 * exact same `<img>` inline-image path. `<canvas>` works the same way:
 * `getContext('2d')` (CanvasContext2D) draws onto a Bitmap that a script
 * mutates directly, and since that Bitmap is the same mutable object the
 * display list already points to, a later re-layout (e.g. a setInterval
 * tick) picks up newly-drawn pixels automatically.
 *
 * `transform: translate()`/`translateX()`/`translateY()` folds into an
 * element's position the same way `position: relative`'s offset does.
 * `scale`/`rotate`/`skew` and animated `transition`/`@keyframes` are not
 * implemented - see Transform.kt for why.
 *
 * Text direction and line-breaking are approximated, not spec-complete:
 * CJK characters (which don't use spaces) each get their own break
 * opportunity instead of forming one unbreakable token (see
 * tokenizeForLineBreaking) - without this a CJK paragraph would never
 * wrap. A paragraph's overall direction is guessed from its first
 * strongly-directional character (detectParagraphIsRtl, mirroring HTML's
 * `dir="auto"`), and RTL paragraphs lay their words out in reverse; this is
 * not the full Unicode Bidirectional Algorithm (UAX #9), so a single
 * paragraph mixing RTL and LTR runs won't get each run's direction
 * resolved independently. `text-align: left/right/center` is honored;
 * `justify` falls back to left.
 *
 * Table support (a literal `<table>` tag, regardless of any `display`
 * override) handles `<tr>`/`<td>`/`<th>` nested directly or via `<thead>`/
 * `<tbody>`/`<tfoot>`, and `colspan`. Column widths come from the first
 * explicit-width cell found per column, with leftover width split evenly
 * across the rest. `rowspan`, `border-collapse`, and cell vertical-align
 * (cells are always top-aligned) aren't implemented.
 *
 * Float support is scoped to the common case - a floated element and the
 * normal-flow content that should wrap around it as DIRECT siblings under
 * the same block parent (e.g. `<div><img style="float:left"><p>...</p></div>`).
 * Floats don't propagate into deeper descendants, so text several levels
 * further down won't wrap around an ancestor's sibling float. `clear`
 * pushes a later sibling below the relevant float(s). A float's width, when
 * not explicit, is approximated the same way as an auto flex-basis
 * (unwrapped text width, or a fallback fraction of the container) - not
 * true shrink-to-fit.
 */
private const val LINE_LEADING = 1.25f

private val SKIPPED_TAGS = setOf("script", "style", "head", "title", "meta", "link", "noscript")

private enum class LayoutMode { BLOCK, INLINE, FLEX, GRID, TABLE }

/** The containing block used to resolve absolute/fixed descendants' geometry. */
data class PositionContext(
    val containerX: Float,
    val containerY: Float,
    val containerWidth: Float,
    val containerHeight: Float
)

/** A floated element's page-coordinate exclusion zone, used to narrow inline text that wraps around it. */
data class FloatBox(val left: Float, val right: Float, val top: Float, val bottom: Float)

/**
 * Decoded images for the current document, keyed by their `<img>` element.
 * Set once per [DocumentLayout.layout] call and read by BlockLayout's
 * inline flow. A plain module-level var rather than a constructor
 * parameter threaded through every BlockLayout (there are many
 * constructor call sites across block/flex/grid/table/float layout) is
 * safe here because layout always runs as one synchronous pass on a
 * single thread - there's no concurrent or reentrant layout to race with.
 */
private var currentImages: Map<ElementNode, Bitmap> = emptyMap()

class DocumentLayout(private val root: ElementNode) {
    var width = 0f
        private set
    var height = 0f
        private set

    fun layout(availableWidth: Float, availableHeight: Float, images: Map<ElementNode, Bitmap> = emptyMap()): List<DisplayCommand> {
        currentImages = images
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
    private val forcedHeight: Float? = null,
    private val floats: List<FloatBox> = emptyList()
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
    private val lineBuffer = ArrayList<LineFragment>()
    private var rtlContext = false
    private var textAlign = "left"

    private sealed class LineFragment(val trailingSpace: Boolean, val sourceElement: ElementNode?)
    private class TextFragment(val text: String, val style: TextStyle, trailingSpace: Boolean, sourceElement: ElementNode?) :
        LineFragment(trailingSpace, sourceElement)
    private class ImageFragment(val bitmap: Bitmap, val imgWidth: Float, val imgHeight: Float, sourceElement: ElementNode?) :
        LineFragment(false, sourceElement)

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

        // transform: translate() applies to any box, not just positioned
        // ones, and folds in the same way relative offsets do above -
        // see Transform.kt for what's (deliberately) not supported here.
        val translate = resolveTranslate(node.style["transform"], width, metrics.explicitContentHeight, fontSizePx)
        x += translate.dx
        y += translate.dy

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
                var leftFloatBottom = y
                var rightFloatBottom = y
                val activeFloats = ArrayList<FloatBox>()
                for (childNode in node.children) {
                    if (childNode !is ElementNode || isSkipped(childNode)) continue
                    val childPosition = childNode.style["position"] ?: "static"
                    val childRemoved = childPosition == "absolute" || childPosition == "fixed"
                    val floatSide = childNode.style["float"]

                    if (!childRemoved && (floatSide == "left" || floatSide == "right")) {
                        val childFontSize = parsePx(childNode.style["font-size"]) ?: 16f
                        val m = resolveBoxMetrics(childNode.style, width, Color.BLACK, childFontSize)
                        val floatContentWidth = m.explicitContentWidth
                            ?: measureNaturalWidth(childNode).let { if (it > 0f) it else width * 0.4f }
                        val outerWidth = m.marginLeft + m.borderLeft + m.paddingLeft +
                            floatContentWidth + m.paddingRight + m.borderRight + m.marginRight
                        val floatTop = if (floatSide == "left") maxOf(cursor, leftFloatBottom) else maxOf(cursor, rightFloatBottom)
                        val floatContainingX = if (floatSide == "left") x else x + width - outerWidth
                        val bl = BlockLayout(childNode, floatContainingX, floatTop, width, childPosContext, fixedContext, forcedWidth = floatContentWidth)
                        bl.layout()
                        val floatBottom = floatTop + bl.outerHeight
                        val floatEntry = if (floatSide == "left") {
                            leftFloatBottom = floatBottom
                            FloatBox(x, x + outerWidth, floatTop, floatBottom)
                        } else {
                            rightFloatBottom = floatBottom
                            FloatBox(x + width - outerWidth, x + width, floatTop, floatBottom)
                        }
                        activeFloats.add(floatEntry)
                        normalChildren.add(bl) // painted, but outerHeight excluded from cursor advancement below
                        continue
                    }

                    val clear = childNode.style["clear"]
                    if (!childRemoved && (clear == "left" || clear == "both")) cursor = maxOf(cursor, leftFloatBottom)
                    if (!childRemoved && (clear == "right" || clear == "both")) cursor = maxOf(cursor, rightFloatBottom)

                    val bl = BlockLayout(
                        childNode, x, cursor, width, childPosContext, fixedContext,
                        floats = if (childRemoved) emptyList() else activeFloats
                    )
                    bl.layout()
                    if (childRemoved) {
                        positionedChildren.add(bl)
                    } else {
                        normalChildren.add(bl)
                        cursor += bl.outerHeight
                    }
                }
                val floatExtent = maxOf(leftFloatBottom, rightFloatBottom) - y
                height = forcedHeight ?: (metrics.explicitContentHeight ?: maxOf(cursor - y, floatExtent).coerceAtLeast(0f))
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
            LayoutMode.TABLE -> {
                val naturalHeight = layoutTable(childPosContext)
                height = forcedHeight ?: (metrics.explicitContentHeight ?: naturalHeight)
            }
            LayoutMode.INLINE -> {
                cursorX = 0f
                cursorY = 0f
                lineBuffer.clear()
                inlineDisplay.clear()
                rtlContext = detectParagraphIsRtl(node)
                textAlign = node.style["text-align"]?.let { if (it == "left" && rtlContext) "right" else it } ?: "left"
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
        if (node.tag == "table") return LayoutMode.TABLE
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

    // ---- Table ----

    private class TableCell(val node: ElementNode, val colSpan: Int)

    private fun collectTableRows(table: ElementNode): List<ElementNode> {
        val rows = ArrayList<ElementNode>()
        for (child in table.children) {
            if (child !is ElementNode || isSkipped(child)) continue
            when (child.tag) {
                "tr" -> rows.add(child)
                "thead", "tbody", "tfoot" -> for (grandchild in child.children) {
                    if (grandchild is ElementNode && grandchild.tag == "tr" && !isSkipped(grandchild)) rows.add(grandchild)
                }
            }
        }
        return rows
    }

    /**
     * Column widths: explicit `width` on a colspan=1 cell wins for that
     * column (first one found), remaining columns split the leftover width
     * evenly. `rowspan` isn't implemented (would need a 2D cell-occupancy
     * grid) - a rowspan cell is treated as spanning just its own row, so
     * later rows won't have that column's slot reserved.
     */
    private fun layoutTable(childPosContext: PositionContext): Float {
        val rows = collectTableRows(node)
        if (rows.isEmpty()) return 0f

        val rowCells = rows.map { row ->
            row.children.filterIsInstance<ElementNode>()
                .filter { (it.tag == "td" || it.tag == "th") && !isSkipped(it) }
                .map { TableCell(it, it.attr("colspan")?.toIntOrNull()?.coerceAtLeast(1) ?: 1) }
        }
        val colCount = rowCells.maxOf { r -> r.sumOf { it.colSpan } }.coerceAtLeast(1)

        val colWidths = FloatArray(colCount)
        val colExplicit = BooleanArray(colCount)
        for (row in rowCells) {
            var col = 0
            for (cell in row) {
                if (cell.colSpan == 1 && col < colCount && !colExplicit[col]) {
                    val fontSizePx = parsePx(cell.node.style["font-size"]) ?: 16f
                    val m = resolveBoxMetrics(cell.node.style, width, Color.BLACK, fontSizePx)
                    m.explicitContentWidth?.let {
                        colWidths[col] = it
                        colExplicit[col] = true
                    }
                }
                col += cell.colSpan
            }
        }
        val explicitSum = colWidths.filterIndexed { i, _ -> colExplicit[i] }.sum()
        val flexibleCount = colExplicit.count { !it }
        val flexWidth = if (flexibleCount > 0) ((width - explicitSum) / flexibleCount).coerceAtLeast(0f) else 0f
        for (i in 0 until colCount) if (!colExplicit[i]) colWidths[i] = flexWidth

        val colX = FloatArray(colCount)
        var acc = x
        for (i in 0 until colCount) {
            colX[i] = acc
            acc += colWidths[i]
        }

        var rowTop = y
        for (row in rowCells) {
            var col = 0
            val laidOutRow = ArrayList<BlockLayout>()
            for (cell in row) {
                if (col >= colCount) break
                val span = cell.colSpan.coerceAtMost(colCount - col)
                val cellWidth = (0 until span).sumOf { colWidths[col + it].toDouble() }.toFloat()
                val bl = BlockLayout(cell.node, colX[col], rowTop, cellWidth, childPosContext, fixedContext, forcedWidth = cellWidth)
                bl.layout()
                laidOutRow.add(bl)
                col += span
            }
            normalChildren.addAll(laidOutRow)
            val rowHeight = laidOutRow.maxOfOrNull { it.outerHeight } ?: 0f
            rowTop += rowHeight
        }
        return (rowTop - y).coerceAtLeast(0f)
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
                    if (isSkipped(n) || n.tag == "br" || n.tag == "img" || n.tag == "svg" || n.tag == "canvas") return
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
                val owner = n.parent ?: node
                val style = textStyleForElement(owner, currentLinkHref)
                for (word in n.text.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                    val tokens = tokenizeForLineBreaking(word)
                    for ((idx, token) in tokens.withIndex()) {
                        addWord(token, style, trailingSpace = idx == tokens.size - 1, sourceElement = owner)
                    }
                }
            }
            is ElementNode -> {
                if (isSkipped(n)) return
                if (n.tag == "br") {
                    flushLine()
                    return
                }
                if (n.tag == "img" || n.tag == "svg" || n.tag == "canvas") {
                    currentImages[n]?.let { addImage(it, n) }
                    return
                }
                val linkHref = if (n.tag == "a") n.attr("href") else currentLinkHref
                for (child in n.children) recurse(child, linkHref)
            }
        }
    }

    /**
     * CJK scripts don't use spaces between "words", so treating whitespace
     * as the only break opportunity would make e.g. a whole Chinese
     * paragraph one unbreakable token that overflows the screen. This
     * splits a whitespace-delimited chunk into individual CJK characters
     * (each its own break opportunity, packed with no artificial space)
     * while keeping runs of non-CJK characters (e.g. embedded Latin/digits)
     * together. It doesn't implement the full UAX #14 line-breaking class
     * rules (e.g. never breaking before closing punctuation).
     */
    private fun tokenizeForLineBreaking(word: String): List<String> {
        if (word.none { isCjkChar(it) }) return listOf(word)
        val tokens = ArrayList<String>()
        val buf = StringBuilder()
        for (c in word) {
            if (isCjkChar(c)) {
                if (buf.isNotEmpty()) { tokens.add(buf.toString()); buf.setLength(0) }
                tokens.add(c.toString())
            } else {
                buf.append(c)
            }
        }
        if (buf.isNotEmpty()) tokens.add(buf.toString())
        return tokens
    }

    private fun isCjkChar(c: Char): Boolean {
        val code = c.code
        return code in 0x4E00..0x9FFF || // CJK Unified Ideographs
            code in 0x3040..0x30FF || // Hiragana + Katakana
            code in 0xAC00..0xD7A3 || // Hangul syllables
            code in 0x3400..0x4DBF || // CJK Extension A
            code in 0xFF00..0xFFEF // Halfwidth/fullwidth forms
    }

    private fun isStrongRtlChar(c: Char): Boolean {
        val code = c.code
        return code in 0x0590..0x08FF || // Hebrew, Arabic, Syriac, Thaana, N'Ko, Arabic Extended
            code in 0xFB1D..0xFDFF || // Hebrew/Arabic presentation forms
            code in 0xFE70..0xFEFF
    }

    /**
     * Approximates HTML's `dir="auto"` heuristic: the paragraph's direction
     * is decided by its first strongly-directional character. This is not
     * the full Unicode Bidirectional Algorithm (UAX #9) - mixed-direction
     * runs within one paragraph aren't resolved per-run, the whole
     * paragraph's lines are just reversed word-by-word if it reads as RTL.
     */
    private fun detectParagraphIsRtl(root: ElementNode): Boolean {
        fun scan(n: Node): Boolean? {
            when (n) {
                is TextNode -> for (c in n.text) {
                    if (isStrongRtlChar(c)) return true
                    if (c.isLetter()) return false
                }
                is ElementNode -> for (child in n.children) {
                    scan(child)?.let { return it }
                }
            }
            return null
        }
        return scan(root) ?: false
    }

    private var lineLeftInset = 0f
    private var lineRightInset = 0f

    /** Left/right inset at a given page-y from any active floats, so text wraps around them. */
    private fun floatInsetsAt(pageY: Float): Pair<Float, Float> {
        var leftInset = 0f
        var rightInset = 0f
        for (f in floats) {
            if (pageY < f.top || pageY >= f.bottom) continue
            if (f.left <= x) leftInset = maxOf(leftInset, f.right - x) else rightInset = maxOf(rightInset, (x + width) - f.left)
        }
        return leftInset.coerceAtLeast(0f) to rightInset.coerceAtLeast(0f)
    }

    private fun addWord(word: String, style: TextStyle, trailingSpace: Boolean = true, sourceElement: ElementNode? = null) {
        addFragment(TextFragment(word, style, trailingSpace, sourceElement), FontCache.paintFor(style).measureText(word))
    }

    /**
     * `<img>` sizing: explicit CSS width/height win; if only one axis is
     * set, the other scales to preserve aspect ratio; with neither set, the
     * decoded bitmap's own pixel dimensions are used as CSS px directly
     * (no DPI-aware intrinsic sizing, e.g. no `srcset`/`sizes` support).
     * The image is baseline-aligned, i.e. its bottom edge sits on the
     * text baseline - the common default for an inline image.
     */
    private fun addImage(bitmap: Bitmap, node: ElementNode) {
        val fontSizePx = parsePx(node.style["font-size"]) ?: 16f
        val cssWidth = node.style["width"]?.let { lengthValue(it, width, fontSizePx) }
        val cssHeight = node.style["height"]?.let { lengthValue(it, 0f, fontSizePx) }
        val intrinsicW = bitmap.width.toFloat().coerceAtLeast(1f)
        val intrinsicH = bitmap.height.toFloat().coerceAtLeast(1f)
        val (imgW, imgH) = when {
            cssWidth != null && cssHeight != null -> cssWidth to cssHeight
            cssWidth != null -> cssWidth to (intrinsicH * (cssWidth / intrinsicW))
            cssHeight != null -> (intrinsicW * (cssHeight / intrinsicH)) to cssHeight
            else -> intrinsicW to intrinsicH
        }
        addFragment(ImageFragment(bitmap, imgW, imgH, node), imgW)
    }

    private fun addFragment(fragment: LineFragment, fragmentWidth: Float) {
        if (lineBuffer.isEmpty()) {
            val (li, ri) = floatInsetsAt(y + cursorY)
            lineLeftInset = li
            lineRightInset = ri
        }
        val spaceWidth = if (fragment.trailingSpace) spaceWidthFor(fragment) else 0f
        val effectiveWidth = width - lineLeftInset - lineRightInset
        if (cursorX > 0f && cursorX + fragmentWidth > effectiveWidth) {
            flushLine()
            val (li, ri) = floatInsetsAt(y + cursorY)
            lineLeftInset = li
            lineRightInset = ri
        }
        lineBuffer.add(fragment)
        cursorX += fragmentWidth + spaceWidth
    }

    private fun spaceWidthFor(fragment: LineFragment): Float =
        if (fragment is TextFragment) FontCache.paintFor(fragment.style).measureText(" ") else 0f

    private fun flushLine() {
        if (lineBuffer.isEmpty()) return
        // Positions are computed here (not incrementally in addFragment) so
        // a right-to-left paragraph can lay the same fragments out in
        // reverse order - see detectParagraphIsRtl.
        val fragments = if (rtlContext) lineBuffer.asReversed() else lineBuffer

        var maxAscent = 0f
        var maxDescent = 0f
        val widths = FloatArray(fragments.size)
        for ((i, f) in fragments.withIndex()) {
            when (f) {
                is TextFragment -> {
                    val paint = FontCache.paintFor(f.style)
                    widths[i] = paint.measureText(f.text)
                    val fm = paint.fontMetrics
                    maxAscent = maxOf(maxAscent, -fm.ascent)
                    maxDescent = maxOf(maxDescent, fm.descent)
                }
                is ImageFragment -> {
                    widths[i] = f.imgWidth
                    maxAscent = maxOf(maxAscent, f.imgHeight) // baseline-aligned: sits entirely above the baseline
                }
            }
        }

        var lineContentWidth = 0f
        for ((i, f) in fragments.withIndex()) {
            lineContentWidth += widths[i]
            if (f.trailingSpace) lineContentWidth += spaceWidthFor(f)
        }
        val effectiveWidth = width - lineLeftInset - lineRightInset
        val alignOffset = when (textAlign) {
            "right" -> (effectiveWidth - lineContentWidth).coerceAtLeast(0f)
            "center" -> ((effectiveWidth - lineContentWidth) / 2f).coerceAtLeast(0f)
            else -> 0f
        }

        val baseline = cursorY + maxAscent
        var runningX = alignOffset
        for ((i, f) in fragments.withIndex()) {
            val fragX = x + lineLeftInset + runningX
            when (f) {
                is TextFragment -> {
                    val paint = FontCache.paintFor(f.style)
                    val fm = paint.fontMetrics
                    inlineDisplay.add(
                        DrawText(
                            x = fragX,
                            baselineY = y + baseline,
                            text = f.text,
                            style = f.style,
                            left = fragX,
                            right = fragX + widths[i],
                            boxTop = y + baseline + fm.ascent,
                            boxBottom = y + baseline + fm.descent,
                            fixed = fixedContext,
                            sourceElement = f.sourceElement
                        )
                    )
                }
                is ImageFragment -> {
                    val imgBottom = y + baseline
                    inlineDisplay.add(
                        DrawImage(
                            fragX, imgBottom - f.imgHeight, fragX + widths[i], imgBottom, f.bitmap,
                            fixed = fixedContext, sourceElement = f.sourceElement
                        )
                    )
                }
            }
            runningX += widths[i]
            if (f.trailingSpace) runningX += spaceWidthFor(f)
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
            cmds.add(DrawRect(borderBoxLeft, borderBoxTop, borderBoxRight, borderBoxBottom, bg, fixed = fixedContext, sourceElement = node))
        }
        if (borderTop > 0f) cmds.add(DrawRect(borderBoxLeft, borderBoxTop, borderBoxRight, borderBoxTop + borderTop, borderColorTop, fixed = fixedContext, sourceElement = node))
        if (borderBottom > 0f) cmds.add(DrawRect(borderBoxLeft, borderBoxBottom - borderBottom, borderBoxRight, borderBoxBottom, borderColorBottom, fixed = fixedContext, sourceElement = node))
        if (borderLeft > 0f) cmds.add(DrawRect(borderBoxLeft, borderBoxTop, borderBoxLeft + borderLeft, borderBoxBottom, borderColorLeft, fixed = fixedContext, sourceElement = node))
        if (borderRight > 0f) cmds.add(DrawRect(borderBoxRight - borderRight, borderBoxTop, borderBoxRight, borderBoxBottom, borderColorRight, fixed = fixedContext, sourceElement = node))

        if (mode == LayoutMode.BLOCK || mode == LayoutMode.FLEX || mode == LayoutMode.GRID || mode == LayoutMode.TABLE) {
            for (c in normalChildren) c.paint(cmds)
            for (c in positionedChildren.sortedBy { it.zIndex }) c.paint(cmds)
        } else {
            cmds.addAll(inlineDisplay)
        }
    }
}
