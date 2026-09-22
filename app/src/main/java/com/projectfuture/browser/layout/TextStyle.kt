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
    val linkHref: String?
)

fun textStyleForElement(node: ElementNode, linkHref: String?): TextStyle {
    val style = node.style
    val sizePx = parsePx(style["font-size"]) ?: 16f
    val weight = style["font-weight"] ?: "normal"
    val bold = weight == "bold" || (weight.toIntOrNull() ?: 0) >= 700
    val italic = (style["font-style"] ?: "normal").let { it == "italic" || it == "oblique" }
    val fontFamily = style["font-family"]?.lowercase() ?: ""
    val monospace = fontFamily.contains("mono") || node.tag in setOf("code", "pre", "tt", "kbd", "samp")
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
        linkHref = linkHref
    )
}

/** Caches Paint objects by their resolved style so we're not allocating one per glyph run. */
object FontCache {
    private val cache = HashMap<String, Paint>()

    fun paintFor(style: TextStyle): Paint {
        val key = "${style.sizePx}-${style.bold}-${style.italic}-${style.monospace}"
        return cache.getOrPut(key) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = style.sizePx
                val base = if (style.monospace) Typeface.MONOSPACE else Typeface.DEFAULT
                var flags = 0
                if (style.bold) flags = flags or Typeface.BOLD
                if (style.italic) flags = flags or Typeface.ITALIC
                typeface = Typeface.create(base, flags)
            }
        }
    }
}
