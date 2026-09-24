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
    val fontFamilyName: String? = null,
    /** The web font for [fontFamilyName], resolved when the style is built, so drawing never looks fonts up by name. */
    val typeface: Typeface? = null
)

private val GENERIC_FONT_FAMILIES = setOf("sans-serif", "serif", "monospace", "cursive", "fantasy", "system-ui")

fun textStyleForElement(node: ElementNode, linkHref: String?): TextStyle {
    val style = node.style
    val sizePx = (parsePx(style["font-size"]) ?: 16f) * textScaleFactor
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
        fontFamilyName = customFamilyName,
        typeface = customFamilyName?.let { customFonts[it.lowercase()] }
    )
}

/**
 * Fonts loaded from `@font-face` for the document being laid out, keyed by lowercased family
 * name. Tab sets it before each layout. It is per thread because each in-process tab lays out on
 * its own engine thread, and the UI thread must not see another tab's value mid-draw.
 */
private val customFontsLocal = ThreadLocal<Map<String, Typeface>>()
var customFonts: Map<String, Typeface>
    get() = customFontsLocal.get() ?: emptyMap()
    set(value) = customFontsLocal.set(value)

/** Builds a Typeface from SFNT bytes through a temp file (Typeface.createFromFile works on every supported API level). */
fun typefaceFromSfnt(sfnt: ByteArray, cacheDir: java.io.File): Typeface? {
    val tempFile = java.io.File.createTempFile("font", ".ttf", cacheDir)
    return try {
        tempFile.writeBytes(sfnt)
        Typeface.createFromFile(tempFile)
    } catch (_: Exception) {
        null
    } finally {
        tempFile.delete()
    }
}

/**
 * A user-controlled text-size multiplier (accessibility "text scaling" /
 * the practical effect of pinch-to-zoom on this engine - see BrowserView's
 * pinch handling, which adjusts this and re-lays-out on release rather
 * than applying a live Canvas scale transform). Applied once, in
 * [textStyleForElement], so it automatically affects line-breaking,
 * element sizing, and everything downstream of a resolved font size -
 * per thread like [customFonts].
 */
private val textScaleLocal = ThreadLocal<Float>()
var textScaleFactor: Float
    get() = textScaleLocal.get() ?: 1f
    set(value) = textScaleLocal.set(value)

/**
 * Caches Paint objects by resolved style so layout and drawing don't allocate one per text run.
 * Each thread gets its own cache: Paint isn't thread-safe, and engine threads measure text while
 * the UI thread draws. The cache is bounded because the key includes the Typeface's identity and
 * each page load brings new Typefaces.
 */
object FontCache {
    private const val MAX_ENTRIES = 256
    private val cache = ThreadLocal.withInitial {
        object : LinkedHashMap<String, Paint>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Paint>?) = size > MAX_ENTRIES
        }
    }

    fun paintFor(style: TextStyle): Paint {
        val face = style.typeface
        val key = "${style.sizePx}-${style.bold}-${style.italic}-${style.monospace}-${face?.let { System.identityHashCode(it) }}"
        return cache.get().getOrPut(key) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = style.sizePx
                val base = face ?: if (style.monospace) Typeface.MONOSPACE else Typeface.DEFAULT
                var flags = 0
                if (style.bold) flags = flags or Typeface.BOLD
                if (style.italic) flags = flags or Typeface.ITALIC
                typeface = Typeface.create(base, flags)
            }
        }
    }
}
