package com.projectfuture.browser.ipc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.projectfuture.browser.html.ElementNode
import com.projectfuture.browser.layout.DisplayCommand
import com.projectfuture.browser.layout.DrawFormControl
import com.projectfuture.browser.layout.DrawImage
import com.projectfuture.browser.layout.DrawRect
import com.projectfuture.browser.layout.DrawText
import com.projectfuture.browser.layout.FormControlType
import com.projectfuture.browser.layout.TextStyle
import java.util.IdentityHashMap

/**
 * UI-process-only mirror of [EngineToUiConverter]: turns received
 * [WireDisplayCommand]s and image bytes back into real `DisplayCommand`
 * objects so [com.projectfuture.browser.view.BrowserView] can paint a
 * sandboxed tab exactly like an in-process one, completely unmodified.
 *
 * No real `ElementNode` ever crosses the process boundary (see
 * `WireDisplayCommand`'s doc), so a "shadow" node - inert, empty tag/
 * attributes, existing only as an identity token - is created once per
 * elementId and reused across relayouts (stable identity matters:
 * BrowserView keys its overlay EditText views and find-in-page match
 * highlighting off `DisplayCommand.sourceElement` object identity). A tap
 * or text edit on a shadow element is translated back to its elementId via
 * [elementIdFor] and shipped to the engine process by [TabEngineClient] -
 * this class never runs any DOM/JS logic itself.
 */
class UiDisplayListConverter {
    private val shadowElements = HashMap<Int, ElementNode>()
    private val idsByShadowElement = IdentityHashMap<ElementNode, Int>()
    private val images = HashMap<Int, Bitmap>()
    /** tag/inputType for each elementId, learned from MSG_ELEMENT_META - see that message's doc. Looked up lazily when a shadow is first built for an id, so ordering only matters relative to that (guaranteed: the engine always sends an id's meta before/alongside the first display list or reply referencing it). */
    private val metaByElementId = HashMap<Int, Pair<String, String?>>()

    /**
     * Clears every per-document cache - the UI-process twin of
     * [EngineToUiConverter.reset], called by [TabEngineClient] when it
     * learns a fresh document has loaded ([TabEngineProtocol.MSG_STATE_LOADED]),
     * before that document's [TabEngineProtocol.MSG_ELEMENT_META]/
     * [TabEngineProtocol.MSG_DISPLAY_LIST] are processed (the engine side
     * sends the state message first for exactly this reason - see
     * [TabEngineServiceBase.onTabStateChanged]'s `TabState.Loaded` branch).
     * Without this, ids recycled for the new document (elementId
     * numbering restarts at 1 per document - see [ElementIdRegistry])
     * would resolve through [shadowFor] to whatever shadow element/tag/
     * inputType a *previous* document's same id had, silently
     * misattributing tap-routing metadata across navigations rather than
     * just leaking memory.
     */
    fun reset() {
        shadowElements.clear()
        idsByShadowElement.clear()
        images.clear()
        metaByElementId.clear()
    }

    fun onImageReceived(imageId: Int, pngBytes: ByteArray) {
        images[imageId] = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
    }

    fun onElementMetaReceived(entries: List<WireElementMeta>) {
        for (entry in entries) metaByElementId[entry.elementId] = entry.tag to entry.inputType
    }

    /** The elementId a shadow ElementNode (from a BrowserView callback) stands in for, or null if it's not one of ours. */
    fun elementIdFor(shadow: ElementNode): Int? = idsByShadowElement[shadow]

    /** Builds (or returns the existing) shadow ElementNode for an id learned outside a display-list conversion - e.g. a `<select>`'s options ([TabEngineProtocol.MSG_SELECT_OPTIONS]) or a detected login form's fields ([TabEngineProtocol.MSG_LOGIN_FORM_RESULT]). Public (unlike the private [shadowFor] below) since those callers aren't converting a WireDisplayCommand. */
    fun shadowElementFor(elementId: Int): ElementNode = shadowFor(elementId)!!

    fun convert(wireCommands: List<WireDisplayCommand>): List<DisplayCommand> =
        wireCommands.mapNotNull { cmd ->
            when (cmd) {
                is WireDrawText -> DrawText(
                    cmd.x, cmd.baselineY, cmd.text, textStyle(cmd.style),
                    cmd.left, cmd.right, cmd.boxTop, cmd.boxBottom, cmd.fixed, shadowFor(cmd.elementId)
                )
                is WireDrawRect -> DrawRect(cmd.left, cmd.top, cmd.right, cmd.bottom, cmd.colorArgb, cmd.fixed, shadowFor(cmd.elementId))
                is WireDrawImage -> images[cmd.imageId]?.let { bitmap ->
                    DrawImage(cmd.left, cmd.top, cmd.right, cmd.bottom, bitmap, cmd.fixed, shadowFor(cmd.elementId))
                }
                is WireDrawFormControl -> DrawFormControl(
                    cmd.left, cmd.top, cmd.right, cmd.bottom, FormControlType.values()[cmd.controlType],
                    cmd.value, cmd.checked, cmd.placeholder, textStyle(cmd.style), cmd.fixed, shadowFor(cmd.elementId)
                )
            }
        }

    private fun shadowFor(elementId: Int): ElementNode? {
        if (elementId == 0) return null
        return shadowElements.getOrPut(elementId) {
            val (tag, inputType) = metaByElementId[elementId] ?: ("" to null)
            ElementNode(tag = tag).also {
                if (inputType != null) it.attributes["type"] = inputType
                idsByShadowElement[it] = elementId
            }
        }
    }

    private fun textStyle(style: WireTextStyle) = TextStyle(
        style.sizePx, style.bold, style.italic, style.monospace, style.colorArgb,
        style.underline, style.strikethrough, style.linkHref, style.fontFamilyName
    )
}
