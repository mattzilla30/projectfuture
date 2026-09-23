package com.projectfuture.browser.css

/**
 * Resolves the CSS box model (margin/border/padding/box-sizing/explicit
 * width+height) for one element against its containing block's width.
 *
 * Known simplification: no margin collapsing between adjacent block boxes.
 * Real CSS collapses adjacent vertical margins to the larger of the two
 * (and collapses through empty blocks, parent/child boundaries, etc.);
 * this engine currently just sums them, which is simpler and correct for
 * the common non-adjacent case but will show extra vertical gaps versus a
 * real browser wherever collapsing would normally kick in.
 */
data class BoxMetrics(
    val marginTop: Float,
    val marginRight: Float,
    val marginBottom: Float,
    val marginLeft: Float,
    val borderTop: Float,
    val borderRight: Float,
    val borderBottom: Float,
    val borderLeft: Float,
    val paddingTop: Float,
    val paddingRight: Float,
    val paddingBottom: Float,
    val paddingLeft: Float,
    val borderColorTop: Int,
    val borderColorRight: Int,
    val borderColorBottom: Int,
    val borderColorLeft: Int,
    /** Resolved content-box width if `width` was specified, else null (auto). */
    val explicitContentWidth: Float?,
    /** Resolved content-box height if `height` was specified in px/em, else null (auto or %). */
    val explicitContentHeight: Float?
)

private val BORDER_STYLES = setOf(
    "none", "hidden", "dotted", "dashed", "solid", "double", "groove", "ridge", "inset", "outset"
)

fun resolveBoxMetrics(style: Map<String, String>, containingWidth: Float, currentColor: Int, fontSizePx: Float): BoxMetrics {
    // CSS quirk (intentional, not a bug): vertical margin/padding percentages
    // resolve against the containing block's WIDTH, not its height.
    val marginTop = sideLength(style, "margin", "top", containingWidth, fontSizePx, allowAuto = true)
    val marginRight = sideLength(style, "margin", "right", containingWidth, fontSizePx, allowAuto = true)
    val marginBottom = sideLength(style, "margin", "bottom", containingWidth, fontSizePx, allowAuto = true)
    val marginLeft = sideLength(style, "margin", "left", containingWidth, fontSizePx, allowAuto = true)

    val paddingTop = sideLength(style, "padding", "top", containingWidth, fontSizePx, allowAuto = false)
    val paddingRight = sideLength(style, "padding", "right", containingWidth, fontSizePx, allowAuto = false)
    val paddingBottom = sideLength(style, "padding", "bottom", containingWidth, fontSizePx, allowAuto = false)
    val paddingLeft = sideLength(style, "padding", "left", containingWidth, fontSizePx, allowAuto = false)

    val border = resolveBorderSides(style, currentColor, fontSizePx)

    val boxSizing = style["box-sizing"] ?: "content-box"

    val explicitContentWidth = style["width"]?.let { raw ->
        val resolved = lengthValue(raw, containingWidth, fontSizePx) ?: return@let null
        if (boxSizing == "border-box") {
            (resolved - border.leftWidth - border.rightWidth - paddingLeft - paddingRight).coerceAtLeast(0f)
        } else {
            resolved
        }
    }

    val explicitContentHeight = style["height"]?.let { raw ->
        if (raw.trim().endsWith("%")) return@let null // no definite containing height to resolve against
        val resolved = lengthValue(raw, 0f, fontSizePx) ?: return@let null
        if (boxSizing == "border-box") {
            (resolved - border.topWidth - border.bottomWidth - paddingTop - paddingBottom).coerceAtLeast(0f)
        } else {
            resolved
        }
    }

    return BoxMetrics(
        marginTop, marginRight, marginBottom, marginLeft,
        border.topWidth, border.rightWidth, border.bottomWidth, border.leftWidth,
        paddingTop, paddingRight, paddingBottom, paddingLeft,
        border.topColor, border.rightColor, border.bottomColor, border.leftColor,
        explicitContentWidth, explicitContentHeight
    )
}

private data class BorderSides(
    val topWidth: Float, val rightWidth: Float, val bottomWidth: Float, val leftWidth: Float,
    val topColor: Int, val rightColor: Int, val bottomColor: Int, val leftColor: Int
)

private fun resolveBorderSides(style: Map<String, String>, currentColor: Int, fontSizePx: Float): BorderSides {
    var uniformWidth = 0f
    var uniformStyle: String? = null
    var uniformColor: Int? = null

    style["border"]?.let { parseBorderShorthand(it, fontSizePx).let { (w, s, c) ->
        w?.let { uniformWidth = it }; s?.let { uniformStyle = it }; c?.let { uniformColor = it }
    } }
    style["border-width"]?.let { borderWidthKeyword(it, fontSizePx)?.let { w -> uniformWidth = w } }
    style["border-style"]?.let { uniformStyle = it }
    style["border-color"]?.let { parseCssColor(it)?.let { uniformColor = it } }

    val widths = FloatArray(4) { uniformWidth } // top, right, bottom, left
    val styles: Array<String?> = Array(4) { uniformStyle }
    val colors: Array<Int?> = Array(4) { uniformColor }
    val sideNames = listOf("top", "right", "bottom", "left")

    for (i in sideNames.indices) {
        style["border-${sideNames[i]}"]?.let { raw ->
            val (w, s, c) = parseBorderShorthand(raw, fontSizePx)
            w?.let { widths[i] = it }
            s?.let { styles[i] = it }
            c?.let { colors[i] = it }
        }
    }

    fun effectiveWidth(i: Int): Float {
        val st = styles[i]
        return if (st == null || st == "none" || st == "hidden") 0f else widths[i]
    }

    return BorderSides(
        topWidth = effectiveWidth(0), rightWidth = effectiveWidth(1),
        bottomWidth = effectiveWidth(2), leftWidth = effectiveWidth(3),
        topColor = colors[0] ?: currentColor, rightColor = colors[1] ?: currentColor,
        bottomColor = colors[2] ?: currentColor, leftColor = colors[3] ?: currentColor
    )
}

/** Parses `border` / `border-<side>`: some combination of width, style keyword, color, in any order. */
private fun parseBorderShorthand(value: String, fontSizePx: Float): Triple<Float?, String?, Int?> {
    var width: Float? = null
    var style: String? = null
    var color: Int? = null
    for (token in value.trim().split(Regex("\\s+"))) {
        if (token.isEmpty()) continue
        when {
            token in BORDER_STYLES -> style = token
            borderWidthKeyword(token, fontSizePx) != null -> width = borderWidthKeyword(token, fontSizePx)
            else -> parseCssColor(token)?.let { color = it } ?: run {
                lengthValue(token, 0f, fontSizePx)?.let { width = it }
            }
        }
    }
    return Triple(width, style, color)
}

private fun borderWidthKeyword(raw: String, fontSizePx: Float): Float? = when (raw.trim()) {
    "thin" -> 1f
    "medium" -> 3f
    "thick" -> 5f
    else -> lengthValue(raw, 0f, fontSizePx)
}

private fun sideLength(
    style: Map<String, String>,
    prefix: String,
    side: String,
    percentBase: Float,
    fontSizePx: Float,
    allowAuto: Boolean
): Float {
    val longhand = style["$prefix-$side"]
    val raw = longhand ?: style[prefix]?.let { extractShorthandSide(it, side) }
    if (raw == null) return 0f
    if (allowAuto && raw.trim() == "auto") return 0f // no auto-margin centering support yet
    return lengthValue(raw, percentBase, fontSizePx) ?: 0f
}

private fun extractShorthandSide(shorthand: String, side: String): String? {
    val parts = shorthand.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    return when (parts.size) {
        1 -> parts[0]
        2 -> if (side == "top" || side == "bottom") parts[0] else parts[1]
        3 -> when (side) {
            "top" -> parts[0]
            "left", "right" -> parts[1]
            else -> parts[2] // bottom
        }
        else -> when (side) {
            "top" -> parts[0]
            "right" -> parts[1]
            "bottom" -> parts[2]
            else -> parts[3] // left
        }
    }
}

internal fun lengthValue(raw: String, percentBase: Float, fontSizePx: Float): Float? {
    val v = raw.trim()
    if (v.isEmpty() || v == "auto" || v == "none") return null
    if (v.startsWith("calc(") && v.endsWith(")")) {
        return evalCalc(v.substring(5, v.length - 1), percentBase, fontSizePx)
    }
    return when {
        v.endsWith("%") -> v.removeSuffix("%").toFloatOrNull()?.let { percentBase * it / 100f }
        v.endsWith("px") -> v.removeSuffix("px").toFloatOrNull()
        v.endsWith("em") -> v.removeSuffix("em").toFloatOrNull()?.let { fontSizePx * it }
        else -> v.toFloatOrNull() // unitless (e.g. "0")
    }
}

/**
 * Evaluates a flat sum/difference of terms, e.g. `calc(100% - 20px)` or
 * `calc(50% + 2em - 10px)`. Not full CSS calc(): no `*`/`/`, no nested
 * calc(), no mixing units within one multiplication (moot here since
 * there isn't one). Splits on `+`/`-` that have spaces on both sides,
 * exactly like the CSS calc() grammar requires - which conveniently also
 * disambiguates the operator from a negative term like `-20px`.
 */
private fun evalCalc(expr: String, percentBase: Float, fontSizePx: Float): Float? {
    val s = expr.trim()
    if (s.isEmpty()) return null
    val terms = ArrayList<Pair<Char, String>>()
    var sign = '+'
    val buf = StringBuilder()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        val isOperator = (c == '+' || c == '-') && i > 0 && s[i - 1] == ' ' && i + 1 < s.length && s[i + 1] == ' '
        if (isOperator) {
            terms.add(sign to buf.toString().trim())
            buf.setLength(0)
            sign = c
        } else {
            buf.append(c)
        }
        i++
    }
    if (buf.isNotBlank()) terms.add(sign to buf.toString().trim())
    if (terms.isEmpty()) return null

    var total = 0f
    for ((termSign, termText) in terms) {
        val value = lengthValue(termText, percentBase, fontSizePx) ?: return null
        total += if (termSign == '-') -value else value
    }
    return total
}
