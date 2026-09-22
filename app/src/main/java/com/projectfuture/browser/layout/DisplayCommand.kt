package com.projectfuture.browser.layout

/** The output of layout: a flat list of absolute-page-coordinate paint instructions. */
sealed class DisplayCommand {
    abstract val top: Float
    abstract val bottom: Float
}

class DrawText(
    val x: Float,
    val baselineY: Float,
    val text: String,
    val style: TextStyle,
    val left: Float,
    val right: Float,
    val boxTop: Float,
    val boxBottom: Float
) : DisplayCommand() {
    override val top get() = boxTop
    override val bottom get() = boxBottom
}

class DrawRect(
    val left: Float,
    val topPx: Float,
    val right: Float,
    val bottomPx: Float,
    val color: Int
) : DisplayCommand() {
    override val top get() = topPx
    override val bottom get() = bottomPx
}
