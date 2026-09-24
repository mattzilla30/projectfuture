package com.projectfuture.browser.ipc

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Encodes/decodes a [WireDisplayCommand] list to/from a flat `ByteArray`
 * using plain `java.io` streams - the actual bytes that cross the
 * engine-process/UI-process boundary as a Messenger message's payload (see
 * `TabEngineProtocol`). Kept independent of `android.os.Parcel` on purpose:
 * Parcel needs a real Android runtime to marshal/unmarshal (it's backed by
 * native code), so a codec built on it can't be exercised by a plain JVM
 * unit test in this project (no Robolectric dependency here) - this
 * hand-rolled format can be, and [DisplayListCodecTest] is a real
 * encode-then-decode round-trip over it, not a mock.
 *
 * Format: a 4-byte command count, then each command as a 1-byte type tag
 * followed by its fields in a fixed order (see [encodeCommand]/
 * [decodeCommand]).
 */
object DisplayListCodec {

    private const val TAG_TEXT = 1
    private const val TAG_RECT = 2
    private const val TAG_IMAGE = 3
    private const val TAG_FORM_CONTROL = 4

    fun encode(commands: List<WireDisplayCommand>): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.writeInt(commands.size)
        for (cmd in commands) encodeCommand(out, cmd)
        out.flush()
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): List<WireDisplayCommand> {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val count = input.readInt()
        val result = ArrayList<WireDisplayCommand>(count)
        repeat(count) { result.add(decodeCommand(input)) }
        return result
    }

    private fun encodeCommand(out: DataOutputStream, cmd: WireDisplayCommand) {
        when (cmd) {
            is WireDrawText -> {
                out.writeByte(TAG_TEXT)
                out.writeFloat(cmd.x)
                out.writeFloat(cmd.baselineY)
                out.writeUTF(cmd.text)
                writeStyle(out, cmd.style)
                out.writeFloat(cmd.left)
                out.writeFloat(cmd.right)
                out.writeFloat(cmd.boxTop)
                out.writeFloat(cmd.boxBottom)
                out.writeBoolean(cmd.fixed)
                out.writeInt(cmd.elementId)
            }
            is WireDrawRect -> {
                out.writeByte(TAG_RECT)
                out.writeFloat(cmd.left)
                out.writeFloat(cmd.top)
                out.writeFloat(cmd.right)
                out.writeFloat(cmd.bottom)
                out.writeInt(cmd.colorArgb)
                out.writeBoolean(cmd.fixed)
                out.writeInt(cmd.elementId)
            }
            is WireDrawImage -> {
                out.writeByte(TAG_IMAGE)
                out.writeFloat(cmd.left)
                out.writeFloat(cmd.top)
                out.writeFloat(cmd.right)
                out.writeFloat(cmd.bottom)
                out.writeInt(cmd.imageId)
                out.writeBoolean(cmd.fixed)
                out.writeInt(cmd.elementId)
            }
            is WireDrawFormControl -> {
                out.writeByte(TAG_FORM_CONTROL)
                out.writeFloat(cmd.left)
                out.writeFloat(cmd.top)
                out.writeFloat(cmd.right)
                out.writeFloat(cmd.bottom)
                out.writeInt(cmd.controlType)
                out.writeUTF(cmd.value)
                out.writeBoolean(cmd.checked)
                out.writeUTF(cmd.placeholder)
                writeStyle(out, cmd.style)
                out.writeBoolean(cmd.fixed)
                out.writeInt(cmd.elementId)
            }
        }
    }

    private fun decodeCommand(input: DataInputStream): WireDisplayCommand {
        return when (val tag = input.readByte().toInt()) {
            TAG_TEXT -> {
                val x = input.readFloat()
                val baselineY = input.readFloat()
                val text = input.readUTF()
                val style = readStyle(input)
                val left = input.readFloat()
                val right = input.readFloat()
                val boxTop = input.readFloat()
                val boxBottom = input.readFloat()
                val fixed = input.readBoolean()
                val elementId = input.readInt()
                WireDrawText(x, baselineY, text, style, left, right, boxTop, boxBottom, fixed, elementId)
            }
            TAG_RECT -> {
                val left = input.readFloat()
                val top = input.readFloat()
                val right = input.readFloat()
                val bottom = input.readFloat()
                val color = input.readInt()
                val fixed = input.readBoolean()
                val elementId = input.readInt()
                WireDrawRect(left, top, right, bottom, color, fixed, elementId)
            }
            TAG_IMAGE -> {
                val left = input.readFloat()
                val top = input.readFloat()
                val right = input.readFloat()
                val bottom = input.readFloat()
                val imageId = input.readInt()
                val fixed = input.readBoolean()
                val elementId = input.readInt()
                WireDrawImage(left, top, right, bottom, imageId, fixed, elementId)
            }
            TAG_FORM_CONTROL -> {
                val left = input.readFloat()
                val top = input.readFloat()
                val right = input.readFloat()
                val bottom = input.readFloat()
                val controlType = input.readInt()
                val value = input.readUTF()
                val checked = input.readBoolean()
                val placeholder = input.readUTF()
                val style = readStyle(input)
                val fixed = input.readBoolean()
                val elementId = input.readInt()
                WireDrawFormControl(left, top, right, bottom, controlType, value, checked, placeholder, style, fixed, elementId)
            }
            else -> error("Unknown WireDisplayCommand tag $tag - encoder/decoder are out of sync")
        }
    }

    private fun writeStyle(out: DataOutputStream, style: WireTextStyle) {
        out.writeFloat(style.sizePx)
        out.writeBoolean(style.bold)
        out.writeBoolean(style.italic)
        out.writeBoolean(style.monospace)
        out.writeInt(style.colorArgb)
        out.writeBoolean(style.underline)
        out.writeBoolean(style.strikethrough)
        writeNullableUtf(out, style.linkHref)
        writeNullableUtf(out, style.fontFamilyName)
    }

    private fun readStyle(input: DataInputStream): WireTextStyle {
        val sizePx = input.readFloat()
        val bold = input.readBoolean()
        val italic = input.readBoolean()
        val monospace = input.readBoolean()
        val color = input.readInt()
        val underline = input.readBoolean()
        val strikethrough = input.readBoolean()
        val linkHref = readNullableUtf(input)
        val fontFamilyName = readNullableUtf(input)
        return WireTextStyle(sizePx, bold, italic, monospace, color, underline, strikethrough, linkHref, fontFamilyName)
    }

    private fun writeNullableUtf(out: DataOutputStream, value: String?) {
        out.writeBoolean(value != null)
        if (value != null) out.writeUTF(value)
    }

    private fun readNullableUtf(input: DataInputStream): String? = if (input.readBoolean()) input.readUTF() else null
}
