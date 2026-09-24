package com.projectfuture.browser.browser

import com.projectfuture.browser.html.ElementNode
import com.projectfuture.browser.html.TextNode
import com.projectfuture.browser.layout.DisplayCommand
import com.projectfuture.browser.net.Url

/** One `<option>` of a `<select>` - see [TabHandle.requestSelectOptions]. [node] is opaque - hand it straight back to [TabHandle.setSelectValue]. */
data class TabSelectOption(val node: ElementNode, val label: String, val selected: Boolean)

/** A detected login form's fields - see [TabHandle.detectLoginForm]. [usernameAlreadyFilled] stands in for inspecting `usernameField.attr("value")` directly (a remote handle's field is an opaque token, not a live DOM node - see [com.projectfuture.browser.ipc.TabEngineClient]'s class doc). */
data class DetectedLoginForm(val usernameField: ElementNode, val passwordField: ElementNode, val usernameAlreadyFilled: Boolean)

/**
 * Everything MainActivity/the tab-switcher UI actually calls on a tab,
 * whichever of the two ways that tab's engine actually runs:
 *  - [LocalTabHandle]: the classic single-process path, a thin
 *    pass-through onto a real [Tab] running on this same process's
 *    threads.
 *  - [com.projectfuture.browser.ipc.RemoteTabHandle]: the sandboxed path,
 *    proxying every call across Messenger/Binder to a `Tab` running in a
 *    pooled `:tabengineN` process (see
 *    [com.projectfuture.browser.ipc.TabEngineServiceBase]'s class doc).
 *
 * This interface exists so `MainActivity`/`TabManager` can hold either
 * kind interchangeably - see `TabManager.newTab`'s doc for which one gets
 * created by default and how the fallback to the in-process path is
 * chosen.
 *
 * A few methods that are plain synchronous property reads on [Tab] take a
 * callback here instead ([currentFieldValue], [requestSelectOptions],
 * [detectLoginForm], [fetchManifestInfo]) - a live cross-process call has
 * no synchronous return, so every implementation (including the in-
 * process one, which could answer synchronously) uses the same async
 * shape. For [LocalTabHandle] the callback simply runs before the call
 * returns, so call sites that don't care can still (mostly) read like
 * synchronous code.
 */
interface TabHandle {
    val isPrivate: Boolean
    val currentUrl: Url?
    val displayList: List<DisplayCommand>
    val contentHeight: Float
    val isDiscarded: Boolean
    val desktopMode: Boolean
    val readerModeActive: Boolean
    val textScale: Float

    /** Fired for `<a download href="...">` taps; MainActivity hands the URL off to Android's own DownloadManager. */
    var onDownloadRequested: ((url: String, suggestedFilename: String?) -> Unit)?
    /** Fired when a detected login form is submitted with a non-blank username+password. */
    var onLoginFormSubmitted: ((origin: String, username: String, password: String) -> Unit)?

    fun canGoBack(): Boolean
    fun canGoForward(): Boolean
    fun goBack()
    fun goForward()
    fun reload()
    fun navigate(addressBarInput: String, searchTemplate: String = Settings.DEFAULT_SEARCH_TEMPLATE)
    fun followLink(href: String)
    /** Returns true if the size actually changed (and a repaint was triggered) - same contract as `Tab.onViewportSizeChanged`. */
    fun onViewportSizeChanged(widthPx: Float, heightPx: Float): Boolean
    fun setTextScale(scale: Float)
    fun toggleDesktopMode()
    fun toggleReaderMode()
    fun discardForMemoryPressure()

    fun dispatchClick(element: ElementNode)
    fun dispatchInputEvent(element: ElementNode, newValue: String)
    fun setFieldValue(element: ElementNode, value: String)
    fun setSelectValue(select: ElementNode, chosenOption: ElementNode)
    fun currentFieldValue(element: ElementNode, callback: (String) -> Unit)
    fun requestSelectOptions(select: ElementNode, callback: (List<TabSelectOption>) -> Unit)

    /** Derived purely from [currentUrl] - never needs to reach the engine process (see [com.projectfuture.browser.ipc.RemoteTabHandle]'s implementation). */
    fun currentOrigin(): String?
    fun detectLoginForm(callback: (DetectedLoginForm?) -> Unit)
    fun autofillLoginForm(fields: DetectedLoginForm, username: String, password: String)
    fun trustCertificateAndReload(url: Url)
    fun fetchManifestInfo(callback: (name: String, iconBytes: ByteArray?) -> Unit)

    fun destroy()
}

/**
 * Runs a [Tab] in this process on its own engine thread, so page scripts, timers and layout never
 * block drawing. A big page used to lay out on the UI thread and skip 30+ frames.
 *
 * The UI thread never touches the Tab directly. Commands are posted to the engine thread, and the
 * engine publishes an immutable [Snapshot] of what the UI reads (display list, URL, history flags)
 * back to the UI thread, together with any state change. Callbacks that return data
 * ([currentFieldValue], [requestSelectOptions], [detectLoginForm], [fetchManifestInfo]) run on the
 * engine thread and answer on the UI thread, the same async shape the sandboxed path has.
 *
 * DOM nodes in the display list (`sourceElement`) still cross to the UI thread as opaque tokens for
 * tap routing. The UI reads only their tag and attributes.
 */
class LocalTabHandle(context: android.content.Context, override val isPrivate: Boolean, private val onStateChanged: (TabState) -> Unit) : TabHandle {
    private class Snapshot(
        val url: Url?,
        val displayList: List<DisplayCommand>,
        val contentHeight: Float,
        val isDiscarded: Boolean,
        val desktopMode: Boolean,
        val readerModeActive: Boolean,
        val textScale: Float,
        val canGoBack: Boolean,
        val canGoForward: Boolean
    )

    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val engineThread = EngineThread().apply { start() }
    private val engine = android.os.Handler(engineThread.awaitLooper())
    private val tab = Tab(context, { state -> publish(state) }, isPrivate, engineThread.awaitLooper())

    /** Read and written on the UI thread only. */
    private var snapshot = Snapshot(null, emptyList(), 0f, false, false, false, 1f, false, false)
    private var destroyed = false
    /** Engine thread only: the display list the UI last received, to tell whether a command changed it. */
    private var publishedList: List<DisplayCommand>? = null
    /** UI thread only: the last size handed to the engine, so repeated identical calls are free. */
    private var requestedWidth = 0f
    private var requestedHeight = 0f

    /**
     * Engine thread: captures the tab's state and hands it to the UI thread. A command that changed
     * the display list without reporting a state (text scale, a resize, a form edit) is reported
     * as [TabState.Updated] so MainActivity repaints.
     */
    private fun publish(state: TabState?) {
        val s = Snapshot(
            tab.currentUrl, tab.displayList, tab.contentHeight, tab.isDiscarded, tab.desktopMode,
            tab.readerModeActive, tab.textScale, tab.canGoBack(), tab.canGoForward()
        )
        val listChanged = s.displayList !== publishedList
        publishedList = s.displayList
        val toReport = state ?: if (listChanged) s.url?.let { TabState.Updated(it, null) } else null
        uiHandler.post {
            if (destroyed) return@post
            snapshot = s
            toReport?.let(onStateChanged)
        }
    }

    /** Runs [block] on the engine thread, then publishes whatever it changed. */
    private fun command(block: () -> Unit) {
        engine.post {
            if (destroyed) return@post
            block()
            publish(null)
        }
    }

    /** Runs [query] on the engine thread and delivers its answer on the UI thread. */
    private fun <T> query(query: () -> T, callback: (T) -> Unit) {
        engine.post {
            val result = query()
            uiHandler.post { if (!destroyed) callback(result) }
        }
    }

    override val currentUrl get() = snapshot.url
    override val displayList get() = snapshot.displayList
    override val contentHeight get() = snapshot.contentHeight
    override val isDiscarded get() = snapshot.isDiscarded
    override val desktopMode get() = snapshot.desktopMode
    override val readerModeActive get() = snapshot.readerModeActive
    override val textScale get() = snapshot.textScale

    override var onDownloadRequested: ((String, String?) -> Unit)? = null
        set(value) {
            field = value
            engine.post { tab.onDownloadRequested = value?.let { cb -> { url, name -> uiHandler.post { cb(url, name) } } } }
        }
    override var onLoginFormSubmitted: ((String, String, String) -> Unit)? = null
        set(value) {
            field = value
            engine.post { tab.onLoginFormSubmitted = value?.let { cb -> { o, u, p -> uiHandler.post { cb(o, u, p) } } } }
        }

    override fun canGoBack() = snapshot.canGoBack
    override fun canGoForward() = snapshot.canGoForward
    override fun goBack() = command { tab.goBack() }
    override fun goForward() = command { tab.goForward() }
    override fun reload() = command { tab.reload() }
    override fun navigate(addressBarInput: String, searchTemplate: String) = command { tab.navigate(addressBarInput, searchTemplate) }
    override fun followLink(href: String) = command { tab.followLink(href) }

    /** Always false: the engine lays out asynchronously and reports the new display list as [TabState.Updated]. */
    override fun onViewportSizeChanged(widthPx: Float, heightPx: Float): Boolean {
        if (widthPx == requestedWidth && heightPx == requestedHeight) return false
        requestedWidth = widthPx
        requestedHeight = heightPx
        command { tab.onViewportSizeChanged(widthPx, heightPx) }
        return false
    }

    override fun setTextScale(scale: Float) {
        // Update the UI's copy now so consecutive pinch steps compound from the latest value.
        val clamped = scale.coerceIn(0.5f, 2.5f)
        snapshot.let { snapshot = Snapshot(it.url, it.displayList, it.contentHeight, it.isDiscarded, it.desktopMode, it.readerModeActive, clamped, it.canGoBack, it.canGoForward) }
        command { tab.setTextScale(clamped) }
    }
    override fun toggleDesktopMode() = command { tab.toggleDesktopMode() }
    override fun toggleReaderMode() = command { tab.toggleReaderMode() }
    override fun discardForMemoryPressure() = command { tab.discardForMemoryPressure() }

    override fun dispatchClick(element: ElementNode) = command { tab.dispatchClick(element) }
    override fun dispatchInputEvent(element: ElementNode, newValue: String) = command { tab.dispatchInputEvent(element, newValue) }
    override fun setFieldValue(element: ElementNode, value: String) = command { tab.setFieldValue(element, value) }
    override fun setSelectValue(select: ElementNode, chosenOption: ElementNode) = command { tab.setSelectValue(select, chosenOption) }
    override fun currentFieldValue(element: ElementNode, callback: (String) -> Unit) = query({ tab.currentFieldValue(element) }, callback)

    override fun requestSelectOptions(select: ElementNode, callback: (List<TabSelectOption>) -> Unit) = query({
        select.children.filterIsInstance<ElementNode>().filter { it.tag == "option" }.map { opt ->
            val label = opt.children.filterIsInstance<TextNode>().joinToString("") { it.text }.trim().ifEmpty { opt.attr("value") ?: "" }
            TabSelectOption(opt, label, opt.attr("selected") != null)
        }
    }, callback)

    override fun currentOrigin() = snapshot.url?.credentialOrigin()

    override fun detectLoginForm(callback: (DetectedLoginForm?) -> Unit) = query({
        tab.detectLoginForm()?.let { DetectedLoginForm(it.usernameField, it.passwordField, it.usernameField.attr("value")?.isNotEmpty() == true) }
    }, callback)

    override fun autofillLoginForm(fields: DetectedLoginForm, username: String, password: String) = command {
        tab.setFieldValue(fields.usernameField, username)
        tab.setFieldValue(fields.passwordField, password)
    }

    override fun trustCertificateAndReload(url: Url) = command { tab.trustCertificateAndReload(url) }

    override fun fetchManifestInfo(callback: (String, ByteArray?) -> Unit) {
        engine.post { tab.fetchManifestInfo { name, icon -> uiHandler.post { if (!destroyed) callback(name, icon) } } }
    }

    override fun destroy() {
        destroyed = true
        engine.post {
            tab.destroy()
            engineThread.quit()
        }
    }

    /** A Looper thread with the same 32MB stack the page-load thread uses: layout and DOM events recurse deeply on real pages. */
    private class EngineThread : Thread(null, null, "tab-engine", 32L * 1024 * 1024) {
        private val ready = java.util.concurrent.CountDownLatch(1)
        @Volatile private var looper: android.os.Looper? = null

        init { isDaemon = true }

        override fun run() {
            android.os.Looper.prepare()
            looper = android.os.Looper.myLooper()
            ready.countDown()
            android.os.Looper.loop()
        }

        fun awaitLooper(): android.os.Looper {
            ready.await()
            return looper!!
        }

        fun quit() { looper?.quitSafely() }
    }
}
