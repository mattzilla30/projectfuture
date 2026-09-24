package com.projectfuture.browser.ipc

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.projectfuture.browser.browser.LoginFormDetector
import com.projectfuture.browser.debug.BrowserLog
import com.projectfuture.browser.browser.LocalStorageStore
import com.projectfuture.browser.browser.Settings
import com.projectfuture.browser.browser.Tab
import com.projectfuture.browser.browser.TabState
import com.projectfuture.browser.browser.sharedLocalStorage
import com.projectfuture.browser.html.ElementNode
import com.projectfuture.browser.html.TextNode
import com.projectfuture.browser.html.walkElements
import com.projectfuture.browser.net.CookieJar
import com.projectfuture.browser.net.TrackingProtection
import com.projectfuture.browser.net.Url
import com.projectfuture.browser.net.sharedCookieJar
import java.io.ByteArrayOutputStream

/**
 * Hosts one tab's whole engine - HTTP fetch, HTML/CSS parsing, the
 * from-scratch JS interpreter/DOM, and layout (the real [Tab] class,
 * unmodified) - bound to this `Service`, which one of the concrete
 * `TabEngineServiceN` subclasses below runs in its own OS process (see
 * their `android:process` manifest entries and [TabEngineServicePool]'s
 * doc for why there's a fixed pool of them rather than one process per
 * tab). The UI process never touches this `Tab` instance directly; every
 * interaction crosses a `Messenger`/Binder call in
 * [TabEngineProtocol]'s vocabulary, and the DOM/JS/layout state this `Tab`
 * builds up is only ever exposed to the other side as the flattened,
 * reference-free [WireDisplayCommand] list ([EngineToUiConverter]) plus a
 * handful of small typed replies (element metadata, select options, field
 * values, login-form detection - see [TabEngineProtocol]'s message list).
 *
 * What this buys, concretely: a JS bug (an infinite loop, a native crash
 * in glyph shaping triggered by pathological content, an OOM from a huge
 * page) inside this process's `Tab` no longer takes down the UI process
 * or any other tab's engine process - the same isolation goal per-tab
 * process sandboxing exists for in a real multi-process browser. The
 * costs are real too: every tap/keystroke/relayout round-trips a Binder
 * call instead of a direct method call, images are re-encoded to PNG once
 * per distinct bitmap instead of shared as a texture, and this is
 * meaningfully slower than the in-process path - acceptable for isolation,
 * not tuned for latency.
 *
 * Two persistence stores that are process-global `var`s in the
 * single-process design ([sharedCookieJar], [sharedLocalStorage]) are
 * (re)initialized here in [onCreate] so cookies/`localStorage` actually
 * work for a sandboxed tab at all - without this they'd silently stay
 * `null` in this process and every page would look logged-out/storage-
 * less every time. That fixes "does it work in this process", but not the
 * deeper problem: `SharedPreferences` (what [CookieJar]/[LocalStorageStore]
 * are built on) is documented by Android as unsafe to share across
 * processes - each process keeps its own in-memory cache of the file and
 * doesn't observe another process's writes, so a cookie set by tab A's
 * engine process (`:tabengine0`) is not reliably visible to tab B's
 * (`:tabengine1`), or even to the UI process's own `CookieJar` instance,
 * without that other process being restarted. This is a real, un-fixed
 * gap - the honest fix is a `ContentProvider`-backed store with proper
 * cross-process notification, which is real, substantial work this pass
 * didn't attempt. History recording and saved-password prompts don't have
 * this problem: MainActivity does that work itself, in the UI process,
 * off the plain (title, url) / (origin, username, password) strings that
 * already cross via [TabEngineProtocol]'s state and credential messages.
 */
abstract class TabEngineServiceBase : Service() {
    private var tab: Tab? = null
    private var uiMessenger: Messenger? = null
    private var isPrivateTab = false
    private val elementIds = ElementIdRegistry()
    private val converter = EngineToUiConverter(elementIds)
    /** Font data the UI process already has for the current document, by identity so a reloaded font is sent again. */
    private val sentFonts = java.util.IdentityHashMap<ByteArray, Unit>()

    override fun onCreate() {
        super.onCreate()
        BrowserLog.i("ipc", "tab engine service ${javaClass.simpleName} started in pid ${android.os.Process.myPid()}")
        // See this class's doc for what this does and doesn't fix.
        if (sharedCookieJar == null) sharedCookieJar = CookieJar(applicationContext)
        if (sharedLocalStorage == null) sharedLocalStorage = LocalStorageStore(applicationContext)
        TrackingProtection.enabled = Settings(applicationContext).trackingProtectionEnabled
    }

    private val incomingHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                TabEngineProtocol.MSG_REGISTER_CLIENT -> uiMessenger = msg.replyTo
                TabEngineProtocol.MSG_INIT -> isPrivateTab = msg.data.getBoolean(TabEngineProtocol.KEY_IS_PRIVATE)
                TabEngineProtocol.MSG_LOAD_URL -> {
                    val url = msg.data.getString(TabEngineProtocol.KEY_URL) ?: return
                    val template = msg.data.getString(TabEngineProtocol.KEY_SEARCH_TEMPLATE) ?: Settings.DEFAULT_SEARCH_TEMPLATE
                    ensureTab().navigate(url, template)
                }
                TabEngineProtocol.MSG_DISPATCH_TAP -> withElement(msg) { tab?.dispatchClick(it) }
                TabEngineProtocol.MSG_DISPATCH_INPUT -> withElement(msg) {
                    tab?.dispatchInputEvent(it, msg.data.getString(TabEngineProtocol.KEY_VALUE) ?: "")
                }
                TabEngineProtocol.MSG_SET_VIEWPORT_SIZE -> {
                    val width = msg.data.getFloat(TabEngineProtocol.KEY_WIDTH)
                    val height = msg.data.getFloat(TabEngineProtocol.KEY_HEIGHT)
                    if (tab?.onViewportSizeChanged(width, height) == true) sendDisplayList()
                }
                TabEngineProtocol.MSG_GO_BACK -> { tab?.goBack(); sendTabInfo() }
                TabEngineProtocol.MSG_GO_FORWARD -> { tab?.goForward(); sendTabInfo() }
                TabEngineProtocol.MSG_RELOAD -> tab?.reload()
                TabEngineProtocol.MSG_FOLLOW_LINK -> msg.data.getString(TabEngineProtocol.KEY_URL)?.let { tab?.followLink(it) }
                TabEngineProtocol.MSG_TOGGLE_DESKTOP_MODE -> { tab?.toggleDesktopMode(); sendTabInfo() }
                TabEngineProtocol.MSG_TOGGLE_READER_MODE -> { tab?.toggleReaderMode(); sendTabInfo() }
                TabEngineProtocol.MSG_SET_TEXT_SCALE -> {
                    tab?.setTextScale(msg.data.getFloat(TabEngineProtocol.KEY_SCALE))
                    sendDisplayList()
                    sendTabInfo()
                }
                TabEngineProtocol.MSG_DISCARD -> { tab?.discardForMemoryPressure(); sendTabInfo() }
                TabEngineProtocol.MSG_SET_FIELD_VALUE -> withElement(msg) {
                    tab?.setFieldValue(it, msg.data.getString(TabEngineProtocol.KEY_VALUE) ?: "")
                }
                TabEngineProtocol.MSG_SET_SELECT_VALUE -> {
                    val select = elementIds.elementFor(msg.data.getInt(TabEngineProtocol.KEY_ELEMENT_ID))
                    val option = elementIds.elementFor(msg.data.getInt(TabEngineProtocol.KEY_OPTION_ELEMENT_ID))
                    if (select != null && option != null) tab?.setSelectValue(select, option)
                }
                TabEngineProtocol.MSG_REQUEST_FIELD_VALUE -> withElement(msg) { element ->
                    val id = msg.data.getInt(TabEngineProtocol.KEY_ELEMENT_ID)
                    sendMessage(TabEngineProtocol.MSG_FIELD_VALUE) {
                        putInt(TabEngineProtocol.KEY_ELEMENT_ID, id)
                        putString(TabEngineProtocol.KEY_VALUE, tab?.currentFieldValue(element) ?: "")
                    }
                }
                TabEngineProtocol.MSG_REQUEST_SELECT_OPTIONS -> {
                    val id = msg.data.getInt(TabEngineProtocol.KEY_ELEMENT_ID)
                    val select = elementIds.elementFor(id)
                    sendSelectOptions(id, select)
                }
                TabEngineProtocol.MSG_REQUEST_LOGIN_FORM -> sendLoginFormResult()
                TabEngineProtocol.MSG_AUTOFILL_LOGIN_FORM -> {
                    val usernameField = elementIds.elementFor(msg.data.getInt(TabEngineProtocol.KEY_USERNAME_FIELD_ID))
                    val passwordField = elementIds.elementFor(msg.data.getInt(TabEngineProtocol.KEY_PASSWORD_FIELD_ID))
                    if (usernameField != null) tab?.setFieldValue(usernameField, msg.data.getString(TabEngineProtocol.KEY_USERNAME) ?: "")
                    if (passwordField != null) tab?.setFieldValue(passwordField, msg.data.getString(TabEngineProtocol.KEY_PASSWORD) ?: "")
                }
                TabEngineProtocol.MSG_REQUEST_MANIFEST_INFO -> {
                    tab?.fetchManifestInfo { name, iconBytes ->
                        sendMessage(TabEngineProtocol.MSG_MANIFEST_INFO) {
                            putString(TabEngineProtocol.KEY_TITLE, name)
                            if (iconBytes != null) IpcPayload.put(this, TabEngineProtocol.KEY_IMAGE_BYTES, iconBytes, cacheDir)
                        }
                    }
                }
                TabEngineProtocol.MSG_TRUST_CERTIFICATE -> {
                    val urlString = msg.data.getString(TabEngineProtocol.KEY_URL) ?: return
                    try { tab?.trustCertificateAndReload(Url.parse(urlString)) } catch (_: Exception) {}
                }
            }
        }
    }
    private val incomingMessenger = Messenger(incomingHandler)

    private inline fun withElement(msg: Message, action: (ElementNode) -> Unit) {
        elementIds.elementFor(msg.data.getInt(TabEngineProtocol.KEY_ELEMENT_ID))?.let(action)
    }

    override fun onBind(intent: Intent?): IBinder = incomingMessenger.binder

    private fun ensureTab(): Tab = tab ?: Tab(this, ::onTabStateChanged, isPrivateTab).also {
        it.onDownloadRequested = { url, filename ->
            sendMessage(TabEngineProtocol.MSG_DOWNLOAD_REQUESTED) {
                putString(TabEngineProtocol.KEY_URL, url)
                if (filename != null) putString(TabEngineProtocol.KEY_TITLE, filename)
            }
        }
        it.onLoginFormSubmitted = { origin, username, password ->
            sendMessage(TabEngineProtocol.MSG_LOGIN_FORM_SUBMITTED) {
                putString(TabEngineProtocol.KEY_URL, origin)
                putString(TabEngineProtocol.KEY_USERNAME, username)
                putString(TabEngineProtocol.KEY_PASSWORD, password)
            }
        }
        tab = it
    }

    private fun onTabStateChanged(state: TabState) {
        if (state is TabState.Loaded) {
            // A genuinely fresh document (not a same-document update - see TabState's own doc/
            // Tab.load()'s documentGeneration bump) is the one and only point every code path that
            // can load a new document - MSG_LOAD_URL, a followed link, reload, and a goBack/
            // goForward that lands on a different document generation - actually goes through, so
            // this is the single correct place to invalidate the previous document's element ids
            // and per-document conversion caches (see ElementIdRegistry's/EngineToUiConverter.reset's
            // class docs: "a stale id from a discarded document must never be confused with an id
            // in the new one"). Resetting per-IPC-message-type instead (as this used to for
            // elementIds, and never at all for the converter's metaSent/imageIds caches) missed
            // followLink/reload/back/forward entirely and let a recycled id silently resolve to a
            // *previous* document's element tag/type on the UI side.
            //
            // The state message is sent *before* sendDisplayList() (the reverse of every other
            // branch below) specifically so TabEngineClient can reset its own UiDisplayListConverter
            // on receipt, before this document's MSG_ELEMENT_META/MSG_DISPLAY_LIST arrive - see
            // TabEngineClient's MSG_STATE_LOADED handling and UiDisplayListConverter.reset's doc.
            elementIds.reset()
            converter.reset()
            sentFonts.clear()
            send(stateMessage(TabEngineProtocol.MSG_STATE_LOADED, state.url.toString(), state.title))
            sendDisplayList()
            sendTabInfo()
            return
        }
        val message = when (state) {
            is TabState.Loading -> stateMessage(TabEngineProtocol.MSG_STATE_LOADING, state.url.toString())
            is TabState.Loaded -> return // handled above
            is TabState.Updated -> {
                sendDisplayList()
                stateMessage(TabEngineProtocol.MSG_STATE_UPDATED, state.url.toString(), state.title)
            }
            is TabState.Error -> stateMessage(TabEngineProtocol.MSG_STATE_ERROR, state.url.toString(), state.message)
            is TabState.CertificateError -> stateMessage(TabEngineProtocol.MSG_STATE_CERTIFICATE_ERROR, state.url.toString(), state.message)
        }
        send(message)
        sendTabInfo()
    }

    private fun stateMessage(what: Int, url: String, extra: String? = null): Message =
        Message.obtain(null, what).apply {
            data = Bundle().apply {
                putString(TabEngineProtocol.KEY_URL, url)
                if (what == TabEngineProtocol.MSG_STATE_LOADED || what == TabEngineProtocol.MSG_STATE_UPDATED) putString(TabEngineProtocol.KEY_TITLE, extra)
                if (what == TabEngineProtocol.MSG_STATE_ERROR || what == TabEngineProtocol.MSG_STATE_CERTIFICATE_ERROR) putString(TabEngineProtocol.KEY_MESSAGE, extra)
            }
        }

    private fun sendTabInfo() {
        val currentTab = tab ?: return
        val info = WireTabInfo(
            url = currentTab.currentUrl?.toString(),
            title = currentTab.currentDoc?.let { extractTitleFor(it) },
            canGoBack = currentTab.canGoBack(),
            canGoForward = currentTab.canGoForward(),
            desktopMode = currentTab.desktopMode,
            readerModeActive = currentTab.readerModeActive,
            textScale = currentTab.textScale,
            isDiscarded = currentTab.isDiscarded
        )
        sendMessage(TabEngineProtocol.MSG_TAB_INFO) { putByteArray(TabEngineProtocol.KEY_PAYLOAD, TabInfoCodec.encode(info)) }
    }

    /** Same `<title>` lookup `Tab` does internally (private there) - fine to duplicate rather than exposing it, since it's a two-line pure DOM walk. */
    private fun extractTitleFor(root: ElementNode): String? {
        var title: String? = null
        root.walkElements { el ->
            if (el.tag == "title" && title == null) {
                val text = el.children.filterIsInstance<TextNode>().joinToString("") { it.text }.trim()
                if (text.isNotEmpty()) title = text
            }
        }
        return title
    }

    private fun sendSelectOptions(selectId: Int, select: ElementNode?) {
        val options = select?.children?.filterIsInstance<ElementNode>()?.filter { it.tag == "option" } ?: emptyList()
        val wireOptions = options.map { opt ->
            val label = opt.children.filterIsInstance<TextNode>().joinToString("") { it.text }.trim().ifEmpty { opt.attr("value") ?: "" }
            WireSelectOption(elementIds.idFor(opt), label, opt.attr("selected") != null)
        }
        sendMessage(TabEngineProtocol.MSG_SELECT_OPTIONS) {
            putInt(TabEngineProtocol.KEY_ELEMENT_ID, selectId)
            putByteArray(TabEngineProtocol.KEY_PAYLOAD, SelectOptionsCodec.encode(wireOptions))
        }
    }

    private fun sendLoginFormResult() {
        val doc = tab?.currentDoc
        val fields = doc?.let { LoginFormDetector.findLoginForm(it) }
        sendMessage(TabEngineProtocol.MSG_LOGIN_FORM_RESULT) {
            putInt(TabEngineProtocol.KEY_USERNAME_FIELD_ID, fields?.usernameField?.let { elementIds.idFor(it) } ?: 0)
            putInt(TabEngineProtocol.KEY_PASSWORD_FIELD_ID, fields?.passwordField?.let { elementIds.idFor(it) } ?: 0)
            putBoolean(TabEngineProtocol.KEY_USERNAME_PREFILLED, fields?.usernameField?.attr("value")?.isNotEmpty() == true)
        }
    }

    private fun sendDisplayList() {
        val currentTab = tab ?: return
        try {
            val (wireCommands, newImages, newMeta) = converter.convert(currentTab.displayList)
            for ((id, bitmap) in newImages) sendImage(id, bitmap)
            sendFonts(currentTab.currentFontData)
            if (newMeta.isNotEmpty()) sendMessage(TabEngineProtocol.MSG_ELEMENT_META) {
                IpcPayload.put(this, TabEngineProtocol.KEY_PAYLOAD, ElementMetaCodec.encode(newMeta), cacheDir)
            }
            val encoded = DisplayListCodec.encode(wireCommands)
            sendMessage(TabEngineProtocol.MSG_DISPLAY_LIST) {
                IpcPayload.put(this, TabEngineProtocol.KEY_PAYLOAD, encoded, cacheDir)
                putFloat(TabEngineProtocol.KEY_CONTENT_HEIGHT, currentTab.contentHeight)
            }
            BrowserLog.i(
                "ipc",
                "sent display list: ${wireCommands.size} commands, ${encoded.size}B" +
                    (if (encoded.size > IpcPayload.INLINE_LIMIT) " via file" else "") +
                    ", ${newImages.size} new images, ${newMeta.size} new elements"
            )
        } catch (e: Throwable) {
            BrowserLog.e("ipc", "couldn't build or send the display list", e)
            // Report it rather than leave a blank page that looks like it loaded fine.
            val url = currentTab.currentUrl?.toString() ?: return
            send(stateMessage(TabEngineProtocol.MSG_STATE_ERROR, url, "Couldn't display this page: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    private fun sendFonts(fonts: Map<String, ByteArray>) {
        for ((family, sfnt) in fonts) {
            if (sentFonts.containsKey(sfnt)) continue
            sentFonts[sfnt] = Unit
            sendMessage(TabEngineProtocol.MSG_FONT_DATA) {
                putString(TabEngineProtocol.KEY_FONT_FAMILY, family)
                IpcPayload.put(this, TabEngineProtocol.KEY_FONT_BYTES, sfnt, cacheDir)
            }
        }
    }

    private fun sendImage(imageId: Int, bitmap: Bitmap) {
        val bytes = ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
        sendMessage(TabEngineProtocol.MSG_IMAGE_DATA) {
            putInt(TabEngineProtocol.KEY_IMAGE_ID, imageId)
            IpcPayload.put(this, TabEngineProtocol.KEY_IMAGE_BYTES, bytes, cacheDir)
        }
    }

    private inline fun sendMessage(what: Int, crossinline buildData: Bundle.() -> Unit) {
        send(Message.obtain(null, what).apply { data = Bundle().apply(buildData) })
    }

    private fun send(message: Message) {
        val reply = uiMessenger ?: return
        try {
            reply.send(message)
        } catch (e: Exception) {
            BrowserLog.w("ipc", "dropped message ${message.what} to the UI process", e)
        }
    }

    override fun onDestroy() {
        tab?.destroy()
        super.onDestroy()
    }
}

/**
 * A fixed pool of 4 separate process names (`:tabengine0`..`:tabengine3`,
 * declared in AndroidManifest.xml), each backed by one of these trivial
 * subclasses. Android has no API for a dynamically-named process per
 * Service instance - `android:process` is a static manifest attribute - so
 * genuine per-tab process isolation on this platform means either a fixed
 * pool (what's implemented here: tab N gets process `N mod 4`, so at most
 * 4 tabs are ever isolated from each other at once, and a 5th tab shares a
 * process with the 1st) or `android:isolatedProcess` services started
 * per-tab (which sandbox far more tightly - no network/most permissions -
 * which would break this browser's actual networking, so it isn't used
 * here). [TabEngineClient] does the pool assignment.
 */
class TabEngineService0 : TabEngineServiceBase()
class TabEngineService1 : TabEngineServiceBase()
class TabEngineService2 : TabEngineServiceBase()
class TabEngineService3 : TabEngineServiceBase()
