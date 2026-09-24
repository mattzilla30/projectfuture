package com.projectfuture.browser.layout

import com.projectfuture.browser.css.CssParser
import com.projectfuture.browser.css.computeStyles
import com.projectfuture.browser.html.ElementNode
import com.projectfuture.browser.html.HtmlParser
import com.projectfuture.browser.html.walkElements
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for real bugs found in LayoutBox.kt's text/inline-layout
 * sections (line-breaking, `<br>` handling, and the RTL paragraph-direction
 * heuristic). These deliberately avoid any content that would reach
 * FontCache.paintFor(): a plain JVM unit test's android.graphics.Paint
 * throws "not mocked" the moment any of its methods (e.g. setTextSize) are
 * called - see LayoutBoxFlexGridTest's class doc for the same constraint
 * affecting DrawRect-based tests. So bugs in the actual text-measurement
 * path are exercised through the pure helper functions LayoutBox.kt
 * extracts for exactly this reason ([sumLineFragmentWidths],
 * [resolveTextAlign]), and the `<br>`-only-paragraph bug is exercised
 * through a real (Paint-free, since it has no text) layout.
 */
class LayoutBoxTextTest {

    // ---- Bug: a <br> landing on an empty line buffer was silently dropped ----

    /**
     * A lone `<br>` should still occupy one line's height (browsers render
     * a blank line), but flushLine() no-ops on an empty buffer and <br>
     * used to call flushLine() directly with no fallback - so a
     * `<br>`-only paragraph laid out to a height of exactly 0.
     */
    /** [BlockLayout.height] (content-box only) so a UA/default `<p>` margin doesn't skew the comparison. */
    private fun brOnlyParagraphHeight(html: String): Float {
        val root = HtmlParser(html).parse()
        computeStyles(root, CssParser("body{margin:0}").parseRules())
        var p: ElementNode? = null
        root.walkElements { if (it.tag == "p") p = it }
        val bl = BlockLayout(p!!, 0f, 0f, 300f, PositionContext(0f, 0f, 300f, 1000f), inheritedFixed = false)
        bl.layout()
        return bl.height
    }

    @Test
    fun soleBrStillOccupiesOneLineHeight() {
        assertEquals(16f * 1.25f, brOnlyParagraphHeight("<p><br></p>"), 0.01f)
    }

    /**
     * Two consecutive `<br>`s should produce a taller box than one (a blank
     * line between two breaks) - previously they collapsed to the exact
     * same (zero) height as a single `<br>`, since the second `<br>` also
     * landed on an already-empty buffer.
     */
    @Test
    fun consecutiveBrsEachAdvanceTheLine() {
        val oneBr = brOnlyParagraphHeight("<p><br></p>")
        val twoBrs = brOnlyParagraphHeight("<p><br><br></p>")
        assertEquals(oneBr * 2, twoBrs, 0.01f)
    }

    // ---- Bug: trailing whitespace at a line-break point inflated the line's content width ----

    @Test
    fun trailingSpaceOnLastFragmentExcludedFromLineWidth() {
        // "aa " + "bb" (trailing space only on the non-last fragment counts;
        // the last fragment's own trailing space must not, since nothing
        // after it is actually on this line).
        val widths = listOf(10f, 8f)
        val trailingSpace = listOf(true, true) // every word carries trailingSpace=true in this engine (see recurse())
        val spaceWidths = listOf(4f, 4f)
        val total = sumLineFragmentWidths(widths, trailingSpace, spaceWidths)
        // Old (buggy) behavior summed to 10 + 4 + 8 + 4 = 26.
        assertEquals(10f + 4f + 8f, total, 0.01f)
    }

    @Test
    fun trailingSpaceOnNonLastFragmentStillCounts() {
        val widths = listOf(10f, 8f, 6f)
        val trailingSpace = listOf(true, true, true)
        val spaceWidths = listOf(4f, 4f, 4f)
        val total = sumLineFragmentWidths(widths, trailingSpace, spaceWidths)
        // First two fragments' trailing spaces are real inter-word gaps on
        // this line; only the last fragment's is a phantom end-of-line one.
        assertEquals(10f + 4f + 8f + 4f + 6f, total, 0.01f)
    }

    @Test
    fun singleFragmentLineExcludesItsTrailingSpace() {
        val total = sumLineFragmentWidths(listOf(12f), listOf(true), listOf(4f))
        assertEquals(12f, total, 0.01f)
    }

    // ---- Bug: an RTL paragraph with no explicit text-align defaulted to "left" ----

    @Test
    fun rtlParagraphWithNoExplicitAlignDefaultsToRight() {
        // A pure-RTL paragraph (e.g. Arabic/Hebrew) with no author `text-align`
        // must default to "right" - the same way real browsers align RTL
        // block content to its start edge by default - not "left", which
        // would flush the (already word-reversed) line against the wrong
        // physical edge of the box.
        assertEquals("right", resolveTextAlign(null, rtlContext = true))
    }

    @Test
    fun ltrParagraphWithNoExplicitAlignStaysLeft() {
        assertEquals("left", resolveTextAlign(null, rtlContext = false))
    }

    @Test
    fun explicitTextAlignLeftInRtlContextFlipsToRight() {
        // "text-align: left" in an RTL paragraph means "start edge", i.e. right.
        assertEquals("right", resolveTextAlign("left", rtlContext = true))
    }

    @Test
    fun explicitTextAlignIsOtherwiseHonoredVerbatim() {
        assertEquals("center", resolveTextAlign("center", rtlContext = true))
        assertEquals("right", resolveTextAlign("right", rtlContext = false))
        assertEquals("left", resolveTextAlign("left", rtlContext = false))
    }
}
