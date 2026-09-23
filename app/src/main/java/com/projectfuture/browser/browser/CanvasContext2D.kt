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
class CanvasContext2D(val bitmap: Bitmap) : JsObject() {
    private val canvas = Canvas(bitmap)
    private var fillColor = Color.BLACK
    private var strokeColor = Color.BLACK
    private var lineWidthPx = 1f
    private var fontSizePx = 10f
    private var globalAlphaValue = 1f
    private var path = Path()

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
            val cx = f(a, 0)
            val cy = f(a, 1)
            val r = f(a, 2)
            val startDeg = Math.toDegrees(f(a, 3).toDouble()).toFloat()
            val endDeg = Math.toDegrees(f(a, 4).toDouble()).toFloat()
            val anticlockwise = (a.getOrNull(5) as? JsBoolean)?.value ?: false
            var sweep = endDeg - startDeg
            if (anticlockwise && sweep > 0) sweep -= 360f
            if (!anticlockwise && sweep < 0) sweep += 360f
            path.addArc(cx - r, cy - r, cx + r, cy + r, startDeg, sweep)
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
        "save" -> NativeFunction("save", 0) { _, _, _ -> canvas.save(); JsUndefined }
        "restore" -> NativeFunction("restore", 0) { _, _, _ -> canvas.restore(); JsUndefined }
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
