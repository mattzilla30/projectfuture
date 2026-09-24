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

/** Thin, synchronous pass-through onto a real in-process [Tab] - see [TabHandle]'s doc. */
class LocalTabHandle(context: android.content.Context, isPrivate: Boolean, onStateChanged: (TabState) -> Unit) : TabHandle {
    private val tab = Tab(context, onStateChanged, isPrivate)

    override val isPrivate get() = tab.isPrivate
    override val currentUrl get() = tab.currentUrl
    override val displayList get() = tab.displayList
    override val contentHeight get() = tab.contentHeight
    override val isDiscarded get() = tab.isDiscarded
    override val desktopMode get() = tab.desktopMode
    override val readerModeActive get() = tab.readerModeActive
    override val textScale get() = tab.textScale

    override var onDownloadRequested: ((String, String?) -> Unit)?
        get() = tab.onDownloadRequested
        set(value) { tab.onDownloadRequested = value }
    override var onLoginFormSubmitted: ((String, String, String) -> Unit)?
        get() = tab.onLoginFormSubmitted
        set(value) { tab.onLoginFormSubmitted = value }

    override fun canGoBack() = tab.canGoBack()
    override fun canGoForward() = tab.canGoForward()
    override fun goBack() = tab.goBack()
    override fun goForward() = tab.goForward()
    override fun reload() = tab.reload()
    override fun navigate(addressBarInput: String, searchTemplate: String) = tab.navigate(addressBarInput, searchTemplate)
    override fun followLink(href: String) = tab.followLink(href)
    override fun onViewportSizeChanged(widthPx: Float, heightPx: Float) = tab.onViewportSizeChanged(widthPx, heightPx)
    override fun setTextScale(scale: Float) = tab.setTextScale(scale)
    override fun toggleDesktopMode() = tab.toggleDesktopMode()
    override fun toggleReaderMode() = tab.toggleReaderMode()
    override fun discardForMemoryPressure() = tab.discardForMemoryPressure()

    override fun dispatchClick(element: ElementNode) = tab.dispatchClick(element)
    override fun dispatchInputEvent(element: ElementNode, newValue: String) = tab.dispatchInputEvent(element, newValue)
    override fun setFieldValue(element: ElementNode, value: String) = tab.setFieldValue(element, value)
    override fun setSelectValue(select: ElementNode, chosenOption: ElementNode) = tab.setSelectValue(select, chosenOption)
    override fun currentFieldValue(element: ElementNode, callback: (String) -> Unit) = callback(tab.currentFieldValue(element))

    override fun requestSelectOptions(select: ElementNode, callback: (List<TabSelectOption>) -> Unit) {
        val options = select.children.filterIsInstance<ElementNode>().filter { it.tag == "option" }
        callback(options.map { opt ->
            val label = opt.children.filterIsInstance<TextNode>().joinToString("") { it.text }.trim().ifEmpty { opt.attr("value") ?: "" }
            TabSelectOption(opt, label, opt.attr("selected") != null)
        })
    }

    override fun currentOrigin() = tab.currentOrigin()

    override fun detectLoginForm(callback: (DetectedLoginForm?) -> Unit) {
        val fields = tab.detectLoginForm()
        callback(fields?.let { DetectedLoginForm(it.usernameField, it.passwordField, it.usernameField.attr("value")?.isNotEmpty() == true) })
    }

    override fun autofillLoginForm(fields: DetectedLoginForm, username: String, password: String) {
        tab.setFieldValue(fields.usernameField, username)
        tab.setFieldValue(fields.passwordField, password)
    }

    override fun trustCertificateAndReload(url: Url) = tab.trustCertificateAndReload(url)
    override fun fetchManifestInfo(callback: (String, ByteArray?) -> Unit) = tab.fetchManifestInfo(callback)
    override fun destroy() = tab.destroy()
}
