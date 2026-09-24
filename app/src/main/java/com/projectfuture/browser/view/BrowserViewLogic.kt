package com.projectfuture.browser.view

import com.projectfuture.browser.layout.DrawText

/**
 * Pure (no-Android) helpers factored out of [BrowserView] purely so they can
 * be exercised by plain JUnit tests - this project has no Robolectric/
 * emulator, so anything that needs a real `View`/`MotionEvent`/`Canvas`
 * can't be unit-tested directly, but the *decision logic* inside those
 * methods can be, once it doesn't touch an Android type.
 */
internal object BrowserViewLogic {

    /** The furthest [scrollY] is allowed to go: never negative, never past the point where the
     * bottom of the content lines up with the bottom of the viewport (or 0 if content is shorter
     * than the viewport). */
    fun maxScroll(contentHeight: Float, viewportHeight: Float): Float =
        (contentHeight - viewportHeight).coerceAtLeast(0f)

    /** Clamps a candidate scroll position into `[0, maxScroll]` for the given content/viewport
     * sizes - used both for gesture-driven scrolling and for re-clamping after content changes
     * size (e.g. a page shrinking while scrolled near its old bottom). */
    fun clampScroll(scrollY: Float, contentHeight: Float, viewportHeight: Float): Float =
        scrollY.coerceIn(0f, maxScroll(contentHeight, viewportHeight))

    /**
     * The topmost (last-painted) of [items] whose box contains ([x], [y]), or null. Paint order
     * is back-to-front, so the *last* match in the list is the one actually visible at that
     * point and should win a hit test - mirrors [onSingleTapUp]'s two lookups (fixed commands in
     * viewport space, normal commands in page space) so both can share one tested implementation.
     */
    fun <T> hitTest(items: List<T>, x: Float, y: Float, left: (T) -> Float, right: (T) -> Float, top: (T) -> Float, bottom: (T) -> Float): T? =
        items.lastOrNull { x in left(it)..right(it) && y in top(it)..bottom(it) }

    /**
     * Which of [matches] belong on the layer painting `fixed == wantFixed` content, paired with
     * their original index into [matches] (needed to compare against `findCurrentIndex`, which
     * indexes the *whole* list, not the per-layer sublist). Both
     * [BrowserView.ScrollingContentLayer] and [BrowserView.FixedContentLayer] need this - a
     * match that lands inside `position: fixed` content (e.g. a sticky header's search hit) must
     * be highlighted on the fixed layer, not silently dropped.
     */
    fun findMatchesForLayer(matches: List<DrawText>, wantFixed: Boolean): List<IndexedValue<DrawText>> =
        matches.withIndex().filter { it.value.fixed == wantFixed }

    /**
     * Tracks whether events should be forwarded to the tap/scroll [GestureDetector][android.view.GestureDetector]
     * while a pinch (2+ finger) gesture is active or has just ended.
     *
     * `ScaleGestureDetector.isInProgress` alone isn't enough: while a pinch is in progress, its
     * events are withheld from the tap detector (correct - a pinch isn't a tap), but the *instant*
     * the pinch ends (second finger lifts), `isInProgress` flips back to `false` for that very
     * event, and every event afterwards - including the final lift of the remaining finger - would
     * go straight back to the tap [GestureDetector][android.view.GestureDetector]. That detector
     * never saw the `ACTION_MOVE` events during the pinch (they were withheld), so as far as its
     * internal state is concerned, the original `ACTION_DOWN` was followed immediately by this
     * `ACTION_UP` with no intervening movement - a "tap" - even though the finger actually
     * travelled across most of the pinch. That's a real spurious-tap-through bug: pinch-zooming a
     * page could also fire a link tap or form-control tap wherever the remaining finger happened
     * to be when it lifted.
     *
     * The fix: once a pinch has been in progress, forwarding stays withheld until a genuinely
     * fresh gesture starts (`ACTION_DOWN`), not just until `isInProgress` goes back to false.
     */
    class PinchTapGate(private val actionDown: Int) {
        private var suppressUntilFreshDown = false

        fun shouldForwardToTapDetector(actionMasked: Int, scaleInProgress: Boolean): Boolean {
            if (scaleInProgress) {
                suppressUntilFreshDown = true
                return false
            }
            if (suppressUntilFreshDown) {
                if (actionMasked == actionDown) {
                    suppressUntilFreshDown = false
                    return true
                }
                return false
            }
            return true
        }
    }

    // Plain ARGB ints (not android.graphics.Color references) so this stays usable from pure
    // JUnit tests with no Robolectric - values match Color.DKGRAY/WHITE/LTGRAY/GRAY exactly.
    const val DARK_MODE_EDIT_BACKGROUND = 0xFF444444.toInt() // Color.DKGRAY
    const val DARK_MODE_EDIT_TEXT = 0xFFFFFFFF.toInt() // Color.WHITE
    const val DARK_MODE_EDIT_HINT = 0xFFCCCCCC.toInt() // Color.LTGRAY
    const val LIGHT_MODE_EDIT_HINT = 0xFF888888.toInt() // Color.GRAY

    /** null [backgroundColor] means "use the default bordered drawable" (light mode uses
     * android.R.drawable.edit_text, not a solid fill color) rather than an actual color. */
    data class EditTextColorScheme(val backgroundColor: Int?, val textColor: Int, val hintColor: Int)

    /**
     * Which colors an overlay `EditText` (see [BrowserView.createEditTextFor]) should use, given
     * whether dark mode is on and the field's own page-supplied text color.
     *
     * Real bug this backs the fix for: [BrowserView] used to compute these colors only once, in
     * `createEditTextFor`, at the moment a field's overlay `EditText` was first created. Toggling
     * dark mode afterwards - or any later `setContent()` call that reused an existing overlay
     * view via `syncOverlayViews`'s `getOrPut` - never re-ran that logic, so a field created
     * before the toggle kept showing its stale pre-toggle colors: e.g. a light background with
     * dark text sitting on top of an otherwise dark-mode-inverted page (or vice versa toggling
     * off). [BrowserView.setDarkMode] now recomputes and reapplies this for every live overlay
     * field, not just future ones.
     */
    fun editTextColorScheme(darkMode: Boolean, originalTextColor: Int): EditTextColorScheme =
        if (darkMode) {
            EditTextColorScheme(DARK_MODE_EDIT_BACKGROUND, DARK_MODE_EDIT_TEXT, DARK_MODE_EDIT_HINT)
        } else {
            EditTextColorScheme(backgroundColor = null, textColor = originalTextColor, hintColor = LIGHT_MODE_EDIT_HINT)
        }
}
