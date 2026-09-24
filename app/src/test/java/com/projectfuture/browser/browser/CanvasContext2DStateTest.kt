package com.projectfuture.browser.browser

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * CanvasContext2D wraps android.graphics.Canvas/Path/Bitmap, none of which work in plain
 * JUnit here (no Robolectric - every android.graphics.* call throws "not mocked", including
 * just constructing a Canvas/Bitmap). CanvasStyleStack and computeArcGeometry were pulled
 * out as pure, Android-free logic specifically so the save()/restore() state-stack bug and
 * the arc() angle math can be verified directly.
 */
class CanvasContext2DStateTest {

    @Test
    fun saveThenRestoreRoundTripsAllStyleFields() {
        // Real bug: save()/restore() used to delegate straight to Canvas.save()/restore(),
        // which only covers the transform+clip stack - fillStyle/strokeStyle/lineWidth/font/
        // globalAlpha were plain fields never touched by save/restore at all, so a script
        // doing ctx.save(); ctx.fillStyle = 'red'; ...; ctx.restore() would find fillStyle
        // still 'red' afterwards instead of restored to whatever it was before.
        val stack = CanvasStyleStack()
        val before = CanvasStyleSnapshot(fillColor = 0xFF0000, strokeColor = 0x00FF00, lineWidthPx = 2f, fontSizePx = 12f, globalAlphaValue = 0.5f)
        stack.push(before)
        val after = stack.pop()
        assertEquals(before, after)
    }

    @Test
    fun restoreOnEmptyStackReturnsNullInsteadOfThrowing() {
        // Real bug: calling ctx.restore() with no matching ctx.save() used to call
        // Canvas.restore() directly, which throws IllegalStateException ("Underflow in
        // restore/restoreToCount") - a script bug in page JS (an extra restore()) would crash
        // the whole render rather than being a harmless no-op, which is what real browsers do.
        val stack = CanvasStyleStack()
        assertNull(stack.pop())
        assertEquals(0, stack.depth)
    }

    @Test
    fun nestedSaveRestoreIsLastInFirstOut() {
        val stack = CanvasStyleStack()
        val s1 = CanvasStyleSnapshot(0x111111, 0x222222, 1f, 10f, 1f)
        val s2 = CanvasStyleSnapshot(0x333333, 0x444444, 3f, 14f, 0.3f)
        stack.push(s1)
        stack.push(s2)
        assertEquals(2, stack.depth)
        assertEquals(s2, stack.pop())
        assertEquals(s1, stack.pop())
        assertNull(stack.pop())
    }

    @Test
    fun arcClockwiseShortWay() {
        val g = computeArcGeometry(cx = 0f, cy = 0f, r = 10f, startAngleRad = 0f, endAngleRad = (Math.PI / 2).toFloat(), anticlockwise = false)!!
        assertArrayEquals(floatArrayOf(-10f, -10f, 10f, 10f, 0f, 90f), g, 0.001f)
    }

    @Test
    fun arcClockwiseWhenEndIsBehindStartGoesTheLongWayAround() {
        // start=90deg, end=0deg, clockwise: must NOT take the naive -90deg shortcut; it has to
        // sweep all the way around (+270deg) to keep going clockwise from 90 to 0/360.
        val g = computeArcGeometry(cx = 0f, cy = 0f, r = 5f, startAngleRad = (Math.PI / 2).toFloat(), endAngleRad = 0f, anticlockwise = false)!!
        assertEquals(270f, g[5], 0.01f)
    }

    @Test
    fun arcAnticlockwiseWhenEndIsAheadOfStartGoesTheLongWayAroundNegative() {
        val g = computeArcGeometry(cx = 0f, cy = 0f, r = 5f, startAngleRad = 0f, endAngleRad = (Math.PI / 2).toFloat(), anticlockwise = true)!!
        assertEquals(-270f, g[5], 0.01f)
    }

    @Test
    fun negativeRadiusRejectedInsteadOfProducingInvertedOvalBounds() {
        // Real bug: a negative radius used to flow straight into
        // path.addArc(cx - r, cy - r, cx + r, cy + r, ...) with r < 0, producing an inverted
        // (left > right) oval rect and silently garbage/mirrored geometry instead of the
        // IndexSizeError a real browser throws for a negative radius.
        assertNull(computeArcGeometry(cx = 0f, cy = 0f, r = -5f, startAngleRad = 0f, endAngleRad = 1f, anticlockwise = false))
    }

    @Test
    fun zeroRadiusIsAllowedAsADegenerateNoOpArc() {
        val g = computeArcGeometry(cx = 3f, cy = 4f, r = 0f, startAngleRad = 0f, endAngleRad = 1f, anticlockwise = false)!!
        assertArrayEquals(floatArrayOf(3f, 4f, 3f, 4f, 0f, g[5]), g, 0.001f)
    }
}
