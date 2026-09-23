package com.projectfuture.browser.browser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.projectfuture.browser.css.CssParser
import com.projectfuture.browser.css.parseCssColor
import com.projectfuture.browser.html.ElementNode

/**
 * Renders a static (non-scripted) `<svg>` subtree to a Bitmap once, up
 * front - the same way `<img>` is decoded - so it flows through the exact
 * same inline-image layout/paint path (Tab.collectAndRenderSvgs,
 * LayoutBox's addImage) with no separate rendering pipeline needed.
 *
 * Deliberately narrow: `<rect>`/`<circle>`/`<ellipse>`/`<line>`/
 * `<polygon>`/`<polyline>`/`<g>`, plus `<path>` for the M/L/H/V/C/Z
 * commands only (absolute and relative). NOT implemented: Q/T/S/A
 * (quadratic/smooth/arc curves), `<text>`, gradients/patterns/clipping/
 * masking, `<use>`/`<symbol>`, nested `<svg>`, and CSS styling of SVG
 * content (only presentation attributes and an inline `style="..."` are
 * read, no author-stylesheet selector matching against SVG elements).
 * This covers simple icon/diagram SVGs built from basic shapes or plain
 * paths - most real-world logos/icon-font glyphs exported by design tools
 * lean heavily on the curve commands skipped here, so this is a genuinely
 * partial implementation, not general SVG support.
 */
object SvgRenderer {

    private data class ViewBox(val minX: Float, val minY: Float, val width: Float, val height: Float)

    fun render(svg: ElementNode): Bitmap? {
        val viewBox = parseViewBox(svg.attr("viewbox"))
        val width = svg.attr("width")?.toFloatOrNull() ?: viewBox?.width ?: 100f
        val height = svg.attr("height")?.toFloatOrNull() ?: viewBox?.height ?: 100f
        if (width <= 0f || height <= 0f || width > 4000f || height > 4000f) return null

        val bitmap = Bitmap.createBitmap(width.toInt().coerceAtLeast(1), height.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (viewBox != null && viewBox.width > 0f && viewBox.height > 0f) {
            canvas.scale(width / viewBox.width, height / viewBox.height)
            canvas.translate(-viewBox.minX, -viewBox.minY)
        }
        for (child in svg.children) {
            if (child is ElementNode) drawNode(canvas, child)
        }
        return bitmap
    }

    private fun drawNode(canvas: Canvas, el: ElementNode) {
        when (el.tag) {
            "rect" -> drawRect(canvas, el)
            "circle" -> drawCircle(canvas, el)
            "ellipse" -> drawEllipse(canvas, el)
            "line" -> drawLine(canvas, el)
            "polygon" -> drawPoly(canvas, el, close = true)
            "polyline" -> drawPoly(canvas, el, close = false)
            "path" -> drawPath(canvas, el)
            "g" -> for (child in el.children) if (child is ElementNode) drawNode(canvas, child)
        }
    }

    private fun attrF(el: ElementNode, name: String, default: Float = 0f): Float = el.attr(name)?.toFloatOrNull() ?: default

    private fun presentation(el: ElementNode, name: String): String? {
        val fromStyle = el.attr("style")?.let { CssParser.parseInlineDeclarations(it)[name] }
        return fromStyle ?: el.attr(name)
    }

    private fun fillPaint(el: ElementNode): Paint? {
        val raw = presentation(el, "fill")
        if (raw == "none") return null
        val color = raw?.let { parseCssColor(it) } ?: Color.BLACK // SVG's own default fill
        return Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }
    }

    private fun strokePaint(el: ElementNode): Paint? {
        val raw = presentation(el, "stroke") ?: return null // SVG's own default stroke is none
        if (raw == "none") return null
        val color = parseCssColor(raw) ?: return null
        val strokeW = presentation(el, "stroke-width")?.toFloatOrNull() ?: 1f
        return Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; this.color = color; strokeWidth = strokeW }
    }

    private fun drawShapePaints(canvas: Canvas, el: ElementNode, path: Path) {
        fillPaint(el)?.let { canvas.drawPath(path, it) }
        strokePaint(el)?.let { canvas.drawPath(path, it) }
    }

    private fun drawRect(canvas: Canvas, el: ElementNode) {
        val x = attrF(el, "x")
        val y = attrF(el, "y")
        val w = attrF(el, "width")
        val h = attrF(el, "height")
        if (w <= 0f || h <= 0f) return
        drawShapePaints(canvas, el, Path().apply { addRect(x, y, x + w, y + h, Path.Direction.CW) })
    }

    private fun drawCircle(canvas: Canvas, el: ElementNode) {
        val cx = attrF(el, "cx")
        val cy = attrF(el, "cy")
        val r = attrF(el, "r")
        if (r <= 0f) return
        drawShapePaints(canvas, el, Path().apply { addCircle(cx, cy, r, Path.Direction.CW) })
    }

    private fun drawEllipse(canvas: Canvas, el: ElementNode) {
        val cx = attrF(el, "cx")
        val cy = attrF(el, "cy")
        val rx = attrF(el, "rx")
        val ry = attrF(el, "ry")
        if (rx <= 0f || ry <= 0f) return
        drawShapePaints(canvas, el, Path().apply { addOval(cx - rx, cy - ry, cx + rx, cy + ry, Path.Direction.CW) })
    }

    private fun drawLine(canvas: Canvas, el: ElementNode) {
        val stroke = strokePaint(el) ?: return
        canvas.drawLine(attrF(el, "x1"), attrF(el, "y1"), attrF(el, "x2"), attrF(el, "y2"), stroke)
    }

    private fun drawPoly(canvas: Canvas, el: ElementNode, close: Boolean) {
        val points = el.attr("points") ?: return
        val nums = points.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.mapNotNull { it.toFloatOrNull() }
        if (nums.size < 4) return
        val path = Path()
        path.moveTo(nums[0], nums[1])
        var i = 2
        while (i + 1 < nums.size) {
            path.lineTo(nums[i], nums[i + 1])
            i += 2
        }
        if (close) path.close()
        drawShapePaints(canvas, el, path)
    }

    private fun drawPath(canvas: Canvas, el: ElementNode) {
        val d = el.attr("d") ?: return
        val path = parsePathData(d)
        drawShapePaints(canvas, el, path)
    }

    private fun parseViewBox(raw: String?): ViewBox? {
        if (raw == null) return null
        val nums = raw.trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
        if (nums.size != 4 || nums[2] <= 0f || nums[3] <= 0f) return null
        return ViewBox(nums[0], nums[1], nums[2], nums[3])
    }

    /** M/m, L/l, H/h, V/v, C/c, Z/z only - see class doc for what's skipped. */
    private fun parsePathData(d: String): Path {
        val path = Path()
        var i = 0
        val n = d.length
        var cx = 0f
        var cy = 0f
        var startX = 0f
        var startY = 0f
        var hasCurrentPoint = false

        fun skipSeparators() {
            while (i < n && (d[i].isWhitespace() || d[i] == ',')) i++
        }
        fun readNumber(): Float? {
            skipSeparators()
            val start = i
            if (i < n && (d[i] == '+' || d[i] == '-')) i++
            var sawDigitOrDot = false
            while (i < n && (d[i].isDigit() || d[i] == '.')) {
                i++
                sawDigitOrDot = true
            }
            if (i < n && (d[i] == 'e' || d[i] == 'E')) {
                i++
                if (i < n && (d[i] == '+' || d[i] == '-')) i++
                while (i < n && d[i].isDigit()) i++
            }
            if (!sawDigitOrDot) return null
            return d.substring(start, i).toFloatOrNull()
        }

        while (i < n) {
            skipSeparators()
            if (i >= n) break
            val cmd = d[i]
            if (!cmd.isLetter()) break // malformed; keep whatever was built so far
            i++
            when (cmd) {
                'M', 'm' -> {
                    val x = readNumber() ?: break
                    val y = readNumber() ?: break
                    if (cmd == 'm' && hasCurrentPoint) { cx += x; cy += y } else { cx = x; cy = y }
                    path.moveTo(cx, cy)
                    startX = cx
                    startY = cy
                    hasCurrentPoint = true
                }
                'L', 'l' -> {
                    val x = readNumber() ?: break
                    val y = readNumber() ?: break
                    if (cmd == 'l') { cx += x; cy += y } else { cx = x; cy = y }
                    path.lineTo(cx, cy)
                }
                'H', 'h' -> {
                    val x = readNumber() ?: break
                    cx = if (cmd == 'h') cx + x else x
                    path.lineTo(cx, cy)
                }
                'V', 'v' -> {
                    val y = readNumber() ?: break
                    cy = if (cmd == 'v') cy + y else y
                    path.lineTo(cx, cy)
                }
                'C', 'c' -> {
                    val x1 = readNumber() ?: break
                    val y1 = readNumber() ?: break
                    val x2 = readNumber() ?: break
                    val y2 = readNumber() ?: break
                    val x = readNumber() ?: break
                    val y = readNumber() ?: break
                    if (cmd == 'c') {
                        path.cubicTo(cx + x1, cy + y1, cx + x2, cy + y2, cx + x, cy + y)
                        cx += x
                        cy += y
                    } else {
                        path.cubicTo(x1, y1, x2, y2, x, y)
                        cx = x
                        cy = y
                    }
                }
                'Z', 'z' -> {
                    path.close()
                    cx = startX
                    cy = startY
                }
                else -> break // unsupported command (Q/T/S/A/...): stop, keep what's drawn so far
            }
        }
        return path
    }
}
