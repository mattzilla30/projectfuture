package com.projectfuture.browser.layout

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * Converts a downloaded font file into raw SFNT bytes (TrueType/OpenType)
 * that `android.graphics.Typeface` can load. TTF/OTF pass through as-is.
 * WOFF (v1) is unwrapped: its per-table zlib compression uses
 * `java.util.zip.Inflater` - a standard JDK/Android compression utility,
 * the same tier as `BitmapFactory` for images, not "browser engine" logic.
 *
 * WOFF2 is NOT supported: it compresses the whole font with Brotli, which
 * has no equivalent in the standard library - implementing a Brotli
 * decoder from scratch would be a substantial project of its own, on the
 * order of a PNG or JPEG decoder. Since WOFF2 is the default format most
 * font CDNs (e.g. Google Fonts) serve today, a `@font-face` pointing only
 * at a `.woff2` file will simply fail to load and fall back to the
 * inherited/system font - this is a real, known gap, not a bug.
 *
 * This conversion is unverified beyond compiling: there's no emulator or
 * device available in this environment to visually confirm a WOFF file
 * actually renders correctly end to end. The implementation follows the
 * WOFF1 spec directly; Typeface construction is wrapped in a try/catch by
 * the caller, so a subtly wrong conversion fails closed (falls back to the
 * system font) rather than crashing.
 */
object FontDecoder {

    fun toSfnt(bytes: ByteArray): ByteArray? {
        if (bytes.size < 4) return null
        val tag = u32(bytes, 0)
        return when (tag) {
            0x774F4646L -> woffToSfnt(bytes) // 'wOFF'
            0x00010000L, 0x4F54544FL, 0x74727565L -> bytes // sfnt v1, 'OTTO', 'true' - already usable directly
            else -> null // includes 'wOF2' (WOFF2) and anything unrecognized
        }
    }

    private class TableEntry(val tag: Long, val offset: Int, val compLength: Int, val origLength: Int)

    private fun woffToSfnt(bytes: ByteArray): ByteArray? {
        if (bytes.size < 44) return null
        val flavor = u32(bytes, 4)
        val numTables = u16(bytes, 12)
        if (numTables <= 0) return null

        val entries = ArrayList<TableEntry>(numTables)
        var pos = 44
        for (i in 0 until numTables) {
            if (pos + 20 > bytes.size) return null
            entries.add(
                TableEntry(
                    tag = u32(bytes, pos),
                    offset = u32(bytes, pos + 4).toInt(),
                    compLength = u32(bytes, pos + 8).toInt(),
                    origLength = u32(bytes, pos + 12).toInt()
                )
            )
            pos += 20
        }

        val tableData = ArrayList<ByteArray>(numTables)
        for (e in entries) {
            if (e.offset < 0 || e.compLength < 0 || e.offset.toLong() + e.compLength > bytes.size) return null
            val data = if (e.compLength == e.origLength) {
                bytes.copyOfRange(e.offset, e.offset + e.compLength)
            } else {
                inflate(bytes, e.offset, e.compLength, e.origLength) ?: return null
            }
            tableData.add(data)
        }

        val entrySelector = 31 - Integer.numberOfLeadingZeros(numTables)
        val searchRange = (1 shl entrySelector) * 16
        val rangeShift = numTables * 16 - searchRange

        val out = ByteArrayOutputStream()
        writeU32(out, flavor)
        writeU16(out, numTables)
        writeU16(out, searchRange)
        writeU16(out, entrySelector)
        writeU16(out, rangeShift)

        var dataOffset = 12 + numTables * 16
        val directory = ByteArrayOutputStream()
        val dataSection = ByteArrayOutputStream()
        for ((i, e) in entries.withIndex()) {
            val data = tableData[i]
            writeU32(directory, e.tag)
            writeU32(directory, checksum(data))
            writeU32(directory, dataOffset.toLong())
            writeU32(directory, data.size.toLong())
            dataSection.write(data)
            val padding = (4 - (data.size % 4)) % 4
            repeat(padding) { dataSection.write(0) }
            dataOffset += data.size + padding
        }

        out.write(directory.toByteArray())
        out.write(dataSection.toByteArray())
        return out.toByteArray()
    }

    private fun inflate(src: ByteArray, offset: Int, compLength: Int, origLength: Int): ByteArray? {
        val inflater = Inflater()
        inflater.setInput(src, offset, compLength)
        val out = ByteArray(origLength)
        var written = 0
        try {
            while (written < origLength && !inflater.finished()) {
                val n = inflater.inflate(out, written, origLength - written)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                written += n
            }
        } catch (_: Exception) {
            return null
        } finally {
            inflater.end()
        }
        return if (written == origLength) out else null
    }

    /** Standard TrueType table checksum: sum of the table treated as big-endian uint32 words, zero-padded. */
    private fun checksum(data: ByteArray): Long {
        var sum = 0L
        var i = 0
        while (i < data.size) {
            val b0 = byteAt(data, i)
            val b1 = byteAt(data, i + 1)
            val b2 = byteAt(data, i + 2)
            val b3 = byteAt(data, i + 3)
            sum = (sum + ((b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3)) and 0xFFFFFFFFL
            i += 4
        }
        return sum
    }

    private fun byteAt(data: ByteArray, i: Int): Long = if (i < data.size) (data[i].toLong() and 0xFF) else 0L

    private fun u32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

    private fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun writeU32(out: ByteArrayOutputStream, v: Long) {
        out.write(((v shr 24) and 0xFF).toInt())
        out.write(((v shr 16) and 0xFF).toInt())
        out.write(((v shr 8) and 0xFF).toInt())
        out.write((v and 0xFF).toInt())
    }

    private fun writeU16(out: ByteArrayOutputStream, v: Int) {
        out.write((v shr 8) and 0xFF)
        out.write(v and 0xFF)
    }
}
