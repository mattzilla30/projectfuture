package com.projectfuture.browser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SvgRenderer draws through android.graphics.Path/Canvas/Bitmap, which are unavailable in
 * plain JUnit (no Robolectric in this project - every android.graphics.* call throws "not
 * mocked"). SvgRenderer.parsePathSegments/parseViewBox are pure, Android-free helpers
 * extracted specifically so the `d`-attribute grammar and viewBox math can be verified
 * directly, without needing a real Canvas to check pixels.
 */
class SvgRendererPathTest {

    @Test
    fun implicitLineToAfterMoveToIsNotDropped() {
        // Real bug: "M10,10 20,20 30,30" must mean "M10,10 L20,20 L30,30" - the second and
        // third coordinate pairs have no command letter of their own, which per the SVG path
        // grammar means "repeat the previous command" (and a repeated M/m is L/l). The old
        // parser required every command to start with a letter and `break`ed out of parsing
        // entirely the moment it saw a bare number where it expected one, silently dropping
        // the rest of the path - only the initial moveTo survived.
        val segments = SvgRenderer.parsePathSegments("M10,10 20,20 30,30")
        assertEquals(
            listOf(
                SvgRenderer.PathSegment.MoveTo(10f, 10f),
                SvgRenderer.PathSegment.LineTo(20f, 20f),
                SvgRenderer.PathSegment.LineTo(30f, 30f),
            ),
            segments,
        )
    }

    @Test
    fun implicitRepeatAlsoWorksForNonMoveCommands() {
        // "L" repeats as "L" (only M/m degrades to L/l - every other command just repeats
        // itself for further implicit coordinate groups).
        val segments = SvgRenderer.parsePathSegments("M0,0 L10,0 20,0 30,0")
        assertEquals(
            listOf(
                SvgRenderer.PathSegment.MoveTo(0f, 0f),
                SvgRenderer.PathSegment.LineTo(10f, 0f),
                SvgRenderer.PathSegment.LineTo(20f, 0f),
                SvgRenderer.PathSegment.LineTo(30f, 0f),
            ),
            segments,
        )
    }

    @Test
    fun relativeImplicitMoveRepeatsAccumulateAsRelativeLines() {
        val segments = SvgRenderer.parsePathSegments("m10,10 5,5 5,5")
        assertEquals(
            listOf(
                SvgRenderer.PathSegment.MoveTo(10f, 10f),
                SvgRenderer.PathSegment.LineTo(15f, 15f),
                SvgRenderer.PathSegment.LineTo(20f, 20f),
            ),
            segments,
        )
    }

    @Test
    fun basicCommandLettersStillWork() {
        val segments = SvgRenderer.parsePathSegments("M0,0 H10 V10 L0,10 Z")
        assertEquals(
            listOf(
                SvgRenderer.PathSegment.MoveTo(0f, 0f),
                SvgRenderer.PathSegment.LineTo(10f, 0f),
                SvgRenderer.PathSegment.LineTo(10f, 10f),
                SvgRenderer.PathSegment.LineTo(0f, 10f),
                SvgRenderer.PathSegment.Close,
            ),
            segments,
        )
    }

    @Test
    fun closeReturnsCurrentPointToSubpathStartForFollowingRelativeCommands() {
        val segments = SvgRenderer.parsePathSegments("M0,0 L10,0 L10,10 Z l5,5")
        // After Z, current point resets to (0,0), so the trailing relative lineto lands at (5,5).
        assertEquals(SvgRenderer.PathSegment.LineTo(5f, 5f), segments.last())
    }

    @Test
    fun cubicCurveHandlesAbsoluteAndRelativeForms() {
        val abs = SvgRenderer.parsePathSegments("M0,0 C1,1 2,2 3,3")
        assertEquals(SvgRenderer.PathSegment.CubicTo(1f, 1f, 2f, 2f, 3f, 3f), abs.last())

        val rel = SvgRenderer.parsePathSegments("M10,10 c1,1 2,2 3,3")
        assertEquals(SvgRenderer.PathSegment.CubicTo(11f, 11f, 12f, 12f, 13f, 13f), rel.last())
    }

    @Test
    fun garbageBeforeAnyCommandLetterParsesNothing() {
        // No previous command to implicitly repeat, so this is simply malformed.
        assertEquals(emptyList<SvgRenderer.PathSegment>(), SvgRenderer.parsePathSegments("10,10 20,20"))
    }

    @Test
    fun viewBoxScalesAndTranslatesToOutputSize() {
        val vb = SvgRenderer.parseViewBox("0 0 50 50")
        assertEquals(SvgRenderer.ViewBox(0f, 0f, 50f, 50f), vb)
    }

    @Test
    fun viewBoxWithNonZeroOriginParsesOffsets() {
        val vb = SvgRenderer.parseViewBox("10,20,100,200")
        assertEquals(SvgRenderer.ViewBox(10f, 20f, 100f, 200f), vb)
    }

    @Test
    fun invalidViewBoxRejected() {
        assertNull(SvgRenderer.parseViewBox("0 0 0 100")) // zero width
        assertNull(SvgRenderer.parseViewBox("0 0 -10 100")) // negative height component
        assertNull(SvgRenderer.parseViewBox("0 0 100")) // wrong count
        assertNull(SvgRenderer.parseViewBox(null))
    }
}
