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
import com.projectfuture.browser.js.JsValue
import com.projectfuture.browser.js.Lexer
import com.projectfuture.browser.js.NativeFunction
import com.projectfuture.browser.js.Parser
import com.projectfuture.browser.js.jsError
import com.projectfuture.browser.js.parseJsonToJsValue
import com.projectfuture.browser.js.toJsString
import com.projectfuture.browser.layout.DisplayCommand
import com.projectfuture.browser.layout.DocumentLayout
import com.projectfuture.browser.layout.FontDecoder
import com.projectfuture.browser.layout.customFonts
import com.projectfuture.browser.net.HttpResponse
import com.projectfuture.browser.net.Url
import com.projectfuture.browser.net.corsAllows
import com.projectfuture.browser.net.isSameOrigin
import java.io.File
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
     * element. Order of operations mirrors a real browser closely enough for
     * alpha purposes: a checkbox/radio's `checked` state flips first (native,
     * unconditional behavior - not something `preventDefault()` can undo
     * here, a real but minor simplification), then the click bubbles through
     * DomBridge, then - only if nothing called `preventDefault()` - the
     * default action runs: follow an ancestor `<a href>`, or submit an
     * ancestor `<form>` if the tapped element is a submit control. Any of
     * this mutating the DOM (a listener running, a checkbox toggling) is
     * enough to re-style/re-layout and report TabState.Updated rather than
     * Loaded, so the UI just repaints without resetting scroll or the
     * address bar.
     */
    fun dispatchClick(element: ElementNode) {
        val interpreter = currentInterpreter ?: return
        val bridge = currentDomBridge ?: return
        val doc = currentDoc ?: return
        val url = currentUrl ?: return

        val toggled = toggleFormControlIfNeeded(element)

        val event = try {
            bridge.dispatchClick(element, interpreter)
        } catch (_: Exception) {
            null
        }
        if (toggled) {
            try { bridge.dispatchEvent(element, "change", interpreter) } catch (_: Exception) {}
        }

        if (event?.defaultPrevented != true) {
            findAncestorHref(element)?.let { href ->
                followLink(href)
                return
            }
            findSubmitForm(element)?.let { form ->
                submitForm(form)
                return
            }
        }

        if (toggled || event?.listenersRan == true) {
            computeStyles(doc, currentAuthorRules)
            relayout()
            onStateChanged(TabState.Updated(url, extractTitle(doc)))
        }
    }

    /**
     * Called by BrowserView's overlay `EditText` on every keystroke in a
     * text/password/textarea field. Keeps the DOM's `value` attribute (and
     * hence `input.value` in scripts) in sync, fires `input` listeners, and
     * re-lays-out so reactive scripts see the change reflected - see
     * BrowserView's class doc for why this doesn't clobber the field's own
     * live text or cursor position.
     */
    fun dispatchInputEvent(element: ElementNode, newValue: String) {
        val interpreter = currentInterpreter ?: return
        val bridge = currentDomBridge ?: return
        val doc = currentDoc ?: return
        val url = currentUrl ?: return
        element.attributes["value"] = newValue
        try { bridge.dispatchEvent(element, "input", interpreter) } catch (_: Exception) {}
        computeStyles(doc, currentAuthorRules)
        relayout()
        onStateChanged(TabState.Updated(url, extractTitle(doc)))
    }

    /** Native pre-listener checkbox/radio toggle behavior. Returns true if this element's checked state changed. */
    private fun toggleFormControlIfNeeded(element: ElementNode): Boolean {
        if (element.tag != "input") return false
        return when (element.attr("type")?.lowercase()) {
            "checkbox" -> {
                if (element.attributes.containsKey("checked")) element.attributes.remove("checked") else element.attributes["checked"] = "checked"
                true
            }
            "radio" -> {
                val name = element.attr("name")
                if (name != null) {
                    val scope = nearestForm(element) ?: currentDoc
                    scope?.walkElements { el ->
                        if (el !== element && el.tag == "input" && el.attr("type")?.lowercase() == "radio" && el.attr("name") == name) {
                            el.attributes.remove("checked")
                        }
                    }
                }
                element.attributes["checked"] = "checked"
                true
            }
            else -> false
        }
    }

    private fun nearestForm(element: ElementNode): ElementNode? {
        var current: ElementNode? = element
        while (current != null) {
            if (current.tag == "form") return current
            current = current.parent
        }
        return null
    }

    private fun findAncestorHref(element: ElementNode): String? {
        var current: ElementNode? = element
        while (current != null) {
            if (current.tag == "a") current.attr("href")?.let { return it }
            current = current.parent
        }
        return null
    }

    /** True if [element] is a submit control (`<button>`/`<input type=submit>`, `<button>`'s implicit default type). */
    private fun findSubmitForm(element: ElementNode): ElementNode? {
        val isSubmit = when (element.tag) {
            "button" -> (element.attr("type")?.lowercase() ?: "submit") == "submit"
            "input" -> element.attr("type")?.lowercase() == "submit"
            else -> false
        }
        if (!isSubmit) return null
        return nearestForm(element)
    }

    /**
     * A basic GET/POST form submission: collects every named, enabled,
     * non-button input/textarea's current value (checkboxes/radios only if
     * checked) and either appends them as a query string (GET, or no
     * explicit `method`) or sends them as an
     * `application/x-www-form-urlencoded` POST body. No `<select>`, no file
     * inputs, no multipart encoding - a script that wants more than this
     * should call `preventDefault()` in a `submit` listener and handle it
     * itself via `fetch()`.
     */
    private fun submitForm(form: ElementNode) {
        val base = currentUrl ?: return
        val action = form.attr("action")
        val target = if (action.isNullOrBlank()) base else base.resolve(action)
        val isPost = form.attr("method")?.lowercase() == "post"
        val params = ArrayList<Pair<String, String>>()
        form.walkElements { el ->
            if (el === form) return@walkElements
            if (el.tag != "input" && el.tag != "textarea") return@walkElements
            if (el.attributes.containsKey("disabled")) return@walkElements
            val name = el.attr("name") ?: return@walkElements
            val type = if (el.tag == "input") el.attr("type")?.lowercase() else null
            when (type) {
                "submit", "button", "reset" -> {}
                "checkbox", "radio" -> if (el.attributes.containsKey("checked")) params.add(name to (el.attr("value") ?: "on"))
                else -> params.add(name to (el.attr("value") ?: ""))
            }
        }
        val query = params.joinToString("&") { (k, v) ->
            java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")
        }
        if (isPost) {
            load(target, HistoryAction.PUSH, method = "POST", body = query.toByteArray(Charsets.UTF_8))
        } else {
            val separator = if (target.path.contains("?")) "&" else "?"
            val finalUrl = if (query.isEmpty()) target else target.copy(path = target.path + separator + query)
            load(finalUrl, HistoryAction.PUSH)
        }
    }

    /** Called by the view when its size changes; re-runs layout without re-fetching. */
    fun onViewportSizeChanged(widthPx: Float, heightPx: Float): Boolean {
        if (widthPx <= 0f || heightPx <= 0f || (widthPx == viewportWidth && heightPx == viewportHeight)) return false
        viewportWidth = widthPx
        viewportHeight = heightPx
        relayout()
        return true
    }

    private fun load(url: Url, action: HistoryAction, method: String = "GET", body: ByteArray? = null) {
        onStateChanged(TabState.Loading(url))
        // Read on the main thread (load() is always called from one) before
        // handing off to the background executor, rather than reading the
        // mutable viewportWidth field from that other thread later.
        val mediaViewportWidth = if (viewportWidth > 0f) viewportWidth else 360f
        executor.execute {
            try {
                val response = url.fetch(method, body, if (body != null) mapOf("Content-Type" to "application/x-www-form-urlencoded") else emptyMap())
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
                val options = args.getOrNull(1) as? JsObject
                val method = options?.get("method")?.let { if (it != JsUndefined) toJsString(it) else null } ?: "GET"
                val bodyText = options?.get("body")?.let { if (it != JsUndefined) toJsString(it) else null }
                val headers = HashMap<String, String>()
                (options?.get("headers") as? JsObject)?.let { h -> for (k in h.ownKeys()) headers[k] = toJsString(h.get(k)) }
                executor.execute {
                    try {
                        val response = requestUrl.fetch(method, bodyText?.toByteArray(Charsets.UTF_8), headers)
                        mainHandler.post {
                            if (isSameOrigin(baseUrl, response.url) || corsAllows(baseUrl, response.headers)) {
                                promise.resolve(makeFetchResponse(response))
                            } else {
                                // The request went out (matching real browser CORS behavior - it's the
                                // *response* that's withheld from script, not the request itself), but
                                // without a matching Access-Control-Allow-Origin header the response body
                                // is not exposed to the page.
                                promise.reject(makeError("Failed to fetch '$requestUrl': blocked by CORS policy (no matching Access-Control-Allow-Origin)"))
                            }
                            afterAsyncWork()
                        }
                    } catch (e: Exception) {
                        mainHandler.post {
                            promise.reject(makeError(e.message ?: "Network request failed"))
                            afterAsyncWork()
                        }
                    }
                }
            }
            promise
        })

        installXmlHttpRequest(interpreter, baseUrl, ::afterAsyncWork)

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

    private fun makeError(message: String): JsValue = jsError(message).value

    /**
     * `XMLHttpRequest`: the older callback-based sibling of `fetch`, still
     * common enough in real pages to be worth supporting. Same same-origin/
     * CORS check as `fetch` (see [corsAllows]), same background-executor +
     * main-thread-callback plumbing as everything else in this file, and
     * the same `afterAsyncWork` re-style/re-layout-if-still-on-this-page
     * hook. Only `onload`/`onerror`/`onreadystatechange` callbacks and
     * `responseText`/`status`/`readyState` are implemented - no
     * `withCredentials`, no upload progress events, no synchronous mode
     * (real sync XHR would need a *second* blocking network call path on
     * the main thread, which Android's NetworkOnMainThreadException
     * forbids anyway).
     */
    private fun installXmlHttpRequest(interpreter: Interpreter, baseUrl: Url, afterAsyncWork: () -> Unit) {
        val ctor = NativeFunction("XMLHttpRequest", 0) { _, thisArg, _ ->
            val xhr = thisArg as? JsObject ?: JsObject()
            var method = "GET"
            var targetUrl: Url? = null
            val requestHeaders = HashMap<String, String>()
            xhr.set("readyState", JsNumber(0.0))
            xhr.set("status", JsNumber(0.0))
            xhr.set("responseText", JsString(""))
            xhr.set("open", NativeFunction("open", 2) { _, _, args ->
                method = toJsString(args.getOrElse(0) { JsString("GET") }).ifBlank { "GET" }
                targetUrl = try { baseUrl.resolve(toJsString(args.getOrElse(1) { JsUndefined })) } catch (_: Exception) { null }
                xhr.set("readyState", JsNumber(1.0))
                JsUndefined
            })
            xhr.set("setRequestHeader", NativeFunction("setRequestHeader", 2) { _, _, args ->
                requestHeaders[toJsString(args.getOrElse(0) { JsUndefined })] = toJsString(args.getOrElse(1) { JsUndefined })
                JsUndefined
            })
            xhr.set("send", NativeFunction("send", 1) { interp, _, args ->
                val url = targetUrl
                fun fire(type: String) {
                    (xhr.get("on$type") as? JsFunction)?.call(interp, xhr, emptyList())
                    (xhr.get("onreadystatechange") as? JsFunction)?.call(interp, xhr, emptyList())
                }
                if (url == null) {
                    xhr.set("readyState", JsNumber(4.0))
                    fire("error")
                    afterAsyncWork()
                    return@NativeFunction JsUndefined
                }
                val bodyText = args.getOrNull(0)?.let { if (it != JsUndefined) toJsString(it) else null }
                executor.execute {
                    try {
                        val response = url.fetch(method, bodyText?.toByteArray(Charsets.UTF_8), requestHeaders)
                        mainHandler.post {
                            if (isSameOrigin(baseUrl, response.url) || corsAllows(baseUrl, response.headers)) {
                                xhr.set("status", JsNumber(response.statusCode.toDouble()))
                                xhr.set("responseText", JsString(response.body))
                                xhr.set("readyState", JsNumber(4.0))
                                fire("load")
                            } else {
                                xhr.set("readyState", JsNumber(4.0))
                                fire("error")
                            }
                            afterAsyncWork()
                        }
                    } catch (e: Exception) {
                        mainHandler.post {
                            xhr.set("readyState", JsNumber(4.0))
                            fire("error")
                            afterAsyncWork()
                        }
                    }
                }
                JsUndefined
            })
            xhr
        }
        interpreter.globalEnv.declare("XMLHttpRequest", ctor)
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
