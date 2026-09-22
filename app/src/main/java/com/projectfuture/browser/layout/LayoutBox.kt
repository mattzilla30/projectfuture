package com.projectfuture.browser.layout

import android.graphics.Color
import com.projectfuture.browser.css.parseCssColor
import com.projectfuture.browser.css.parsePx
import com.projectfuture.browser.css.resolveBoxMetrics
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
 * Each block box now carries a real CSS box model (margin/border/padding,
 * box-sizing, explicit width/height) via [resolveBoxMetrics]. Sibling
 * vertical margins are summed rather than collapsed - see the note on
 * BoxMetrics for why that's a deliberate, documented simplification.
 */
private const val LINE_LEADING = 1.25f

private val SKIPPED_TAGS = setOf("script", "style", "head", "title", "meta", "link", "noscript")

private enum class LayoutMode { BLOCK, INLINE }

class DocumentLayout(private val root: ElementNode) {
    var width = 0f
        private set
    var height = 0f
        private set

    fun layout(availableWidth: Float): List<DisplayCommand> {
        width = availableWidth
        val child = BlockLayout(root, containingX = 0f, containingY = 0f, containingWidth = availableWidth)
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
    private val containingWidth: Float
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

    private val children = ArrayList<BlockLayout>()
    private val inlineDisplay = ArrayList<DisplayCommand>()
    private var mode = LayoutMode.INLINE

    private var cursorX = 0f
    private var cursorY = 0f
    private val lineBuffer = ArrayList<WordFragment>()

    private class WordFragment(val text: String, val xOffset: Float, val style: TextStyle)

    fun layout() {
        val currentColor = parseCssColor(node.style["color"]) ?: Color.BLACK
        val fontSizePx = parsePx(node.style["font-size"]) ?: 16f
        val metrics = resolveBoxMetrics(node.style, containingWidth, currentColor, fontSizePx)

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

        val marginBoxX = containingX + metrics.marginLeft
        val borderBoxX = marginBoxX + borderLeft
        x = borderBoxX + paddingLeft
        y = containingY + marginTop + borderTop + paddingTop

        val availableContentWidth = containingWidth - metrics.marginLeft - metrics.marginRight -
            borderLeft - borderRight - paddingLeft - paddingRight
        width = metrics.explicitContentWidth ?: availableContentWidth.coerceAtLeast(0f)

        mode = layoutMode()
        if (mode == LayoutMode.BLOCK) {
            var cursor = y
            for (childNode in node.children) {
                if (childNode !is ElementNode || isSkipped(childNode)) continue
                val bl = BlockLayout(childNode, x, cursor, width)
                bl.layout()
                children.add(bl)
                cursor += bl.outerHeight
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

        outerHeight = marginTop + borderTop + paddingTop + height + paddingBottom + borderBottom + marginBottom
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
                    boxBottom = y + baseline + fm.descent
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
            cmds.add(DrawRect(borderBoxLeft, borderBoxTop, borderBoxRight, borderBoxBottom, bg))
        }
        if (borderTop > 0f) cmds.add(DrawRect(borderBoxLeft, borderBoxTop, borderBoxRight, borderBoxTop + borderTop, borderColorTop))
        if (borderBottom > 0f) cmds.add(DrawRect(borderBoxLeft, borderBoxBottom - borderBottom, borderBoxRight, borderBoxBottom, borderColorBottom))
        if (borderLeft > 0f) cmds.add(DrawRect(borderBoxLeft, borderBoxTop, borderBoxLeft + borderLeft, borderBoxBottom, borderColorLeft))
        if (borderRight > 0f) cmds.add(DrawRect(borderBoxRight - borderRight, borderBoxTop, borderBoxRight, borderBoxBottom, borderColorRight))

        if (mode == LayoutMode.BLOCK) {
            for (c in children) c.paint(cmds)
        } else {
            cmds.addAll(inlineDisplay)
        }
    }
}
