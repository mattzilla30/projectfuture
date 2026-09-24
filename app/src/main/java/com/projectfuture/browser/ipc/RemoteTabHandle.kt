package com.projectfuture.browser.ipc

import android.content.Context
import com.projectfuture.browser.browser.DetectedLoginForm
import com.projectfuture.browser.browser.TabHandle
import com.projectfuture.browser.browser.TabSelectOption
import com.projectfuture.browser.browser.TabState
import com.projectfuture.browser.browser.credentialOrigin
import com.projectfuture.browser.html.ElementNode
import com.projectfuture.browser.net.Url

/**
 * [TabHandle] implementation backed by a sandboxed [TabEngineClient] -
 * the out-of-process default (see `TabManager.newTab`'s doc). Adapts
 * [TabEngineClient]'s Messenger-shaped, engine-specific API onto the
 * common interface `MainActivity` actually calls; [TabEngineClient]'s own
 * class doc has the full account of what's proxied, what deliberately
 * isn't, and why.
 */
class RemoteTabHandle(context: Context, tabIndex: Int, isPrivate: Boolean, onStateChanged: (TabState) -> Unit) : TabHandle {
    private val client = TabEngineClient(context, tabIndex, isPrivate)

    init {
        client.onStateChanged = onStateChanged
        client.bind()
    }

    override val isPrivate get() = client.isPrivate
    override val currentUrl get() = client.currentUrl
    override val displayList get() = client.displayList
    override val contentHeight get() = client.contentHeight
    override val isDiscarded get() = client.isDiscarded
    override val desktopMode get() = client.desktopMode
    override val readerModeActive get() = client.readerModeActive
    override val textScale get() = client.textScale

    override var onDownloadRequested: ((String, String?) -> Unit)?
        get() = client.onDownloadRequested
        set(value) { client.onDownloadRequested = value }
    override var onLoginFormSubmitted: ((String, String, String) -> Unit)?
        get() = client.onLoginFormSubmitted
        set(value) { client.onLoginFormSubmitted = value }

    override fun canGoBack() = client.canGoBack
    override fun canGoForward() = client.canGoForward
    override fun goBack() = client.goBack()
    override fun goForward() = client.goForward()
    override fun reload() = client.reload()
    override fun navigate(addressBarInput: String, searchTemplate: String) = client.loadUrl(addressBarInput, searchTemplate)
    override fun followLink(href: String) = client.followLink(href)
    override fun onViewportSizeChanged(widthPx: Float, heightPx: Float): Boolean {
        client.setViewportSize(widthPx, heightPx)
        // Unlike the in-process Tab, there's no synchronous way to know whether this actually
        // changed anything on the engine side - the display list update (if any) arrives later as
        // an ordinary MSG_DISPLAY_LIST/onDisplayListChanged callback. Callers that used the return
        // value only to decide whether to repaint immediately (MainActivity does, on rotation) just
        // get one extra no-op repaint attempt in the remote case - harmless, not a correctness gap.
        return true
    }
    override fun setTextScale(scale: Float) = client.setTextScale(scale)
    override fun toggleDesktopMode() = client.toggleDesktopMode()
    override fun toggleReaderMode() = client.toggleReaderMode()
    override fun discardForMemoryPressure() = client.discard()

    override fun dispatchClick(element: ElementNode) = client.dispatchTap(element)
    override fun dispatchInputEvent(element: ElementNode, newValue: String) = client.dispatchInput(element, newValue)
    override fun setFieldValue(element: ElementNode, value: String) = client.setFieldValue(element, value)
    override fun setSelectValue(select: ElementNode, chosenOption: ElementNode) = client.setSelectValue(select, chosenOption)
    override fun currentFieldValue(element: ElementNode, callback: (String) -> Unit) = client.requestFieldValue(element, callback)

    override fun requestSelectOptions(select: ElementNode, callback: (List<TabSelectOption>) -> Unit) {
        client.requestSelectOptions(select) { options -> callback(options.map { TabSelectOption(it.node, it.label, it.selected) }) }
    }

    /** Purely derived from the cached [currentUrl] - see [TabHandle.currentOrigin]'s doc for why this never needs to reach the engine process. */
    override fun currentOrigin(): String? = currentUrl?.credentialOrigin()

    override fun detectLoginForm(callback: (DetectedLoginForm?) -> Unit) {
        client.requestLoginForm { form -> callback(form?.let { DetectedLoginForm(it.usernameField, it.passwordField, it.usernamePrefilled) }) }
    }

    override fun autofillLoginForm(fields: DetectedLoginForm, username: String, password: String) {
        client.autofillLoginForm(RemoteLoginForm(fields.usernameField, fields.passwordField, fields.usernameAlreadyFilled), username, password)
    }

    override fun trustCertificateAndReload(url: Url) = client.trustCertificateAndReload(url)
    override fun fetchManifestInfo(callback: (String, ByteArray?) -> Unit) = client.requestManifestInfo(callback)

    override fun destroy() = client.unbind()
}
