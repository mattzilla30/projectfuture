package com.projectfuture.browser.layout

import android.graphics.Bitmap
import com.projectfuture.browser.html.ElementNode

/**
 * The output of layout: a flat list of absolute paint instructions.
 * Coordinates are in page space for normal content ([fixed] = false) - the
 * renderer scrolls these. For `position: fixed` content ([fixed] = true),
 * coordinates are in viewport space and the renderer paints them without
 * applying scroll offset, so they stay pinned on screen.
 *
 * [sourceElement] is the DOM element a command was painted for, used by
 * BrowserView to hit-test taps back to an element for click dispatch (see
 * DomBridge). It's only ever set on content that's actually painted -
 * an element with a transparent background and no text/image content
 * paints no command at all, so taps on the "empty" parts of its box
 * won't reach it. That's a real, deliberate limitation of painting-based
 * hit-testing rather than true box-geometry hit-testing.
 */
sealed class DisplayCommand {
    abstract val left: Float
    abstract val right: Float
    abstract val top: Float
    abstract val bottom: Float
    abstract val fixed: Boolean
    abstract val sourceElement: ElementNode?
}

class DrawText(
    val x: Float,
    val baselineY: Float,
    val text: String,
    val style: TextStyle,
    override val left: Float,
    override val right: Float,
    val boxTop: Float,
    val boxBottom: Float,
    override val fixed: Boolean = false,
    override val sourceElement: ElementNode? = null
) : DisplayCommand() {
    override val top get() = boxTop
    override val bottom get() = boxBottom
}

class DrawRect(
    override val left: Float,
    val topPx: Float,
    override val right: Float,
    val bottomPx: Float,
    val color: Int,
    override val fixed: Boolean = false,
    override val sourceElement: ElementNode? = null
) : DisplayCommand() {
    override val top get() = topPx
    override val bottom get() = bottomPx
}

class DrawImage(
    override val left: Float,
    val topPx: Float,
    override val right: Float,
    val bottomPx: Float,
    val bitmap: Bitmap,
    override val fixed: Boolean = false,
    override val sourceElement: ElementNode? = null
) : DisplayCommand() {
    override val top get() = topPx
    override val bottom get() = bottomPx
}

enum class FormControlType { TEXT, PASSWORD, TEXTAREA, CHECKBOX, RADIO, BUTTON }

/**
 * A form control's box, in the same absolute page/viewport space as every
 * other DisplayCommand. TEXT/PASSWORD/TEXTAREA aren't painted onto the
 * Canvas at all - BrowserView instead positions a real overlaid `EditText`
 * over this rect (see its class doc for why: genuine keyboard/IME input is
 * a platform-widget job, not something worth hand-rolling). CHECKBOX/
 * RADIO/BUTTON have no live keyboard-input need, so BrowserView paints
 * them directly from [value]/[checked] like any other DisplayCommand.
 */
class DrawFormControl(
    override val left: Float,
    val topPx: Float,
    override val right: Float,
    val bottomPx: Float,
    val controlType: FormControlType,
    val value: String,
    val checked: Boolean,
    val placeholder: String,
    val style: TextStyle,
    override val fixed: Boolean = false,
    override val sourceElement: ElementNode? = null
) : DisplayCommand() {
    override val top get() = topPx
    override val bottom get() = bottomPx
}
