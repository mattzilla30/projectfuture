package com.projectfuture.browser.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [BrotliDecoder] against known-good compressed samples produced by the reference `brotli` CLI/
 * Python binding (i.e. real encoder output, not anything this project generated) - the same
 * "known-good fixture, not just round-tripping our own code" approach [HpackTest] uses for the
 * RFC 7541 appendix examples. Sizes here span an empty body, a tiny literal-only block, a longer
 * text with real LZ77-style back-references, and a long repeated run (a big back-reference), so
 * more than one code path in the vendored decoder (see `BrotliDecoder.kt`'s doc for why it's
 * vendored) is actually exercised.
 */
class BrotliDecoderTest {
    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "").replace("\n", "")
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    @Test fun decodesAnEmptyBody() {
        assertArrayEquals(ByteArray(0), BrotliDecoder.decode(hex("3b")))
    }

    @Test fun decodesAShortLiteralString() {
        val decoded = BrotliDecoder.decode(hex("0b028068656c6c6f03"))
        assertEquals("hello", String(decoded, Charsets.UTF_8))
    }

    @Test fun decodesTextWithBackReferences() {
        val expected = "The quick brown fox jumps over the lazy dog. ".repeat(20)
        val compressed = hex(
            "1b8303882c0e78d3d0955d9710bb172ba9cad092cc8cad415ce6f236c8199e9e0a7b830d387048206f24be41a715ce1c1e27aa2938c289b84f8301"
        )
        val decoded = BrotliDecoder.decode(compressed)
        assertEquals(expected, String(decoded, Charsets.UTF_8))
    }

    @Test fun decodesALongRunViaASingleLargeBackReference() {
        val expected = "a".repeat(5000)
        val compressed = hex("1b8713f825c2e2b14020680100")
        val decoded = BrotliDecoder.decode(compressed)
        assertEquals(expected, String(decoded, Charsets.UTF_8))
    }
}
