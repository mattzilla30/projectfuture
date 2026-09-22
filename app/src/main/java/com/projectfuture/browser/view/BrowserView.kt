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
 *
 * Commands are split into two groups: normal page content, which scrolls
 * (painted under a scroll translate), and `position: fixed` content, which
 * is painted in a second, untranslated pass so it stays pinned on screen -
 * see DisplayCommand's doc for why the coordinate spaces differ.
 */
class BrowserView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onLinkTapped: ((String) -> Unit)? = null
    var onSizeAvailable: ((Float, Float) -> Unit)? = null

    private var normalCommands: List<DisplayCommand> = emptyList()
    private var fixedCommands: List<DisplayCommand> = emptyList()
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
            val fixedHit = fixedCommands.asSequence()
                .filterIsInstance<DrawText>()
                .firstOrNull { it.style.linkHref != null && e.x in it.left..it.right && e.y in it.top..it.bottom }
            if (fixedHit != null) {
                fixedHit.style.linkHref?.let { onLinkTapped?.invoke(it) }
                return true
            }
            val py = e.y + scrollYPx
            val hit = normalCommands.asSequence()
                .filterIsInstance<DrawText>()
                .firstOrNull { it.style.linkHref != null && e.x in it.left..it.right && py in it.top..it.bottom }
            hit?.style?.linkHref?.let { onLinkTapped?.invoke(it) }
            return true
        }
    })

    fun setContent(commands: List<DisplayCommand>, height: Float) {
        val (fixed, normal) = commands.partition { it.fixed }
        normalCommands = normal
        fixedCommands = fixed
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
        if (w > 0 && h > 0) onSizeAvailable?.invoke(w.toFloat(), h.toFloat())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.save()
        canvas.translate(0f, -scrollYPx)
        drawCommands(canvas, normalCommands, scrollYPx, scrollYPx + height)
        canvas.restore()

        drawCommands(canvas, fixedCommands, 0f, height.toFloat())
    }

    private fun drawCommands(canvas: Canvas, commands: List<DisplayCommand>, viewTop: Float, viewBottom: Float) {
        for (cmd in commands) {
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
    }
}
