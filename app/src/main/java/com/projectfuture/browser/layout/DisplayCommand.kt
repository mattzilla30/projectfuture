package com.projectfuture.browser.layout

import android.graphics.Bitmap

/**
 * The output of layout: a flat list of absolute paint instructions.
 * Coordinates are in page space for normal content ([fixed] = false) - the
 * renderer scrolls these. For `position: fixed` content ([fixed] = true),
 * coordinates are in viewport space and the renderer paints them without
 * applying scroll offset, so they stay pinned on screen.
 */
sealed class DisplayCommand {
    abstract val top: Float
    abstract val bottom: Float
    abstract val fixed: Boolean
}

class DrawText(
    val x: Float,
    val baselineY: Float,
    val text: String,
    val style: TextStyle,
    val left: Float,
    val right: Float,
    val boxTop: Float,
    val boxBottom: Float,
    override val fixed: Boolean = false
) : DisplayCommand() {
    override val top get() = boxTop
    override val bottom get() = boxBottom
}

class DrawRect(
    val left: Float,
    val topPx: Float,
    val right: Float,
    val bottomPx: Float,
    val color: Int,
    override val fixed: Boolean = false
) : DisplayCommand() {
    override val top get() = topPx
    override val bottom get() = bottomPx
}

class DrawImage(
    val left: Float,
    val topPx: Float,
    val right: Float,
    val bottomPx: Float,
    val bitmap: Bitmap,
    override val fixed: Boolean = false
) : DisplayCommand() {
    override val top get() = topPx
    override val bottom get() = bottomPx
}
