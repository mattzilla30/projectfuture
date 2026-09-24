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

/**
 * Splits a track list on whitespace, but NOT inside parens - so a function
 * like `minmax(100px, 1fr)` or `repeat(2, 1fr)` stays one token instead of
 * being shredded into fragments (`minmax(100px,` / `1fr)`) that fail to
 * parse as anything and get silently dropped, corrupting the column count.
 */
private fun splitTopLevel(s: String): List<String> {
    val tokens = ArrayList<String>()
    val buf = StringBuilder()
    var depth = 0
    for (c in s) {
        when {
            c == '(' -> { depth++; buf.append(c) }
            c == ')' -> { depth--; buf.append(c) }
            c.isWhitespace() && depth == 0 -> {
                if (buf.isNotEmpty()) { tokens.add(buf.toString()); buf.setLength(0) }
            }
            else -> buf.append(c)
        }
    }
    if (buf.isNotEmpty()) tokens.add(buf.toString())
    return tokens
}

/** Index of the first comma at paren-depth 0, or -1 - so `minmax(1px, 2px)` inside a `repeat(...)`'s track list isn't mistaken for the repeat's own count/track-list separator. */
private fun topLevelCommaIndex(s: String): Int {
    var depth = 0
    for (i in s.indices) {
        when (s[i]) {
            '(' -> depth++
            ')' -> depth--
            ',' -> if (depth == 0) return i
        }
    }
    return -1
}

/** Expands `repeat(N, <tracks>)` by literal repetition. `auto-fill`/`auto-fit` counts aren't supported (treated as 1). */
private fun expandRepeat(raw: String): List<String> {
    val result = ArrayList<String>()
    for (tok in splitTopLevel(raw)) {
        if (tok.startsWith("repeat(") && tok.endsWith(")")) {
            val inner = tok.substring(7, tok.length - 1)
            val comma = topLevelCommaIndex(inner)
            if (comma != -1) {
                val count = inner.substring(0, comma).trim().toIntOrNull() ?: 1
                val trackPart = inner.substring(comma + 1).trim()
                repeat(count) { result.addAll(splitTopLevel(trackPart)) }
            }
        } else {
            result.add(tok)
        }
    }
    return result
}

/**
 * `minmax(min, max)` isn't given a true min/max-content-aware clamp (this
 * engine doesn't do intrinsic sizing passes for grid tracks) - as a
 * reasonable approximation, the flexible side wins when one bound is an
 * `fr`, otherwise the larger fixed bound is used. Without this, minmax()
 * fell through to plain whitespace tokenization (see [splitTopLevel]) and
 * was silently dropped entirely rather than cleanly rejected.
 */
private fun parseTrackToken(token: String, containingWidth: Float, fontSizePx: Float): GridTrackSize? {
    val t = token.trim()
    if (t.isEmpty()) return null
    if (t.startsWith("minmax(") && t.endsWith(")")) {
        val inner = t.substring(7, t.length - 1)
        val comma = topLevelCommaIndex(inner)
        if (comma == -1) return null
        val minTrack = parseTrackToken(inner.substring(0, comma).trim(), containingWidth, fontSizePx)
        val maxTrack = parseTrackToken(inner.substring(comma + 1).trim(), containingWidth, fontSizePx)
        return if (maxTrack is GridTrackSize.Fraction) maxTrack else (maxTrack ?: minTrack)
    }
    if (t.endsWith("fr")) return t.removeSuffix("fr").toFloatOrNull()?.let { GridTrackSize.Fraction(it) }
    if (t == "auto" || t == "min-content" || t == "max-content") return GridTrackSize.Fraction(1f)
    return lengthValue(t, containingWidth, fontSizePx)?.let { GridTrackSize.Fixed(it) }
}
