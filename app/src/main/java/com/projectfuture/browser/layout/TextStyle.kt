package com.projectfuture.browser.layout

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.Color
import com.projectfuture.browser.css.parseCssColor
import com.projectfuture.browser.css.parsePx
import com.projectfuture.browser.html.ElementNode

/** Resolved, paint-ready text style for a run of inline content. */
data class TextStyle(
    val sizePx: Float,
    val bold: Boolean,
    val italic: Boolean,
    val monospace: Boolean,
    val color: Int,
    val underline: Boolean,
    val strikethrough: Boolean,
    val linkHref: String?,
    /** First non-generic font-family name (e.g. from `@font-face`), or null. See [customFonts]. */
    val fontFamilyName: String? = null
)

private val GENERIC_FONT_FAMILIES = setOf("sans-serif", "serif", "monospace", "cursive", "fantasy", "system-ui")

fun textStyleForElement(node: ElementNode, linkHref: String?): TextStyle {
    val style = node.style
    val sizePx = parsePx(style["font-size"]) ?: 16f
    val weight = style["font-weight"] ?: "normal"
    val bold = weight == "bold" || (weight.toIntOrNull() ?: 0) >= 700
    val italic = (style["font-style"] ?: "normal").let { it == "italic" || it == "oblique" }
    val fontFamilyRaw = style["font-family"] ?: ""
    val firstFamily = fontFamilyRaw.split(",").firstOrNull()?.trim()?.trim('"', '\'')
    val customFamilyName = firstFamily?.takeIf { it.isNotEmpty() && it.lowercase() !in GENERIC_FONT_FAMILIES }
    val monospace = fontFamilyRaw.lowercase().contains("mono") || node.tag in setOf("code", "pre", "tt", "kbd", "samp")
    val decoration = style["text-decoration"] ?: ""
    val color = parseCssColor(style["color"]) ?: Color.BLACK
    return TextStyle(
        sizePx = sizePx,
        bold = bold,
        italic = italic,
        monospace = monospace,
        color = color,
        underline = decoration.contains("underline"),
        strikethrough = decoration.contains("line-through"),
        linkHref = linkHref,
        fontFamilyName = customFamilyName
    )
}

/**
 * Fonts loaded from `@font-face` for the current document, keyed by
 * lowercased family name. Set once per page load by Tab; see
 * [currentImages] for why a module-level var (rather than threading a
 * parameter through every call site) is safe here.
 */
var customFonts: Map<String, Typeface> = emptyMap()

/** Caches Paint objects by their resolved style so we're not allocating one per glyph run. */
object FontCache {
    private val cache = HashMap<String, Paint>()

    fun paintFor(style: TextStyle): Paint {
        val key = "${style.sizePx}-${style.bold}-${style.italic}-${style.monospace}-${style.fontFamilyName}"
        return cache.getOrPut(key) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = style.sizePx
                val customBase = style.fontFamilyName?.let { customFonts[it.lowercase()] }
                val base = customBase ?: if (style.monospace) Typeface.MONOSPACE else Typeface.DEFAULT
                var flags = 0
                if (style.bold) flags = flags or Typeface.BOLD
                if (style.italic) flags = flags or Typeface.ITALIC
                typeface = Typeface.create(base, flags)
            }
        }
    }
}
