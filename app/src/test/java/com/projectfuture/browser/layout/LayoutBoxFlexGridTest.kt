package com.projectfuture.browser.layout

import com.projectfuture.browser.css.CssParser
import com.projectfuture.browser.css.GridTrackSize
import com.projectfuture.browser.css.computeStyles
import com.projectfuture.browser.css.resolveGridColumnWidths
import com.projectfuture.browser.css.resolveGridContainerProps
import com.projectfuture.browser.html.ElementNode
import com.projectfuture.browser.html.HtmlParser
import com.projectfuture.browser.html.walkElements
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for real bugs found in the flexbox/grid/image-sizing math in
 * LayoutBox.kt and its css/Flexbox.kt + css/Grid.kt helpers.
 */
class LayoutBoxFlexGridTest {

    /**
     * Parses+styles+lays out a fragment, then finds each `id`'d element's *margin-box* left
     * edge (i.e. exactly the main-axis position the flex algorithm placed it at) by reading its
     * border DrawRect - NOT its background-color rect, which android.graphics.Color's real
     * (non-stub) color-name/hex parsing needs and is unavailable in a plain JVM unit test, so
     * background-color silently resolves to null here and paints nothing (see paint()'s
     * `parseCssColor(...)?.let { ... }`). Every item below therefore uses `border: 1px solid`
     * (color omitted, so it falls back to the currentColor constant, no parsing needed) purely
     * as a marker, and margin is 0, so borderBoxLeft == the flex algorithm's own placement x.
     */
    private fun layoutBorderLeftById(html: String, containerWidth: Float = 300f): Map<String, Float> {
        val root = HtmlParser(html).parse()
        val rules = CssParser("body{margin:0}").parseRules()
        computeStyles(root, rules)
        val cmds = DocumentLayout(root).layout(containerWidth, 1000f)
        val byElement = HashMap<ElementNode, Float>()
        for (cmd in cmds) if (cmd is DrawRect) cmd.sourceElement?.let { byElement.putIfAbsent(it, cmd.left) }
        val byId = HashMap<String, Float>()
        root.walkElements { el ->
            val id = el.attr("id")
            if (id != null) byElement[el]?.let { byId[id] = it }
        }
        return byId
    }

    // ---- justify-content: space-between with a single item (off-by-one item count) ----

    @Test
    fun spaceBetweenWithSingleItemStaysAtMainStart() {
        // Per spec, with nothing to distribute "between", a lone item under
        // justify-content: space-between sits flush with the main-start edge,
        // not centered. The container is 300px wide with one 50px-wide item:
        // expected left edge = 0 (flex-start), not 125 (centered).
        val html = """
            <div style="display:flex;justify-content:space-between;width:300px;height:20px">
              <div id="a" style="width:50px;height:20px;border:1px solid"></div>
            </div>
        """.trimIndent()
        val lefts = layoutBorderLeftById(html)
        assertEquals(0f, lefts.getValue("a"), 0.01f)
    }

    // ---- justify-content: space-evenly (previously aliased to space-around) ----

    @Test
    fun spaceEvenlyDistributesGapsEquallyIncludingEdges() {
        // Container 300px wide, two items with a 50px content width + 1px border each side
        // (52px margin-box each) -> outer total 104px, remaining 196px. space-evenly: 3 equal
        // gaps of 196/3 = 65.333px (edges + the one gap between). (space-around would instead
        // give a smaller edge gap and a larger between-gap - see the next test.)
        val html = """
            <div style="display:flex;justify-content:space-evenly;width:300px;height:20px">
              <div id="a" style="width:50px;height:20px;border:1px solid"></div>
              <div id="b" style="width:50px;height:20px;border:1px solid"></div>
            </div>
        """.trimIndent()
        val lefts = layoutBorderLeftById(html)
        val itemOuter = 52f
        val expectedGap = (300f - itemOuter * 2) / 3f
        assertEquals(expectedGap, lefts.getValue("a"), 0.05f)
        assertEquals(expectedGap + itemOuter + expectedGap, lefts.getValue("b"), 0.05f)
    }

    @Test
    fun spaceEvenlyDiffersFromSpaceAround() {
        val htmlEvenly = """
            <div style="display:flex;justify-content:space-evenly;width:300px;height:20px">
              <div id="a" style="width:50px;height:20px;border:1px solid"></div>
              <div id="b" style="width:50px;height:20px;border:1px solid"></div>
            </div>
        """.trimIndent()
        val htmlAround = htmlEvenly.replace("space-evenly", "space-around")
        val evenly = layoutBorderLeftById(htmlEvenly)
        val around = layoutBorderLeftById(htmlAround)
        assertTrue(Math.abs(evenly.getValue("a") - around.getValue("a")) > 1f)
    }

    // ---- grid-template-columns: minmax() previously got shredded by whitespace tokenization ----

    @Test
    fun minmaxTrackIsNotSilentlyDropped() {
        val style = mapOf("grid-template-columns" to "minmax(100px, 1fr) auto")
        val props = resolveGridContainerProps(style, 300f, 16f)
        // Before the fix, "minmax(100px," and "1fr)" each failed to parse as any track and were
        // silently dropped, leaving only the unrelated "auto" token -> a single 1fr column
        // instead of the two declared columns.
        assertEquals(2, props.columns.size)
    }

    @Test
    fun minmaxWithFrMaxResolvesAsFlexible() {
        val style = mapOf("grid-template-columns" to "minmax(50px, 1fr) minmax(50px, 2fr)")
        val props = resolveGridContainerProps(style, 300f, 16f)
        assertEquals(2, props.columns.size)
        val widths = resolveGridColumnWidths(props.columns, 300f, 0f)
        // 1fr + 2fr split 300px leftover space 1:2 -> 100px / 200px.
        assertEquals(100f, widths[0], 0.5f)
        assertEquals(200f, widths[1], 0.5f)
    }

    @Test
    fun minmaxWithFixedBoundsPicksTheLargerBound() {
        val style = mapOf("grid-template-columns" to "minmax(50px, 150px) 1fr")
        val props = resolveGridContainerProps(style, 300f, 16f)
        assertEquals(2, props.columns.size)
        val first = props.columns[0]
        assertTrue(first is GridTrackSize.Fixed)
        assertEquals(150f, (first as GridTrackSize.Fixed).px, 0.01f)
    }

    @Test
    fun repeatWithMinmaxInsideExpandsCorrectly() {
        val style = mapOf("grid-template-columns" to "repeat(3, minmax(0, 1fr))")
        val props = resolveGridContainerProps(style, 300f, 16f)
        assertEquals(3, props.columns.size)
        val widths = resolveGridColumnWidths(props.columns, 300f, 0f)
        assertEquals(100f, widths[0], 0.5f)
        assertEquals(100f, widths[1], 0.5f)
        assertEquals(100f, widths[2], 0.5f)
    }

    // ---- <img> with a percentage height used to collapse the image to 0x0 ----

    @Test
    fun percentageHeightOnImageIsUnresolvedNotZero() {
        // A "%" height has no definite containing height to resolve against; it must fall back
        // to intrinsic sizing (like an unset height does), not resolve against a fake 0 base,
        // which used to silently collapse the image to a 0x0 box.
        assertEquals(null, resolveCssImageHeight("50%", 16f))
        assertEquals(50f, resolveCssImageHeight("50px", 16f))
        assertEquals(null, resolveCssImageHeight(null, 16f))
    }

    @Test
    fun resolveImageSizeFallsBackToIntrinsicWhenHeightUnresolved() {
        val (w, h) = resolveImageSize(cssWidth = null, cssHeight = null, intrinsicW = 200f, intrinsicH = 300f)
        assertEquals(200f, w, 0.01f)
        assertEquals(300f, h, 0.01f)
    }

    // ---- <img width=".."> / height=".."> HTML attributes used to be ignored entirely ----

    @Test
    fun htmlWidthAndHeightAttributesSizeTheImageWhenNoCssIsSet() {
        // The core bug: an <img width="300" height="200"> with no CSS at all fell through
        // to intrinsic (decoded-bitmap-pixel) sizing instead of honoring the attributes -
        // unlike <canvas>/<svg> elsewhere in this codebase, which already read their own
        // width/height attributes.
        val (w, h) = resolveImageBoxSize(
            styleWidth = null, attrWidth = "300",
            styleHeight = null, attrHeight = "200",
            containingWidth = 1000f, fontSizePx = 16f,
            intrinsicW = 50f, intrinsicH = 50f
        )
        assertEquals(300f, w, 0.01f)
        assertEquals(200f, h, 0.01f)
    }

    @Test
    fun cssWidthHeightWinOverHtmlAttributes() {
        val (w, h) = resolveImageBoxSize(
            styleWidth = "150px", attrWidth = "300",
            styleHeight = "100px", attrHeight = "200",
            containingWidth = 1000f, fontSizePx = 16f,
            intrinsicW = 50f, intrinsicH = 50f
        )
        assertEquals(150f, w, 0.01f)
        assertEquals(100f, h, 0.01f)
    }

    @Test
    fun onlyWidthAttributeSetPreservesAspectRatio() {
        // Only the width attribute is set (a common real-world pattern); height must scale
        // to preserve the intrinsic aspect ratio, not default to the raw intrinsic height.
        val (w, h) = resolveImageBoxSize(
            styleWidth = null, attrWidth = "400",
            styleHeight = null, attrHeight = null,
            containingWidth = 1000f, fontSizePx = 16f,
            intrinsicW = 200f, intrinsicH = 100f
        )
        assertEquals(400f, w, 0.01f)
        assertEquals(200f, h, 0.01f)
    }

    @Test
    fun noCssOrAttributesFallsBackToIntrinsicSize() {
        val (w, h) = resolveImageBoxSize(
            styleWidth = null, attrWidth = null,
            styleHeight = null, attrHeight = null,
            containingWidth = 1000f, fontSizePx = 16f,
            intrinsicW = 640f, intrinsicH = 480f
        )
        assertEquals(640f, w, 0.01f)
        assertEquals(480f, h, 0.01f)
    }
}
