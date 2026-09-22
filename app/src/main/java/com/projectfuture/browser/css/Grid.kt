package com.projectfuture.browser.css

/**
 * A deliberately bounded CSS Grid implementation: `grid-template-columns`
 * (fixed/percent/`fr` tracks, `repeat(N, ...)`) plus simple row-major
 * auto-placement, gap. NOT implemented, as follow-up work:
 * `grid-template-rows` track sizing (rows are always auto-sized to their
 * tallest item), explicit placement (`grid-column`/`grid-row`/`grid-area`,
 * named lines/areas), `repeat(auto-fill/auto-fit, ...)`, subgrid, and
 * justify/align-items (grid items are top-left aligned in their cell, not
 * stretched). This covers the common "template-columns gallery/card grid"
 * pattern, not the full spec.
 */
sealed class GridTrackSize {
    data class Fixed(val px: Float) : GridTrackSize()
    data class Fraction(val fr: Float) : GridTrackSize()
}

data class GridContainerProps(
    val columns: List<GridTrackSize>,
    val rowGap: Float,
    val columnGap: Float
)

fun resolveGridContainerProps(style: Map<String, String>, containingWidth: Float, fontSizePx: Float): GridContainerProps {
    val columns = parseTrackList(style["grid-template-columns"], containingWidth, fontSizePx)

    var rowGap = 0f
    var columnGap = 0f
    style["gap"]?.let { raw ->
        val parts = raw.trim().split(Regex("\\s+"))
        rowGap = lengthValue(parts.getOrElse(0) { "0" }, containingWidth, fontSizePx) ?: 0f
        columnGap = lengthValue(parts.getOrElse(1) { parts[0] }, containingWidth, fontSizePx) ?: rowGap
    }
    style["row-gap"]?.let { lengthValue(it, containingWidth, fontSizePx)?.let { v -> rowGap = v } }
    style["column-gap"]?.let { lengthValue(it, containingWidth, fontSizePx)?.let { v -> columnGap = v } }

    return GridContainerProps(columns, rowGap, columnGap)
}

/** Resolves each column's pixel width: fixed tracks first, remaining space split across `fr` tracks. */
fun resolveGridColumnWidths(columns: List<GridTrackSize>, availableWidth: Float, columnGap: Float): FloatArray {
    val n = columns.size.coerceAtLeast(1)
    val gapTotal = columnGap * (n - 1).coerceAtLeast(0)
    val fixedSum = columns.filterIsInstance<GridTrackSize.Fixed>().sumOf { it.px.toDouble() }.toFloat()
    val frSum = columns.filterIsInstance<GridTrackSize.Fraction>().sumOf { it.fr.toDouble() }.toFloat()
    val remaining = (availableWidth - gapTotal - fixedSum).coerceAtLeast(0f)
    val frUnit = if (frSum > 0f) remaining / frSum else 0f
    return FloatArray(n) { i ->
        when (val c = columns[i]) {
            is GridTrackSize.Fixed -> c.px
            is GridTrackSize.Fraction -> c.fr * frUnit
        }
    }
}

private fun parseTrackList(raw: String?, containingWidth: Float, fontSizePx: Float): List<GridTrackSize> {
    if (raw.isNullOrBlank()) return listOf(GridTrackSize.Fraction(1f))
    val tokens = expandRepeat(raw.trim())
    val tracks = tokens.mapNotNull { parseTrackToken(it, containingWidth, fontSizePx) }
    return tracks.ifEmpty { listOf(GridTrackSize.Fraction(1f)) }
}

/** Expands `repeat(N, <tracks>)` by literal repetition. `auto-fill`/`auto-fit` counts aren't supported (treated as 1). */
private fun expandRepeat(raw: String): List<String> {
    val result = ArrayList<String>()
    var i = 0
    while (i < raw.length) {
        if (raw.startsWith("repeat(", i)) {
            val close = findMatchingParen(raw, i + 6)
            if (close == -1) break
            val inner = raw.substring(i + 7, close)
            val comma = inner.indexOf(',')
            if (comma != -1) {
                val count = inner.substring(0, comma).trim().toIntOrNull() ?: 1
                val trackPart = inner.substring(comma + 1).trim()
                repeat(count) { result.addAll(trackPart.split(Regex("\\s+")).filter { it.isNotEmpty() }) }
            }
            i = close + 1
        } else {
            val nextSpace = raw.indexOf(' ', i)
            val token = if (nextSpace == -1) raw.substring(i) else raw.substring(i, nextSpace)
            if (token.isNotEmpty()) result.add(token)
            i = if (nextSpace == -1) raw.length else nextSpace + 1
        }
    }
    return result
}

private fun findMatchingParen(s: String, openIdx: Int): Int {
    var depth = 0
    for (i in openIdx until s.length) {
        when (s[i]) {
            '(' -> depth++
            ')' -> { depth--; if (depth == 0) return i }
        }
    }
    return -1
}

private fun parseTrackToken(token: String, containingWidth: Float, fontSizePx: Float): GridTrackSize? {
    val t = token.trim()
    if (t.isEmpty()) return null
    if (t.endsWith("fr")) return t.removeSuffix("fr").toFloatOrNull()?.let { GridTrackSize.Fraction(it) }
    if (t == "auto" || t == "min-content" || t == "max-content") return GridTrackSize.Fraction(1f)
    return lengthValue(t, containingWidth, fontSizePx)?.let { GridTrackSize.Fixed(it) }
}
