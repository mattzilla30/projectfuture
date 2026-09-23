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
 * Paints a DisplayCommand list straight onto a Canvas and turns touch
 * input into scroll offset changes / link taps / form-control taps. This is
 * the entire "renderer" - there is no WebView or system browser widget
 * standing in for the page itself.
 *
 * It's a `FrameLayout`, not a plain `View`, for exactly one reason: text
 * entry (`<input type=text>`/`password`/`<textarea>`) needs a real IME
 * keyboard, cursor, and selection handling, and hand-rolling that is squarely
 * "browser engine" work this project isn't reimplementing (same boundary as
 * using Canvas/Paint for glyph rendering rather than a rasterizer written
 * from scratch) - so each such field is a genuine overlaid `EditText` child,
 * positioned to match its DrawFormControl's page rect and kept in sync with
 * scrolling via `translationY` (cheap - no layout pass per scroll tick).
 * Checkbox/radio/button controls have no keyboard-input need, so they're
 * just painted directly (see [drawCommands]) and hit-tested the same way
 * links and other elements already are.
 *
 * Commands are split into two groups: normal page content, which scrolls
 * (painted under a scroll translate), and `position: fixed` content, which
 * is painted in a second, untranslated pass so it stays pinned on screen -
 * see DisplayCommand's doc for why the coordinate spaces differ.
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

    init {
        setWillNotDraw(false)
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            scrollYPx = (scrollYPx + distanceY).coerceIn(0f, maxScroll())
            repositionOverlayViews()
            invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // lastOrNull, not firstOrNull: commands are in paint order (back to front), so the
            // last one under the tap is the topmost one visually and should win the hit test.
            val fixedHit = fixedCommands.lastOrNull { e.x in it.left..it.right && e.y in it.top..it.bottom }
            if (fixedHit != null) return dispatchHit(fixedHit)

            val py = e.y + scrollYPx
            val hit = normalCommands.lastOrNull { e.x in it.left..it.right && py in it.top..it.bottom }
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        // A pinch (2+ fingers) shouldn't also register as a scroll/tap - GestureDetector only ever
        // sees single-pointer semantics, so gate it on the scale detector not currently mid-gesture.
        if (!scaleGestureDetector.isInProgress) gestureDetector.onTouchEvent(event)
        return true
    }

    fun setContent(commands: List<DisplayCommand>, height: Float) {
        val (fixed, normal) = commands.partition { it.fixed }
        normalCommands = normal
        fixedCommands = fixed
        contentHeight = height
        scrollYPx = scrollYPx.coerceIn(0f, maxScroll())
        syncOverlayViews((normal + fixed).filterIsInstance<DrawFormControl>())
        invalidate()
    }

    fun resetScroll() {
        scrollYPx = 0f
        repositionOverlayViews()
        invalidate()
    }

    /** Called on full page navigation, so stale fields from the old document don't linger. */
    fun clearOverlayViews() {
        for (view in textFieldViews.values) removeView(view)
        textFieldViews.clear()
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
            repositionOverlayViews()
        }
        invalidate()
    }

    fun clearFindMatches() {
        findMatches = emptyList()
        findCurrentIndex = -1
        invalidate()
    }

    private fun maxScroll(): Float = (contentHeight - height).coerceAtLeast(0f)

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
        if (darkModeEnabled) {
            // The page's own Canvas-painted content gets its colors inverted wholesale (see onDraw's
            // doc); this real EditText isn't part of that layer, so it needs manually dark-aware colors.
            editText.setBackgroundColor(Color.DKGRAY)
            editText.setTextColor(Color.WHITE)
            editText.setHintTextColor(Color.LTGRAY)
        } else {
            editText.setBackgroundResource(android.R.drawable.edit_text)
            editText.setTextColor(cmd.style.color)
            editText.setHintTextColor(Color.GRAY)
        }
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
     * "Dark mode" here is a full-color inversion of everything this View
     * paints (`saveLayer` with a negating `ColorMatrixColorFilter`), the
     * same technique as Android's own accessibility "Invert colors" -
     * not a per-element light/dark re-theming like a real browser's
     * "force dark" heuristic (which selectively inverts backgrounds/text
     * while leaving images alone). The trade-off: images and anything
     * already-colorful invert too (a photo looks like a photo negative),
     * but it's correct and simple for arbitrary pages, and overlaid
     * EditText fields are separately re-colored in [createEditTextFor]
     * (real Views aren't part of this Canvas layer, so they need their own
     * dark-aware colors).
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
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (darkModeEnabled) {
            val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), invertLayerPaint)
            drawPageContent(canvas)
            canvas.restoreToCount(layer)
        } else {
            drawPageContent(canvas)
        }
    }

    private fun drawPageContent(canvas: Canvas) {
        canvas.save()
        canvas.translate(0f, -scrollYPx)
        drawCommands(canvas, normalCommands, scrollYPx, scrollYPx + height)
        for ((i, cmd) in findMatches.withIndex()) {
            if (cmd.fixed) continue
            canvas.drawRect(cmd.left, cmd.top, cmd.right, cmd.bottom, if (i == findCurrentIndex) findCurrentMatchPaint else findMatchPaint)
        }
        canvas.restore()

        drawCommands(canvas, fixedCommands, 0f, height.toFloat())
    }

    /**
     * Paints the whole (unscrolled) page onto an arbitrary Canvas - used
     * for printing (see MainActivity's print menu action), where the
     * caller has already scaled the canvas to fit a print page. `position:
     * fixed` content is skipped (it's relative to an on-screen viewport,
     * which printing doesn't have). Text/password/textarea field values
     * won't appear - they live in real overlaid EditText views, not this
     * Canvas paint path, and printing doesn't recreate an Android view
     * hierarchy for a PDF page - a known, documented print limitation.
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
