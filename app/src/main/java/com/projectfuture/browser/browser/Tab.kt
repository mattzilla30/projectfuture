package com.projectfuture.browser.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import com.projectfuture.browser.css.CssParser
import com.projectfuture.browser.css.CssRule
import com.projectfuture.browser.css.FontFaceRule
import com.projectfuture.browser.css.computeStyles
import com.projectfuture.browser.css.parseFontFaceRule
import com.projectfuture.browser.html.ElementNode
import com.projectfuture.browser.html.HtmlParser
import com.projectfuture.browser.html.TextNode
import com.projectfuture.browser.html.walkElements
import com.projectfuture.browser.js.Interpreter
import com.projectfuture.browser.js.Lexer
import com.projectfuture.browser.js.Parser
import com.projectfuture.browser.layout.DisplayCommand
import com.projectfuture.browser.layout.DocumentLayout
import com.projectfuture.browser.layout.FontDecoder
import com.projectfuture.browser.layout.customFonts
import com.projectfuture.browser.net.Url
import java.io.File
import java.util.concurrent.Executors

sealed class TabState {
    data class Loading(val url: Url) : TabState()
    data class Loaded(val url: Url, val title: String?) : TabState()
    data class Error(val url: Url, val message: String) : TabState()
}

private enum class HistoryAction { PUSH, NONE }

/**
 * Owns one page's lifecycle: fetch -> parse -> style -> layout, all off the
 * main thread except the final layout/paint handoff, plus back/forward
 * history. This is the only place that talks to the network and DOM/CSS/
 * layout modules together.
 */
class Tab(private val context: Context, private val onStateChanged: (TabState) -> Unit) {

    private val history = ArrayList<Url>()
    private var historyIndex = -1
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    var currentUrl: Url? = null
        private set
    var currentDoc: ElementNode? = null
        private set
    var displayList: List<DisplayCommand> = emptyList()
        private set
    var contentHeight: Float = 0f
        private set
    private var viewportWidth: Float = 0f
    private var viewportHeight: Float = 0f
    private var currentImages: Map<ElementNode, Bitmap> = emptyMap()

    fun canGoBack() = historyIndex > 0
    fun canGoForward() = historyIndex in 0 until (history.size - 1)

    fun navigate(addressBarInput: String) {
        load(Url.fromAddressBar(addressBarInput), HistoryAction.PUSH)
    }

    fun goBack() {
        if (!canGoBack()) return
        historyIndex--
        load(history[historyIndex], HistoryAction.NONE)
    }

    fun goForward() {
        if (!canGoForward()) return
        historyIndex++
        load(history[historyIndex], HistoryAction.NONE)
    }

    fun reload() {
        currentUrl?.let { load(it, HistoryAction.NONE) }
    }

    fun followLink(href: String) {
        val base = currentUrl ?: return
        load(base.resolve(href), HistoryAction.PUSH)
    }

    /** Called by the view when its size changes; re-runs layout without re-fetching. */
    fun onViewportSizeChanged(widthPx: Float, heightPx: Float): Boolean {
        if (widthPx <= 0f || heightPx <= 0f || (widthPx == viewportWidth && heightPx == viewportHeight)) return false
        viewportWidth = widthPx
        viewportHeight = heightPx
        relayout()
        return true
    }

    private fun load(url: Url, action: HistoryAction) {
        onStateChanged(TabState.Loading(url))
        // Read on the main thread (load() is always called from one) before
        // handing off to the background executor, rather than reading the
        // mutable viewportWidth field from that other thread later.
        val mediaViewportWidth = if (viewportWidth > 0f) viewportWidth else 360f
        executor.execute {
            try {
                val response = url.fetch()
                val root = HtmlParser(response.body).parse()
                runScripts(root, response.url) // may mutate the DOM before CSS/images/fonts are collected below
                val authorCss = collectAuthorCss(root, response.url, mediaViewportWidth)
                computeStyles(root, authorCss.rules)
                val title = extractTitle(root)
                val images = collectAndDecodeImages(root, response.url) + collectAndRenderSvgs(root)
                val fonts = loadFontFaces(authorCss.fontFaces)
                mainHandler.post {
                    currentUrl = response.url
                    currentDoc = root
                    currentImages = images
                    customFonts = fonts
                    when (action) {
                        HistoryAction.PUSH -> {
                            while (history.size > historyIndex + 1) history.removeAt(history.size - 1)
                            history.add(response.url)
                            historyIndex = history.size - 1
                        }
                        HistoryAction.NONE -> if (history.isEmpty()) {
                            history.add(response.url)
                            historyIndex = 0
                        }
                    }
                    relayout()
                    onStateChanged(TabState.Loaded(response.url, title))
                }
            } catch (e: Exception) {
                mainHandler.post { onStateChanged(TabState.Error(url, e.message ?: e.toString())) }
            }
        }
    }

    private fun relayout() {
        val doc = currentDoc ?: return
        if (viewportWidth <= 0f || viewportHeight <= 0f) return
        val docLayout = DocumentLayout(doc)
        displayList = docLayout.layout(viewportWidth, viewportHeight, currentImages)
        contentHeight = docLayout.height
    }

    /**
     * Fetches and decodes every `<img src>` up front (sequentially, on the
     * background executor) before layout runs. Image *decoding* itself uses
     * Android's BitmapFactory - a platform media codec, the same boundary
     * already drawn for text glyph shaping - rather than a hand-written
     * JPEG/PNG/WebP decoder, which would each be a substantial project of
     * their own. Fetching the bytes and wiring decoded bitmaps into layout
     * is this engine's own code. A broken or unreachable image is simply
     * skipped, matching how the reference build already treats a failed
     * stylesheet fetch - no broken-image icon or alt-text fallback yet.
     */
    private fun collectAndDecodeImages(root: ElementNode, baseUrl: Url): Map<ElementNode, Bitmap> {
        val result = HashMap<ElementNode, Bitmap>()
        root.walkElements { el ->
            if (el.tag == "img") {
                val src = el.attr("src")
                if (!src.isNullOrBlank()) {
                    try {
                        val bytes = baseUrl.resolve(src).fetchBytes().body
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { result[el] = it }
                    } catch (_: Exception) {
                        // Broken/unreachable image: skip it rather than failing the whole page load.
                    }
                }
            }
        }
        return result
    }

    /** Rasterizes each `<svg>` to a Bitmap up front - see SvgRenderer for what it does and doesn't support. */
    private fun collectAndRenderSvgs(root: ElementNode): Map<ElementNode, Bitmap> {
        val result = HashMap<ElementNode, Bitmap>()
        root.walkElements { el ->
            if (el.tag == "svg") {
                try {
                    SvgRenderer.render(el)?.let { result[el] = it }
                } catch (_: Exception) {
                    // Malformed/unsupported SVG content: skip it rather than failing the whole page load.
                }
            }
        }
        return result
    }

    /**
     * Runs every `<script>` (inline or external `src=`) in document order
     * against a fresh Interpreter with the DOM bridge installed, then
     * fires `DOMContentLoaded`. A script that fails to fetch, parse, or
     * run is skipped rather than failing the whole page load - matching
     * how a broken stylesheet is already handled above. See DomBridge's
     * class doc for what the script<->DOM/window bridge does and doesn't
     * cover yet (no real event dispatch from taps, no setTimeout/fetch).
     */
    private fun runScripts(root: ElementNode, baseUrl: Url) {
        val interpreter = Interpreter()
        val bridge = DomBridge(root)
        bridge.install(interpreter.globalEnv)

        val scripts = ArrayList<ElementNode>()
        root.walkElements { if (it.tag == "script") scripts.add(it) }

        for (scriptEl in scripts) {
            val src = scriptEl.attr("src")
            val code = if (!src.isNullOrBlank()) {
                try { baseUrl.resolve(src).fetch().body } catch (_: Exception) { null }
            } else {
                scriptEl.children.filterIsInstance<TextNode>().joinToString("") { it.text }
            }
            if (code.isNullOrBlank()) continue
            try {
                interpreter.run(Parser(Lexer(code).tokenize()).parseProgram())
            } catch (_: Exception) {
                // A script that fails to parse or throws shouldn't take down the whole page.
            }
        }

        bridge.fireDomContentLoaded(interpreter)
    }

    private class AuthorCss(val rules: List<CssRule>, val fontFaces: List<Pair<FontFaceRule, Url>>)

    private fun collectAuthorCss(root: ElementNode, baseUrl: Url, viewportWidth: Float): AuthorCss {
        val rules = ArrayList<CssRule>()
        val fontFaces = ArrayList<Pair<FontFaceRule, Url>>()

        fun harvest(parser: CssParser, styleSheetBase: Url) {
            rules.addAll(parser.parseRules())
            for (decl in parser.fontFaceRules) {
                parseFontFaceRule(decl)?.let { fontFaces.add(it to styleSheetBase) }
            }
        }

        root.walkElements { el ->
            when {
                el.tag == "style" -> {
                    val text = el.children.filterIsInstance<TextNode>().joinToString("") { it.text }
                    harvest(CssParser(text, viewportWidth), baseUrl)
                }
                el.tag == "link" && el.attr("rel")?.lowercase()?.contains("stylesheet") == true -> {
                    val href = el.attr("href")
                    if (href != null) {
                        try {
                            val styleSheetUrl = baseUrl.resolve(href)
                            val response = styleSheetUrl.fetch()
                            // url()s inside an external stylesheet resolve against ITS location, not the page's.
                            harvest(CssParser(response.body, viewportWidth), styleSheetUrl)
                        } catch (_: Exception) {
                            // A failed stylesheet fetch shouldn't block the page from rendering.
                        }
                    }
                }
            }
        }
        return AuthorCss(rules, fontFaces)
    }

    /**
     * Fetches and converts each @font-face's font file to SFNT (see
     * FontDecoder), then hands it to Typeface via a temp cache file -
     * Typeface.createFromFile works across all supported API levels,
     * unlike the newer ByteBuffer-based builder. The first successfully
     * loaded font for a given family wins; later ones for the same family
     * (e.g. bold/italic variants declared separately) are ignored, and
     * FontCache synthesizes bold/italic from whichever one loaded.
     */
    private fun loadFontFaces(entries: List<Pair<FontFaceRule, Url>>): Map<String, Typeface> {
        val result = HashMap<String, Typeface>()
        for ((rule, styleSheetBase) in entries) {
            val key = rule.family.lowercase()
            if (result.containsKey(key)) continue
            try {
                val bytes = styleSheetBase.resolve(rule.srcUrl).fetchBytes().body
                val sfnt = FontDecoder.toSfnt(bytes) ?: continue
                val tempFile = File.createTempFile("font", ".ttf", context.cacheDir)
                try {
                    tempFile.writeBytes(sfnt)
                    result[key] = Typeface.createFromFile(tempFile)
                } finally {
                    tempFile.delete()
                }
            } catch (_: Exception) {
                // Unsupported (e.g. WOFF2) or broken font file: skip it, inherited/system font still applies.
            }
        }
        return result
    }

    private fun extractTitle(root: ElementNode): String? {
        var title: String? = null
        root.walkElements { el ->
            if (el.tag == "title" && title == null) {
                val text = el.children.filterIsInstance<TextNode>().joinToString("") { it.text }.trim()
                if (text.isNotEmpty()) title = text
            }
        }
        return title
    }

    fun destroy() {
        executor.shutdownNow()
    }
}
