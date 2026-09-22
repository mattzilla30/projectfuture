package com.projectfuture.browser.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.projectfuture.browser.layout.DisplayCommand
import com.projectfuture.browser.layout.DrawRect
import com.projectfuture.browser.layout.DrawText
import com.projectfuture.browser.layout.FontCache

/**
 * Paints a DisplayCommand list straight onto a Canvas and turns touch
 * input into scroll offset changes / link taps. This is the entire
 * "renderer" - there is no WebView or system browser widget involved.
 */
class BrowserView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onLinkTapped: ((String) -> Unit)? = null
    var onWidthAvailable: ((Float) -> Unit)? = null

    private var displayList: List<DisplayCommand> = emptyList()
    private var contentHeight = 0f
    private var scrollYPx = 0f
    private val rectPaint = Paint()

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            scrollYPx = (scrollYPx + distanceY).coerceIn(0f, maxScroll())
            invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val px = e.x
            val py = e.y + scrollYPx
            val hit = displayList.asSequence()
                .filterIsInstance<DrawText>()
                .firstOrNull { it.style.linkHref != null && px in it.left..it.right && py in it.top..it.bottom }
            hit?.style?.linkHref?.let { onLinkTapped?.invoke(it) }
            return true
        }
    })

    fun setContent(commands: List<DisplayCommand>, height: Float) {
        displayList = commands
        contentHeight = height
        scrollYPx = scrollYPx.coerceIn(0f, maxScroll())
        invalidate()
    }

    fun resetScroll() {
        scrollYPx = 0f
        invalidate()
    }

    private fun maxScroll(): Float = (contentHeight - height).coerceAtLeast(0f)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0) onWidthAvailable?.invoke(w.toFloat())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.translate(0f, -scrollYPx)

        val viewTop = scrollYPx
        val viewBottom = scrollYPx + height
        for (cmd in displayList) {
            if (cmd.bottom < viewTop || cmd.top > viewBottom) continue
            when (cmd) {
                is DrawRect -> {
                    rectPaint.color = cmd.color
                    canvas.drawRect(cmd.left, cmd.top, cmd.right, cmd.bottom, rectPaint)
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
            }
        }
        canvas.restore()
    }
}
