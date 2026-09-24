package com.projectfuture.browser.browser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.projectfuture.browser.css.parseCssColor
import com.projectfuture.browser.js.JsBoolean
import com.projectfuture.browser.js.JsNumber
import com.projectfuture.browser.js.JsObject
import com.projectfuture.browser.js.JsString
import com.projectfuture.browser.js.JsUndefined
import com.projectfuture.browser.js.JsValue
import com.projectfuture.browser.js.NativeFunction
import com.projectfuture.browser.js.toJsString
import com.projectfuture.browser.js.toNumber

/**
 * `CanvasRenderingContext2D` bridged onto a Bitmap+Canvas the same way
 * SvgRenderer draws SVG shapes - it's the same platform drawing surface
 * already used elsewhere, not "browser engine" logic. Since Bitmap is a
 * mutable, reference-shared object, a script that draws later (in a click
 * handler or setInterval tick) mutates the same bitmap the display list
 * already points to, so a later re-layout picks up the new pixels with no
 * extra plumbing - see DomBridge.getOrCreateCanvasContext.
 *
 * Deliberately narrow: fillRect/strokeRect/clearRect, path building
 * (beginPath/moveTo/lineTo/rect/arc/closePath) with fill()/stroke(),
 * fillText/strokeText/measureText, save/restore, translate/scale/rotate,
 * and the fillStyle/strokeStyle/lineWidth/globalAlpha/font properties.
 * NOT implemented: gradients/patterns, clipping, drawImage,
 * getImageData/putImageData, quadratic/bezier curve path commands,
 * dashed lines, text alignment/baseline. `fillStyle`/`strokeStyle`
 * only round-trips as a hex color string, not whatever format was set.
 */
/** Snapshot of the mutable drawing-style state save()/restore() must round-trip, kept
 *  separate from the Android Canvas's own matrix/clip stack (Canvas.save/restore only
 *  covers transform+clip, never fillStyle/strokeStyle/lineWidth/font/globalAlpha). */
internal data class CanvasStyleSnapshot(
    val fillColor: Int,
    val strokeColor: Int,
    val lineWidthPx: Float,
    val fontSizePx: Float,
    val globalAlphaValue: Float,
)

/** Pure LIFO stack backing save()/restore(), Android-free so it's directly unit-testable
 *  and so a restore() called more times than save() can no-op instead of letting
 *  Canvas.restore() throw IllegalStateException ("Underflow in restore/restoreToCount"). */
internal class CanvasStyleStack {
    private val stack = ArrayDeque<CanvasStyleSnapshot>()
    val depth: Int get() = stack.size
    fun push(snapshot: CanvasStyleSnapshot) {
        stack.addLast(snapshot)
    }

    /** Returns null (leaving the stack untouched) when there is nothing left to restore. */
    fun pop(): CanvasStyleSnapshot? = if (stack.isEmpty()) null else stack.removeLast()
}

/** Pure translation of Canvas 2D's arc(x, y, radius, startAngle, endAngle, anticlockwise)
 *  into the oval-bounds + start/sweep-degrees form Android's Path.addArc wants. Returns
 *  null for a negative radius (invalid per spec) instead of handing addArc an inverted
 *  oval rect, which would otherwise silently draw a mirrored/garbage arc rather than
 *  nothing. Zero radius is allowed through (degenerate arc that draws nothing, same as
 *  what addArc would already do with a zero-size oval).
 */
internal fun computeArcGeometry(
    cx: Float,
    cy: Float,
    r: Float,
    startAngleRad: Float,
    endAngleRad: Float,
    anticlockwise: Boolean,
): FloatArray? {
    if (r < 0f) return null
    val startDeg = Math.toDegrees(startAngleRad.toDouble()).toFloat()
    val endDeg = Math.toDegrees(endAngleRad.toDouble()).toFloat()
    var sweep = endDeg - startDeg
    if (anticlockwise && sweep > 0) sweep -= 360f
    if (!anticlockwise && sweep < 0) sweep += 360f
    return floatArrayOf(cx - r, cy - r, cx + r, cy + r, startDeg, sweep)
}

class CanvasContext2D(val bitmap: Bitmap) : JsObject() {
    private val canvas = Canvas(bitmap)
    private var fillColor = Color.BLACK
    private var strokeColor = Color.BLACK
    private var lineWidthPx = 1f
    private var fontSizePx = 10f
    private var globalAlphaValue = 1f
    private var path = Path()
    private val styleStack = CanvasStyleStack()

    // Paint.alpha overwrites whatever alpha `color` carried, so this combines the
    // color's own alpha with globalAlpha before assigning, rather than losing one of them.
    private fun combinedAlpha(color: Int): Int = (((color ushr 24) and 0xFF) * globalAlphaValue).toInt().coerceIn(0, 255)

    private fun fillPaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fillColor
        style = Paint.Style.FILL
        alpha = combinedAlpha(fillColor)
    }

    private fun strokePaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = strokeColor
        style = Paint.Style.STROKE
        strokeWidth = lineWidthPx
        alpha = combinedAlpha(strokeColor)
    }

    private fun f(args: List<JsValue>, i: Int): Float = toNumber(args.getOrElse(i) { JsUndefined }).toFloat()

    override fun get(name: String): JsValue = when (name) {
        "fillStyle" -> JsString(String.format("#%06X", fillColor and 0xFFFFFF))
        "strokeStyle" -> JsString(String.format("#%06X", strokeColor and 0xFFFFFF))
        "lineWidth" -> JsNumber(lineWidthPx.toDouble())
        "globalAlpha" -> JsNumber(globalAlphaValue.toDouble())
        "fillRect" -> NativeFunction("fillRect", 4) { _, _, a ->
            canvas.drawRect(f(a, 0), f(a, 1), f(a, 0) + f(a, 2), f(a, 1) + f(a, 3), fillPaint())
            JsUndefined
        }
        "strokeRect" -> NativeFunction("strokeRect", 4) { _, _, a ->
            canvas.drawRect(f(a, 0), f(a, 1), f(a, 0) + f(a, 2), f(a, 1) + f(a, 3), strokePaint())
            JsUndefined
        }
        "clearRect" -> NativeFunction("clearRect", 4) { _, _, a ->
            val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
            canvas.drawRect(f(a, 0), f(a, 1), f(a, 0) + f(a, 2), f(a, 1) + f(a, 3), clearPaint)
            JsUndefined
        }
        "beginPath" -> NativeFunction("beginPath", 0) { _, _, _ -> path = Path(); JsUndefined }
        "closePath" -> NativeFunction("closePath", 0) { _, _, _ -> path.close(); JsUndefined }
        "moveTo" -> NativeFunction("moveTo", 2) { _, _, a -> path.moveTo(f(a, 0), f(a, 1)); JsUndefined }
        "lineTo" -> NativeFunction("lineTo", 2) { _, _, a -> path.lineTo(f(a, 0), f(a, 1)); JsUndefined }
        "rect" -> NativeFunction("rect", 4) { _, _, a ->
            path.addRect(f(a, 0), f(a, 1), f(a, 0) + f(a, 2), f(a, 1) + f(a, 3), Path.Direction.CW)
            JsUndefined
        }
        "arc" -> NativeFunction("arc", 5) { _, _, a ->
            val anticlockwise = (a.getOrNull(5) as? JsBoolean)?.value ?: false
            computeArcGeometry(f(a, 0), f(a, 1), f(a, 2), f(a, 3), f(a, 4), anticlockwise)?.let { g ->
                path.addArc(g[0], g[1], g[2], g[3], g[4], g[5])
            }
            JsUndefined
        }
        "fill" -> NativeFunction("fill", 0) { _, _, _ -> canvas.drawPath(path, fillPaint()); JsUndefined }
        "stroke" -> NativeFunction("stroke", 0) { _, _, _ -> canvas.drawPath(path, strokePaint()); JsUndefined }
        "fillText" -> NativeFunction("fillText", 3) { _, _, a ->
            canvas.drawText(toJsString(a.getOrElse(0) { JsUndefined }), f(a, 1), f(a, 2), fillPaint().apply { textSize = fontSizePx })
            JsUndefined
        }
        "strokeText" -> NativeFunction("strokeText", 3) { _, _, a ->
            canvas.drawText(toJsString(a.getOrElse(0) { JsUndefined }), f(a, 1), f(a, 2), strokePaint().apply { textSize = fontSizePx })
            JsUndefined
        }
        "measureText" -> NativeFunction("measureText", 1) { _, _, a ->
            val w = Paint().apply { textSize = fontSizePx }.measureText(toJsString(a.getOrElse(0) { JsUndefined }))
            JsObject().apply { set("width", JsNumber(w.toDouble())) }
        }
        "save" -> NativeFunction("save", 0) { _, _, _ ->
            styleStack.push(CanvasStyleSnapshot(fillColor, strokeColor, lineWidthPx, fontSizePx, globalAlphaValue))
            canvas.save()
            JsUndefined
        }
        "restore" -> NativeFunction("restore", 0) { _, _, _ ->
            // A restore() with no matching save() must no-op, not throw - Canvas.restore()
            // itself would throw IllegalStateException on an empty stack, so only call it
            // when the style stack (kept 1:1 with canvas.save() calls) actually has an entry.
            styleStack.pop()?.let { s ->
                fillColor = s.fillColor
                strokeColor = s.strokeColor
                lineWidthPx = s.lineWidthPx
                fontSizePx = s.fontSizePx
                globalAlphaValue = s.globalAlphaValue
                canvas.restore()
            }
            JsUndefined
        }
        "translate" -> NativeFunction("translate", 2) { _, _, a -> canvas.translate(f(a, 0), f(a, 1)); JsUndefined }
        "scale" -> NativeFunction("scale", 2) { _, _, a -> canvas.scale(f(a, 0), f(a, 1)); JsUndefined }
        "rotate" -> NativeFunction("rotate", 1) { _, _, a -> canvas.rotate(Math.toDegrees(f(a, 0).toDouble()).toFloat()); JsUndefined }
        else -> super.get(name)
    }

    override fun set(name: String, value: JsValue) {
        when (name) {
            "fillStyle" -> parseCssColor(toJsString(value))?.let { fillColor = it }
            "strokeStyle" -> parseCssColor(toJsString(value))?.let { strokeColor = it }
            "lineWidth" -> lineWidthPx = toNumber(value).toFloat()
            "globalAlpha" -> globalAlphaValue = toNumber(value).toFloat().coerceIn(0f, 1f)
            "font" -> Regex("([0-9.]+)px").find(toJsString(value))?.groupValues?.get(1)?.toFloatOrNull()?.let { fontSizePx = it }
            else -> super.set(name, value)
        }
    }
}
