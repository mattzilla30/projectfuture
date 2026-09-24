package com.projectfuture.browser.net.quic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QpackTest {

    @Test fun staticTableHasWellKnownEntries() {
        assertEquals(":authority" to "", QpackStaticTable.entries[0])
        assertEquals(99, QpackStaticTable.entries.size)
        assertNotNull(QpackStaticTable.exactIndex(":method", "GET"))
        assertNotNull(QpackStaticTable.exactIndex(":status", "200"))
        assertNotNull(QpackStaticTable.nameIndex(":path"))
    }

    @Test fun exactStaticMatchEncodesAsSingleByteIndexedLine() {
        val encoded = Qpack.encode(listOf(":method" to "GET"))
        // 2-byte field-section prefix (Required Insert Count=0, Delta Base=0) + 1-byte indexed field line.
        assertEquals(3, encoded.size)
        assertEquals(0xC0 or QpackStaticTable.exactIndex(":method", "GET")!!, encoded[2].toInt() and 0xFF)
    }

    @Test fun exactStaticMatchRoundTrips() {
        val headers = listOf(":method" to "GET", ":scheme" to "https", ":status" to "200")
        val decoded = Qpack.decode(Qpack.encode(headers))
        assertEquals(headers, decoded)
    }

    @Test fun nameOnlyStaticMatchRoundTrips() {
        // ":path" is in the static table only with value "/" - a different value must fall back
        // to a literal-with-name-reference rather than an indexed line.
        val headers = listOf(":path" to "/search?q=quic")
        val decoded = Qpack.decode(Qpack.encode(headers))
        assertEquals(headers, decoded)
    }

    @Test fun fullyLiteralHeaderRoundTrips() {
        val headers = listOf("x-custom-header" to "some value with spaces")
        val decoded = Qpack.decode(Qpack.encode(headers))
        assertEquals(headers, decoded)
    }

    @Test fun mixedHeaderListRoundTrips() {
        val headers = listOf(
            ":method" to "GET",
            ":scheme" to "https",
            ":authority" to "example.com",
            ":path" to "/index.html",
            "user-agent" to "ProjectFutureBrowser/0.1",
            "accept" to "*/*"
        )
        val decoded = Qpack.decode(Qpack.encode(headers))
        assertEquals(headers, decoded)
    }

    @Test fun encodedFieldSectionAlwaysStartsWithZeroPrefix() {
        val encoded = Qpack.encode(listOf("a" to "b"))
        assertEquals(0x00, encoded[0].toInt())
        assertEquals(0x00, encoded[1].toInt())
    }

    @Test fun emptyHeaderListEncodesToJustThePrefix() {
        val encoded = Qpack.encode(emptyList())
        assertEquals(2, encoded.size)
        assertTrue(Qpack.decode(encoded).isEmpty())
    }
}
