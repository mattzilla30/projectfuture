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
import com.projectfuture.browser.browser.Tab
import com.projectfuture.browser.browser.TabState
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
 * reference-free [WireDisplayCommand] list ([EngineToUiConverter]).
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
 */
abstract class TabEngineServiceBase : Service() {
    private var tab: Tab? = null
    private var uiMessenger: Messenger? = null
    private val elementIds = ElementIdRegistry()
    private val converter = EngineToUiConverter(elementIds)

    private val incomingHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                TabEngineProtocol.MSG_REGISTER_CLIENT -> uiMessenger = msg.replyTo
                TabEngineProtocol.MSG_LOAD_URL -> msg.data.getString(TabEngineProtocol.KEY_URL)?.let(::loadUrl)
                TabEngineProtocol.MSG_DISPATCH_TAP -> {
                    val elementId = msg.data.getInt(TabEngineProtocol.KEY_ELEMENT_ID)
                    elementIds.elementFor(elementId)?.let { tab?.dispatchClick(it) }
                }
                TabEngineProtocol.MSG_DISPATCH_INPUT -> {
                    val elementId = msg.data.getInt(TabEngineProtocol.KEY_ELEMENT_ID)
                    val value = msg.data.getString(TabEngineProtocol.KEY_VALUE) ?: ""
                    elementIds.elementFor(elementId)?.let { tab?.dispatchInputEvent(it, value) }
                }
                TabEngineProtocol.MSG_SET_VIEWPORT_SIZE -> {
                    val width = msg.data.getFloat(TabEngineProtocol.KEY_WIDTH)
                    val height = msg.data.getFloat(TabEngineProtocol.KEY_HEIGHT)
                    if (tab?.onViewportSizeChanged(width, height) == true) sendDisplayList()
                }
            }
        }
    }
    private val incomingMessenger = Messenger(incomingHandler)

    override fun onBind(intent: Intent?): IBinder = incomingMessenger.binder

    private fun ensureTab(): Tab = tab ?: Tab(this, ::onTabStateChanged).also { tab = it }

    private fun loadUrl(addressBarInput: String) {
        elementIds.reset()
        ensureTab().navigate(addressBarInput)
    }

    private fun onTabStateChanged(state: TabState) {
        val reply = uiMessenger ?: return
        val message = when (state) {
            is TabState.Loading -> stateMessage(TabEngineProtocol.MSG_STATE_LOADING, state.url.toString())
            is TabState.Loaded -> {
                sendDisplayList()
                stateMessage(TabEngineProtocol.MSG_STATE_LOADED, state.url.toString(), state.title)
            }
            is TabState.Updated -> {
                sendDisplayList()
                null
            }
            is TabState.Error -> stateMessage(TabEngineProtocol.MSG_STATE_ERROR, state.url.toString(), state.message)
            is TabState.CertificateError -> stateMessage(TabEngineProtocol.MSG_STATE_ERROR, state.url.toString(), state.message)
        } ?: return
        try { reply.send(message) } catch (_: Exception) {}
    }

    private fun stateMessage(what: Int, url: String, extra: String? = null): Message =
        Message.obtain(null, what).apply {
            data = Bundle().apply {
                putString(TabEngineProtocol.KEY_URL, url)
                if (what == TabEngineProtocol.MSG_STATE_LOADED) putString(TabEngineProtocol.KEY_TITLE, extra)
                if (what == TabEngineProtocol.MSG_STATE_ERROR) putString(TabEngineProtocol.KEY_MESSAGE, extra)
            }
        }

    private fun sendDisplayList() {
        val reply = uiMessenger ?: return
        val currentTab = tab ?: return
        val (wireCommands, newImages) = converter.convert(currentTab.displayList)
        for ((id, bitmap) in newImages) sendImage(reply, id, bitmap)
        val message = Message.obtain(null, TabEngineProtocol.MSG_DISPLAY_LIST).apply {
            data = Bundle().apply {
                putByteArray(TabEngineProtocol.KEY_PAYLOAD, DisplayListCodec.encode(wireCommands))
                putFloat(TabEngineProtocol.KEY_CONTENT_HEIGHT, currentTab.contentHeight)
            }
        }
        try { reply.send(message) } catch (_: Exception) {}
    }

    private fun sendImage(reply: Messenger, imageId: Int, bitmap: Bitmap) {
        val bytes = ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
        val message = Message.obtain(null, TabEngineProtocol.MSG_IMAGE_DATA).apply {
            data = Bundle().apply {
                putInt(TabEngineProtocol.KEY_IMAGE_ID, imageId)
                putByteArray(TabEngineProtocol.KEY_IMAGE_BYTES, bytes)
            }
        }
        try { reply.send(message) } catch (_: Exception) {}
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
