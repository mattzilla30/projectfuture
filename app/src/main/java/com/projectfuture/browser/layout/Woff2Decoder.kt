package com.projectfuture.browser.layout

import com.projectfuture.browser.net.BrotliDecoder
import java.io.ByteArrayOutputStream

/**
 * Converts a WOFF2 file (W3C WOFF2 spec) into sfnt bytes.
 *
 * WOFF2 stores every table in one Brotli stream. Three tables may also arrive transformed:
 * - glyf: split into separate streams (contour counts, point counts, flags, triplet-coded
 *   coordinates, composites, bounding boxes, instructions). We rebuild each glyph record.
 * - loca: sent empty. We regenerate it from the rebuilt glyph offsets.
 * - hmtx: left side bearings may be omitted. We restore them from each glyph's xMin.
 *
 * Font collections (flavor 'ttcf') return null, and the caller falls back to the system font.
 * Malformed input also returns null.
 */
object Woff2Decoder {
    private const val TAG_GLYF = 0x676C7966L
    private const val TAG_LOCA = 0x6C6F6361L
    private const val TAG_HMTX = 0x686D7478L
    private const val TAG_HHEA = 0x68686561L
    private const val TAG_HEAD = 0x68656164L
    private const val FLAVOR_TTC = 0x74746366L

    /** The spec's known-tag table: a directory entry's low 6 bits index into this list, and 63 means an explicit tag follows. */
    private val KNOWN_TAGS = listOf(
        "cmap", "head", "hhea", "hmtx", "maxp", "name", "OS/2", "post", "cvt ", "fpgm", "glyf", "loca", "prep",
        "CFF ", "VORG", "EBDT", "EBLC", "gasp", "hdmx", "kern", "LTSH", "PCLT", "VDMX", "vhea", "vmtx", "BASE",
        "GDEF", "GPOS", "GSUB", "EBSC", "JSTF", "MATH", "CBDT", "CBLC", "COLR", "CPAL", "SVG ", "sbix", "acnt",
        "avar", "bdat", "bloc", "bsln", "cvar", "fdsc", "feat", "fmtx", "fvar", "gvar", "hsty", "just", "lcar",
        "mort", "morx", "opbd", "prop", "trak", "Zapf", "Silf", "Glat", "Gloc", "Feat", "Sill"
    ).map { t -> t.fold(0L) { acc, c -> (acc shl 8) or c.code.toLong() } }

    private class Entry(val tag: Long, val origLength: Int, val transformed: Boolean, val streamLength: Int, var offset: Int = 0)

    class Woff2Exception(message: String) : Exception(message)

    fun decode(bytes: ByteArray): ByteArray? = try {
        decodeOrThrow(bytes)
    } catch (_: Exception) {
        // Woff2Exception, a corrupt Brotli stream, or an out-of-range read in a malformed table.
        null
    }

    fun decodeOrThrow(bytes: ByteArray): ByteArray {
        if (bytes.size < 48) throw Woff2Exception("header too short")
        val flavor = FontDecoder.u32(bytes, 4)
        if (flavor == FLAVOR_TTC) throw Woff2Exception("font collections unsupported")
        val numTables = FontDecoder.u16(bytes, 12)
        val totalCompressedSize = FontDecoder.u32(bytes, 20).toInt()
        if (numTables == 0) throw Woff2Exception("no tables")

        val dir = Reader(bytes, 48)
        val entries = ArrayList<Entry>(numTables)
        repeat(numTables) {
            val flags = dir.u8()
            val tagIndex = flags and 0x3F
            val tag = if (tagIndex == 63) dir.u32() else KNOWN_TAGS[tagIndex]
            val version = (flags shr 6) and 3
            val origLength = dir.base128()
            // glyf/loca use version 3 for "no transform"; every other table uses version 0.
            val transformed = if (tag == TAG_GLYF || tag == TAG_LOCA) version != 3 else version != 0
            val streamLength = if (transformed) dir.base128() else origLength
            entries.add(Entry(tag, origLength, transformed, streamLength))
        }
        if (dir.pos + totalCompressedSize > bytes.size) throw Woff2Exception("truncated table data")
        val stream = BrotliDecoder.decode(bytes.copyOfRange(dir.pos, dir.pos + totalCompressedSize))
        var offset = 0
        for (e in entries) {
            e.offset = offset
            offset += e.streamLength
        }
        if (offset > stream.size) throw Woff2Exception("table stream too short")

        fun raw(e: Entry) = stream.copyOfRange(e.offset, e.offset + e.streamLength)
        val tables = LinkedHashMap<Long, ByteArray>()
        val glyfEntry = entries.firstOrNull { it.tag == TAG_GLYF }
        var glyphs: GlyfResult? = null
        if (glyfEntry != null && glyfEntry.transformed) {
            glyphs = reconstructGlyf(stream, glyfEntry.offset, glyfEntry.streamLength)
            tables[TAG_GLYF] = glyphs.glyf
            tables[TAG_LOCA] = glyphs.loca
        }
        for (e in entries) {
            if (tables.containsKey(e.tag)) continue
            when {
                !e.transformed -> tables[e.tag] = raw(e)
                e.tag == TAG_HMTX -> {
                    val g = glyphs ?: throw Woff2Exception("hmtx transform without glyf")
                    val hhea = entries.firstOrNull { it.tag == TAG_HHEA } ?: throw Woff2Exception("hmtx transform without hhea")
                    val numHMetrics = FontDecoder.u16(stream, hhea.offset + 34)
                    tables[e.tag] = reconstructHmtx(stream, e.offset, g.xMins, numHMetrics)
                }
                else -> throw Woff2Exception("unknown transform")
            }
        }
        tables[TAG_HEAD]?.let { head ->
            if (head.size >= 12) for (i in 8..11) head[i] = 0
        }
        return FontDecoder.assembleSfnt(flavor, tables.entries.map { it.key to it.value })
    }

    private class GlyfResult(val glyf: ByteArray, val loca: ByteArray, val xMins: ShortArray)

    private fun reconstructGlyf(data: ByteArray, start: Int, length: Int): GlyfResult {
        val header = Reader(data, start)
        header.u16() // reserved
        val optionFlags = header.u16()
        val numGlyphs = header.u16()
        val indexFormat = header.u16()
        val sizes = IntArray(7) { header.u32().toInt() }
        var pos = header.pos
        val streams = sizes.map { size ->
            if (size < 0 || pos + size > start + length) throw Woff2Exception("glyf substream overflow")
            Reader(data, pos, pos + size).also { pos += size }
        }
        val nContourStream = streams[0]
        val nPointsStream = streams[1]
        val flagStream = streams[2]
        val glyphStream = streams[3]
        val compositeStream = streams[4]
        val bboxStream = streams[5]
        val instructionStream = streams[6]
        val bboxBitmapLength = 4 * ((numGlyphs + 31) / 32)
        val bboxBitmap = bboxStream.bytes(bboxBitmapLength)
        val overlapBitmap = if (optionFlags and 1 != 0) Reader(data, pos).bytes((numGlyphs + 7) / 8) else null

        val glyf = ByteArrayOutputStream(length * 2)
        val offsets = IntArray(numGlyphs + 1)
        val xMins = ShortArray(numGlyphs)
        for (i in 0 until numGlyphs) {
            offsets[i] = glyf.size()
            val hasBbox = bboxBitmap[i shr 3].toInt() and (0x80 shr (i and 7)) != 0
            val nContours = nContourStream.s16()
            val record = when {
                nContours == 0 -> {
                    if (hasBbox) throw Woff2Exception("empty glyph with bbox")
                    null
                }
                nContours < 0 -> {
                    if (!hasBbox) throw Woff2Exception("composite glyph without bbox")
                    compositeGlyph(bboxStream, compositeStream, glyphStream, instructionStream)
                }
                else -> {
                    val overlap = overlapBitmap != null && overlapBitmap[i shr 3].toInt() and (0x80 shr (i and 7)) != 0
                    simpleGlyph(nContours, hasBbox, overlap, nPointsStream, flagStream, glyphStream, bboxStream, instructionStream)
                }
            }
            if (record != null) {
                xMins[i] = (((record[2].toInt() and 0xFF) shl 8) or (record[3].toInt() and 0xFF)).toShort()
                glyf.write(record)
                repeat((4 - record.size % 4) % 4) { glyf.write(0) }
            }
        }
        offsets[numGlyphs] = glyf.size()

        val loca = ByteArrayOutputStream()
        for (o in offsets) {
            if (indexFormat == 0) writeU16(loca, o / 2) else writeU32(loca, o.toLong())
        }
        return GlyfResult(glyf.toByteArray(), loca.toByteArray(), xMins)
    }

    private fun compositeGlyph(bbox: Reader, composite: Reader, glyphStream: Reader, instructions: Reader): ByteArray {
        val out = ByteArrayOutputStream()
        writeU16(out, 0xFFFF) // numberOfContours = -1
        out.write(bbox.bytes(8))
        var haveInstructions = false
        do {
            val flags = composite.u16()
            haveInstructions = haveInstructions || flags and 0x0100 != 0
            var argBytes = if (flags and 0x0001 != 0) 4 else 2
            argBytes += when {
                flags and 0x0008 != 0 -> 2
                flags and 0x0040 != 0 -> 4
                flags and 0x0080 != 0 -> 8
                else -> 0
            }
            writeU16(out, flags)
            writeU16(out, composite.u16()) // glyph index
            out.write(composite.bytes(argBytes))
        } while (flags and 0x0020 != 0)
        if (haveInstructions) {
            val n = glyphStream.u255()
            writeU16(out, n)
            out.write(instructions.bytes(n))
        }
        return out.toByteArray()
    }

    private fun simpleGlyph(
        nContours: Int, hasBbox: Boolean, overlap: Boolean,
        nPointsStream: Reader, flagStream: Reader, glyphStream: Reader, bboxStream: Reader, instructionStream: Reader
    ): ByteArray {
        val endPoints = IntArray(nContours)
        var total = 0
        for (c in 0 until nContours) {
            total += nPointsStream.u255()
            endPoints[c] = total - 1
        }
        val dxs = IntArray(total)
        val dys = IntArray(total)
        val onCurve = BooleanArray(total)
        var x = 0
        var y = 0
        var xMin = Int.MAX_VALUE; var yMin = Int.MAX_VALUE; var xMax = Int.MIN_VALUE; var yMax = Int.MIN_VALUE
        for (p in 0 until total) {
            val raw = flagStream.u8()
            onCurve[p] = raw and 0x80 == 0
            val flag = raw and 0x7F
            val dx: Int
            val dy: Int
            when {
                flag < 10 -> {
                    dx = 0
                    dy = withSign(flag, ((flag and 14) shl 7) + glyphStream.u8())
                }
                flag < 20 -> {
                    dx = withSign(flag, (((flag - 10) and 14) shl 7) + glyphStream.u8())
                    dy = 0
                }
                flag < 84 -> {
                    val b0 = flag - 20
                    val b1 = glyphStream.u8()
                    dx = withSign(flag, 1 + (b0 and 0x30) + (b1 shr 4))
                    dy = withSign(flag shr 1, 1 + ((b0 and 0x0C) shl 2) + (b1 and 0x0F))
                }
                flag < 120 -> {
                    val b0 = flag - 84
                    dx = withSign(flag, 1 + ((b0 / 12) shl 8) + glyphStream.u8())
                    dy = withSign(flag shr 1, 1 + (((b0 % 12) shr 2) shl 8) + glyphStream.u8())
                }
                flag < 124 -> {
                    val b0 = glyphStream.u8()
                    val b1 = glyphStream.u8()
                    val b2 = glyphStream.u8()
                    dx = withSign(flag, (b0 shl 4) + (b1 shr 4))
                    dy = withSign(flag shr 1, ((b1 and 0x0F) shl 8) + b2)
                }
                else -> {
                    val b0 = glyphStream.u8()
                    val b1 = glyphStream.u8()
                    val b2 = glyphStream.u8()
                    val b3 = glyphStream.u8()
                    dx = withSign(flag, (b0 shl 8) + b1)
                    dy = withSign(flag shr 1, (b2 shl 8) + b3)
                }
            }
            dxs[p] = dx
            dys[p] = dy
            x += dx
            y += dy
            xMin = minOf(xMin, x); xMax = maxOf(xMax, x); yMin = minOf(yMin, y); yMax = maxOf(yMax, y)
        }
        val instructionLength = glyphStream.u255()

        val out = ByteArrayOutputStream()
        writeU16(out, nContours)
        if (hasBbox) {
            out.write(bboxStream.bytes(8))
        } else {
            if (total == 0) { xMin = 0; yMin = 0; xMax = 0; yMax = 0 }
            writeU16(out, xMin); writeU16(out, yMin); writeU16(out, xMax); writeU16(out, yMax)
        }
        for (e in endPoints) writeU16(out, e)
        writeU16(out, instructionLength)
        out.write(instructionStream.bytes(instructionLength))

        // Flags: short (1-byte) deltas where they fit, and the "same" bit for zero deltas. No repeat runs.
        val xs = ByteArrayOutputStream()
        val ys = ByteArrayOutputStream()
        for (p in 0 until total) {
            var f = if (onCurve[p]) 0x01 else 0
            if (p == 0 && overlap) f = f or 0x40
            val dx = dxs[p]
            when {
                dx == 0 -> f = f or 0x10
                dx in -255..255 -> { f = f or 0x02; if (dx > 0) f = f or 0x10; xs.write(Math.abs(dx)) }
                else -> writeU16(xs, dx)
            }
            val dy = dys[p]
            when {
                dy == 0 -> f = f or 0x20
                dy in -255..255 -> { f = f or 0x04; if (dy > 0) f = f or 0x20; ys.write(Math.abs(dy)) }
                else -> writeU16(ys, dy)
            }
            out.write(f)
        }
        xs.writeTo(out)
        ys.writeTo(out)
        return out.toByteArray()
    }

    private fun reconstructHmtx(data: ByteArray, start: Int, xMins: ShortArray, numHMetrics: Int): ByteArray {
        val numGlyphs = xMins.size
        if (numHMetrics < 1 || numHMetrics > numGlyphs) throw Woff2Exception("bad numberOfHMetrics")
        val r = Reader(data, start)
        val flags = r.u8()
        val advances = IntArray(numHMetrics) { r.u16() }
        val lsbs = IntArray(numGlyphs)
        for (i in 0 until numHMetrics) lsbs[i] = if (flags and 1 != 0) xMins[i].toInt() else r.u16()
        for (i in numHMetrics until numGlyphs) lsbs[i] = if (flags and 2 != 0) xMins[i].toInt() else r.u16()
        val out = ByteArrayOutputStream()
        for (i in 0 until numGlyphs) {
            if (i < numHMetrics) writeU16(out, advances[i])
            writeU16(out, lsbs[i])
        }
        return out.toByteArray()
    }

    private fun withSign(flag: Int, base: Int) = if (flag and 1 != 0) base else -base

    private class Reader(val data: ByteArray, var pos: Int, val end: Int = data.size) {
        fun u8(): Int {
            if (pos >= end) throw Woff2Exception("read past end of stream")
            return data[pos++].toInt() and 0xFF
        }
        fun u16(): Int = (u8() shl 8) or u8()
        fun s16(): Int = u16().toShort().toInt()
        fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()
        fun bytes(n: Int): ByteArray {
            if (n < 0 || pos + n > end) throw Woff2Exception("read past end of stream")
            return data.copyOfRange(pos, pos + n).also { pos += n }
        }

        /** UIntBase128: big-endian 7-bit groups, high bit set on all but the last byte. */
        fun base128(): Int {
            var value = 0L
            for (i in 0 until 5) {
                val b = u8()
                if (i == 0 && b == 0x80) throw Woff2Exception("leading zero in UIntBase128")
                value = (value shl 7) or (b and 0x7F).toLong()
                if (value > 0xFFFFFFFFL) throw Woff2Exception("UIntBase128 overflow")
                if (b and 0x80 == 0) return value.toInt()
            }
            throw Woff2Exception("UIntBase128 too long")
        }

        /** 255UInt16: one byte, or a marker byte followed by one or two more. */
        fun u255(): Int = when (val code = u8()) {
            253 -> u16()
            255 -> 253 + u8()
            254 -> 506 + u8()
            else -> code
        }
    }

    private fun writeU16(out: ByteArrayOutputStream, v: Int) {
        out.write((v shr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun writeU32(out: ByteArrayOutputStream, v: Long) {
        writeU16(out, ((v shr 16) and 0xFFFF).toInt())
        writeU16(out, (v and 0xFFFF).toInt())
    }
}
