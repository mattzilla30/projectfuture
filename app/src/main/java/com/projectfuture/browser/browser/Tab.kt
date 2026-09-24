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
import com.projectfuture.browser.js.JsBoolean
import com.projectfuture.browser.js.JsFunction
import com.projectfuture.browser.js.JsNumber
import com.projectfuture.browser.js.JsObject
import com.projectfuture.browser.js.JsPromise
import com.projectfuture.browser.js.JsString
import com.projectfuture.browser.js.JsUndefined
import com.projectfuture.browser.js.Lexer
import com.projectfuture.browser.js.NativeFunction
import com.projectfuture.browser.js.Parser
import com.projectfuture.browser.js.parseJsonToJsValue
import com.projectfuture.browser.js.toJsString
import com.projectfuture.browser.layout.DisplayCommand
import com.projectfuture.browser.layout.DocumentLayout
import com.projectfuture.browser.layout.FontDecoder
import com.projectfuture.browser.layout.customFonts
import com.projectfuture.browser.net.HttpResponse
import com.projectfuture.browser.net.Url
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.Executors

sealed class TabState {
    data class Loading(val url: Url) : TabState()
    data class Loaded(val url: Url, val title: String?) : TabState()
    /** DOM was mutated by a click handler after load (not a navigation) - just repaint, don't touch scroll/address bar. */
    data class Updated(val url: Url, val title: String?) : TabState()
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
    private var currentInterpreter: Interpreter? = null
    private var currentDomBridge: DomBridge? = null
    private var currentAuthorRules: List<CssRule> = emptyList()
    private var timerIdCounter = 0
    private val canceledTimers = HashSet<Int>()

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

    /**
     * Called by MainActivity when BrowserView hit-tests a tap to a source
     * element. Bubbles the click through DomBridge; if anything actually
     * handled it (an inline onclick or an addEventListener listener ran),
     * re-styles and re-lays-out since the handler may have mutated the DOM,
     * then reports TabState.Updated rather than Loaded so the UI just
     * repaints without resetting scroll or the address bar.
     */
    fun dispatchClick(element: ElementNode) {
        val interpreter = currentInterpreter ?: return
        val bridge = currentDomBridge ?: return
        val doc = currentDoc ?: return
        val url = currentUrl ?: return
        val handled = try {
            bridge.dispatchClick(element, interpreter)
        } catch (_: Exception) {
            false
        }
        if (handled) {
            computeStyles(doc, currentAuthorRules)
            relayout()
            onStateChanged(TabState.Updated(url, extractTitle(doc)))
        }
    }

    /** Current text shown in a text-like `<input>`/`<textarea>`, used to pre-fill MainActivity's edit dialog. */
    fun currentFieldValue(element: ElementNode): String =
        if (element.tag == "textarea") element.children.filterIsInstance<TextNode>().joinToString("") { it.text }
        else element.attr("value") ?: ""

    fun toggleCheckbox(element: ElementNode) {
        if (element.attr("checked") != null) element.attributes.remove("checked") else element.attributes["checked"] = "checked"
        mutateAndRefresh()
    }

    /** Checks [element] and unchecks every other radio sharing its `name` within the same `<form>` (or the whole document if it's outside one). */
    fun selectRadio(element: ElementNode) {
        val name = element.attr("name")
        val scope = findAncestorForm(element) ?: currentDoc
        if (name != null) {
            scope?.walkElements { el ->
                if (el !== element && el.tag == "input" && (el.attr("type") ?: "").lowercase() == "radio" && el.attr("name") == name) {
                    el.attributes.remove("checked")
                }
            }
        }
        element.attributes["checked"] = "checked"
        mutateAndRefresh()
    }

    fun setFieldValue(element: ElementNode, value: String) {
        if (element.tag == "textarea") {
            element.children.clear()
            element.children.add(TextNode(value, element))
        } else {
            element.attributes["value"] = value
        }
        mutateAndRefresh()
    }

    fun setSelectValue(select: ElementNode, chosenOption: ElementNode) {
        select.children.filterIsInstance<ElementNode>().forEach { if (it.tag == "option") it.attributes.remove("selected") }
        chosenOption.attributes["selected"] = "selected"
        mutateAndRefresh()
    }

    /**
     * Submits [trigger]'s nearest ancestor `<form>`: gathers every named
     * control's current value (checkboxes/radios only when checked; a
     * `<select>` contributes its selected `<option>`'s value), URL-encodes
     * them, and navigates - as a `?query` string on the form's `action` for
     * `method="get"` (the default), or as a request body for
     * `method="post"`. `enctype="multipart/form-data"` (file uploads) isn't
     * supported - `<input type=file>` never has a value to contribute here.
     */
    fun submitForm(trigger: ElementNode) {
        val form = findAncestorForm(trigger) ?: return
        val baseUrl = currentUrl ?: return
        val action = form.attr("action")?.takeIf { it.isNotBlank() }?.let { baseUrl.resolve(it) } ?: baseUrl
        val method = (form.attr("method") ?: "get").trim().lowercase()

        val params = ArrayList<Pair<String, String>>()
        form.walkElements { el ->
            if (el === form) return@walkElements
            val name = el.attr("name") ?: return@walkElements
            when (el.tag) {
                "input" -> when ((el.attr("type") ?: "text").lowercase()) {
                    "submit", "button", "reset", "image", "file" -> {}
                    "checkbox", "radio" -> if (el.attr("checked") != null) params.add(name to (el.attr("value") ?: "on"))
                    else -> params.add(name to (el.attr("value") ?: ""))
                }
                "textarea" -> params.add(name to currentFieldValue(el))
                "select" -> {
                    val options = el.children.filterIsInstance<ElementNode>().filter { it.tag == "option" }
                    val chosen = options.firstOrNull { it.attr("selected") != null } ?: options.firstOrNull()
                    if (chosen != null) {
                        val value = chosen.attr("value") ?: chosen.children.filterIsInstance<TextNode>().joinToString("") { it.text }
                        params.add(name to value)
                    }
                }
            }
        }
        val encoded = params.joinToString("&") { (k, v) -> "${urlEncode(k)}=${urlEncode(v)}" }

        if (method == "post") {
            load(action, HistoryAction.PUSH, "POST", encoded.toByteArray(Charsets.UTF_8), "application/x-www-form-urlencoded")
        } else {
            val basePath = action.path.substringBefore('?')
            val query = if (encoded.isEmpty()) "" else "?$encoded"
            load(action.copy(path = basePath + query), HistoryAction.PUSH)
        }
    }

    private fun urlEncode(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun findAncestorForm(element: ElementNode): ElementNode? {
        var current: ElementNode? = element
        while (current != null) {
            if (current.tag == "form") return current
            current = current.parent
        }
        return null
    }

    /** Shared by every form-control mutation above: re-cascade/re-layout and report Updated, same as dispatchClick's DOM-mutation path. */
    private fun mutateAndRefresh() {
        val doc = currentDoc ?: return
        val url = currentUrl ?: return
        computeStyles(doc, currentAuthorRules)
        relayout()
        onStateChanged(TabState.Updated(url, extractTitle(doc)))
    }

    /** Called by the view when its size changes; re-runs layout without re-fetching. */
    fun onViewportSizeChanged(widthPx: Float, heightPx: Float): Boolean {
        if (widthPx <= 0f || heightPx <= 0f || (widthPx == viewportWidth && heightPx == viewportHeight)) return false
        viewportWidth = widthPx
        viewportHeight = heightPx
        relayout()
        return true
    }

    private fun load(url: Url, action: HistoryAction, method: String = "GET", body: ByteArray? = null, contentType: String? = null) {
        onStateChanged(TabState.Loading(url))
        // Read on the main thread (load() is always called from one) before
        // handing off to the background executor, rather than reading the
        // mutable viewportWidth field from that other thread later.
        val mediaViewportWidth = if (viewportWidth > 0f) viewportWidth else 360f
        executor.execute {
            try {
                val response = url.fetch(method, body, contentType)
                val root = HtmlParser(response.body).parse()
                // Scripts may mutate the DOM, so this runs before CSS/images/fonts are collected below.
                val (interpreter, bridge) = runScripts(root, response.url)
                val authorCss = collectAuthorCss(root, response.url, mediaViewportWidth)
                computeStyles(root, authorCss.rules)
                val title = extractTitle(root)
                val images = collectAndDecodeImages(root, response.url) + collectAndRenderSvgs(root) + bridge.canvasBitmaps()
                val fonts = loadFontFaces(authorCss.fontFaces)
                mainHandler.post {
                    currentUrl = response.url
                    currentDoc = root
                    currentImages = images
                    customFonts = fonts
                    currentInterpreter = interpreter
                    currentDomBridge = bridge
                    currentAuthorRules = authorCss.rules
                    canceledTimers.clear()
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
    /** Returns the Interpreter/DomBridge pair so Tab can keep them alive for later click dispatch (see dispatchClick). */
    private fun runScripts(root: ElementNode, baseUrl: Url): Pair<Interpreter, DomBridge> {
        val interpreter = Interpreter()
        val bridge = DomBridge(root)
        bridge.install(interpreter.globalEnv)
        installBrowserRuntime(interpreter, root, baseUrl)

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
        return interpreter to bridge
    }

    /**
     * Browser-runtime globals beyond pure DOM access: `fetch` (genuine
     * background network I/O - raw sockets on the main thread throw
     * NetworkOnMainThreadException on Android, so this can't be
     * synchronous like the rest of this file's networking during load())
     * and `setTimeout`/`setInterval` via the existing mainHandler. Both
     * re-style/re-layout and report TabState.Updated after their callback
     * runs, since it may have mutated the DOM - guarded by a reference
     * check against the page's own root ([pageRoot]) so a timer or fetch
     * that resolves after the user has navigated away is a no-op instead
     * of corrupting whatever page is showing now.
     *
     * `clearTimeout`/`clearInterval` are tracked via a simple ID set
     * rather than being no-ops, so a script that legitimately stops a
     * repeating timer (e.g. a countdown) actually stops instead of
     * running forever until the next navigation. `setInterval` also
     * floors its delay at 16ms (~60fps) as a throttle against a
     * pathological `setInterval(fn, 0)`.
     */
    private fun installBrowserRuntime(interpreter: Interpreter, pageRoot: ElementNode, baseUrl: Url) {
        fun afterAsyncWork() {
            if (currentDoc !== pageRoot) return
            computeStyles(pageRoot, currentAuthorRules)
            relayout()
            currentUrl?.let { onStateChanged(TabState.Updated(it, extractTitle(pageRoot))) }
        }

        interpreter.globalEnv.declare("fetch", NativeFunction("fetch", 2) { _, _, args ->
            val promise = JsPromise()
            val requestUrl = try {
                baseUrl.resolve(toJsString(args.getOrElse(0) { JsUndefined }))
            } catch (e: Exception) {
                null
            }
            if (requestUrl == null) {
                promise.reject(JsString("Invalid URL"))
            } else {
                executor.execute {
                    try {
                        val response = requestUrl.fetch()
                        mainHandler.post {
                            promise.resolve(makeFetchResponse(response))
                            afterAsyncWork()
                        }
                    } catch (e: Exception) {
                        mainHandler.post {
                            promise.reject(JsString(e.message ?: "Network request failed"))
                            afterAsyncWork()
                        }
                    }
                }
            }
            promise
        })

        interpreter.globalEnv.declare("setTimeout", NativeFunction("setTimeout", 2) { _, _, args ->
            val fn = args.getOrNull(0) as? JsFunction
            val delay = (args.getOrNull(1) as? JsNumber)?.value?.toLong()?.coerceAtLeast(0L) ?: 0L
            val id = ++timerIdCounter
            if (fn != null) {
                mainHandler.postDelayed({
                    if (id !in canceledTimers && currentDoc === pageRoot) {
                        try { fn.call(interpreter, JsUndefined, emptyList()) } catch (_: Exception) { }
                        afterAsyncWork()
                    }
                }, delay)
            }
            JsNumber(id.toDouble())
        })
        interpreter.globalEnv.declare("clearTimeout", NativeFunction("clearTimeout", 1) { _, _, args ->
            (args.getOrNull(0) as? JsNumber)?.let { canceledTimers.add(it.value.toInt()) }
            JsUndefined
        })

        interpreter.globalEnv.declare("setInterval", NativeFunction("setInterval", 2) { _, _, args ->
            val fn = args.getOrNull(0) as? JsFunction
            val delay = (args.getOrNull(1) as? JsNumber)?.value?.toLong()?.coerceAtLeast(16L) ?: 1000L
            val id = ++timerIdCounter
            if (fn != null) {
                lateinit var tick: () -> Unit
                tick = {
                    if (id !in canceledTimers && currentDoc === pageRoot) {
                        try { fn.call(interpreter, JsUndefined, emptyList()) } catch (_: Exception) { }
                        afterAsyncWork()
                        mainHandler.postDelayed(tick, delay)
                    }
                }
                mainHandler.postDelayed(tick, delay)
            }
            JsNumber(id.toDouble())
        })
        interpreter.globalEnv.declare("clearInterval", NativeFunction("clearInterval", 1) { _, _, args ->
            (args.getOrNull(0) as? JsNumber)?.let { canceledTimers.add(it.value.toInt()) }
            JsUndefined
        })
    }

    private fun makeFetchResponse(response: HttpResponse): JsObject {
        val obj = JsObject()
        obj.set("ok", JsBoolean(response.statusCode in 200..299))
        obj.set("status", JsNumber(response.statusCode.toDouble()))
        obj.set("text", NativeFunction("text", 0) { _, _, _ -> JsPromise().apply { resolve(JsString(response.body)) } })
        obj.set("json", NativeFunction("json", 0) { _, _, _ ->
            JsPromise().apply {
                try {
                    resolve(parseJsonToJsValue(response.body))
                } catch (_: Exception) {
                    reject(JsString("Invalid JSON"))
                }
            }
        })
        return obj
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
