package com.projectfuture.browser.ipc

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Wire twin of enough of an `ElementNode` (see [WireDisplayCommand]'s doc
 * for why no real one ever crosses the process boundary) for the UI
 * process's own tap-routing logic to work unmodified: MainActivity's
 * `handleElementTap`/`promptForFieldValue` switch on `element.tag` and
 * `element.attr("type")` directly. [inputType] is null when the element
 * has no `type` attribute at all (a bare `<input>` defaults to a text
 * field, same as the real DOM).
 */
data class WireElementMeta(val elementId: Int, val tag: String, val inputType: String?)

/** Wire twin of one `<option>` inside a `<select>` - see [TabEngineProtocol.MSG_SELECT_OPTIONS]. */
data class WireSelectOption(val elementId: Int, val label: String, val selected: Boolean)

/**
 * Everything about a tab's own navigation/mode state that the UI process
 * reads synchronously in many places (menu checkmarks, back/forward button
 * enablement) but can't ask the engine process for on demand - a Messenger
 * call has no synchronous return. The engine instead pushes one of these
 * after every state change or toggle, and [TabEngineClient] caches the
 * latest one - see [TabEngineProtocol.MSG_TAB_INFO]'s doc.
 */
data class WireTabInfo(
    val url: String?,
    val title: String?,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    val desktopMode: Boolean,
    val readerModeActive: Boolean,
    val textScale: Float,
    val isDiscarded: Boolean
)

/** Same hand-rolled `java.io`-stream approach as [DisplayListCodec] - see its class doc for why (JVM-testable, no Robolectric/Android runtime needed). */
object ElementMetaCodec {
    fun encode(entries: List<WireElementMeta>): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.writeInt(entries.size)
        for (entry in entries) {
            out.writeInt(entry.elementId)
            out.writeUTF(entry.tag)
            writeNullableUtf(out, entry.inputType)
        }
        out.flush()
        return bytes.toByteArray()
    }

    fun decode(data: ByteArray): List<WireElementMeta> {
        val input = DataInputStream(ByteArrayInputStream(data))
        val count = input.readInt()
        return (0 until count).map {
            WireElementMeta(input.readInt(), input.readUTF(), readNullableUtf(input))
        }
    }
}

object SelectOptionsCodec {
    fun encode(options: List<WireSelectOption>): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.writeInt(options.size)
        for (option in options) {
            out.writeInt(option.elementId)
            out.writeUTF(option.label)
            out.writeBoolean(option.selected)
        }
        out.flush()
        return bytes.toByteArray()
    }

    fun decode(data: ByteArray): List<WireSelectOption> {
        val input = DataInputStream(ByteArrayInputStream(data))
        val count = input.readInt()
        return (0 until count).map {
            WireSelectOption(input.readInt(), input.readUTF(), input.readBoolean())
        }
    }
}

object TabInfoCodec {
    fun encode(info: WireTabInfo): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        writeNullableUtf(out, info.url)
        writeNullableUtf(out, info.title)
        out.writeBoolean(info.canGoBack)
        out.writeBoolean(info.canGoForward)
        out.writeBoolean(info.desktopMode)
        out.writeBoolean(info.readerModeActive)
        out.writeFloat(info.textScale)
        out.writeBoolean(info.isDiscarded)
        out.flush()
        return bytes.toByteArray()
    }

    fun decode(data: ByteArray): WireTabInfo {
        val input = DataInputStream(ByteArrayInputStream(data))
        val url = readNullableUtf(input)
        val title = readNullableUtf(input)
        val canGoBack = input.readBoolean()
        val canGoForward = input.readBoolean()
        val desktopMode = input.readBoolean()
        val readerModeActive = input.readBoolean()
        val textScale = input.readFloat()
        val isDiscarded = input.readBoolean()
        return WireTabInfo(url, title, canGoBack, canGoForward, desktopMode, readerModeActive, textScale, isDiscarded)
    }
}

private fun writeNullableUtf(out: DataOutputStream, value: String?) {
    out.writeBoolean(value != null)
    if (value != null) out.writeUTF(value)
}

private fun readNullableUtf(input: DataInputStream): String? = if (input.readBoolean()) input.readUTF() else null
