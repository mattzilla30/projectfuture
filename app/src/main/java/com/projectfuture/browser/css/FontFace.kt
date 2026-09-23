package com.projectfuture.browser.css

/**
 * A resolved `@font-face` declaration: just enough to fetch one font file
 * per family. Multiple `src` fallbacks (`url() format(), url() format()`)
 * and per-weight/per-style variants aren't modeled - the first `url()`
 * found wins, and later @font-face rules for a family already loaded are
 * ignored. `local()` sources are skipped (only remote `url()` is fetched).
 */
data class FontFaceRule(val family: String, val srcUrl: String)

fun parseFontFaceRule(declarations: Map<String, String>): FontFaceRule? {
    val family = declarations["font-family"]?.trim()?.trim('"', '\'') ?: return null
    val src = declarations["src"] ?: return null
    val url = extractFirstUrl(src) ?: return null
    if (family.isEmpty() || url.isEmpty()) return null
    return FontFaceRule(family, url)
}

private fun extractFirstUrl(src: String): String? {
    val idx = src.indexOf("url(")
    if (idx == -1) return null
    val end = src.indexOf(')', idx)
    if (end == -1) return null
    return src.substring(idx + 4, end).trim().trim('"', '\'')
}
