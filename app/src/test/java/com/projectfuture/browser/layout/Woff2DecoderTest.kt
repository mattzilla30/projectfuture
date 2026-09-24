package com.projectfuture.browser.layout

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `sample.ttf` is a 15-glyph subset of Liberation Sans (simple glyphs, composites such as
 * Eacute and ntilde, hinting instructions). `sample.woff2` is fontTools' WOFF2 encoding of it
 * with the glyf, loca and hmtx transforms applied, so decoding exercises all three
 * reconstructions.
 */
class Woff2DecoderTest {

    private fun fixture(name: String) = File("src/test/resources/font_fixtures/$name").readBytes()

    private fun u32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

    private fun u16(b: ByteArray, off: Int): Int = ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
    private fun s16(b: ByteArray, off: Int): Int = u16(b, off).toShort().toInt()

    private fun tables(sfnt: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        for (i in 0 until u16(sfnt, 4)) {
            val pos = 12 + i * 16
            val offset = u32(sfnt, pos + 8).toInt()
            out[String(sfnt, pos, 4, Charsets.US_ASCII)] = sfnt.copyOfRange(offset, offset + u32(sfnt, pos + 12).toInt())
        }
        return out
    }

    private fun checksum(data: ByteArray): Long {
        var sum = 0L
        for (i in data.indices step 4) {
            var word = 0L
            for (k in 0..3) word = (word shl 8) or (if (i + k < data.size) data[i + k].toLong() and 0xFF else 0L)
            sum = (sum + word) and 0xFFFFFFFFL
        }
        return sum
    }

    /** Splits glyf into per-glyph records using loca, in either index format. */
    private fun glyphRecords(t: Map<String, ByteArray>): List<ByteArray> {
        val longFormat = s16(t.getValue("head"), 50) == 1
        val loca = t.getValue("loca")
        val glyf = t.getValue("glyf")
        val count = loca.size / (if (longFormat) 4 else 2) - 1
        return (0 until count).map { i ->
            val start = if (longFormat) u32(loca, i * 4).toInt() else u16(loca, i * 2) * 2
            val end = if (longFormat) u32(loca, i * 4 + 4).toInt() else u16(loca, i * 2 + 2) * 2
            glyf.copyOfRange(start, end)
        }
    }

    /** Decodes a simple glyph to (endPoints, instructions, onCurve flags, absolute points) so encodings can differ. */
    private fun simpleOutline(g: ByteArray): List<Any> {
        val n = s16(g, 0)
        val endPoints = (0 until n).map { u16(g, 10 + it * 2) }
        val points = if (n == 0) 0 else endPoints.last() + 1
        var pos = 10 + n * 2
        val instructionLength = u16(g, pos)
        val instructions = g.copyOfRange(pos + 2, pos + 2 + instructionLength).toList()
        pos += 2 + instructionLength
        val flags = ArrayList<Int>()
        while (flags.size < points) {
            val f = g[pos++].toInt() and 0xFF
            flags.add(f)
            if (f and 0x08 != 0) repeat(g[pos++].toInt() and 0xFF) { flags.add(f) }
        }
        fun coords(shortBit: Int, sameBit: Int): List<Int> {
            var v = 0
            return flags.map { f ->
                v += when {
                    f and shortBit != 0 -> (g[pos++].toInt() and 0xFF).let { if (f and sameBit != 0) it else -it }
                    f and sameBit != 0 -> 0
                    else -> s16(g, pos).also { pos += 2 }
                }
                v
            }
        }
        val xs = coords(0x02, 0x10)
        val ys = coords(0x04, 0x20)
        return listOf(g.copyOfRange(0, 10).toList(), endPoints, instructions, flags.map { it and 1 }, xs, ys)
    }

    @Test
    fun decodesTransformedWoff2ToTheOriginalFont() {
        val sfnt = FontDecoder.toSfnt(fixture("sample.woff2"))
        assertNotNull(sfnt)
        val decoded = tables(sfnt!!)
        val original = tables(fixture("sample.ttf"))
        assertEquals(original.keys.sorted(), decoded.keys.sorted())

        for ((tag, bytes) in original) {
            if (tag == "glyf" || tag == "loca" || tag == "head") continue
            assertArrayEquals("table '$tag' should round-trip byte for byte", bytes, decoded[tag])
        }

        val originalGlyphs = glyphRecords(original)
        val decodedGlyphs = glyphRecords(decoded)
        assertEquals(originalGlyphs.size, decodedGlyphs.size)
        for (i in originalGlyphs.indices) {
            val a = originalGlyphs[i]
            val b = decodedGlyphs[i]
            when {
                a.isEmpty() -> assertEquals("glyph $i should stay empty", 0, b.size)
                s16(a, 0) < 0 -> assertArrayEquals("composite glyph $i", a.copyOf(a.size.coerceAtMost(b.size)), b.copyOf(a.size.coerceAtMost(b.size)))
                else -> assertEquals("simple glyph $i outline", simpleOutline(a), simpleOutline(b))
            }
        }
        assertTrue("sample should contain composites", originalGlyphs.any { it.isNotEmpty() && s16(it, 0) < 0 })
    }

    @Test
    fun wholeFileChecksumIsValid() {
        val sfnt = FontDecoder.toSfnt(fixture("sample.woff2"))!!
        assertEquals(0xB1B0AFBAL, checksum(sfnt))
    }

    @Test
    fun truncatedFileReturnsNull() {
        val woff2 = fixture("sample.woff2")
        assertNull(FontDecoder.toSfnt(woff2.copyOf(woff2.size / 2)))
        assertNull(FontDecoder.toSfnt(woff2.copyOf(60)))
    }
}
