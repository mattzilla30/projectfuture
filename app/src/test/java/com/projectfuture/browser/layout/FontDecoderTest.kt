package com.projectfuture.browser.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Exercises `FontDecoder.toSfnt`'s WOFF1 -> SFNT path against a *real* WOFF1 file
 * (`src/test/resources/font_fixtures/real.woff`), produced by running fontTools'
 * `TTFont(...).flavor = "woff"` converter over a real TTF
 * (`src/test/resources/font_fixtures/real.ttf`, IBM Plex Mono Regular). This WOFF has 18
 * tables, some stored compressed (`compLength != origLength`) and some stored raw
 * (`compLength == origLength`, e.g. `head`, `maxp`, `gasp`, `cvt `, `loca`), and its WOFF
 * table directory is NOT already sorted by tag (GDEF/GPOS/GSUB/OS2/cmap/cvt/fpgm/gasp come
 * before the alphabetically-earlier head/hhea/...) - exactly the kind of real-world layout a
 * minimal synthetic fixture wouldn't exercise.
 *
 * Two real bugs were found this way and are covered below:
 *  1. The reconstructed SFNT's table directory just mirrored the WOFF's own (arbitrary) table
 *     order instead of being sorted ascending by tag, which the OpenType spec requires.
 *  2. The `head` table's `checkSumAdjustment` field (a checksum of the *whole final file*,
 *     computed with that field zeroed) was carried over unchanged from the WOFF's compressed
 *     `head` table. That value is only valid for the byte layout the *WOFF encoder* used when
 *     it computed it - once table order/offsets change during WOFF->SFNT reconstruction, the
 *     stale value is simply wrong for the produced file.
 *
 * (`head`'s `checkSumAdjustment` and `modified` timestamp fields are intentionally excluded
 * from the raw byte-for-byte table comparison below: real WOFF encoders such as fontTools
 * legitimately rewrite both when producing the WOFF, so `real.woff`'s `head` table content
 * differs from `real.ttf`'s `head` table content in those two spots even though nothing has
 * gone wrong - that's normal round-trip behavior, not a decoder bug.)
 */
class FontDecoderTest {

    private fun fixture(name: String) = File("src/test/resources/font_fixtures/$name").readBytes()

    private class SfntTable(val tag: String, val offset: Int, val length: Int, val checksum: Long)

    private fun u32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

    private fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun parseSfntDirectory(sfnt: ByteArray): Map<String, SfntTable> {
        val numTables = u16(sfnt, 4)
        val out = LinkedHashMap<String, SfntTable>()
        var pos = 12
        for (i in 0 until numTables) {
            val tag = String(sfnt.copyOfRange(pos, pos + 4), Charsets.US_ASCII)
            val checksum = u32(sfnt, pos + 4)
            val offset = u32(sfnt, pos + 8).toInt()
            val length = u32(sfnt, pos + 12).toInt()
            out[tag] = SfntTable(tag, offset, length, checksum)
            pos += 16
        }
        return out
    }

    /** Standard TrueType/OpenType table (and, applied to a whole file, font) checksum. */
    private fun checksum(data: ByteArray): Long {
        var sum = 0L
        var i = 0
        fun byteAt(idx: Int) = if (idx < data.size) (data[idx].toLong() and 0xFF) else 0L
        while (i < data.size) {
            sum = (sum + ((byteAt(i) shl 24) or (byteAt(i + 1) shl 16) or (byteAt(i + 2) shl 8) or byteAt(i + 3))) and 0xFFFFFFFFL
            i += 4
        }
        return sum
    }

    @Test
    fun decodesRealWoff1TablesMatchOriginalTtfBytes() {
        val woff = fixture("real.woff")
        val originalTtf = fixture("real.ttf")

        val sfnt = FontDecoder.toSfnt(woff)
        assertNotNull("real WOFF1 file should decode to SFNT", sfnt)
        sfnt!!

        val decodedTables = parseSfntDirectory(sfnt)
        val originalTables = parseSfntDirectory(originalTtf)

        assertEquals("table count should be preserved", originalTables.size, decodedTables.size)

        for ((tag, orig) in originalTables) {
            val decoded = decodedTables[tag]
            assertNotNull("table '$tag' missing from decoded SFNT", decoded)
            decoded!!

            val origBytes = originalTtf.copyOfRange(orig.offset, orig.offset + orig.length)
            val decodedBytes = sfnt.copyOfRange(decoded.offset, decoded.offset + decoded.length)

            assertEquals("table '$tag' length should match the original TTF's table", origBytes.size, decodedBytes.size)

            if (tag == "head") {
                // checkSumAdjustment (bytes 8..11) and modified (bytes 28..35) legitimately
                // differ - see class doc. Zero them out in copies before comparing the rest of
                // the table (version, fontRevision, magicNumber, flags, unitsPerEm, created,
                // bounding box, style flags, etc).
                val origContent = origBytes.copyOf()
                val decodedContent = decodedBytes.copyOf()
                for (i in 8..11) { origContent[i] = 0; decodedContent[i] = 0 }
                for (i in 28..35) { origContent[i] = 0; decodedContent[i] = 0 }
                assertTrue(
                    "table 'head' bytes should match the original TTF's table content aside from " +
                        "checkSumAdjustment/modified",
                    origContent.contentEquals(decodedContent)
                )

                // Real-world fonts (verified against real.ttf itself) leave the 'head' table's
                // own directory-entry checksum computed with checkSumAdjustment zeroed, and never
                // update it to reflect the real, final adjustment - so compare against that,
                // not against the actual final bytes (which include the real adjustment).
                val zeroAdjustment = decodedBytes.copyOf()
                for (i in 8..11) zeroAdjustment[i] = 0
                assertEquals(
                    "table 'head' checksum in the SFNT directory should be computed with checkSumAdjustment zeroed",
                    checksum(zeroAdjustment),
                    decoded.checksum
                )
            } else {
                assertTrue(
                    "table '$tag' bytes should match the original TTF's table content " +
                        "(covers both compressed and stored-uncompressed WOFF tables)",
                    origBytes.contentEquals(decodedBytes)
                )
                assertEquals("table '$tag' checksum in the SFNT directory should match its data", checksum(decodedBytes), decoded.checksum)
            }
        }
    }

    @Test
    fun sfntTableDirectoryIsSortedByTagAscending() {
        // real.woff's own WOFF table directory is NOT sorted by tag (GDEF/GPOS/GSUB come
        // before the alphabetically-earlier head/hhea). The OpenType spec requires the SFNT
        // table directory to be sorted ascending by tag.
        val sfnt = FontDecoder.toSfnt(fixture("real.woff"))
        assertNotNull(sfnt)
        sfnt!!

        val numTables = u16(sfnt, 4)
        val tags = ArrayList<String>()
        var pos = 12
        for (i in 0 until numTables) {
            tags.add(String(sfnt.copyOfRange(pos, pos + 4), Charsets.US_ASCII))
            pos += 16
        }
        assertEquals("SFNT table directory must be sorted ascending by tag per the OpenType spec", tags.sorted(), tags)
    }

    @Test
    fun headCheckSumAdjustmentIsRecomputedForTheReconstructedFileLayout() {
        // head.checkSumAdjustment must equal 0xB1B0AFBA minus the checksum of the whole file
        // with that field zeroed (the standard OpenType/TrueType font-checksum algorithm). The
        // value stored inside the WOFF's compressed 'head' table is only valid for whatever
        // byte layout the WOFF *encoder* used - never for FontDecoder's own reconstruction - so
        // it must be recomputed here, not passed through.
        val sfnt = FontDecoder.toSfnt(fixture("real.woff"))
        assertNotNull(sfnt)
        sfnt!!

        val head = parseSfntDirectory(sfnt).getValue("head")
        val patched = sfnt.copyOf()
        for (i in 0..3) patched[head.offset + 8 + i] = 0
        val expectedAdjustment = (0xB1B0AFBAL - checksum(patched)) and 0xFFFFFFFFL

        val actualAdjustment = u32(sfnt, head.offset + 8)
        assertEquals("head.checkSumAdjustment must be valid for the file FontDecoder actually produced", expectedAdjustment, actualAdjustment)
    }
}
