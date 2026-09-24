package com.projectfuture.browser.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import com.projectfuture.browser.css.CssParser
import com.projectfuture.browser.debug.BrowserLog
import com.projectfuture.browser.css.CssRule
import com.projectfuture.browser.css.FontFaceRule
import com.projectfuture.browser.css.computeStyles
import com.projectfuture.browser.css.parseFontFaceRule
import com.projectfuture.browser.html.ElementNode
import com.projectfuture.browser.html.HtmlParser
import com.projectfuture.browser.html.TextNode
import com.projectfuture.browser.html.walkElements
import com.projectfuture.browser.js.InMemoryStorageBacking
import com.projectfuture.browser.js.Interpreter
import com.projectfuture.browser.js.JsBoolean
import com.projectfuture.browser.js.JsEvent
import com.projectfuture.browser.js.JsFunction
import com.projectfuture.browser.js.JsNull
import com.projectfuture.browser.js.JsNumber
import com.projectfuture.browser.js.JsArray
import com.projectfuture.browser.js.JsObject
import com.projectfuture.browser.js.JsPromise
import com.projectfuture.browser.js.JsStorage
import com.projectfuture.browser.js.JsString
import com.projectfuture.browser.js.JsUndefined
import com.projectfuture.browser.js.JsValue
import com.projectfuture.browser.js.Lexer
import com.projectfuture.browser.js.NativeFunction
import com.projectfuture.browser.js.StorageBacking
import com.projectfuture.browser.js.Parser
import com.projectfuture.browser.js.jsError
import com.projectfuture.browser.js.jsonStringify
import com.projectfuture.browser.js.parseJsonToJsValue
import com.projectfuture.browser.js.JsException
import com.projectfuture.browser.js.toJsString
import com.projectfuture.browser.js.toNumber
import com.projectfuture.browser.layout.DisplayCommand
import com.projectfuture.browser.layout.DocumentLayout
import com.projectfuture.browser.layout.FontDecoder
import com.projectfuture.browser.layout.customFonts
import com.projectfuture.browser.layout.typefaceFromSfnt
import com.projectfuture.browser.net.decodeDataUrl
import com.projectfuture.browser.layout.textScaleFactor
import com.projectfuture.browser.net.CertificateExceptions
import com.projectfuture.browser.net.HttpResponse
import com.projectfuture.browser.net.TrackingProtection
import com.projectfuture.browser.net.Url
import com.projectfuture.browser.net.sharedCookieJar
import com.projectfuture.browser.net.WebSocketClient
import com.projectfuture.browser.net.ContentSecurityPolicy
import com.projectfuture.browser.net.corsAllows
import com.projectfuture.browser.net.isMixedContent
import com.projectfuture.browser.net.isSameOrigin
import com.projectfuture.browser.rtc.PeerConnectionCore
import com.projectfuture.browser.rtc.RTCDataChannelCore
import com.projectfuture.browser.rtc.SdpSession
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.net.ssl.SSLException

sealed class TabState {
    data class Loading(val url: Url) : TabState()
    data class Loaded(val url: Url, val title: String?) : TabState()
    /** DOM was mutated by a click handler after load (not a navigation) - just repaint, don't touch scroll/address bar. */
    data class Updated(val url: Url, val title: String?) : TabState()
    data class Error(val url: Url, val message: String) : TabState()
    /** A TLS certificate validation failure specifically - MainActivity shows a "proceed anyway?" interstitial instead of a plain error toast. */
    data class CertificateError(val url: Url, val message: String) : TabState()
}

private enum class HistoryAction { PUSH, NONE, REPLACE }

private val JAVASCRIPT_TYPES = setOf(
    "text/javascript", "application/javascript", "application/x-javascript", "text/ecmascript",
    "application/ecmascript", "text/jscript", "module"
)

// A real, mainstream-looking User-Agent, not this project's own name. Many real-world sites
// (news publishers, retailers, anything behind Akamai/Cloudflare/PerimeterX-style bot
// mitigation) return a small blocked/challenge page - or just omit their normal
// stylesheets/scripts - for a request whose User-Agent doesn't look like a recognized
// browser, which reproduces as "the page renders bare/unstyled" even though every part of
// this project's own fetch/HTML/CSS pipeline worked correctly on the (non-)response it got.
// Identifying as Chrome (both variants below) is what lets those sites serve their real,
// normal markup, exactly like any other browser has to do to be treated as one.
internal const val MOBILE_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
private const val DESKTOP_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
private const val DESKTOP_MEDIA_VIEWPORT_WIDTH = 1024f

/**
 * A page's subresources - stylesheets, `@font-face` files, `<img>`s - used to be fetched one at
 * a time on [Tab]'s own serial `executor`, each paying its own full network round trip before the
 * next one even started (see `collectAuthorCss`/`loadFontFaces`/`collectAndDecodeImages`'s docs).
 * For a real page with several of each, that stacks into a genuinely slow, user-visible load time
 * - real browsers fetch subresources concurrently instead. Shared (not one pool per [Tab]) and
 * bounded to 6, matching the per-host parallel-connection limit real browsers use, so many tabs
 * loading at once don't spawn an unbounded number of threads.
 */
private val subresourceExecutor: java.util.concurrent.ExecutorService =
    Executors.newFixedThreadPool(6) { r -> Thread(r, "subresource-fetch").apply { isDaemon = true } }

/**
 * Owns one page's lifecycle: fetch -> parse -> style -> layout, plus
 * back/forward history. Fetching, parsing, scripts and styling run on the
 * load thread; layout, timers and events run on [looper] (an engine thread
 * for in-process tabs, never the UI thread). This is the only place that talks to the network and DOM/CSS/
 * layout modules together.
 */
class Tab(
    private val context: Context,
    private val onStateChanged: (TabState) -> Unit,
    /** A private/incognito tab: no cookies sent or stored (real isolation, not just skipped history - see Url.fetch's allowCookies), no HTTP cache reuse. History recording is skipped by MainActivity checking this flag. */
    val isPrivate: Boolean = false,
    /**
     * The thread that runs this tab's JavaScript, timers and layout. An in-process tab gets its
     * own engine thread (see LocalTabHandle) so a long layout or script never blocks drawing.
     */
    looper: Looper = Looper.getMainLooper()
) {

    private val history = ArrayList<Url>()
    /**
     * Parallel to [history]: which "document generation" each entry
     * belongs to. `pushState`/`replaceState` add/update entries in the
     * *current* generation (no real navigation happened - see
     * [pushState]'s doc), while an ordinary page load bumps the
     * generation. `goBack`/`goForward` compare the target entry's
     * generation against the current one to decide whether landing on it
     * needs a real re-fetch or just a `popstate` fire against the
     * still-loaded document.
     */
    private val historyGenerations = ArrayList<Int>()
    private var documentGeneration = 0
    // Bumped by every load(). Deferred navigations (a script's `location.replace`, a meta refresh)
    // capture it and run only if no newer navigation has started since.
    @Volatile private var navigationSeq = 0
    private var historyIndex = -1
    // A custom, large-stack thread factory rather than the default ~1MB thread stack: page load
    // runs HTML parsing, this hand-written tree-walking JS interpreter, style computation, and
    // layout all recursively over the DOM, and real-world pages routinely produce either deep DOM
    // nesting (framework-generated markup) or deeply recursive JS evaluation (minified bundles'
    // long chained expressions) that a 1MB stack exhausts well before a real browser's JS engine
    // would even notice - which used to surface as a StackOverflowError that escaped every `catch
    // (e: Exception)` in this file (Error, not Exception) and silently killed this thread mid-load,
    // leaving the tab stuck in TabState.Loading forever. DuckDuckGo's JS-free `/html/` results page
    // never triggers either case, which is why it kept working while ordinary sites didn't.
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(null, r, "tab-load", 32L * 1024 * 1024).apply { isDaemon = true }
    }
    private val mainHandler = Handler(looper)
    /**
     * Set by [destroy]. Background work (network fetches, timers, the Service
     * Worker/WebSocket/WebRTC callbacks below) is all dispatched back to the
     * main thread via [postMain]/[postMainDelayed] rather than raw
     * `mainHandler.post`/`postDelayed`, specifically so a callback that was
     * already in flight when the tab was destroyed - a `fetch()` that was
     * mid-request, a `setInterval` tick already queued, a WebSocket message
     * that arrived a moment later - checks this flag before touching any of
     * this (by-then-stale) tab's state or invoking [onStateChanged] again.
     * Without this, a background task racing [destroy] could fire after
     * `TabManager` has already dropped this tab from its list, mutating dead
     * state and delivering a state update for a tab the UI no longer shows.
     */
    private var destroyed = false

    /** See [destroyed]'s doc: runs [block] on the main thread, but only if this tab hasn't been destroyed by the time it's due to run. */
    private fun postMain(block: () -> Unit) {
        mainHandler.post { if (!destroyed) block() }
    }

    /** Delayed variant of [postMain], used by `setTimeout`/`setInterval`/`requestAnimationFrame`. */
    private fun postMainDelayed(delayMs: Long, block: () -> Unit) {
        mainHandler.postDelayed({ if (!destroyed) block() }, delayMs)
    }

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
    private var currentFonts: Map<String, Typeface> = emptyMap()
    /** SFNT bytes of the current document's web fonts by lowercased family, for the UI process (see TabEngineServiceBase.sendFonts). */
    var currentFontData: Map<String, ByteArray> = emptyMap()
        private set
    private var currentInterpreter: Interpreter? = null
    private var currentDomBridge: DomBridge? = null
    private var currentAuthorRules: List<CssRule> = emptyList()
    private var timerIdCounter = 0
    private val canceledTimers = HashSet<Int>()
    /** `sessionStorage` is per-tab (unlike `localStorage`, which is shared via [sharedLocalStorage]) - see JsStorage's doc. */
    private val sessionStorageBacking = InMemoryStorageBacking()
    /**
     * `localStorage`'s backing - see [tabStorageBacking]'s doc for why a
     * private tab always gets its own throwaway instance here rather than
     * ever touching the real, disk-persisted [sharedLocalStorage] every
     * regular tab shares. One instance for this tab's whole lifetime (not
     * re-picked per page load), same as [sessionStorageBacking] - origin-
     * keyed backings like this are shared safely across every origin the
     * tab visits (see [com.projectfuture.browser.js.JsStorage]).
     */
    private val localStorageBacking: StorageBacking =
        tabStorageBacking(isPrivate, sharedLocalStorage) { InMemoryStorageBacking() }
    private val indexedDbStore = IndexedDbStore()
    /**
     * Service Worker registrations and Cache Storage are shared across
     * every *regular* tab of the same origin and persist across app
     * restarts (real per-origin, cross-session state, unlike
     * sessionStorage/IndexedDbStore above) - via the module-level
     * [sharedServiceWorkerRegistry]/[sharedCacheStorageStore]. A private
     * tab must never use those: same reasoning as
     * [privateLocalStorageBacking] above - a Service Worker registered (or
     * a Cache Storage entry written) from a private tab would otherwise
     * leak into every regular tab's same-origin state and persist to disk
     * after the private tab closes, defeating private browsing entirely.
     * It gets its own throwaway in-memory instance instead (also used as
     * the non-private fallback when MainActivity hasn't set the shared
     * ones up yet, e.g. in a test harness that constructs a Tab directly).
     */
    private val serviceWorkerRegistry: ServiceWorkerRegistry =
        tabStorageBacking(isPrivate, sharedServiceWorkerRegistry) { ServiceWorkerRegistry(InMemoryStorageBacking()) }
    private val cacheStorageStore: CacheStorageStore =
        tabStorageBacking(isPrivate, sharedCacheStorageStore) { CacheStorageStore(InMemoryStorageBacking()) }

    /**
     * True once this (background) tab's heavy in-memory state has been
     * dropped under memory pressure - see [discardForMemoryPressure]. The
     * tab still "exists" (its URL and history survive), it just isn't
     * rendered until something (MainActivity, on switching to it) calls
     * [reload]. This is this engine's bounded stand-in for real browsers'
     * multi-process renderer discarding: no separate process to kill here,
     * just the same single-process state a discarded renderer would hold.
     */
    var isDiscarded: Boolean = false
        private set

    fun discardForMemoryPressure() {
        if (isDiscarded || currentUrl == null) return
        currentDoc = null
        displayList = emptyList()
        contentHeight = 0f
        currentImages = emptyMap()
        currentFonts = emptyMap()
        currentFontData = emptyMap()
        currentInterpreter = null
        currentDomBridge = null
        currentAuthorRules = emptyList()
        isDiscarded = true
    }

    /**
     * "Desktop site" mode: widens the layout viewport (so media queries and
     * any width-dependent CSS pick a desktop-ish layout) and sends a
     * desktop-claiming `User-Agent`, since many real sites branch their
     * served markup/CSS on UA sniffing rather than (or in addition to)
     * responsive media queries. Toggling reloads the current page.
     */
    var desktopMode: Boolean = false
        private set

    fun toggleDesktopMode() {
        desktopMode = !desktopMode
        reload()
    }

    /** Fired for `<a download href="...">` taps; MainActivity hands the URL off to Android's own DownloadManager (a platform primitive, not "browser engine" logic). */
    var onDownloadRequested: ((url: String, suggestedFilename: String?) -> Unit)? = null

    /** Fired when a detected login form (see [LoginFormDetector]) is submitted with a non-blank username+password; MainActivity offers to save it via [CredentialStore]. */
    var onLoginFormSubmitted: ((origin: String, username: String, password: String) -> Unit)? = null

    /**
     * A per-tab text-size multiplier - see [textScaleFactor]'s doc for why
     * this is the practical stand-in for "pinch-to-zoom" on this engine.
     * Applying it re-lays-out immediately (cheap enough on a single-page
     * document; the module-level var is only read during layout).
     */
    var textScale: Float = 1f
        private set

    fun setTextScale(scale: Float) {
        textScale = scale.coerceIn(0.5f, 2.5f)
        relayout()
    }

    var readerModeActive: Boolean = false
        private set
    private var docBeforeReaderMode: ElementNode? = null
    private var authorRulesBeforeReaderMode: List<CssRule> = emptyList()
    private val readerModeRules: List<CssRule> by lazy { CssParser(READER_MODE_CSS).parseRules() }

    /**
     * Swaps the live document for one built by [extractReaderContent] and
     * a plain typographic stylesheet, or restores the original if already
     * active. Purely a presentation swap on the already-loaded page - no
     * re-fetch, no history entry, no interaction with scripts (a reader-
     * mode document has no JS bridge; the original interpreter/DomBridge
     * stay untouched underneath so toggling back is exact). Images don't
     * render in reader mode: [currentImages] is keyed by the original
     * page's own `<img>` ElementNode identity, and the extracted document
     * is built from fresh clones (see extractReaderContent) that were
     * never decoded against - a real, accepted gap rather than rebuilding
     * that map by matching `src` strings across both trees.
     */
    fun toggleReaderMode() {
        val doc = currentDoc ?: return
        val url = currentUrl ?: return
        if (readerModeActive) {
            currentDoc = docBeforeReaderMode ?: doc
            // Restore currentAuthorRules too, not just the doc - it drives every
            // later computeStyles() call this Tab makes off the user's own
            // interactions (dispatchClick/dispatchInputEvent). Leaving it set to
            // readerModeRules after leaving reader mode would silently apply the
            // reader stylesheet to the real page's DOM on the next click/keystroke.
            currentAuthorRules = authorRulesBeforeReaderMode
            computeStyles(currentDoc!!, currentAuthorRules)
            readerModeActive = false
        } else {
            val extracted = extractReaderContent(doc) ?: return
            docBeforeReaderMode = doc
            authorRulesBeforeReaderMode = currentAuthorRules
            currentDoc = extracted
            // Same reasoning in the other direction: while reader mode is active,
            // currentAuthorRules must reflect readerModeRules so that any
            // in-place restyle triggered by interacting with the reader-mode
            // document (e.g. tapping a preserved link/checkbox) doesn't reapply
            // the original page's author CSS to the extracted reader DOM.
            currentAuthorRules = readerModeRules
            computeStyles(extracted, currentAuthorRules)
            readerModeActive = true
        }
        relayout()
        onStateChanged(TabState.Updated(url, extractTitle(currentDoc!!)))
    }

    /**
     * "Add to Home Screen": looks for `<link rel="manifest">`, and if
     * present, fetches and reads just `name`/`short_name` and the first
     * `icons[].src` from it - not a full Web App Manifest implementation
     * (no `display`/`start_url`/`theme_color`/multiple-icon-size
     * selection). Falls back to the page's own title and no icon (the
     * caller uses a generic launcher icon instead) if there's no manifest
     * or the fetch fails. [callback] always runs on the main thread.
     */
    fun fetchManifestInfo(callback: (name: String, iconBytes: ByteArray?) -> Unit) {
        val doc = currentDoc
        val base = currentUrl
        val fallbackName = doc?.let { extractTitle(it) } ?: base?.toString() ?: "Web Page"
        var manifestHref: String? = null
        doc?.walkElements { el -> if (manifestHref == null && el.tag == "link" && el.attr("rel")?.lowercase() == "manifest") manifestHref = el.attr("href") }
        val href = manifestHref
        if (href == null || base == null) {
            callback(fallbackName, null)
            return
        }
        executor.execute {
            try {
                val manifestUrl = base.resolve(href)
                val json = manifestUrl.fetch(allowCookies = !isPrivate).body
                val parsed = parseJsonToJsValue(json) as? JsObject
                val name = ((parsed?.get("short_name") as? JsString)?.value ?: (parsed?.get("name") as? JsString)?.value ?: fallbackName)
                val icons = parsed?.get("icons") as? JsArray
                val iconSrc = (icons?.elements?.firstOrNull() as? JsObject)?.get("src")?.let { (it as? JsString)?.value }
                val iconBytes = iconSrc?.let {
                    try { manifestUrl.resolve(it).fetchBytes(allowCookies = !isPrivate).body } catch (_: Exception) { null }
                }
                postMain { callback(name, iconBytes) }
            } catch (_: Exception) {
                postMain { callback(fallbackName, null) }
            }
        }
    }

    /**
     * `history.pushState`/`replaceState`: no navigation happens at all -
     * no fetch, no re-parse, the currently loaded document just gets a new
     * URL recorded against it. `pushState` adds a new same-generation
     * history entry (truncating any forward entries, like a real
     * navigation would); `replaceState` overwrites the current one. Both
     * report TabState.Updated so the address bar follows along.
     */
    fun pushState(newUrlString: String) {
        val base = currentUrl ?: return
        val newUrl = try { base.resolve(newUrlString) } catch (_: Exception) { return }
        while (history.size > historyIndex + 1) {
            history.removeAt(history.size - 1)
            historyGenerations.removeAt(historyGenerations.size - 1)
        }
        history.add(newUrl)
        historyGenerations.add(documentGeneration)
        historyIndex = history.size - 1
        currentUrl = newUrl
        onStateChanged(TabState.Updated(newUrl, currentDoc?.let { extractTitle(it) }))
    }

    fun replaceState(newUrlString: String) {
        val base = currentUrl ?: return
        val newUrl = try { base.resolve(newUrlString) } catch (_: Exception) { return }
        if (historyIndex in history.indices) {
            history[historyIndex] = newUrl
            historyGenerations[historyIndex] = documentGeneration
        }
        currentUrl = newUrl
        onStateChanged(TabState.Updated(newUrl, currentDoc?.let { extractTitle(it) }))
    }

    fun canGoBack() = historyIndex > 0
    fun canGoForward() = historyIndex in 0 until (history.size - 1)

    fun navigate(addressBarInput: String, searchTemplate: String = Settings.DEFAULT_SEARCH_TEMPLATE) {
        load(Url.fromAddressBar(addressBarInput, searchTemplate), HistoryAction.PUSH)
    }

    fun goBack() {
        if (!canGoBack()) return
        stepHistory(historyIndex - 1)
    }

    fun goForward() {
        if (!canGoForward()) return
        stepHistory(historyIndex + 1)
    }

    /** Shared by goBack/goForward/history.go(n): reload only if the target entry belongs to a different document generation - see [historyGenerations]'s doc. */
    private fun stepHistory(targetIndex: Int) {
        if (targetIndex !in history.indices) return
        val sameDocument = historyGenerations.getOrNull(targetIndex) == documentGeneration
        historyIndex = targetIndex
        if (sameDocument) {
            currentUrl = history[targetIndex]
            val interpreter = currentInterpreter
            val bridge = currentDomBridge
            if (interpreter != null && bridge != null) {
                try { bridge.dispatchWindowEvent("popstate", interpreter, JsEvent("popstate", JsNull)) } catch (_: Exception) {}
            }
            onStateChanged(TabState.Updated(history[targetIndex], currentDoc?.let { extractTitle(it) }))
        } else {
            load(history[targetIndex], HistoryAction.NONE)
        }
    }

    fun reload() {
        currentUrl?.let { load(it, HistoryAction.NONE) }
    }

    fun followLink(href: String) {
        val base = currentUrl ?: return
        // `javascript:`, `mailto:`, `tel:` and other non-fetchable links resolve to null and are ignored.
        val target = base.resolveOrNull(href) ?: return
        load(target, HistoryAction.PUSH)
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
            findAncestorAnchor(element)?.let { anchor ->
                val href = anchor.attr("href") ?: return@let
                if (anchor.attributes.containsKey("download")) {
                    val suggestedName = anchor.attr("download")?.takeIf { it.isNotBlank() }
                    url.resolveOrNull(href)?.let { onDownloadRequested?.invoke(it.toString(), suggestedName) }
                } else {
                    followLink(href)
                }
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
        if (element.tag == "textarea") {
            element.children.clear()
            element.children.add(TextNode(newValue, element))
        } else {
            element.attributes["value"] = newValue
        }
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
                    val scope = findAncestorForm(element) ?: currentDoc
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

    /** The current document's origin (`scheme://host:port`), for matching saved credentials - null if nothing's loaded. */
    fun currentOrigin(): String? = currentUrl?.credentialOrigin()

    /** The first login-shaped form in the current document, if any - see [LoginFormDetector]. Used by MainActivity to offer autofill after a page loads. */
    fun detectLoginForm(): LoginFormFields? = currentDoc?.let { LoginFormDetector.findLoginForm(it) }

    /** Fills in a detected login form's fields (from [detectLoginForm]) with a saved credential and re-lays-out, same as any other field edit. */
    fun autofillLoginForm(fields: LoginFormFields, username: String, password: String) {
        setFieldValue(fields.usernameField, username)
        setFieldValue(fields.passwordField, password)
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
        val action = form.attr("action")?.takeIf { it.isNotBlank() }?.let { baseUrl.resolveOrNull(it) } ?: baseUrl
        val method = (form.attr("method") ?: "get").trim().lowercase()

        LoginFormDetector.findLoginForm(form)?.let { fields ->
            LoginFormDetector.extractSubmittedCredentials(fields)?.let { (username, password) ->
                onLoginFormSubmitted?.invoke(baseUrl.credentialOrigin(), username, password)
            }
        }

        val params = collectFormParams(form)
        val encoded = encodeFormParams(params)

        if (method == "post") {
            load(action, HistoryAction.PUSH, method = "POST", body = encoded.toByteArray(Charsets.UTF_8))
        } else {
            val basePath = action.path.substringBefore('#').substringBefore('?')
            val query = if (encoded.isEmpty()) "" else "?$encoded"
            load(action.copy(path = basePath + query), HistoryAction.PUSH)
        }
    }

    private fun findAncestorForm(element: ElementNode): ElementNode? {
        var current: ElementNode? = element
        while (current != null) {
            if (current.tag == "form") return current
            current = current.parent
        }
        return null
    }

    private fun findAncestorAnchor(element: ElementNode): ElementNode? {
        var current: ElementNode? = element
        while (current != null) {
            if (current.tag == "a" && current.attr("href") != null) return current
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
        return findAncestorForm(element)
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

    private fun load(requestedUrl: Url, action: HistoryAction, method: String = "GET", body: ByteArray? = null) {
        val url = unwrapSearchRedirect(requestedUrl)
        val navigation = ++navigationSeq
        val startedAt = System.nanoTime()
        fun ms() = (System.nanoTime() - startedAt) / 1_000_000
        BrowserLog.i("load", "#$navigation $method ${logUrl(url)} ($action)" + if (url != requestedUrl) " unwrapped from ${logUrl(requestedUrl)}" else "")
        onStateChanged(TabState.Loading(url))
        // Read on the main thread (load() is always called from one) before
        // handing off to the background executor, rather than reading the
        // mutable viewportWidth/desktopMode fields from that other thread later.
        // The real device width still drives actual box layout (no horizontal
        // scroll/pinch-zoom exists to pan a wider one), so "desktop site" only
        // widens what CSS media queries see, plus the User-Agent - a bounded
        // but real subset of what the toggle does in a real browser.
        val mediaViewportWidth = if (desktopMode) DESKTOP_MEDIA_VIEWPORT_WIDTH else (if (viewportWidth > 0f) viewportWidth else 360f)
        val requestHeaders = HashMap<String, String>()
        requestHeaders["User-Agent"] = if (desktopMode) DESKTOP_USER_AGENT else MOBILE_USER_AGENT
        if (body != null) requestHeaders["Content-Type"] = "application/x-www-form-urlencoded"
        executor.execute {
            try {
                val response = url.fetch(method, body, requestHeaders, allowCookies = !isPrivate)
                BrowserLog.i(
                    "load",
                    "#$navigation fetched ${logUrl(response.url)} status=${response.statusCode} " +
                        "type=${response.headers["content-type"]} chars=${response.body.length} at ${ms()}ms"
                )
                if (isDownloadResponse(response.headers["content-type"], response.headers["content-disposition"])) {
                    // Not an `<a download>` tap - a navigation (JS-driven, or a redirect chain)
                    // that landed on a non-HTML/attachment response. Route it to the download
                    // path instead of running HtmlParser over binary/garbage bytes; the page
                    // already on screen (if any) stays put, exactly like a real browser doesn't
                    // navigate away from the current page for a download.
                    val suggestedName = parseContentDispositionFilename(response.headers["content-disposition"])
                        ?: response.url.path.substringAfterLast('/').takeIf { it.isNotBlank() }
                    postMain {
                        onDownloadRequested?.invoke(response.url.toString(), suggestedName)
                        val stayUrl = currentUrl
                        if (stayUrl != null) {
                            onStateChanged(TabState.Loaded(stayUrl, currentDoc?.let { extractTitle(it) }))
                        } else {
                            onStateChanged(TabState.Error(url, "Download started"))
                        }
                    }
                    return@execute
                }
                val csp = ContentSecurityPolicy.parse(response.headers["content-security-policy"])
                val root = HtmlParser(response.body).parse()
                var elementCount = 0
                root.walkElements { elementCount++ }
                BrowserLog.i("load", "#$navigation parsed $elementCount elements at ${ms()}ms")
                warmPreconnectHints(root, response.url)
                // Scripts may mutate the DOM, so this runs before CSS/images/fonts are collected below.
                val (interpreter, bridge) = runScripts(root, response.url, csp, navigation)
                val metaRefresh = findMetaRefresh(root)
                val authorCss = collectAuthorCss(root, response.url, mediaViewportWidth, csp)
                computeStyles(root, authorCss.rules)
                val title = extractTitle(root)
                val images = collectAndDecodeImages(root, response.url, csp) + collectAndRenderSvgs(root) + bridge.canvasBitmaps()
                val fonts = loadFontFaces(authorCss.fontFaces, response.url, csp)
                BrowserLog.i(
                    "load",
                    "#$navigation styled: ${authorCss.rules.size} css rules, ${images.size} images, ${fonts.typefaces.size} fonts at ${ms()}ms"
                )
                postMain {
                    currentUrl = response.url
                    currentDoc = root
                    currentImages = images
                    currentFonts = fonts.typefaces
                    currentFontData = fonts.sfnt
                    currentInterpreter = interpreter
                    currentDomBridge = bridge
                    currentAuthorRules = authorCss.rules
                    canceledTimers.clear()
                    isDiscarded = false
                    documentGeneration++
                    when (action) {
                        HistoryAction.PUSH -> {
                            while (history.size > historyIndex + 1) {
                                history.removeAt(history.size - 1)
                                historyGenerations.removeAt(historyGenerations.size - 1)
                            }
                            history.add(response.url)
                            historyGenerations.add(documentGeneration)
                            historyIndex = history.size - 1
                        }
                        HistoryAction.REPLACE -> if (history.isEmpty()) {
                            history.add(response.url)
                            historyGenerations.add(documentGeneration)
                            historyIndex = 0
                        } else {
                            history[historyIndex] = response.url
                            historyGenerations[historyIndex] = documentGeneration
                        }
                        HistoryAction.NONE -> if (history.isEmpty()) {
                            history.add(response.url)
                            historyGenerations.add(documentGeneration)
                            historyIndex = 0
                        } else {
                            // Re-fetched an existing entry (goBack/goForward crossing into a different
                            // document generation, or reload()) - it's now this fresh generation.
                            historyGenerations[historyIndex] = documentGeneration
                        }
                    }
                    relayout()
                    BrowserLog.i(
                        "load",
                        "#$navigation laid out ${displayList.size} draw commands, content height ${contentHeight.toInt()}px, " +
                            "viewport ${viewportWidth.toInt()}x${viewportHeight.toInt()}, done at ${ms()}ms"
                    )
                    onStateChanged(TabState.Loaded(response.url, title))
                    scheduleMetaRefresh(metaRefresh, response.url)
                }
            } catch (e: Throwable) {
                BrowserLog.w("load", "#$navigation failed for ${logUrl(url)} at ${ms()}ms", e)
                // Throwable, not Exception: a StackOverflowError from deeply recursive real-world
                // JS/DOM (see the executor's doc above) or any other Error must still resolve this
                // tab out of TabState.Loading instead of silently killing this thread and leaving
                // the page spinning forever.
                postMain {
                    if (e is SSLException) {
                        onStateChanged(TabState.CertificateError(url, e.message ?: e.toString()))
                    } else {
                        onStateChanged(TabState.Error(url, e.message ?: e.toString()))
                    }
                }
            }
        }
    }

    /** Called by MainActivity when the user clicks through a certificate warning for [url]'s host. */
    fun trustCertificateAndReload(url: Url) {
        CertificateExceptions.allow(url.host)
        load(url, HistoryAction.NONE)
    }

    private fun relayout() {
        val doc = currentDoc ?: return
        if (viewportWidth <= 0f || viewportHeight <= 0f) return
        // The layout inputs below are per-thread globals (see their docs). Several tabs can share
        // a thread (tabs in one sandbox process), so each relayout installs this tab's own values.
        textScaleFactor = textScale
        customFonts = currentFonts
        val docLayout = DocumentLayout(doc)
        displayList = docLayout.layout(viewportWidth, viewportHeight, currentImages)
        contentHeight = docLayout.height
    }

    /**
     * Fetches and decodes every `<img src>` up front (concurrently, on
     * [subresourceExecutor]) before layout runs. Image *decoding* itself uses
     * Android's BitmapFactory - a platform media codec, the same boundary
     * already drawn for text glyph shaping - rather than a hand-written
     * JPEG/PNG/WebP decoder, which would each be a substantial project of
     * their own. Fetching the bytes and wiring decoded bitmaps into layout
     * is this engine's own code. A broken or unreachable image is simply
     * skipped, matching how the reference build already treats a failed
     * stylesheet fetch - no broken-image icon or alt-text fallback yet.
     */
    /**
     * `<link rel="preconnect" href="...">`: warms a connection to that
     * host on its own throwaway thread (not this Tab's serial [executor],
     * whose whole point is finishing the actual page load - a preconnect
     * blocking that queue would defeat its own purpose of overlapping with
     * other work instead of adding to the critical path).
     */
    private fun warmPreconnectHints(root: ElementNode, baseUrl: Url) {
        root.walkElements { el ->
            if (el.tag == "link" && el.attr("rel")?.lowercase() == "preconnect") {
                val href = el.attr("href") ?: return@walkElements
                val target = try { baseUrl.resolve(href) } catch (_: Exception) { return@walkElements }
                Thread { target.preconnect() }.apply { isDaemon = true }.start()
            }
        }
    }

    private fun collectAndDecodeImages(root: ElementNode, baseUrl: Url, csp: ContentSecurityPolicy): Map<ElementNode, Bitmap> {
        val candidates = ArrayList<Pair<ElementNode, Url>>()
        root.walkElements { el ->
            if (el.tag == "img") {
                val src = el.attr("src")
                if (!src.isNullOrBlank()) {
                    val imgUrl = baseUrl.resolveOrNull(src) ?: return@walkElements
                    if (isMixedContent(baseUrl, imgUrl)) return@walkElements
                    if (!csp.allowsImgSrc(baseUrl, imgUrl)) return@walkElements
                    if (TrackingProtection.isBlocked(baseUrl, imgUrl)) return@walkElements
                    candidates.add(el to imgUrl)
                }
            }
        }
        // Fetch+decode every image concurrently instead of one at a time - see subresourceExecutor's
        // doc for why serial fetching here was a real, user-visible page-load slowdown. Order doesn't
        // matter for this result (a plain element->bitmap map), so futures are just awaited in
        // whatever order they were submitted.
        val futures: List<Pair<ElementNode, Future<Bitmap?>>> = candidates.map { (el, imgUrl) ->
            el to subresourceExecutor.submit(Callable {
                try {
                    val bytes = imgUrl.fetchBytes(allowCookies = !isPrivate).body
                    // Decode bounds first (no pixel buffer allocated yet), then decode for
                    // real with an inSampleSize chosen to bound the allocation - see
                    // computeInSampleSize's doc for the OOM this avoids on a huge image.
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight)
                    }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                } catch (_: Exception) {
                    // Broken/unreachable image: skip it rather than failing the whole page load.
                    null
                }
            })
        }
        val result = HashMap<ElementNode, Bitmap>()
        for ((el, future) in futures) {
            future.get()?.let { result[el] = it }
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
    /** A URL for [BrowserLog]: never a private tab's. */
    private fun logUrl(url: Url): String = if (isPrivate) "[private]" else url.toString().take(300)

    /** A thrown JS value (`throw new TypeError(...)`) arrives as a JsException wrapping it; show its message, not "[object Object]". */
    private fun describeJsError(e: Throwable): String {
        val thrown = (e as? JsException)?.value as? JsObject ?: return e.message ?: ""
        val name = toJsString(thrown.get("name"))
        val message = toJsString(thrown.get("message"))
        return if (name == "undefined" && message == "undefined") e.message ?: "" else "$name: $message"
    }

    /**
     * `<meta http-equiv="refresh" content="N; url=...">`: after N seconds, replace this page with the
     * target, as long as nothing else has navigated first. A refresh with no URL (a periodic
     * self-reload) is ignored.
     */
    private fun scheduleMetaRefresh(refresh: MetaRefresh?, pageUrl: Url) {
        val href = refresh?.url ?: return
        val target = pageUrl.resolveOrNull(href) ?: return
        val navigation = navigationSeq
        postMainDelayed((refresh.delaySeconds * 1000).toLong()) {
            if (navigationSeq == navigation) load(target, HistoryAction.REPLACE)
        }
    }

    /** Returns the Interpreter/DomBridge pair so Tab can keep them alive for later click dispatch (see dispatchClick). */
    private fun runScripts(root: ElementNode, baseUrl: Url, csp: ContentSecurityPolicy, navigation: Int): Pair<Interpreter, DomBridge> {
        val interpreter = Interpreter()
        val bridge = DomBridge(root)
        val density = context.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        if (viewportWidth > 0f) scriptViewportWidth = (viewportWidth / density).toDouble()
        if (viewportHeight > 0f) scriptViewportHeight = (viewportHeight / density).toDouble()
        // A private tab never shares cookies with scripts, matching its requests (see Url.fetch's allowCookies).
        if (!isPrivate) {
            bridge.cookieReader = { sharedCookieJar?.scriptCookieString(baseUrl) ?: "" }
            bridge.cookieWriter = { cookie -> sharedCookieJar?.storeFromScript(baseUrl, cookie) }
        }
        bridge.install(interpreter.globalEnv)
        installBrowserRuntime(interpreter, root, baseUrl, csp, navigation)

        val scripts = ArrayList<ElementNode>()
        root.walkElements { if (it.tag == "script") scripts.add(it) }
        var scriptsRun = 0
        var scriptsFailed = 0

        for (scriptEl in scripts) {
            // Only JavaScript types run. `application/ld+json` metadata, `text/template` markup and
            // the like are data blocks, and real browsers never execute them.
            val type = scriptEl.attr("type")?.substringBefore(';')?.trim()?.lowercase()
            if (!type.isNullOrEmpty() && type !in JAVASCRIPT_TYPES) continue
            val src = scriptEl.attr("src")
            val code = if (!src.isNullOrBlank()) {
                val scriptUrl = baseUrl.resolveOrNull(src)
                if (scriptUrl == null || isMixedContent(baseUrl, scriptUrl) || !csp.allowsScriptSrc(baseUrl, scriptUrl) || TrackingProtection.isBlocked(baseUrl, scriptUrl)) null
                else try { scriptUrl.fetch(allowCookies = !isPrivate).body } catch (_: Exception) { null }
            } else if (csp.allowsInlineScript()) {
                scriptEl.children.filterIsInstance<TextNode>().joinToString("") { it.text }
            } else {
                null
            }
            if (code.isNullOrBlank()) continue
            val label = if (src.isNullOrBlank()) "inline script (${code.length} chars)" else "script ${if (isPrivate) "[private]" else src.take(120)}"
            val scriptStart = System.nanoTime()
            bridge.currentScript = scriptEl
            try {
                interpreter.run(Parser(Lexer(code).tokenize()).parseProgram())
                scriptsRun++
            } catch (e: Throwable) {
                // A script that fails to parse or throws - including a StackOverflowError from
                // deeply recursive real-world JS, an Error rather than an Exception - shouldn't
                // take down the whole page; move on to the next script instead.
                scriptsFailed++
                BrowserLog.i("js", "$label failed: ${e.javaClass.simpleName}: ${describeJsError(e).take(200)}")
            }
            val scriptMs = (System.nanoTime() - scriptStart) / 1_000_000
            if (scriptMs > 500) BrowserLog.w("js", "$label took ${scriptMs}ms")
        }

        bridge.currentScript = null
        try {
            bridge.fireDomContentLoaded(interpreter)
        } catch (e: Throwable) {
            BrowserLog.i("js", "DOMContentLoaded handler failed: ${e.javaClass.simpleName}: ${describeJsError(e).take(200)}")
        }
        BrowserLog.i("js", "${scripts.size} script elements: $scriptsRun ran, $scriptsFailed failed")
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
    private fun installBrowserRuntime(interpreter: Interpreter, pageRoot: ElementNode, baseUrl: Url, csp: ContentSecurityPolicy, navigation: Int) {
        fun afterAsyncWork() {
            if (currentDoc !== pageRoot) return
            currentDomBridge?.flushPendingCallbacks(interpreter)
            computeStyles(pageRoot, currentAuthorRules)
            relayout()
            currentUrl?.let { onStateChanged(TabState.Updated(it, extractTitle(pageRoot))) }
        }

        // localStorage is shared across every tab (via the module-level sharedLocalStorage); sessionStorage
        // is this tab's own InMemoryStorageBacking - both keyed by the page's own origin, matching the spec.
        val origin = "${baseUrl.scheme}://${baseUrl.host}:${baseUrl.port}"
        val localStorageObj = JsStorage(origin, localStorageBacking)
        val sessionStorageObj = JsStorage(origin, sessionStorageBacking)
        interpreter.globalEnv.declare("localStorage", localStorageObj)
        interpreter.globalEnv.declare("sessionStorage", sessionStorageObj)
        val historyObj = JsObject()
        historyObj.set("pushState", NativeFunction("pushState", 3) { _, _, args ->
            pushState(toJsString(args.getOrElse(2) { JsUndefined }))
            JsUndefined
        })
        historyObj.set("replaceState", NativeFunction("replaceState", 3) { _, _, args ->
            replaceState(toJsString(args.getOrElse(2) { JsUndefined }))
            JsUndefined
        })
        historyObj.set("back", NativeFunction("back", 0) { _, _, _ -> goBack(); JsUndefined })
        historyObj.set("forward", NativeFunction("forward", 0) { _, _, _ -> goForward(); JsUndefined })
        historyObj.set("go", NativeFunction("go", 1) { _, _, args ->
            val delta = toNumber(args.getOrElse(0) { JsNumber(0.0) }).toInt()
            stepHistory(historyIndex + delta)
            JsUndefined
        })
        interpreter.globalEnv.declare("history", historyObj)
        // A script's navigation only counts while its page is still the tab's latest navigation:
        // a timer from a page the user already left must not yank them somewhere else.
        val location = JsLocation(
            currentUrl = { (if (currentDoc === pageRoot) currentUrl else null) ?: baseUrl },
            navigate = { target, replace ->
                postMain { if (navigationSeq == navigation) load(target, if (replace) HistoryAction.REPLACE else HistoryAction.PUSH) }
            },
            reload = { postMain { if (navigationSeq == navigation) reload() } }
        )
        interpreter.globalEnv.declare("location", location)
        (interpreter.globalEnv.get("document") as? JsObject)?.set("location", location)
        (interpreter.globalEnv.get("window") as? JsObject)?.let { window ->
            window.set("localStorage", localStorageObj)
            window.set("sessionStorage", sessionStorageObj)
            window.set("history", historyObj)
            window.set("location", location)
            // Single-frame engine: this window is its own top, parent and self.
            for (name in listOf("self", "top", "parent", "frames")) {
                window.set(name, window)
                interpreter.globalEnv.declare(name, window)
            }
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
            } else if (isMixedContent(baseUrl, requestUrl)) {
                promise.reject(makeError("Mixed Content: the page at '$baseUrl' was loaded over HTTPS, but requested an insecure resource '$requestUrl'. This request has been blocked."))
            } else if (!csp.allowsConnectSrc(baseUrl, requestUrl)) {
                promise.reject(makeError("Refused to connect to '$requestUrl' because it violates the page's Content Security Policy."))
            } else if (TrackingProtection.isBlocked(baseUrl, requestUrl)) {
                promise.reject(makeError("Blocked by tracking protection: '$requestUrl'"))
            } else {
                val options = args.getOrNull(1) as? JsObject
                val method = options?.get("method")?.let { if (it != JsUndefined) toJsString(it) else null } ?: "GET"
                val bodyText = options?.get("body")?.let { if (it != JsUndefined) toJsString(it) else null }
                val headers = HashMap<String, String>()
                (options?.get("headers") as? JsObject)?.let { h -> for (k in h.ownKeys()) headers[k] = toJsString(h.get(k)) }
                executor.execute {
                    try {
                        // A registered, installed Service Worker whose scope covers this page gets first
                        // look at every GET - see interceptWithServiceWorker's doc. Its response (if any)
                        // never touches the network, matching the real Fetch/Service Worker spec's ordering.
                        val swResponse = interceptWithServiceWorker(origin, requestUrl, method)
                        if (swResponse != null) {
                            postMain {
                                promise.resolve(makeFetchResponse(HttpResponse(swResponse.status, emptyMap(), swResponse.body, requestUrl)))
                                afterAsyncWork()
                            }
                            return@execute
                        }
                        val response = requestUrl.fetch(method, bodyText?.toByteArray(Charsets.UTF_8), headers, allowCookies = !isPrivate)
                        postMain {
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
                        postMain {
                            promise.reject(makeError(e.message ?: "Network request failed"))
                            afterAsyncWork()
                        }
                    }
                }
            }
            promise
        })

        installXmlHttpRequest(interpreter, baseUrl, csp, ::afterAsyncWork)

        interpreter.globalEnv.declare("setTimeout", NativeFunction("setTimeout", 2) { _, _, args ->
            val fn = args.getOrNull(0) as? JsFunction
            val delay = (args.getOrNull(1) as? JsNumber)?.value?.toLong()?.coerceAtLeast(0L) ?: 0L
            val id = ++timerIdCounter
            if (fn != null) {
                postMainDelayed(delay) {
                    if (id !in canceledTimers && currentDoc === pageRoot) {
                        try { fn.call(interpreter, JsUndefined, emptyList()) } catch (_: Exception) { }
                        afterAsyncWork()
                    }
                }
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
                        postMainDelayed(delay) { tick() }
                    }
                }
                postMainDelayed(delay) { tick() }
            }
            JsNumber(id.toDouble())
        })
        interpreter.globalEnv.declare("clearInterval", NativeFunction("clearInterval", 1) { _, _, args ->
            (args.getOrNull(0) as? JsNumber)?.let { canceledTimers.add(it.value.toInt()) }
            JsUndefined
        })

        // requestAnimationFrame: same one-shot-timer plumbing as setTimeout, just floored at ~60fps
        // (16ms) and passed a timestamp - there's no real display vsync signal behind this, since
        // painting isn't compositor-driven (see Phase 6's doc on why GPU compositing isn't attempted).
        interpreter.globalEnv.declare("requestAnimationFrame", NativeFunction("requestAnimationFrame", 1) { _, _, args ->
            val fn = args.getOrNull(0) as? JsFunction
            val id = ++timerIdCounter
            if (fn != null) {
                postMainDelayed(16L) {
                    if (id !in canceledTimers && currentDoc === pageRoot) {
                        try { fn.call(interpreter, JsUndefined, listOf(JsNumber(System.nanoTime() / 1_000_000.0))) } catch (_: Exception) { }
                        afterAsyncWork()
                    }
                }
            }
            JsNumber(id.toDouble())
        })
        interpreter.globalEnv.declare("cancelAnimationFrame", NativeFunction("cancelAnimationFrame", 1) { _, _, args ->
            (args.getOrNull(0) as? JsNumber)?.let { canceledTimers.add(it.value.toInt()) }
            JsUndefined
        })

        installWebSocket(interpreter, pageRoot, baseUrl, ::afterAsyncWork)
        installIndexedDb(interpreter, pageRoot)
        installWorker(interpreter, pageRoot, baseUrl, ::afterAsyncWork)
        installServiceWorker(interpreter, pageRoot, baseUrl)
        installWebRTC(interpreter, pageRoot)
    }

    /**
     * `navigator.serviceWorker.register(scriptUrl, {scope})`: persists a
     * [ServiceWorkerRegistration] (see [serviceWorkerRegistry]) and runs
     * its `install`/`activate` events once, off the main thread, via
     * [ServiceWorkerHost] - the same executor already used for
     * `fetch()`/XHR network I/O. Resolves to a minimal
     * `ServiceWorkerRegistration`-shaped object (`scope`, `unregister()`).
     * Fetch interception itself is wired into the `fetch()` builtin below
     * (see its own doc) rather than here, since that's where requests
     * actually originate.
     */
    private fun installServiceWorker(interpreter: Interpreter, pageRoot: ElementNode, baseUrl: Url) {
        val origin = "${baseUrl.scheme}://${baseUrl.host}:${baseUrl.port}"
        val serviceWorkerObj = JsObject()
        serviceWorkerObj.set("register", NativeFunction("register", 2) { _, _, args ->
            val promise = JsPromise()
            val scriptUrlString = toJsString(args.getOrElse(0) { JsUndefined })
            val scriptUrl = try { baseUrl.resolve(scriptUrlString) } catch (e: Exception) {
                promise.reject(makeError("Invalid Service Worker script URL: $scriptUrlString")); return@NativeFunction promise
            }
            val options = args.getOrNull(1) as? JsObject
            val scopeOption = options?.get("scope")?.takeIf { it != JsUndefined }?.let { toJsString(it) }
            // Default scope is the script's own directory, matching the real spec.
            val scope = scopeOption ?: scriptUrl.path.substringBeforeLast('/', "").let { if (it.isEmpty()) "/" else "$it/" }
            executor.execute {
                val reg = serviceWorkerRegistry.register(origin, scope, scriptUrl.toString())
                postMain {
                    if (currentDoc !== pageRoot) return@postMain
                    val regObj = JsObject()
                    regObj.set("scope", JsString(reg.scope))
                    regObj.set("active", JsObject().apply { set("scriptURL", JsString(reg.scriptUrl)) })
                    regObj.set("unregister", NativeFunction("unregister", 0) { _, _, _ ->
                        JsPromise().apply { resolve(JsBoolean(serviceWorkerRegistry.unregister(origin, scope))) }
                    })
                    promise.resolve(regObj)
                }
            }
            promise
        })
        serviceWorkerObj.set("ready", JsPromise().apply { resolve(serviceWorkerObj) })
        val navigatorObj = (if (interpreter.globalEnv.has("navigator")) interpreter.globalEnv.get("navigator") as? JsObject else null)
            ?: JsObject().also { interpreter.globalEnv.declare("navigator", it) }
        navigatorObj.set("serviceWorker", serviceWorkerObj)
        navigatorObj.set("userAgent", JsString(MOBILE_USER_AGENT))
        (interpreter.globalEnv.get("window") as? JsObject)?.set("navigator", navigatorObj)

        // caches: also exposed to the page itself (not just inside a service worker) per spec -
        // a page can read/populate Cache Storage directly, without going through a worker at all.
        interpreter.globalEnv.declare("caches", cachesForPage(origin))
    }

    /** The page-visible (non-worker) `caches` object: same [cacheStorageStore] backing, no `self`/event plumbing needed. */
    private fun cachesForPage(origin: String): JsObject {
        val caches = JsObject()
        fun cacheObject(name: String): JsObject {
            val cache = JsObject()
            cache.set("put", NativeFunction("put", 2) { _, _, args ->
                val reqUrl = args.getOrElse(0) { JsUndefined }.let { if (it is JsObject) toJsString(it.get("url")) else toJsString(it) }
                val respArg = args.getOrElse(1) { JsUndefined } as? JsObject
                val body = respArg?.get("body")?.let { if (it is JsString) it.value else "" } ?: ""
                val status = (respArg?.get("status") as? JsNumber)?.value?.toInt() ?: 200
                cacheStorageStore.put(origin, name, reqUrl, status, "OK", body)
                JsPromise().apply { resolve(JsUndefined) }
            })
            cache.set("match", NativeFunction("match", 1) { _, _, args ->
                val reqUrl = args.getOrElse(0) { JsUndefined }.let { if (it is JsObject) toJsString(it.get("url")) else toJsString(it) }
                val entry = cacheStorageStore.match(origin, name, reqUrl)
                JsPromise().apply {
                    resolve(entry?.let { e -> JsObject().apply { set("status", JsNumber(e.status.toDouble())); set("ok", JsBoolean(e.status in 200..299)); set("body", JsString(e.body)); set("text", NativeFunction("text", 0) { _, _, _ -> JsPromise().apply { resolve(JsString(e.body)) } }) } } ?: JsUndefined)
                }
            })
            cache.set("delete", NativeFunction("delete", 1) { _, _, args ->
                val reqUrl = args.getOrElse(0) { JsUndefined }.let { if (it is JsObject) toJsString(it.get("url")) else toJsString(it) }
                JsPromise().apply { resolve(JsBoolean(cacheStorageStore.deleteEntry(origin, name, reqUrl))) }
            })
            cache.set("keys", NativeFunction("keys", 0) { _, _, _ ->
                JsPromise().apply { resolve(JsArray(cacheStorageStore.keys(origin, name).map { JsObject().apply { set("url", JsString(it)) } as JsValue }.toMutableList())) }
            })
            return cache
        }
        caches.set("open", NativeFunction("open", 1) { _, _, args ->
            val name = toJsString(args.getOrElse(0) { JsUndefined })
            cacheStorageStore.open(origin, name)
            JsPromise().apply { resolve(cacheObject(name)) }
        })
        caches.set("match", NativeFunction("match", 1) { _, _, args ->
            val reqUrl = args.getOrElse(0) { JsUndefined }.let { if (it is JsObject) toJsString(it.get("url")) else toJsString(it) }
            val entry = cacheStorageStore.matchAny(origin, reqUrl)
            JsPromise().apply {
                resolve(entry?.let { e -> JsObject().apply { set("status", JsNumber(e.status.toDouble())); set("ok", JsBoolean(e.status in 200..299)); set("body", JsString(e.body)); set("text", NativeFunction("text", 0) { _, _, _ -> JsPromise().apply { resolve(JsString(e.body)) } }) } } ?: JsUndefined)
            }
        })
        caches.set("delete", NativeFunction("delete", 1) { _, _, args -> JsPromise().apply { resolve(JsBoolean(cacheStorageStore.deleteCache(origin, toJsString(args.getOrElse(0) { JsUndefined })))) } })
        caches.set("keys", NativeFunction("keys", 0) { _, _, _ -> JsPromise().apply { resolve(JsArray(cacheStorageStore.cacheNames(origin).map { JsString(it) as JsValue }.toMutableList())) } })
        return caches
    }

    /**
     * A Service Worker script whose `fetch` event handler calls
     * `respondWith(...)` intercepts a matching page's requests. Only GET
     * `fetch()` calls are checked here (matching [ServiceWorkerHost]'s own
     * scope) - if a registration controls this page (its `scope` is a
     * prefix of the request path, on the same origin) and its script
     * actually handles this URL, the request never touches the network at
     * all; otherwise this returns null and the caller falls through to a
     * real `Url.fetch()`.
     */
    private fun interceptWithServiceWorker(origin: String, requestUrl: Url, method: String): SwResponse? {
        if (requestUrl.scheme != "http" && requestUrl.scheme != "https") return null
        val reg = serviceWorkerRegistry.findControlling(origin, requestUrl.path) ?: return null
        val scriptCode = try { Url.parse(reg.scriptUrl).fetch().body } catch (_: Exception) { null } ?: return null
        val host = ServiceWorkerHost(scriptCode, origin, reg.scope, serviceWorkerRegistry, cacheStorageStore, networkFetcher = { url ->
            try {
                val r = Url.parse(url).fetch()
                SwResponse(r.statusCode, "OK", r.body)
            } catch (_: Exception) { null }
        })
        return host.handleFetch(requestUrl.toString(), method)
    }

    /**
     * `new RTCPeerConnection()` / `RTCDataChannel`: a thin JS-facing
     * wrapper around [PeerConnectionCore] - all the actual SDP/ICE/
     * transport logic (and, critically, an honest account of what does
     * and doesn't really work) lives there, not here. This function only
     * translates between JS objects/Promises and that engine's plain
     * Kotlin API, using the same `mainHandler.post` + `currentDoc ===
     * pageRoot` pattern as WebSocket/Worker above for every callback that
     * crosses from a background thread back into this page's script.
     */
    private fun installWebRTC(interpreter: Interpreter, pageRoot: ElementNode) {
        fun makeSdpObject(type: String, sdp: SdpSession): JsObject = JsObject().apply {
            set("type", JsString(type))
            set("sdp", JsString(sdp.toSdpString()))
        }

        fun makeDataChannelObject(interp: Interpreter, core: RTCDataChannelCore): JsObject {
            val dc = JsObject()
            dc.set("label", JsString(core.label))
            dc.set("readyState", JsString(core.readyState))
            dc.set("bufferedAmount", JsNumber(0.0))
            dc.set("send", NativeFunction("send", 1) { _, _, args -> core.send(toJsString(args.getOrElse(0) { JsUndefined })); JsUndefined })
            dc.set("close", NativeFunction("close", 0) { _, _, _ -> core.close(); JsUndefined })
            core.onOpen = {
                postMain {
                    if (currentDoc === pageRoot) {
                        dc.set("readyState", JsString("open"))
                        (dc.get("onopen") as? JsFunction)?.call(interp, dc, listOf(JsEvent("open", dc)))
                    }
                }
            }
            core.onMessage = { text ->
                postMain {
                    if (currentDoc === pageRoot) {
                        val event = JsEvent("message", dc)
                        event.set("data", JsString(text))
                        (dc.get("onmessage") as? JsFunction)?.call(interp, dc, listOf(event))
                        afterAsyncWorkForRtc(pageRoot)
                    }
                }
            }
            core.onClose = {
                postMain {
                    if (currentDoc === pageRoot) {
                        dc.set("readyState", JsString("closed"))
                        (dc.get("onclose") as? JsFunction)?.call(interp, dc, listOf(JsEvent("close", dc)))
                    }
                }
            }
            return dc
        }

        val ctor = NativeFunction("RTCPeerConnection", 0) { interp, thisArg, _ ->
            val obj = thisArg as? JsObject ?: JsObject()
            val core = PeerConnectionCore()
            obj.set("connectionState", JsString("new"))
            obj.set("iceConnectionState", JsString("new"))

            obj.set("createDataChannel", NativeFunction("createDataChannel", 1) { _, _, args ->
                val label = toJsString(args.getOrElse(0) { JsUndefined })
                makeDataChannelObject(interp, core.createDataChannel(label))
            })
            obj.set("createOffer", NativeFunction("createOffer", 0) { _, _, _ ->
                JsPromise().apply { resolve(makeSdpObject("offer", core.createOffer())) }
            })
            obj.set("createAnswer", NativeFunction("createAnswer", 0) { _, _, _ ->
                JsPromise().apply { resolve(makeSdpObject("answer", core.createAnswer())) }
            })
            obj.set("setLocalDescription", NativeFunction("setLocalDescription", 1) { _, _, args ->
                val desc = args.getOrElse(0) { JsUndefined } as? JsObject
                val sdpText = desc?.get("sdp")?.let { if (it != JsUndefined) toJsString(it) else null }
                val promise = JsPromise()
                if (sdpText == null) {
                    promise.reject(makeError("setLocalDescription requires a session description with an sdp string"))
                } else {
                    executor.execute {
                        try {
                            core.setLocalDescription(SdpSession.parse(sdpText))
                            postMain { promise.resolve(JsUndefined) }
                        } catch (e: Exception) {
                            postMain { promise.reject(makeError(e.message ?: "Invalid SDP")) }
                        }
                    }
                }
                promise
            })
            obj.set("setRemoteDescription", NativeFunction("setRemoteDescription", 1) { _, _, args ->
                val desc = args.getOrElse(0) { JsUndefined } as? JsObject
                val sdpText = desc?.get("sdp")?.let { if (it != JsUndefined) toJsString(it) else null }
                val promise = JsPromise()
                if (sdpText == null) {
                    promise.reject(makeError("setRemoteDescription requires a session description with an sdp string"))
                } else {
                    executor.execute {
                        try {
                            core.setRemoteDescription(SdpSession.parse(sdpText))
                            postMain { promise.resolve(JsUndefined) }
                        } catch (e: Exception) {
                            postMain { promise.reject(makeError(e.message ?: "Invalid SDP")) }
                        }
                    }
                }
                promise
            })
            // Non-trickle only (see PeerConnectionCore's class doc): the one host candidate this engine
            // ever generates already travels inline in the offer/answer SDP, so there is nothing further
            // for addIceCandidate to add - it's accepted (so real trickle-ICE-shaped calling code doesn't
            // throw) and simply resolves.
            obj.set("addIceCandidate", NativeFunction("addIceCandidate", 1) { _, _, _ -> JsPromise().apply { resolve(JsUndefined) } })
            obj.set("close", NativeFunction("close", 0) { _, _, _ -> core.close(); JsUndefined })

            core.onConnectionStateChange = {
                postMain {
                    if (currentDoc === pageRoot) {
                        obj.set("connectionState", JsString(core.connectionState))
                        obj.set("iceConnectionState", JsString(if (core.connectionState == "connected") "connected" else core.connectionState))
                        (obj.get("onconnectionstatechange") as? JsFunction)?.call(interp, obj, listOf(JsEvent("connectionstatechange", obj)))
                    }
                }
            }
            core.onDataChannel = { channelCore ->
                postMain {
                    if (currentDoc === pageRoot) {
                        val event = JsObject()
                        event.set("channel", makeDataChannelObject(interp, channelCore))
                        (obj.get("ondatachannel") as? JsFunction)?.call(interp, obj, listOf(event))
                    }
                }
            }
            obj
        }
        interpreter.globalEnv.declare("RTCPeerConnection", ctor)
    }

    /** Same re-style/re-layout hook `afterAsyncWork` provides elsewhere in this file, for WebRTC callbacks specifically (installWebRTC is called before that closure exists in scope). */
    private fun afterAsyncWorkForRtc(pageRoot: ElementNode) {
        if (currentDoc !== pageRoot) return
        computeStyles(pageRoot, currentAuthorRules)
        relayout()
        currentUrl?.let { onStateChanged(TabState.Updated(it, extractTitle(pageRoot))) }
    }

    /**
     * `new Worker(url)`: a genuinely separate JS execution context on its
     * own thread with its own fresh `Interpreter()` (no DOM bridge - real
     * Web Workers don't get DOM access either), communicating with the
     * main thread only via `postMessage`/`onmessage`. Message *values* are
     * round-tripped through `JSON.stringify`/`parse` before crossing
     * threads (see [jsonStringify]) - a deliberate, real safety measure,
     * not just a spec nicety: this interpreter's JsObject/JsArray are
     * plain, unsynchronized mutable Kotlin collections, so hand one
     * across threads without cloning and a concurrent mutation from
     * either side is a genuine data race. Functions can't be cloned this
     * way and become `null`, matching a real structured-clone
     * DataCloneError's spirit if not its exact behavior. No
     * `importScripts`, no nested Workers, no `SharedWorker`.
     *
     * The page's own `worker.onmessage`/`worker.onerror` run on the main
     * thread with full DOM access, just like the `onmessage` handlers
     * XHR/WebSocket already wire up - so, matching those, both call the
     * same `afterAsyncWork` re-style/re-layout hook afterward in case the
     * handler mutated the DOM. (This used to be missing entirely: a
     * DOM mutation made from inside `worker.onmessage`/`onerror` would sit
     * unlaid-out until some unrelated later trigger happened to call
     * `relayout()`.)
     */
    private fun installWorker(interpreter: Interpreter, pageRoot: ElementNode, baseUrl: Url, afterAsyncWork: () -> Unit) {
        fun cloneForThread(value: JsValue): JsValue = if (value is JsObject) {
            try { parseJsonToJsValue(jsonStringify(value)) } catch (_: Exception) { JsUndefined }
        } else {
            value // primitives are immutable - safe to share across threads as-is
        }

        val ctor = NativeFunction("Worker", 1) { interp, thisArg, args ->
            val obj = thisArg as? JsObject ?: JsObject()
            val scriptUrlString = toJsString(args.getOrElse(0) { JsUndefined })
            val scriptUrl = try { baseUrl.resolve(scriptUrlString) } catch (e: Exception) { throw jsError("Invalid Worker script URL: $scriptUrlString") }

            class ToWorkerMessage(val value: JsValue)
            val terminateSentinel = Any()
            val inbox = java.util.concurrent.LinkedBlockingQueue<Any>()

            obj.set("postMessage", NativeFunction("postMessage", 1) { _, _, pmArgs ->
                inbox.put(ToWorkerMessage(cloneForThread(pmArgs.getOrElse(0) { JsUndefined })))
                JsUndefined
            })
            obj.set("terminate", NativeFunction("terminate", 0) { _, _, _ -> inbox.put(terminateSentinel); JsUndefined })

            Thread {
                try {
                    val code = scriptUrl.fetch().body
                    val workerInterpreter = Interpreter()
                    val workerEnv = workerInterpreter.globalEnv
                    workerEnv.declare("postMessage", NativeFunction("postMessage", 1) { _, _, args ->
                        val cloned = cloneForThread(args.getOrElse(0) { JsUndefined })
                        postMain {
                            if (currentDoc === pageRoot) {
                                val event = JsObject()
                                event.set("data", cloned)
                                (obj.get("onmessage") as? JsFunction)?.call(interp, obj, listOf(event))
                                afterAsyncWork()
                            }
                        }
                        JsUndefined
                    })
                    workerInterpreter.run(Parser(Lexer(code).tokenize()).parseProgram())
                    while (true) {
                        when (val msg = inbox.take()) {
                            is ToWorkerMessage -> {
                                val event = JsObject()
                                event.set("data", msg.value)
                                try {
                                    (workerEnv.get("onmessage") as? JsFunction)?.call(workerInterpreter, JsUndefined, listOf(event))
                                } catch (e: Exception) {
                                    // An uncaught exception inside onmessage shouldn't kill the worker
                                    // thread (it keeps servicing later messages, matching a real
                                    // Worker), but it must still surface to the page via `onerror`
                                    // instead of vanishing silently - this used to just be swallowed.
                                    postMain {
                                        if (currentDoc === pageRoot) {
                                            (obj.get("onerror") as? JsFunction)?.call(interp, obj, listOf(makeError(e.message ?: "Worker error")))
                                            afterAsyncWork()
                                        }
                                    }
                                }
                            }
                            else -> return@Thread // Terminate
                        }
                    }
                } catch (e: Exception) {
                    postMain {
                        if (currentDoc === pageRoot) {
                            (obj.get("onerror") as? JsFunction)?.call(interp, obj, listOf(makeError(e.message ?: "Worker error")))
                            afterAsyncWork()
                        }
                    }
                }
            }.apply { isDaemon = true }.start()

            obj
        }
        interpreter.globalEnv.declare("Worker", ctor)
    }

    /**
     * `indexedDB.open(name)`: every request's `onsuccess`/`onupgradeneeded`
     * fires via `mainHandler.post` (a real "next tick", not synchronously
     * inline) specifically so a script's normal pattern of attaching
     * `request.onsuccess = ...` *after* calling `open()`/`get()`/etc still
     * works - if these fired synchronously during the call itself, the
     * handler wouldn't be attached yet. See IndexedDbStore's doc for the
     * bounded storage model underneath (session-only, per-tab, no
     * indexes/cursors/key ranges). Transaction `oncomplete`/`onabort` firing
     * and the `db.close()` guard are driven by the pure, separately-tested
     * [IndexedDbTransactionTracker]/[IndexedDbConnectionGuard].
     */
    private fun installIndexedDb(interpreter: Interpreter, pageRoot: ElementNode) {
        fun <T : JsValue> deferRequest(interp: Interpreter, request: JsObject, onSettled: ((Boolean) -> Unit)? = null, resultProvider: () -> T) {
            postMain {
                if (currentDoc !== pageRoot) return@postMain
                val result = try { resultProvider() } catch (e: Exception) {
                    (request.get("onerror") as? JsFunction)?.call(interp, request, listOf(makeError(e.message ?: "IndexedDB error")))
                    onSettled?.invoke(false)
                    return@postMain
                }
                val event = JsObject()
                val target = JsObject()
                target.set("result", result)
                event.set("target", target)
                (request.get("onsuccess") as? JsFunction)?.call(interp, request, listOf(event))
                onSettled?.invoke(true)
            }
        }

        // Fires a transaction's `oncomplete`/`onabort` handler - see IndexedDbTransactionTracker's
        // doc for why this needs to exist at all (previously nothing ever called it).
        fun fireTransactionOutcome(interp: Interpreter, tx: JsObject, outcome: TransactionOutcome) {
            val handlerName = if (outcome == TransactionOutcome.COMPLETE) "oncomplete" else "onabort"
            (tx.get(handlerName) as? JsFunction)?.call(interp, tx, listOf(JsObject()))
        }

        fun makeObjectStoreObject(
            dbName: String,
            storeName: String,
            interp: Interpreter,
            tracker: IndexedDbTransactionTracker,
            onOutcome: (TransactionOutcome) -> Unit
        ): JsObject {
            fun <T : JsValue> trackedRequest(resultProvider: () -> T): JsObject {
                val request = JsObject()
                tracker.requestStarted()
                deferRequest(interp, request, onSettled = { success ->
                    tracker.requestFinished(success)?.let(onOutcome)
                }, resultProvider = resultProvider)
                return request
            }
            val store = JsObject()
            val putFn = NativeFunction("put", 2) { _, _, args ->
                val value = args.getOrElse(0) { JsUndefined }
                val key = toJsString(args.getOrElse(1) { JsUndefined })
                // Spec: `put`/`add`'s request.result is the record's *key*, not the stored value.
                trackedRequest { indexedDbStore.put(dbName, storeName, key, value); JsString(key) }
            }
            store.set("put", putFn)
            store.set("add", putFn) // bounded: behaves like put, no "key already exists" error
            store.set("get", NativeFunction("get", 1) { _, _, args ->
                val key = toJsString(args.getOrElse(0) { JsUndefined })
                trackedRequest { indexedDbStore.get(dbName, storeName, key) ?: JsUndefined }
            })
            store.set("delete", NativeFunction("delete", 1) { _, _, args ->
                val key = toJsString(args.getOrElse(0) { JsUndefined })
                trackedRequest { indexedDbStore.delete(dbName, storeName, key); JsUndefined }
            })
            store.set("getAll", NativeFunction("getAll", 0) { _, _, _ ->
                trackedRequest { JsArray(indexedDbStore.getAll(dbName, storeName).toMutableList()) }
            })
            store.set("clear", NativeFunction("clear", 0) { _, _, _ ->
                trackedRequest { indexedDbStore.clear(dbName, storeName); JsUndefined }
            })
            return store
        }

        fun makeDbObject(dbName: String, interp: Interpreter): JsObject {
            val db = JsObject()
            val connection = IndexedDbConnectionGuard()
            db.set("createObjectStore", NativeFunction("createObjectStore", 1) { _, _, args ->
                indexedDbStore.createObjectStore(dbName, toJsString(args.getOrElse(0) { JsUndefined }))
                JsUndefined
            })
            db.set("close", NativeFunction("close", 0) { _, _, _ ->
                connection.close()
                JsUndefined
            })
            db.set("transaction", NativeFunction("transaction", 2) { txInterp, _, args ->
                if (!connection.isOpen) {
                    throw jsError("Failed to execute 'transaction' on 'IDBDatabase': The database connection is closed.")
                }
                val storeName = toJsString(args.getOrElse(0) { JsUndefined })
                val tx = JsObject()
                val tracker = IndexedDbTransactionTracker()
                tx.set("objectStore", NativeFunction("objectStore", 1) { _, _, _ ->
                    makeObjectStoreObject(dbName, storeName, txInterp, tracker) { outcome -> fireTransactionOutcome(txInterp, tx, outcome) }
                })
                // Fires oncomplete for a transaction whose requests (if any) have all already
                // settled by the time the current synchronous script finishes - including the
                // case where the script never issued a request through this tx at all.
                postMain {
                    if (currentDoc !== pageRoot) return@postMain
                    tracker.finalizeIfIdle()?.let { outcome -> fireTransactionOutcome(txInterp, tx, outcome) }
                }
                tx
            })
            return db
        }

        val indexedDbObj = JsObject()
        indexedDbObj.set("open", NativeFunction("open", 2) { interp, _, args ->
            val dbName = toJsString(args.getOrElse(0) { JsUndefined })
            val request = JsObject()
            postMain {
                if (currentDoc !== pageRoot) return@postMain
                val dbObj = makeDbObject(dbName, interp)
                if (indexedDbStore.isFirstOpen(dbName)) {
                    val event = JsObject()
                    val target = JsObject()
                    target.set("result", dbObj)
                    event.set("target", target)
                    (request.get("onupgradeneeded") as? JsFunction)?.call(interp, request, listOf(event))
                }
                val event = JsObject()
                val target = JsObject()
                target.set("result", dbObj)
                event.set("target", target)
                (request.get("onsuccess") as? JsFunction)?.call(interp, request, listOf(event))
            }
            request
        })
        interpreter.globalEnv.declare("indexedDB", indexedDbObj)
    }

    /**
     * `new WebSocket(url)`: a real client (see WebSocketClient), wired to
     * `onopen`/`onmessage`/`onclose`/`onerror` properties (the addEventListener
     * style isn't supported for WebSocket, only the property-assignment
     * style most real code actually uses) plus `send()`/`close()` and a
     * standards-shaped `readyState`. Same mixed-content principle as
     * fetch()/XHR: an HTTPS page can't open a plain `ws://` socket, only
     * `wss://`.
     */
    private fun installWebSocket(interpreter: Interpreter, pageRoot: ElementNode, baseUrl: Url, afterAsyncWork: () -> Unit) {
        val ctor = NativeFunction("WebSocket", 1) { interp, thisArg, args ->
            val obj = thisArg as? JsObject ?: JsObject()
            val urlString = toJsString(args.getOrElse(0) { JsUndefined })
            val wsUrl = try { baseUrl.resolve(urlString) } catch (e: Exception) { throw jsError("Invalid WebSocket URL: $urlString") }
            if (baseUrl.isHttps && wsUrl.scheme == "ws") {
                throw jsError("Mixed Content: the page at '$baseUrl' was loaded over HTTPS, but attempted to open an insecure WebSocket to '$wsUrl'. This request has been blocked.")
            }
            obj.set("readyState", JsNumber(0.0)) // CONNECTING
            obj.set("url", JsString(wsUrl.toString()))
            obj.set("send", NativeFunction("send", 1) { _, _, sendArgs ->
                JsUndefined.also { wsClientFor(obj)?.send(toJsString(sendArgs.getOrElse(0) { JsUndefined })) }
            })
            obj.set("close", NativeFunction("close", 0) { _, _, _ -> wsClientFor(obj)?.close(); JsUndefined })

            val client = WebSocketClient(wsUrl)
            webSockets[obj] = client
            client.onOpen = {
                postMain {
                    if (currentDoc === pageRoot) {
                        obj.set("readyState", JsNumber(1.0))
                        (obj.get("onopen") as? JsFunction)?.call(interp, obj, listOf(JsEvent("open", obj)))
                    }
                }
            }
            client.onMessage = { data ->
                postMain {
                    val event = JsEvent("message", obj)
                    event.set("data", JsString(data))
                    (obj.get("onmessage") as? JsFunction)?.call(interp, obj, listOf(event))
                    afterAsyncWork()
                }
            }
            client.onClose = { code, reason ->
                postMain {
                    obj.set("readyState", JsNumber(3.0))
                    val event = JsEvent("close", obj)
                    event.set("code", JsNumber(code.toDouble()))
                    event.set("reason", JsString(reason))
                    (obj.get("onclose") as? JsFunction)?.call(interp, obj, listOf(event))
                    webSockets.remove(obj)
                }
            }
            client.onError = { message ->
                postMain {
                    (obj.get("onerror") as? JsFunction)?.call(interp, obj, listOf(JsEvent("error", obj)))
                    println("[WebSocket error] $message")
                }
            }
            client.connect()
            obj
        }
        interpreter.globalEnv.declare("WebSocket", ctor)
    }

    private val webSockets = HashMap<JsObject, WebSocketClient>()
    private fun wsClientFor(obj: JsObject): WebSocketClient? = webSockets[obj]

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
    private fun installXmlHttpRequest(interpreter: Interpreter, baseUrl: Url, csp: ContentSecurityPolicy, afterAsyncWork: () -> Unit) {
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
                if (url == null || isMixedContent(baseUrl, url) || !csp.allowsConnectSrc(baseUrl, url) || TrackingProtection.isBlocked(baseUrl, url)) {
                    xhr.set("readyState", JsNumber(4.0))
                    fire("error")
                    afterAsyncWork()
                    return@NativeFunction JsUndefined
                }
                val bodyText = args.getOrNull(0)?.let { if (it != JsUndefined) toJsString(it) else null }
                executor.execute {
                    try {
                        val response = url.fetch(method, bodyText?.toByteArray(Charsets.UTF_8), requestHeaders, allowCookies = !isPrivate)
                        postMain {
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
                        postMain {
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

    private class PendingCssSource(val inlineText: String?, val future: Future<String?>?, val base: Url)

    /**
     * External stylesheets are now fetched concurrently (see [subresourceExecutor]'s doc), but the
     * cascade is order-sensitive - a later rule of equal specificity must still win over an earlier
     * one - so the document-order work list is built up front and [PendingCssSource.future]s are
     * only awaited afterward, in that original order, not in whatever order the network happens to
     * finish them.
     */
    private fun collectAuthorCss(root: ElementNode, baseUrl: Url, viewportWidth: Float, csp: ContentSecurityPolicy): AuthorCss {
        val rules = ArrayList<CssRule>()
        val fontFaces = ArrayList<Pair<FontFaceRule, Url>>()

        fun harvest(parser: CssParser, styleSheetBase: Url) {
            rules.addAll(parser.parseRules())
            for (decl in parser.fontFaceRules) {
                parseFontFaceRule(decl)?.let { fontFaces.add(it to styleSheetBase) }
            }
        }

        val sources = ArrayList<PendingCssSource>()
        root.walkElements { el ->
            when {
                el.tag == "style" -> {
                    if (csp.allowsInlineStyle()) {
                        val text = el.children.filterIsInstance<TextNode>().joinToString("") { it.text }
                        sources.add(PendingCssSource(text, null, baseUrl))
                    }
                }
                el.tag == "link" && el.attr("rel")?.lowercase()?.contains("stylesheet") == true -> {
                    val href = el.attr("href")
                    if (href != null) {
                        val styleSheetUrl = baseUrl.resolveOrNull(href)
                        if (styleSheetUrl != null && !isMixedContent(baseUrl, styleSheetUrl) && csp.allowsStyleSrc(baseUrl, styleSheetUrl) && !TrackingProtection.isBlocked(baseUrl, styleSheetUrl)) {
                            val future = subresourceExecutor.submit(Callable {
                                try { styleSheetUrl.fetch(allowCookies = !isPrivate).body } catch (_: Exception) { null }
                            })
                            sources.add(PendingCssSource(null, future, styleSheetUrl))
                        }
                    }
                }
            }
        }

        for (source in sources) {
            val text = source.inlineText ?: source.future?.get() ?: continue
            // url()s inside an external stylesheet resolve against ITS location, not the page's.
            harvest(CssParser(text, viewportWidth), source.base)
        }
        return AuthorCss(rules, fontFaces)
    }

    /** Typefaces for layout in this process, and their SFNT bytes for the UI process that draws them. */
    private class LoadedFonts(val typefaces: Map<String, Typeface>, val sfnt: Map<String, ByteArray>)

    /**
     * Loads one font file per `@font-face` family. A family usually has many rules (one per
     * unicode-range subset, weight and style), and fetching all of them wastes bandwidth, so the
     * candidates are ranked: Latin coverage first, then weight closest to 400, then upright. The
     * best candidate of every family is fetched concurrently (see [subresourceExecutor]); if it
     * fails to fetch or decode, the next one is tried. FontCache synthesizes bold and italic from
     * the loaded file.
     */
    private fun loadFontFaces(entries: List<Pair<FontFaceRule, Url>>, pageUrl: Url, csp: ContentSecurityPolicy): LoadedFonts {
        // Each candidate is a loader keyed by its URL text, so duplicates collapse.
        val byFamily = LinkedHashMap<String, LinkedHashMap<String, () -> ByteArray?>>()
        val ranked = entries.sortedWith(
            compareBy<Pair<FontFaceRule, Url>>({ !it.first.coversLatin }, { Math.abs(it.first.weight - 400) }, { it.first.italic })
        )
        for ((rule, styleSheetBase) in ranked) {
            val candidates = byFamily.getOrPut(rule.family.lowercase()) { LinkedHashMap() }
            if (rule.srcUrl.startsWith("data:", ignoreCase = true)) {
                candidates.getOrPut(rule.srcUrl.take(200) + rule.srcUrl.length) { { decodeDataUrl(rule.srcUrl) } }
                continue
            }
            val fontUrl = styleSheetBase.resolveOrNull(rule.srcUrl) ?: continue
            if (isMixedContent(pageUrl, fontUrl) || !csp.allowsFontSrc(pageUrl, fontUrl) || TrackingProtection.isBlocked(pageUrl, fontUrl)) continue
            candidates.getOrPut(fontUrl.toString()) {
                { try { fontUrl.fetchBytes(allowCookies = !isPrivate).body } catch (_: Exception) { null } }
            }
        }
        val first = byFamily.filterValues { it.isNotEmpty() }.mapValues { (_, c) -> subresourceExecutor.submit(Callable { c.values.first()() }) }

        val typefaces = HashMap<String, Typeface>()
        val sfntByFamily = HashMap<String, ByteArray>()
        for ((family, future) in first) {
            for ((i, entry) in byFamily.getValue(family).entries.withIndex().take(3)) {
                val bytes = (if (i == 0) future.get() else entry.value()) ?: continue
                val sfnt = FontDecoder.toSfnt(bytes)
                if (sfnt == null) {
                    BrowserLog.w("fonts", "couldn't decode $family from ${entry.key.take(120)} (${bytes.size}B)")
                    continue
                }
                val typeface = typefaceFromSfnt(sfnt, context.cacheDir) ?: continue
                typefaces[family] = typeface
                sfntByFamily[family] = sfnt
                break
            }
        }
        return LoadedFonts(typefaces, sfntByFamily)
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

    /**
     * See [destroyed]'s doc. Order matters: [destroyed] is set first so any
     * background task that's already mid-flight and about to call
     * [postMain]/[postMainDelayed] finds it set the moment it runs on the
     * main thread; [Handler.removeCallbacksAndMessages] then drops every
     * already-queued (not yet run) post/delayed-post on this tab's own
     * `mainHandler` - timers, an in-flight fetch's continuation, a WebSocket
     * message - since a background thread can still queue one concurrently
     * with this call, both guards matter, not just one.
     */
    fun destroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
    }
}
