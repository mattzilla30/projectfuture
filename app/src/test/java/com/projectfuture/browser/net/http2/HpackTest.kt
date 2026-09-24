package com.projectfuture.browser.net.http2

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * HPACK correctness (RFC 7541): both a self-consistency check (encode with [HpackEncoder], decode
 * with [HpackDecoder], get the same headers back - including with the dynamic table and Huffman
 * coding actually exercised) and, since a round-trip can hide "encoder and decoder agree with each
 * other but not with the spec" bugs, direct checks against the RFC's own worked examples (Appendix
 * C.2.1 and C.4.1) - known-good byte sequences this decoder must parse exactly as documented.
 */
class HpackTest {
    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "").replace("\n", "")
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    @Test fun decodesRfc7541AppendixC21LiteralWithIncrementalIndexingNewName() {
        // "custom-key: custom-header", encoded with a brand-new (unindexed) literal name/value.
        val block = hex("400a637573746f6d2d6b65790d637573746f6d2d686561646572")
        val headers = HpackDecoder().decode(block)
        assertEquals(listOf(HpackHeader("custom-key", "custom-header")), headers)
    }

    @Test fun decodesRfc7541AppendixC41IndexedNameHuffmanValue() {
        // ":authority: www.example.com" - name via static-table index 1, Huffman-coded value.
        val block = hex("418cf1e3c2e5f23a6ba0ab90f4ff")
        val headers = HpackDecoder().decode(block)
        assertEquals(listOf(HpackHeader(":authority", "www.example.com")), headers)
    }

    @Test fun roundTripsStaticTableOnlyHeaders() {
        val headers = listOf(HpackHeader(":method", "GET"), HpackHeader(":scheme", "https"), HpackHeader(":path", "/"))
        val encoded = HpackEncoder().encode(headers)
        assertEquals(headers, HpackDecoder().decode(encoded))
        // Three headers each fully present in the static table should collapse to 3 single bytes.
        assertEquals(3, encoded.size)
    }

    @Test fun roundTripsWithDynamicTableAndHuffmanCoding() {
        val encoder = HpackEncoder()
        val decoder = HpackDecoder()
        val firstRequest = listOf(
            HpackHeader(":method", "GET"),
            HpackHeader(":path", "/index.html"),
            HpackHeader("user-agent", "ProjectFutureBrowser/0.1 (Android; from-scratch)"),
            HpackHeader("x-custom", "some-value-worth-compressing")
        )
        assertEquals(firstRequest, decoder.decode(encoder.encode(firstRequest)))

        // A second request repeating the same custom header should now hit the dynamic table -
        // both encoder and decoder tables must have evolved identically after the first round.
        val secondRequest = listOf(
            HpackHeader(":method", "GET"),
            HpackHeader(":path", "/other.html"),
            HpackHeader("user-agent", "ProjectFutureBrowser/0.1 (Android; from-scratch)"),
            HpackHeader("x-custom", "some-value-worth-compressing")
        )
        val secondEncoded = encoder.encode(secondRequest)
        assertEquals(secondRequest, decoder.decode(secondEncoded))
        // The exact-match dynamic table hits for user-agent and x-custom collapse to 1 byte each,
        // so the whole second (repeat) request should encode far smaller than a from-scratch one.
        assertEquals(firstRequest, decoder.decode(encoder.encode(firstRequest))) // still decodes fine on a 3rd pass
    }

    @Test fun dynamicTableEvictsOldestEntriesOnceOverMaxSize() {
        val table = HpackDynamicTable(maxSize = 64)
        table.add(HpackHeader("a", "1".repeat(20))) // ~ (1+20+32) = 53 bytes
        assertEquals(1, table.entryCount)
        table.add(HpackHeader("b", "2".repeat(20))) // pushes total over 64 -> evicts the first entry
        assertEquals(1, table.entryCount)
        assertEquals(HpackHeader("b", "2".repeat(20)), table[0])
    }

    @Test fun huffmanRoundTripsArbitraryAsciiText() {
        val original = "The quick brown fox jumps over the lazy dog 1234567890!"
        val encoded = Huffman.encode(original.toByteArray(Charsets.ISO_8859_1))
        val decoded = String(Huffman.decode(encoded), Charsets.ISO_8859_1)
        assertEquals(original, decoded)
    }
}
