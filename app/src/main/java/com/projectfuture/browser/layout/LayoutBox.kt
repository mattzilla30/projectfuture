package com.projectfuture.browser.layout

import com.projectfuture.browser.css.parseCssColor
import com.projectfuture.browser.html.ElementNode
import com.projectfuture.browser.html.HtmlParser
import com.projectfuture.browser.html.Node
import com.projectfuture.browser.html.TextNode

/**
 * The box tree + line-breaking layout engine. Two layout modes, chosen per
 * box the way real browsers approximate it for a simple engine: BLOCK
 * (children stack vertically, each full-width) or INLINE (children are text
 * flowed into wrapped, baseline-aligned lines). This produces the absolute
 * page-coordinate DisplayCommand list that BrowserView paints. There is no
 * box-model margin/padding/border here by design - only a fixed page
 * margin and inter-block gap for readability.
 */
private const val PAGE_PADDING = 16f
private const val BLOCK_GAP = 10f
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
        val contentWidth = (availableWidth - PAGE_PADDING * 2).coerceAtLeast(1f)
        val child = BlockLayout(root, x = PAGE_PADDING, y = PAGE_PADDING, width = contentWidth)
        child.layout()
        height = child.height + PAGE_PADDING * 2
        val cmds = ArrayList<DisplayCommand>()
        child.paint(cmds)
        return cmds
    }
}

class BlockLayout(
    val node: ElementNode,
    val x: Float,
    val y: Float,
    val width: Float
) {
    var height = 0f
        private set

    private val children = ArrayList<BlockLayout>()
    private val inlineDisplay = ArrayList<DisplayCommand>()
    private var mode = LayoutMode.INLINE

    private var cursorX = 0f
    private var cursorY = 0f
    private val lineBuffer = ArrayList<WordFragment>()

    private class WordFragment(val text: String, val xOffset: Float, val style: TextStyle)

    fun layout() {
        mode = layoutMode()
        if (mode == LayoutMode.BLOCK) {
            var childY = y
            for (childNode in node.children) {
                if (childNode !is ElementNode || isSkipped(childNode)) continue
                val bl = BlockLayout(childNode, x, childY, width)
                bl.layout()
                children.add(bl)
                childY += bl.height + BLOCK_GAP
            }
            height = if (children.isEmpty()) 0f else (childY - y - BLOCK_GAP).coerceAtLeast(0f)
        } else {
            cursorX = 0f
            cursorY = 0f
            lineBuffer.clear()
            inlineDisplay.clear()
            recurse(node, currentLinkHref = null)
            flushLine()
            height = cursorY
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
                    boxBottom = y + baseline + fm.descent
                )
            )
        }
        cursorY = (baseline + maxDescent) + (maxAscent + maxDescent) * (LINE_LEADING - 1f)
        lineBuffer.clear()
        cursorX = 0f
    }

    fun paint(cmds: MutableList<DisplayCommand>) {
        parseCssColor(node.style["background-color"])?.let { bg ->
            cmds.add(DrawRect(x, y, x + width, y + height, bg))
        }
        if (mode == LayoutMode.BLOCK) {
            for (c in children) c.paint(cmds)
        } else {
            cmds.addAll(inlineDisplay)
        }
    }
}
