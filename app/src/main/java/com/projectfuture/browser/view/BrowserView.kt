package com.projectfuture.browser.view

import android.content.Context
import android.os.Build
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import com.projectfuture.browser.html.ElementNode
import com.projectfuture.browser.layout.DisplayCommand
import com.projectfuture.browser.layout.DrawFormControl
import com.projectfuture.browser.layout.DrawImage
import com.projectfuture.browser.layout.DrawRect
import com.projectfuture.browser.layout.DrawText
import com.projectfuture.browser.layout.FontCache
import com.projectfuture.browser.layout.FormControlType

/**
 * Paints a DisplayCommand list and turns touch input into scroll offset
 * changes / link taps / form-control taps. This is the entire "renderer" -
 * there is no WebView or system browser widget standing in for the page
 * itself.
 *
 * It's a `FrameLayout`, not a plain `View`, for two reasons now. The
 * original one: text entry (`<input type=text>`/`password`/`<textarea>`)
 * needs a real IME keyboard, cursor, and selection handling, so each such
 * field is a genuine overlaid `EditText` child (see [syncOverlayViews]).
 * The second, newer one: painting itself is split across two *hardware-
 * layer* child Views rather than done directly in this View's own
 * `onDraw` - [ScrollingContentLayer] for normal page content and
 * [FixedContentLayer] for `position: fixed` content - so they're
 * independently GPU-composited layers, not one single CPU-painted bitmap.
 *
 * Why that split earns its keep (not just "hardware acceleration is
 * already on" - it was, via the manifest's default, before this): with a
 * single `onDraw`, *every* scroll-driven `invalidate()` re-rasterizes
 * *all* content - the fixed header/footer/nav included - even though a
 * scroll never changes a single pixel of it (that's the whole point of
 * `position: fixed`). Each layer here is `LAYER_TYPE_HARDWARE`, an
 * independently-cached GPU texture: scrolling invalidates only
 * [ScrollingContentLayer], so [FixedContentLayer]'s texture is reused by
 * the compositor untouched on every scroll frame - a real reduction in
 * per-frame CPU paint work on any page with fixed content, not just a
 * manifest flag. Dark mode ([setDarkMode]) is applied the same way: a
 * `ColorMatrixColorFilter` set once via `View.setLayerPaint` on each
 * layer, composited by the GPU when it draws that layer's cached texture,
 * instead of a `Canvas.saveLayer` + color-matrix paint re-run on every
 * single `onDraw` call as before.
 *
 * Honest limits of this pass: content within a layer still repaints on
 * every scroll tick (there's no tiling - the whole visible slice of
 * `ScrollingContentLayer` is CPU-rasterized again each time the scroll
 * offset changes, same as before), and `transform` support in this engine
 * is limited to `translate()`, already resolved into plain layout
 * position by [com.projectfuture.browser.css.resolveTranslate] rather than
 * being a paint-time transform - so there's no per-element rotate/scale
 * layer to GPU-composite here, because the engine doesn't produce one (see
 * that file's doc). No device/emulator was available in this environment
 * to capture actual on-device frame-timing numbers for the before/after;
 * what's verifiable here is the architecture change itself (independent
 * hardware layers, GPU-applied color filter) and that it compiles/behaves
 * identically from every call site's perspective.
 */
class BrowserView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var onLinkTapped: ((String) -> Unit)? = null
    var onElementTapped: ((ElementNode) -> Unit)? = null
    var onFormInput: ((ElementNode, String) -> Unit)? = null
    var onSizeAvailable: ((Float, Float) -> Unit)? = null

    /**
     * Fired once, when a pinch gesture ends, with the cumulative scale
     * factor to apply (e.g. 1.2 for "20% bigger"). This engine maps pinch-
     * to-zoom onto a text-size multiplier and a full re-layout rather than
     * a live Canvas scale transform - see TextStyle.kt's textScaleFactor
     * doc for why (mainly: the overlaid form-control EditText views would
     * otherwise need their own independent scale/position math kept in
     * sync with the Canvas transform on every frame, real complexity for a
     * feature with no emulator available to verify smoothness on).
     * Snapping only on release (not live during the gesture) is a
     * deliberate, bounded trade-off: correct end state, no live preview.
     */
    var onPinchZoomEnded: ((Float) -> Unit)? = null

    private var normalCommands: List<DisplayCommand> = emptyList()
    private var fixedCommands: List<DisplayCommand> = emptyList()
    private var contentHeight = 0f
    private var scrollYPx = 0f
    private var findMatches: List<DrawText> = emptyList()
    private var findCurrentIndex: Int = -1
    private val findMatchPaint = Paint().apply { color = Color.argb(110, 255, 235, 59) } // translucent yellow
    private val findCurrentMatchPaint = Paint().apply { color = Color.argb(180, 255, 152, 0) } // translucent orange
    private val rectPaint = Paint()
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.DKGRAY }
    private val controlBgPaint = Paint().apply { color = Color.parseColor("#E0E0E0") }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val imageDestRect = RectF()

    /** Live overlaid text-entry widgets, keyed by their DOM element. Text/password/textarea only. */
    private val textFieldViews = HashMap<ElementNode, EditText>()

    /** Each field's own page-supplied text color (from its DrawFormControl.style), independent
     * of whatever dark-mode-aware color is currently painted onto its EditText - needed so
     * [setDarkMode] can restore the correct light-mode color when toggling dark mode back off,
     * not just apply dark colors when toggling it on. See [BrowserViewLogic.editTextColorScheme]. */
    private val textFieldOriginalColor = HashMap<ElementNode, Int>()

    /**
     * A GPU-composited layer painting only [normalCommands] (and non-fixed
     * find-match highlights), translated by the live scroll offset. Its
     * own bounds always match the viewport; content further down the page
     * than [scrollOffset] + height is clipped by [drawCommands]'s
     * viewTop/viewBottom check exactly as before the layer split - this
     * class only changes *which View's hardware layer* gets invalidated on
     * a scroll tick, not what's visible.
     */
    private inner class ScrollingContentLayer(context: Context) : View(context) {
        var scrollOffset: Float = 0f

        init { setLayerType(LAYER_TYPE_HARDWARE, null) }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.save()
            canvas.translate(0f, -scrollOffset)
            drawCommands(canvas, normalCommands, scrollOffset, scrollOffset + height)
            drawFindHighlights(canvas, wantFixed = false)
            canvas.restore()
        }
    }

    /**
     * A GPU-composited layer painting only [fixedCommands], never
     * translated - see class doc for why keeping it a *separate* hardware
     * layer from [ScrollingContentLayer] (rather than just also being
     * hardware-accelerated) is the actual point: it's never invalidated by
     * a scroll, so its cached texture is reused by the compositor as-is on
     * every scroll frame instead of being re-rasterized.
     */
    private inner class FixedContentLayer(context: Context) : View(context) {
        init { setLayerType(LAYER_TYPE_HARDWARE, null) }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            drawCommands(canvas, fixedCommands, 0f, height.toFloat())
            // Fixed content is never translated, so its find-match highlights (if any hit fell
            // inside a `position: fixed` header/nav) are drawn here in the same unscrolled
            // coordinates, never in ScrollingContentLayer - see BrowserViewLogic.findMatchesForLayer.
            drawFindHighlights(canvas, wantFixed = true)
        }
    }

    /** Shared by both layers - see [com.projectfuture.browser.view.BrowserViewLogic.findMatchesForLayer]. */
    private fun drawFindHighlights(canvas: Canvas, wantFixed: Boolean) {
        for ((i, cmd) in BrowserViewLogic.findMatchesForLayer(findMatches, wantFixed)) {
            canvas.drawRect(cmd.left, cmd.top, cmd.right, cmd.bottom, if (i == findCurrentIndex) findCurrentMatchPaint else findMatchPaint)
        }
    }

    private val scrollingContentLayer = ScrollingContentLayer(context)
    private val fixedContentLayer = FixedContentLayer(context)

    init {
        // BrowserView itself paints nothing directly any more - both layers below do, each as its
        // own hardware-accelerated texture. Order matters: fixed content (a header/nav bar, say)
        // must composite above scrolled page content, and overlay EditText fields (added later, in
        // syncOverlayViews) must composite above both.
        addView(scrollingContentLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(fixedContentLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            scrollYPx = (scrollYPx + distanceY).coerceIn(0f, maxScroll())
            scrollingContentLayer.scrollOffset = scrollYPx
            repositionOverlayViews()
            // Only the scrolling layer's texture needs re-rasterizing - fixedContentLayer's cached
            // hardware layer is left completely untouched, which is the real saving this split buys
            // (see class doc).
            scrollingContentLayer.invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // Fixed content is checked first, in unscrolled viewport coordinates (it's painted
            // that way, never translated by scroll) - see BrowserViewLogic.hitTest's doc for why
            // the *last* match wins.
            val fixedHit = BrowserViewLogic.hitTest(fixedCommands, e.x, e.y, { it.left }, { it.right }, { it.top }, { it.bottom })
            if (fixedHit != null) return dispatchHit(fixedHit)

            // Normal content is painted in page space, so the tap's viewport-space y must be
            // converted into page space by adding the current scroll offset before hit-testing.
            val py = e.y + scrollYPx
            val hit = BrowserViewLogic.hitTest(normalCommands, e.x, py, { it.left }, { it.right }, { it.top }, { it.bottom })
            if (hit != null) return dispatchHit(hit)
            return true
        }

        private fun dispatchHit(cmd: DisplayCommand): Boolean {
            val linkHref = (cmd as? DrawText)?.style?.linkHref
            val element = cmd.sourceElement
            if (element != null) {
                onElementTapped?.invoke(element)
            } else if (linkHref != null) {
                onLinkTapped?.invoke(linkHref)
            }
            return true
        }
    })

    /**
     * A tap that starts inside a live overlay `EditText` must reach that
     * child normally (for cursor placement, selection, IME focus) instead
     * of being swallowed by our own gesture detector - so interception is
     * withheld for any gesture starting there.
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            for (view in textFieldViews.values) {
                if (view.visibility != VISIBLE) continue
                if (ev.x >= view.x && ev.x <= view.x + view.width && ev.y >= view.y && ev.y <= view.y + view.height) {
                    return false
                }
            }
        }
        return true
    }

    private var pinchAccumulatedScale = 1f
    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            pinchAccumulatedScale = 1f
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            pinchAccumulatedScale *= detector.scaleFactor
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            if (pinchAccumulatedScale != 1f) onPinchZoomEnded?.invoke(pinchAccumulatedScale)
        }
    })

    private val pinchTapGate = BrowserViewLogic.PinchTapGate(MotionEvent.ACTION_DOWN)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        // A pinch (2+ fingers) shouldn't also register as a scroll/tap - GestureDetector only ever
        // sees single-pointer semantics, so gate it on the scale detector not currently mid-gesture.
        // Withholding events only while isInProgress is true isn't enough on its own though: see
        // BrowserViewLogic.PinchTapGate's doc for the spurious-tap-through this closes.
        if (pinchTapGate.shouldForwardToTapDetector(event.actionMasked, scaleGestureDetector.isInProgress)) {
            gestureDetector.onTouchEvent(event)
        }
        return true
    }

    fun setContent(commands: List<DisplayCommand>, height: Float) {
        val (fixed, normal) = commands.partition { it.fixed }
        normalCommands = normal
        fixedCommands = fixed
        contentHeight = height
        scrollYPx = scrollYPx.coerceIn(0f, maxScroll())
        scrollingContentLayer.scrollOffset = scrollYPx
        syncOverlayViews((normal + fixed).filterIsInstance<DrawFormControl>())
        scrollingContentLayer.invalidate()
        fixedContentLayer.invalidate()
    }

    fun resetScroll() {
        scrollYPx = 0f
        scrollingContentLayer.scrollOffset = 0f
        repositionOverlayViews()
        scrollingContentLayer.invalidate()
    }

    /** Called on full page navigation, so stale fields from the old document don't linger. */
    fun clearOverlayViews() {
        for (view in textFieldViews.values) removeView(view)
        textFieldViews.clear()
        textFieldOriginalColor.clear()
    }

    /**
     * Find-in-page: [matches] are highlighted with a translucent overlay
     * (drawn in the same page-space coordinates as the underlying
     * DrawText, so they scroll with the page), and [currentIndex] gets a
     * stronger highlight and is scrolled into view. Matching itself is
     * done by the caller (MainActivity) against the current DisplayCommand
     * list - see its class doc for the per-token-only matching limitation.
     */
    fun setFindMatches(matches: List<DrawText>, currentIndex: Int) {
        findMatches = matches
        findCurrentIndex = currentIndex
        val current = matches.getOrNull(currentIndex)
        if (current != null && !current.fixed) {
            scrollYPx = (current.top - height / 3f).coerceIn(0f, maxScroll())
            scrollingContentLayer.scrollOffset = scrollYPx
            repositionOverlayViews()
        }
        scrollingContentLayer.invalidate()
        // Real bug: a match landing inside `position: fixed` content (e.g. a sticky header) is
        // drawn by FixedContentLayer.onDraw (see drawFindHighlights/BrowserViewLogic.
        // findMatchesForLayer), but that layer is its own separately-cached hardware texture
        // (see class doc) - unlike ScrollingContentLayer above, it was never told to
        // re-rasterize here, so a fixed-content match's highlight (or a change in which match is
        // "current") silently never appeared on screen until something unrelated (e.g. a scroll)
        // happened to invalidate it.
        fixedContentLayer.invalidate()
    }

    fun clearFindMatches() {
        findMatches = emptyList()
        findCurrentIndex = -1
        scrollingContentLayer.invalidate()
        // Same reasoning as setFindMatches: without this, a highlight previously drawn on
        // FixedContentLayer would stay stuck on screen after "clear" until an unrelated redraw.
        fixedContentLayer.invalidate()
    }

    private fun maxScroll(): Float = BrowserViewLogic.maxScroll(contentHeight, height.toFloat())

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) onSizeAvailable?.invoke(w.toFloat(), h.toFloat())
    }

    private val TEXT_LIKE = setOf(FormControlType.TEXT, FormControlType.PASSWORD, FormControlType.TEXTAREA)

    /**
     * Creates/removes/repositions overlay `EditText` views to match the
     * current set of text-like form controls. A view's typed text is left
     * alone once created (not re-synced from the DOM value on every
     * relayout) so an in-progress keystroke isn't clobbered by an unrelated
     * re-layout elsewhere on the page - a known, documented simplification:
     * a script that reassigns `.value` on a field the user is actively
     * editing won't visibly update it.
     */
    private fun syncOverlayViews(formControls: List<DrawFormControl>) {
        val textControls = formControls.filter { it.controlType in TEXT_LIKE && it.sourceElement != null }
        val liveElements = textControls.mapNotNull { it.sourceElement }.toSet()

        val iter = textFieldViews.entries.iterator()
        while (iter.hasNext()) {
            val (el, view) = iter.next()
            if (el !in liveElements) {
                removeView(view)
                iter.remove()
                textFieldOriginalColor.remove(el)
            }
        }

        for (cmd in textControls) {
            val el = cmd.sourceElement!!
            val isNew = el !in textFieldViews
            val view = textFieldViews.getOrPut(el) { createEditTextFor(cmd) }
            val lp = FrameLayout.LayoutParams(
                (cmd.right - cmd.left).toInt().coerceAtLeast(1),
                (cmd.bottom - cmd.top).toInt().coerceAtLeast(1)
            )
            lp.leftMargin = cmd.left.toInt()
            lp.topMargin = cmd.top.toInt()
            view.layoutParams = lp
            view.translationY = -scrollYPx
            if (isNew) addView(view, lp)
        }
    }

    private fun repositionOverlayViews() {
        for (view in textFieldViews.values) view.translationY = -scrollYPx
    }

    /**
     * Maps `<input>` type/name/autocomplete to Android's Autofill hint
     * constants, so the platform's own Autofill framework (password
     * managers, etc.) can recognize and fill these real EditText fields -
     * this falls out almost for free given they're genuine platform
     * widgets already (see class doc), needing only API 26+ (`minSdk` is
     * 24, hence the version guard) and a plausible-hint heuristic rather
     * than full HTML autocomplete-token parsing.
     */
    private fun autofillHintsFor(el: ElementNode?): Array<String>? {
        if (el == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val autocomplete = el.attr("autocomplete")?.lowercase() ?: ""
        val type = el.attr("type")?.lowercase() ?: ""
        val name = el.attr("name")?.lowercase() ?: ""
        val hint = when {
            type == "password" || "password" in autocomplete -> View.AUTOFILL_HINT_PASSWORD
            type == "email" || "email" in autocomplete || "email" in name -> View.AUTOFILL_HINT_EMAIL_ADDRESS
            type == "tel" || "tel" in autocomplete -> View.AUTOFILL_HINT_PHONE
            "username" in autocomplete || "user" in name || "login" in name -> View.AUTOFILL_HINT_USERNAME
            "cc-number" in autocomplete -> View.AUTOFILL_HINT_CREDIT_CARD_NUMBER
            "postal-code" in autocomplete || "zip" in name -> View.AUTOFILL_HINT_POSTAL_CODE
            "name" in autocomplete || "name" in name -> View.AUTOFILL_HINT_NAME
            else -> return null
        }
        return arrayOf(hint)
    }

    private fun createEditTextFor(cmd: DrawFormControl): EditText {
        val editText = EditText(context)
        editText.setPadding(8, 4, 8, 4)
        editText.setBackgroundResource(android.R.drawable.edit_text)
        editText.setTextSize(TypedValue.COMPLEX_UNIT_PX, cmd.style.sizePx)
        // The page's own hardware-layer-composited content gets its colors inverted wholesale
        // (see setDarkMode's doc); this real EditText isn't part of either layer, so it needs
        // manually dark-aware colors - see applyEditTextColorScheme for why this also has to be
        // re-run later, not just here at creation.
        cmd.sourceElement?.let { textFieldOriginalColor[it] = cmd.style.color }
        applyEditTextColorScheme(editText, BrowserViewLogic.editTextColorScheme(darkModeEnabled, cmd.style.color))
        editText.hint = cmd.placeholder
        editText.setText(cmd.value)
        autofillHintsFor(cmd.sourceElement)?.let { hints ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) editText.setAutofillHints(*hints)
        }
        if (cmd.controlType == FormControlType.TEXTAREA) {
            editText.isSingleLine = false
            editText.gravity = Gravity.TOP or Gravity.START
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        } else if (cmd.controlType == FormControlType.PASSWORD) {
            editText.isSingleLine = true
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else {
            editText.isSingleLine = true
            editText.inputType = InputType.TYPE_CLASS_TEXT
        }
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                cmd.sourceElement?.let { onFormInput?.invoke(it, s?.toString() ?: "") }
            }
        })
        return editText
    }

    /**
     * "Dark mode" here is a full-color inversion of everything painted -
     * the same technique as Android's own accessibility "Invert colors" -
     * not a per-element light/dark re-theming like a real browser's
     * "force dark" heuristic (which selectively inverts backgrounds/text
     * while leaving images alone). The trade-off: images and anything
     * already-colorful invert too (a photo looks like a photo negative),
     * but it's correct and simple for arbitrary pages.
     *
     * Applied via [View.setLayerPaint] on each hardware layer rather than
     * a `Canvas.saveLayer` inside `onDraw`: the `ColorMatrixColorFilter`
     * becomes a property of how the GPU composites that layer's cached
     * texture, evaluated by the compositor, not re-executed as CPU/Skia
     * draw commands on every single frame. Overlaid EditText fields aren't
     * part of either layer's texture, so they're separately re-colored in
     * [createEditTextFor].
     */
    private var darkModeEnabled = false
    private val invertColorMatrix = ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        )
    )
    private val invertLayerPaint = Paint().apply { colorFilter = ColorMatrixColorFilter(invertColorMatrix) }

    fun setDarkMode(enabled: Boolean) {
        darkModeEnabled = enabled
        val paint = if (enabled) invertLayerPaint else null
        scrollingContentLayer.setLayerPaint(paint)
        fixedContentLayer.setLayerPaint(paint)
        // Real bug fixed here: overlay EditText fields used to only get dark-mode-aware colors
        // at the moment they were first created (in createEditTextFor), so toggling dark mode
        // after a field already existed left it showing stale colors - a light-themed text box
        // on a dark-inverted page, or vice versa. Re-apply the scheme to every live field now.
        for ((el, view) in textFieldViews) {
            val originalColor = textFieldOriginalColor[el] ?: continue
            applyEditTextColorScheme(view, BrowserViewLogic.editTextColorScheme(enabled, originalColor))
        }
    }

    /** Applies a [BrowserViewLogic.EditTextColorScheme] to a live overlay field - shared by
     * [createEditTextFor] and [setDarkMode] so both stay in sync (see setDarkMode's doc). */
    private fun applyEditTextColorScheme(view: EditText, scheme: BrowserViewLogic.EditTextColorScheme) {
        if (scheme.backgroundColor != null) {
            view.setBackgroundColor(scheme.backgroundColor)
        } else {
            view.setBackgroundResource(android.R.drawable.edit_text)
        }
        view.setTextColor(scheme.textColor)
        view.setHintTextColor(scheme.hintColor)
    }

    /**
     * Paints the whole (unscrolled) page onto an arbitrary Canvas - used
     * for printing (see MainActivity's print menu action), where the
     * caller has already scaled the canvas to fit a print page. `position:
     * fixed` content is skipped (it's relative to an on-screen viewport,
     * which printing doesn't have). Text/password/textarea field values
     * won't appear - they live in real overlaid EditText views, not either
     * paint layer, and printing doesn't recreate an Android view hierarchy
     * for a PDF page - a known, documented print limitation.
     */
    fun paintFullPageForPrint(canvas: Canvas, commands: List<DisplayCommand>) {
        drawCommands(canvas, commands.filterNot { it.fixed }, 0f, Float.MAX_VALUE)
    }

    private fun drawCommands(canvas: Canvas, commands: List<DisplayCommand>, viewTop: Float, viewBottom: Float) {
        for (cmd in commands) {
            if (cmd.bottom < viewTop || cmd.top > viewBottom) continue
            when (cmd) {
                is DrawRect -> {
                    rectPaint.color = cmd.color
                    canvas.drawRect(cmd.left, cmd.top, cmd.right, cmd.bottom, rectPaint)
                }
                is DrawImage -> {
                    imageDestRect.set(cmd.left, cmd.top, cmd.right, cmd.bottom)
                    canvas.drawBitmap(cmd.bitmap, null, imageDestRect, bitmapPaint)
                }
                is DrawText -> {
                    val paint = FontCache.paintFor(cmd.style)
                    paint.color = cmd.style.color
                    canvas.drawText(cmd.text, cmd.x, cmd.baselineY, paint)
                    if (cmd.style.underline) {
                        val lineY = cmd.baselineY + paint.textSize * 0.08f
                        canvas.drawLine(cmd.left, lineY, cmd.right, lineY, paint)
                    }
                    if (cmd.style.strikethrough) {
                        val lineY = cmd.baselineY - paint.textSize * 0.3f
                        canvas.drawLine(cmd.left, lineY, cmd.right, lineY, paint)
                    }
                }
                is DrawFormControl -> drawFormControl(canvas, cmd)
            }
        }
    }

    /** TEXT/PASSWORD/TEXTAREA aren't painted here at all - they're real overlaid EditText children (see class doc). */
    private fun drawFormControl(canvas: Canvas, cmd: DrawFormControl) {
        when (cmd.controlType) {
            FormControlType.CHECKBOX -> {
                canvas.drawRect(cmd.left, cmd.top, cmd.right, cmd.bottom, strokePaint)
                if (cmd.checked) {
                    val w = cmd.right - cmd.left
                    val h = cmd.bottom - cmd.top
                    canvas.drawLine(cmd.left + w * 0.2f, cmd.top + h * 0.5f, cmd.left + w * 0.45f, cmd.bottom - h * 0.2f, strokePaint)
                    canvas.drawLine(cmd.left + w * 0.45f, cmd.bottom - h * 0.2f, cmd.right - w * 0.15f, cmd.top + h * 0.2f, strokePaint)
                }
            }
            FormControlType.RADIO -> {
                val cx = (cmd.left + cmd.right) / 2f
                val cy = (cmd.top + cmd.bottom) / 2f
                val r = (cmd.right - cmd.left) / 2f - 2f
                canvas.drawCircle(cx, cy, r, strokePaint)
                if (cmd.checked) {
                    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY }
                    canvas.drawCircle(cx, cy, r * 0.5f, fillPaint)
                }
            }
            FormControlType.BUTTON -> {
                canvas.drawRect(cmd.left, cmd.top, cmd.right, cmd.bottom, controlBgPaint)
                canvas.drawRect(cmd.left, cmd.top, cmd.right, cmd.bottom, strokePaint)
                // A private copy, not the shared FontCache Paint - textAlign must not leak into normal text draws.
                val labelPaint = Paint(FontCache.paintFor(cmd.style)).apply {
                    color = cmd.style.color
                    textAlign = Paint.Align.CENTER
                }
                val fm = labelPaint.fontMetrics
                val textY = (cmd.top + cmd.bottom) / 2f - (fm.ascent + fm.descent) / 2f
                canvas.drawText(cmd.value, (cmd.left + cmd.right) / 2f, textY, labelPaint)
            }
            FormControlType.TEXT, FormControlType.PASSWORD, FormControlType.TEXTAREA -> {} // overlay EditText handles this
        }
    }
}
