package com.projectfuture.browser.css

/**
 * A resolved `@font-face` declaration: one font file for a family, plus what the loader needs to
 * choose between the several rules a family usually has. Google Fonts, for example, splits each
 * family into cyrillic, greek, vietnamese, latin-ext and latin subsets by `unicode-range`, and
 * declares each weight separately. `local()` sources are skipped (only remote `url()` is fetched).
 */
data class FontFaceRule(
    val family: String,
    val srcUrl: String,
    /** Whether `unicode-range` includes basic Latin letters (true when the rule has no range). */
    val coversLatin: Boolean = true,
    val weight: Int = 400,
    val italic: Boolean = false
)

private val SUPPORTED_FORMATS = setOf("woff2", "woff", "truetype", "opentype", "woff2-variations", "woff-variations", "truetype-variations", "opentype-variations")
private val SRC_ENTRY = Regex("""url\(\s*(['"]?)(.*?)\1\s*\)(?:\s*format\(\s*['"]?([^'")]+)['"]?\s*\))?""")

fun parseFontFaceRule(declarations: Map<String, String>): FontFaceRule? {
    val family = declarations["font-family"]?.trim()?.trim('"', '\'') ?: return null
    val src = declarations["src"] ?: return null
    val url = pickSource(src) ?: return null
    if (family.isEmpty() || url.isEmpty()) return null
    val weight = when (val w = declarations["font-weight"]?.trim()?.lowercase()) {
        null, "normal" -> 400
        "bold" -> 700
        // A variable font's range ("100 900") covers regular text.
        else -> w.split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }.let { if (it.size == 2 && 400 in it[0]..it[1]) 400 else it.firstOrNull() ?: 400 }
    }
    val italic = declarations["font-style"]?.trim()?.lowercase()?.let { it.startsWith("italic") || it.startsWith("oblique") } ?: false
    val coversLatin = declarations["unicode-range"]?.let { unicodeRangeContains(it, 'a'.code) } ?: true
    return FontFaceRule(family, url, coversLatin, weight, italic)
}

/** The first `url()` whose `format()` we can decode; a source without `format()` counts unless its extension says EOT or SVG. */
private fun pickSource(src: String): String? {
    for (m in SRC_ENTRY.findAll(src)) {
        val url = m.groupValues[2].trim()
        if (url.isEmpty()) continue
        val format = m.groupValues[3].trim().lowercase()
        val ok = if (format.isNotEmpty()) format in SUPPORTED_FORMATS
        else !url.substringBefore('?').substringBefore('#').lowercase().let { it.endsWith(".eot") || it.endsWith(".svg") }
        if (ok) return url
    }
    return null
}

/** Checks a `unicode-range` list such as `U+0000-00FF, U+0131, U+4??` for [codePoint]. */
fun unicodeRangeContains(range: String, codePoint: Int): Boolean = range.split(',').any { part ->
    val p = part.trim().uppercase().removePrefix("U+")
    if (p.isEmpty()) return@any false
    val (lo, hi) = when {
        '-' in p -> p.substringBefore('-').toIntOrNull(16) to p.substringAfter('-').toIntOrNull(16)
        '?' in p -> p.replace('?', '0').toIntOrNull(16) to p.replace('?', 'F').toIntOrNull(16)
        else -> p.toIntOrNull(16).let { it to it }
    }
    lo != null && hi != null && codePoint in lo..hi
}
