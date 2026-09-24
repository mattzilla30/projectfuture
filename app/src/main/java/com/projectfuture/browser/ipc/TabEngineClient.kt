package com.projectfuture.browser.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.projectfuture.browser.browser.Settings
import com.projectfuture.browser.browser.TabState
import com.projectfuture.browser.debug.BrowserLog
import com.projectfuture.browser.html.ElementNode
import com.projectfuture.browser.layout.DisplayCommand
import com.projectfuture.browser.net.Url
import java.util.ArrayDeque
import com.projectfuture.browser.layout.typefaceFromSfnt

/** One `<option>` of a `<select>`, as reported by [TabEngineClient.requestSelectOptions] - [node] is a shadow element to hand straight to [TabEngineClient.setSelectValue]. */
data class RemoteSelectOption(val node: ElementNode, val label: String, val selected: Boolean)

/** A detected login form's fields, as reported by [TabEngineClient.requestLoginForm] - see [com.projectfuture.browser.browser.LoginFormDetector]'s in-process twin. [usernamePrefilled] stands in for inspecting `usernameField.attr("value")` directly, which a shadow element can't carry live (see [UiDisplayListConverter]'s doc). */
data class RemoteLoginForm(val usernameField: ElementNode, val passwordField: ElementNode, val usernamePrefilled: Boolean)

private val POOL: List<Class<out TabEngineServiceBase>> =
    listOf(TabEngineService0::class.java, TabEngineService1::class.java, TabEngineService2::class.java, TabEngineService3::class.java)

/**
 * UI-process handle onto one sandboxed tab: binds to a pooled
 * `TabEngineServiceN` (see [TabEngineServiceBase]'s pool doc) and proxies
 * the full method surface [com.projectfuture.browser.browser.MainActivity]
 * actually calls on a `Tab` (see [com.projectfuture.browser.browser.TabHandle]),
 * turning replies back into a paintable `DisplayCommand` list
 * ([UiDisplayListConverter]) and [TabState] updates - a drop-in enough
 * source of both for [com.projectfuture.browser.view.BrowserView] that
 * BrowserView itself needs no changes to render a sandboxed tab.
 *
 * Load/tap/input/viewport-size/navigation/toggle calls are simple
 * fire-and-forget Messenger sends. A few operations need a value *back*
 * (the current text of a field to prefill an edit dialog, a `<select>`'s
 * option list, whether a detected login form's username is already
 * filled in) - those go out as a request message and come back as a
 * separate reply message on [incomingMessenger], matched up with the
 * callback that asked for it via a plain FIFO queue per request kind
 * (see the `pending*` fields below). That's a real simplification: it
 * assumes at most one outstanding request of a given kind is ever
 * in flight for this tab at a time, which holds for how MainActivity
 * actually uses this (one dialog / one autofill prompt at a time) but
 * would misattribute replies if that ever changed - a correlation id per
 * request would be the general fix, not implemented here since nothing
 * in this app's real call pattern needs it yet.
 *
 * What's deliberately **not** proxied, and why:
 *  - **Printing** doesn't send a live `Canvas`/PDF page across the
 *    process boundary - Canvas isn't Parcelable and PDF generation reuses
 *    the *already-crossed* `displayList`/`contentHeight` this class caches
 *    from [onDisplayListChanged], painted by `BrowserView.paintFullPageForPrint`
 *    in the UI process exactly as it already does for an in-process tab.
 *    So printing/thumbnails need no engine-side proxy work at all here -
 *    the display list *is* the rendered snapshot.
 *  - **Find-in-page** matches text already present in the cached, UI-
 *    process-side `displayList` (`DrawText.text`), so it also needs no
 *    round trip - see MainActivity's `performFind`.
 *  - **Cookies/session persistence**: `Tab`'s networking already goes
 *    through the process-global `sharedCookieJar`/`sharedLocalStorage` -
 *    [TabEngineServiceBase.onCreate] re-initializes both of those inside
 *    each engine process so they aren't silently null there, but
 *    `SharedPreferences` (what they're built on) isn't safe to share
 *    *between* processes on Android - see that class's doc for the real,
 *    unresolved gap this leaves (a cookie set in one `:tabengineN`
 *    process isn't reliably visible to another, or to the UI process).
 *  - **History recording** and **save-password prompts** need no engine-
 *    side proxy either: MainActivity already does that work itself, off
 *    the (title, url) / (origin, username, password) strings carried by
 *    the state/credential messages below - see `onTabStateChanged`'s doc.
 *  - **WebSocket-driven live updates** (a page's `onmessage` handler
 *    mutating the DOM after a socket frame arrives) work already, with no
 *    extra plumbing: they're just another `TabState.Updated` from
 *    `Tab.installBrowserRuntime`'s `afterAsyncWork`, which crosses exactly
 *    like every other DOM-mutation update this class already forwards.
 *  - **Canvas/SVG extraction**: already crosses today - both are baked
 *    into the display list as ordinary `DrawImage` commands by `Tab`
 *    before layout ever runs (see `Tab.collectAndRenderSvgs`/
 *    `DomBridge.canvasBitmaps`), so [EngineToUiConverter]/
 *    [UiDisplayListConverter]'s existing image pipeline (send bytes once,
 *    reference by id after) already ships the extracted pixels - no
 *    separate message type was needed for this.
 */
class TabEngineClient(private val context: Context, tabIndex: Int, val isPrivate: Boolean) {
    private val serviceClass = POOL[Math.floorMod(tabIndex, POOL.size)]
    private var outgoingMessenger: Messenger? = null
    private var bound = false
    private val converter = UiDisplayListConverter()

    // Cached from the latest MSG_DISPLAY_LIST/MSG_TAB_INFO - see this class's doc for why these
    // can't be synchronous queries against the engine process.
    var displayList: List<DisplayCommand> = emptyList(); private set
    var contentHeight: Float = 0f; private set
    var canGoBack: Boolean = false; private set
    var canGoForward: Boolean = false; private set
    var desktopMode: Boolean = false; private set
    var readerModeActive: Boolean = false; private set
    var textScale: Float = 1f; private set
    var isDiscarded: Boolean = false; private set
    var currentUrl: Url? = null; private set

    var onDisplayListChanged: ((List<DisplayCommand>, Float) -> Unit)? = null
    var onStateChanged: ((TabState) -> Unit)? = null
    var onDownloadRequested: ((url: String, suggestedFilename: String?) -> Unit)? = null
    var onLoginFormSubmitted: ((origin: String, username: String, password: String) -> Unit)? = null

    private val pendingFieldValue = ArrayDeque<(String) -> Unit>()
    private val pendingSelectOptions = ArrayDeque<(List<RemoteSelectOption>) -> Unit>()
    private val pendingLoginForm = ArrayDeque<(RemoteLoginForm?) -> Unit>()
    private val pendingManifestInfo = ArrayDeque<(String, ByteArray?) -> Unit>()

    private val incomingMessenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                TabEngineProtocol.MSG_DISPLAY_LIST -> {
                    val payload = IpcPayload.take(msg.data, TabEngineProtocol.KEY_PAYLOAD, context.cacheDir) ?: return
                    contentHeight = msg.data.getFloat(TabEngineProtocol.KEY_CONTENT_HEIGHT)
                    displayList = converter.convert(DisplayListCodec.decode(payload))
                    onDisplayListChanged?.invoke(displayList, contentHeight)
                }
                TabEngineProtocol.MSG_IMAGE_DATA -> {
                    val id = msg.data.getInt(TabEngineProtocol.KEY_IMAGE_ID)
                    val bytes = IpcPayload.take(msg.data, TabEngineProtocol.KEY_IMAGE_BYTES, context.cacheDir) ?: return
                    converter.onImageReceived(id, bytes)
                }
                TabEngineProtocol.MSG_FONT_DATA -> {
                    val family = msg.data.getString(TabEngineProtocol.KEY_FONT_FAMILY) ?: return
                    val bytes = IpcPayload.take(msg.data, TabEngineProtocol.KEY_FONT_BYTES, context.cacheDir) ?: return
                    typefaceFromSfnt(bytes, context.cacheDir)?.let { converter.onFontReceived(family, it) }
                }
                TabEngineProtocol.MSG_ELEMENT_META -> {
                    val payload = IpcPayload.take(msg.data, TabEngineProtocol.KEY_PAYLOAD, context.cacheDir) ?: return
                    converter.onElementMetaReceived(ElementMetaCodec.decode(payload))
                }
                TabEngineProtocol.MSG_TAB_INFO -> {
                    val payload = msg.data.getByteArray(TabEngineProtocol.KEY_PAYLOAD) ?: return
                    val info = TabInfoCodec.decode(payload)
                    canGoBack = info.canGoBack
                    canGoForward = info.canGoForward
                    desktopMode = info.desktopMode
                    readerModeActive = info.readerModeActive
                    textScale = info.textScale
                    isDiscarded = info.isDiscarded
                    currentUrl = info.url?.let { runCatching { Url.parse(it) }.getOrNull() }
                }
                TabEngineProtocol.MSG_STATE_LOADING -> forwardState(msg) { url -> TabState.Loading(url) }
                TabEngineProtocol.MSG_STATE_LOADED -> {
                    // The engine process sends this before that fresh document's MSG_ELEMENT_META/
                    // MSG_DISPLAY_LIST (see TabEngineServiceBase.onTabStateChanged's TabState.Loaded
                    // branch) specifically so this reset lands before any of that document's ids are
                    // resolved through the converter - see UiDisplayListConverter.reset's doc for
                    // what goes wrong (silently stale shadow elements/tag-type metadata) without it.
                    converter.reset()
                    forwardState(msg) { url -> TabState.Loaded(url, msg.data.getString(TabEngineProtocol.KEY_TITLE)) }
                }
                TabEngineProtocol.MSG_STATE_UPDATED -> forwardState(msg) { url -> TabState.Updated(url, msg.data.getString(TabEngineProtocol.KEY_TITLE)) }
                TabEngineProtocol.MSG_STATE_ERROR -> forwardState(msg) { url -> TabState.Error(url, msg.data.getString(TabEngineProtocol.KEY_MESSAGE) ?: "") }
                TabEngineProtocol.MSG_STATE_CERTIFICATE_ERROR -> forwardState(msg) { url -> TabState.CertificateError(url, msg.data.getString(TabEngineProtocol.KEY_MESSAGE) ?: "") }
                TabEngineProtocol.MSG_FIELD_VALUE -> {
                    val value = msg.data.getString(TabEngineProtocol.KEY_VALUE) ?: ""
                    pendingFieldValue.poll()?.invoke(value)
                }
                TabEngineProtocol.MSG_SELECT_OPTIONS -> {
                    val payload = msg.data.getByteArray(TabEngineProtocol.KEY_PAYLOAD) ?: return
                    val options = SelectOptionsCodec.decode(payload).map { RemoteSelectOption(converter.shadowElementFor(it.elementId), it.label, it.selected) }
                    pendingSelectOptions.poll()?.invoke(options)
                }
                TabEngineProtocol.MSG_LOGIN_FORM_RESULT -> {
                    val usernameId = msg.data.getInt(TabEngineProtocol.KEY_USERNAME_FIELD_ID)
                    val passwordId = msg.data.getInt(TabEngineProtocol.KEY_PASSWORD_FIELD_ID)
                    val result = if (usernameId == 0 || passwordId == 0) null else RemoteLoginForm(
                        converter.shadowElementFor(usernameId), converter.shadowElementFor(passwordId),
                        msg.data.getBoolean(TabEngineProtocol.KEY_USERNAME_PREFILLED)
                    )
                    pendingLoginForm.poll()?.invoke(result)
                }
                TabEngineProtocol.MSG_MANIFEST_INFO -> {
                    val name = msg.data.getString(TabEngineProtocol.KEY_TITLE) ?: ""
                    pendingManifestInfo.poll()?.invoke(name, IpcPayload.take(msg.data, TabEngineProtocol.KEY_IMAGE_BYTES, context.cacheDir))
                }
                TabEngineProtocol.MSG_DOWNLOAD_REQUESTED -> {
                    val url = msg.data.getString(TabEngineProtocol.KEY_URL) ?: return
                    onDownloadRequested?.invoke(url, msg.data.getString(TabEngineProtocol.KEY_TITLE))
                }
                TabEngineProtocol.MSG_LOGIN_FORM_SUBMITTED -> {
                    val origin = msg.data.getString(TabEngineProtocol.KEY_URL) ?: return
                    val username = msg.data.getString(TabEngineProtocol.KEY_USERNAME) ?: return
                    val password = msg.data.getString(TabEngineProtocol.KEY_PASSWORD) ?: return
                    onLoginFormSubmitted?.invoke(origin, username, password)
                }
            }
        }
    })

    private inline fun forwardState(msg: Message, build: (Url) -> TabState) {
        val urlString = msg.data.getString(TabEngineProtocol.KEY_URL) ?: return
        val url = runCatching { Url.parse(urlString) }.getOrNull() ?: return
        onStateChanged?.invoke(build(url))
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            BrowserLog.i("ipc", "connected to ${name.shortClassName}")
            outgoingMessenger = Messenger(service)
            send(TabEngineProtocol.MSG_REGISTER_CLIENT, fillReplyTo = { replyTo = incomingMessenger })
            send(TabEngineProtocol.MSG_INIT) { putBoolean(TabEngineProtocol.KEY_IS_PRIVATE, isPrivate) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            outgoingMessenger = null
            // The engine process crashed or the system killed it. Its page is gone with it, so say
            // so instead of leaving whatever was last drawn looking alive.
            BrowserLog.e("ipc", "tab engine ${name?.shortClassName} disconnected: its process crashed or was killed")
            val url = currentUrl ?: return
            onStateChanged?.invoke(TabState.Error(url, "This tab's engine process stopped. Reload to try again."))
        }
    }

    fun bind() {
        if (bound) return
        val intent = Intent(context, serviceClass)
        bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun unbind() {
        if (!bound) return
        try { context.unbindService(connection) } catch (_: Exception) {}
        bound = false
        outgoingMessenger = null
    }

    fun loadUrl(addressBarInput: String, searchTemplate: String = Settings.DEFAULT_SEARCH_TEMPLATE) {
        send(TabEngineProtocol.MSG_LOAD_URL) {
            putString(TabEngineProtocol.KEY_URL, addressBarInput)
            putString(TabEngineProtocol.KEY_SEARCH_TEMPLATE, searchTemplate)
        }
    }

    fun goBack() = send(TabEngineProtocol.MSG_GO_BACK)
    fun goForward() = send(TabEngineProtocol.MSG_GO_FORWARD)
    fun reload() = send(TabEngineProtocol.MSG_RELOAD)
    fun followLink(href: String) = send(TabEngineProtocol.MSG_FOLLOW_LINK) { putString(TabEngineProtocol.KEY_URL, href) }
    fun toggleDesktopMode() = send(TabEngineProtocol.MSG_TOGGLE_DESKTOP_MODE)
    fun toggleReaderMode() = send(TabEngineProtocol.MSG_TOGGLE_READER_MODE)
    fun setTextScale(scale: Float) = send(TabEngineProtocol.MSG_SET_TEXT_SCALE) { putFloat(TabEngineProtocol.KEY_SCALE, scale) }
    fun discard() = send(TabEngineProtocol.MSG_DISCARD)
    fun trustCertificateAndReload(url: Url) = send(TabEngineProtocol.MSG_TRUST_CERTIFICATE) { putString(TabEngineProtocol.KEY_URL, url.toString()) }

    /** [element] must be a shadow node this client itself handed BrowserView (from a converted display list, or an options/login-form reply) - anything else is a no-op, since there'd be no elementId to translate it back to. */
    fun dispatchTap(element: ElementNode) {
        val id = converter.elementIdFor(element) ?: return
        send(TabEngineProtocol.MSG_DISPATCH_TAP) { putInt(TabEngineProtocol.KEY_ELEMENT_ID, id) }
    }

    fun dispatchInput(element: ElementNode, value: String) {
        val id = converter.elementIdFor(element) ?: return
        send(TabEngineProtocol.MSG_DISPATCH_INPUT) {
            putInt(TabEngineProtocol.KEY_ELEMENT_ID, id)
            putString(TabEngineProtocol.KEY_VALUE, value)
        }
    }

    fun setFieldValue(element: ElementNode, value: String) {
        val id = converter.elementIdFor(element) ?: return
        send(TabEngineProtocol.MSG_SET_FIELD_VALUE) {
            putInt(TabEngineProtocol.KEY_ELEMENT_ID, id)
            putString(TabEngineProtocol.KEY_VALUE, value)
        }
    }

    fun setSelectValue(select: ElementNode, chosenOption: ElementNode) {
        val selectId = converter.elementIdFor(select) ?: return
        val optionId = converter.elementIdFor(chosenOption) ?: return
        send(TabEngineProtocol.MSG_SET_SELECT_VALUE) {
            putInt(TabEngineProtocol.KEY_ELEMENT_ID, selectId)
            putInt(TabEngineProtocol.KEY_OPTION_ELEMENT_ID, optionId)
        }
    }

    /** Async twin of `Tab.currentFieldValue` - see this class's doc for why (no synchronous return across a Messenger call). */
    fun requestFieldValue(element: ElementNode, callback: (String) -> Unit) {
        val id = converter.elementIdFor(element) ?: return callback("")
        pendingFieldValue.add(callback)
        send(TabEngineProtocol.MSG_REQUEST_FIELD_VALUE) { putInt(TabEngineProtocol.KEY_ELEMENT_ID, id) }
    }

    fun requestSelectOptions(select: ElementNode, callback: (List<RemoteSelectOption>) -> Unit) {
        val id = converter.elementIdFor(select) ?: return callback(emptyList())
        pendingSelectOptions.add(callback)
        send(TabEngineProtocol.MSG_REQUEST_SELECT_OPTIONS) { putInt(TabEngineProtocol.KEY_ELEMENT_ID, id) }
    }

    fun requestLoginForm(callback: (RemoteLoginForm?) -> Unit) {
        pendingLoginForm.add(callback)
        send(TabEngineProtocol.MSG_REQUEST_LOGIN_FORM)
    }

    fun autofillLoginForm(fields: RemoteLoginForm, username: String, password: String) {
        val usernameId = converter.elementIdFor(fields.usernameField) ?: return
        val passwordId = converter.elementIdFor(fields.passwordField) ?: return
        send(TabEngineProtocol.MSG_AUTOFILL_LOGIN_FORM) {
            putInt(TabEngineProtocol.KEY_USERNAME_FIELD_ID, usernameId)
            putInt(TabEngineProtocol.KEY_PASSWORD_FIELD_ID, passwordId)
            putString(TabEngineProtocol.KEY_USERNAME, username)
            putString(TabEngineProtocol.KEY_PASSWORD, password)
        }
    }

    fun requestManifestInfo(callback: (name: String, iconBytes: ByteArray?) -> Unit) {
        pendingManifestInfo.add(callback)
        send(TabEngineProtocol.MSG_REQUEST_MANIFEST_INFO)
    }

    fun setViewportSize(width: Float, height: Float) {
        send(TabEngineProtocol.MSG_SET_VIEWPORT_SIZE) {
            putFloat(TabEngineProtocol.KEY_WIDTH, width)
            putFloat(TabEngineProtocol.KEY_HEIGHT, height)
        }
    }

    private inline fun send(what: Int, fillReplyTo: Message.() -> Unit = {}, buildData: Bundle.() -> Unit = {}) {
        val messenger = outgoingMessenger ?: return
        val message = Message.obtain(null, what).apply {
            data = Bundle().apply(buildData)
            fillReplyTo()
        }
        try { messenger.send(message) } catch (_: RemoteException) {}
    }
}
