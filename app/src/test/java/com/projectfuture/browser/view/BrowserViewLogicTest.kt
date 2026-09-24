package com.projectfuture.browser.view

import com.projectfuture.browser.layout.DrawText
import com.projectfuture.browser.layout.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BrowserView] itself can't be instantiated in a plain JVM unit test (it's
 * a `FrameLayout` and this project has no Robolectric/emulator), so its
 * scroll-bounds, hit-test and pinch/tap-gate decision logic is exercised
 * here via [BrowserViewLogic], the pure functions it's built from.
 */
class BrowserViewLogicTest {

    private fun style() = TextStyle(16f, false, false, false, -0x1000000, false, false, null, null)

    // ---- maxScroll / clampScroll --------------------------------------------------------------

    @Test fun maxScrollIsZeroWhenContentFitsInTheViewport() {
        assertEquals(0f, BrowserViewLogic.maxScroll(contentHeight = 400f, viewportHeight = 800f))
        assertEquals(0f, BrowserViewLogic.maxScroll(contentHeight = 800f, viewportHeight = 800f))
    }

    @Test fun maxScrollIsContentMinusViewportWhenContentOverflows() {
        assertEquals(200f, BrowserViewLogic.maxScroll(contentHeight = 1000f, viewportHeight = 800f))
    }

    @Test fun clampScrollNeverGoesNegative() {
        assertEquals(0f, BrowserViewLogic.clampScroll(scrollY = -50f, contentHeight = 1000f, viewportHeight = 800f))
    }

    @Test fun clampScrollNeverExceedsMaxScroll() {
        assertEquals(200f, BrowserViewLogic.clampScroll(scrollY = 9999f, contentHeight = 1000f, viewportHeight = 800f))
    }

    @Test fun clampScrollPullsAnOverscrolledPositionBackAfterContentShrinks() {
        // Scrolled to the bottom of a long page (maxScroll = 4200 for a 5000-tall page in an 800-
        // tall viewport)...
        val scrolledToBottom = BrowserViewLogic.clampScroll(scrollY = 4200f, contentHeight = 5000f, viewportHeight = 800f)
        assertEquals(4200f, scrolledToBottom)
        // ...then the page's content shrinks a lot (e.g. a script removed most of the DOM). The
        // old scroll position must be pulled back into the new, much smaller bounds, not left
        // stuck past the new bottom of the page.
        val reclamped = BrowserViewLogic.clampScroll(scrollY = scrolledToBottom, contentHeight = 900f, viewportHeight = 800f)
        assertEquals(100f, reclamped)
    }

    // ---- hitTest --------------------------------------------------------------------------------

    private data class Box(val left: Float, val right: Float, val top: Float, val bottom: Float, val name: String)

    private fun hit(x: Float, y: Float, vararg boxes: Box) =
        BrowserViewLogic.hitTest(boxes.toList(), x, y, Box::left, Box::right, Box::top, Box::bottom)

    @Test fun hitTestReturnsNullWhenNothingContainsThePoint() {
        assertNull(hit(500f, 500f, Box(0f, 100f, 0f, 100f, "a")))
    }

    @Test fun hitTestIsInclusiveOfBoxEdges() {
        val a = Box(0f, 100f, 0f, 100f, "a")
        assertSame(a, hit(0f, 0f, a))
        assertSame(a, hit(100f, 100f, a))
    }

    @Test fun hitTestPrefersTheLastVisuallyTopmostMatchWhenBoxesOverlap() {
        val bottom = Box(0f, 100f, 0f, 100f, "painted-first")
        val top = Box(0f, 100f, 0f, 100f, "painted-last")
        // Paint order is back-to-front, so the later element in the list is the one actually
        // visible on screen at an overlapping point and must win the hit test.
        assertSame(top, hit(50f, 50f, bottom, top))
    }

    @Test fun hitTestAtASharedEdgeOfTwoAdjacentNonOverlappingBoxesPicksTheLaterOneInPaintOrder() {
        // Two side-by-side siblings sharing the boundary x = 100 (a's right edge, b's left edge).
        // Whichever is painted later owns that shared pixel visually, and the hit test agrees.
        val a = Box(0f, 100f, 0f, 50f, "a")
        val b = Box(100f, 200f, 0f, 50f, "b")
        assertSame(b, hit(100f, 25f, a, b))
    }

    // ---- findMatchesForLayer ----------------------------------------------------------------

    private fun match(fixed: Boolean) = DrawText(0f, 10f, "x", style(), 0f, 10f, 0f, 20f, fixed = fixed)

    @Test fun findMatchesForLayerSplitsMatchesBetweenTheFixedAndScrollingLayers() {
        val scrolled1 = match(fixed = false)
        val fixed1 = match(fixed = true)
        val scrolled2 = match(fixed = false)
        val all = listOf(scrolled1, fixed1, scrolled2)

        val scrollingLayerMatches = BrowserViewLogic.findMatchesForLayer(all, wantFixed = false)
        assertEquals(listOf(0, 2), scrollingLayerMatches.map { it.index })
        assertEquals(listOf(scrolled1, scrolled2), scrollingLayerMatches.map { it.value })

        val fixedLayerMatches = BrowserViewLogic.findMatchesForLayer(all, wantFixed = true)
        assertEquals(listOf(1), fixedLayerMatches.map { it.index })
        assertEquals(listOf(fixed1), fixedLayerMatches.map { it.value })
    }

    @Test fun findMatchesForLayerPreservesTheOriginalIndexForCurrentMatchComparison() {
        // findCurrentIndex indexes into the *whole* match list, not a per-layer sublist, so a
        // fixed-content match at index 1 of 3 must keep reporting index 1, not 0, once filtered
        // down to just the fixed layer's matches.
        val all = listOf(match(fixed = false), match(fixed = true), match(fixed = false))
        val fixedLayerMatches = BrowserViewLogic.findMatchesForLayer(all, wantFixed = true)
        assertEquals(1, fixedLayerMatches.single().index)
    }

    @Test fun findMatchesForLayerReturnsEmptyForALayerWithNoMatches() {
        val all = listOf(match(fixed = false), match(fixed = false))
        assertTrue(BrowserViewLogic.findMatchesForLayer(all, wantFixed = true).isEmpty())
    }

    // ---- PinchTapGate -----------------------------------------------------------------------

    private val ACTION_DOWN = 0
    private val ACTION_MOVE = 2
    private val ACTION_UP = 1

    @Test fun pinchTapGateForwardsOrdinaryEventsWhenNoScaleIsInProgress() {
        val gate = BrowserViewLogic.PinchTapGate(ACTION_DOWN)
        assertTrue(gate.shouldForwardToTapDetector(ACTION_DOWN, scaleInProgress = false))
        assertTrue(gate.shouldForwardToTapDetector(ACTION_MOVE, scaleInProgress = false))
        assertTrue(gate.shouldForwardToTapDetector(ACTION_UP, scaleInProgress = false))
    }

    @Test fun pinchTapGateWithholdsEventsWhileAScaleGestureIsInProgress() {
        val gate = BrowserViewLogic.PinchTapGate(ACTION_DOWN)
        assertTrue(gate.shouldForwardToTapDetector(ACTION_DOWN, scaleInProgress = false))
        assertTrue(!gate.shouldForwardToTapDetector(ACTION_MOVE, scaleInProgress = true))
    }

    @Test fun pinchTapGateSuppressesTheFinalLiftOfAPinchEvenThoughScaleHasAlreadyEnded() {
        // Reproduces the real bug: ScaleGestureDetector.isInProgress flips back to false on the
        // very event that ends the pinch (the last finger's ACTION_UP), so a naive
        // `if (!isInProgress) forward(event)` check would forward that ACTION_UP straight to the
        // tap GestureDetector - which never saw the pinch's intervening ACTION_MOVE events and so
        // would compute a spurious tap from its stale ACTION_DOWN.
        val gate = BrowserViewLogic.PinchTapGate(ACTION_DOWN)
        gate.shouldForwardToTapDetector(ACTION_DOWN, scaleInProgress = false)
        gate.shouldForwardToTapDetector(ACTION_MOVE, scaleInProgress = true) // pinch starts
        // The pinch-ending event itself already reports isInProgress = false.
        val forwardedAtPinchEnd = gate.shouldForwardToTapDetector(ACTION_UP, scaleInProgress = false)
        assertTrue(!forwardedAtPinchEnd)
    }

    @Test fun pinchTapGateResumesForwardingOnlyOnceAFreshDownArrives() {
        val gate = BrowserViewLogic.PinchTapGate(ACTION_DOWN)
        gate.shouldForwardToTapDetector(ACTION_DOWN, scaleInProgress = false)
        gate.shouldForwardToTapDetector(ACTION_MOVE, scaleInProgress = true)
        gate.shouldForwardToTapDetector(ACTION_UP, scaleInProgress = false) // pinch ends, suppressed

        // Any stray event before a fresh down (e.g. ACTION_CANCEL, or a leftover MOVE) stays
        // suppressed too - it's not really a new gesture yet.
        assertTrue(!gate.shouldForwardToTapDetector(ACTION_MOVE, scaleInProgress = false))

        // Only a genuinely fresh ACTION_DOWN resumes forwarding.
        assertTrue(gate.shouldForwardToTapDetector(ACTION_DOWN, scaleInProgress = false))
        assertTrue(gate.shouldForwardToTapDetector(ACTION_UP, scaleInProgress = false))
    }

    // ---- editTextColorScheme -------------------------------------------------------------------

    @Test fun editTextColorSchemeUsesDarkColorsWhenDarkModeIsOn() {
        // Real bug this backs the fix for: BrowserView used to compute an overlay EditText's
        // dark-mode colors only once, at creation time, so toggling dark mode later never
        // recomputed them. This is the pure decision both createEditTextFor and setDarkMode now
        // share, so they can't drift out of sync with each other again.
        val scheme = BrowserViewLogic.editTextColorScheme(darkMode = true, originalTextColor = -0x1000000)
        assertEquals(BrowserViewLogic.DARK_MODE_EDIT_BACKGROUND, scheme.backgroundColor)
        assertEquals(BrowserViewLogic.DARK_MODE_EDIT_TEXT, scheme.textColor)
        assertEquals(BrowserViewLogic.DARK_MODE_EDIT_HINT, scheme.hintColor)
    }

    @Test fun editTextColorSchemeRestoresThePageOwnTextColorWhenDarkModeIsOff() {
        val originalColor = -0x10000 // an arbitrary page-supplied text color
        val scheme = BrowserViewLogic.editTextColorScheme(darkMode = false, originalTextColor = originalColor)
        // null background means "use the default bordered drawable", not a literal color -
        // light mode never painted a solid background here.
        assertNull(scheme.backgroundColor)
        assertEquals(originalColor, scheme.textColor)
        assertEquals(BrowserViewLogic.LIGHT_MODE_EDIT_HINT, scheme.hintColor)
    }

    @Test fun editTextColorSchemeTogglingBackOffRestoresTheExactOriginalColorEvenAfterDarkMode() {
        val originalColor = -0x654321
        val onScheme = BrowserViewLogic.editTextColorScheme(darkMode = true, originalTextColor = originalColor)
        assertEquals(BrowserViewLogic.DARK_MODE_EDIT_TEXT, onScheme.textColor)

        // Toggling back off must recover the field's own original color, not the dark-mode
        // color it was showing a moment ago - this only works because BrowserView now keeps
        // each field's original color around (textFieldOriginalColor) rather than reading it
        // back off the already-recolored EditText.
        val offScheme = BrowserViewLogic.editTextColorScheme(darkMode = false, originalTextColor = originalColor)
        assertEquals(originalColor, offScheme.textColor)
    }
}
