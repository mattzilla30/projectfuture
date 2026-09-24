package com.projectfuture.browser.css

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for a real bug found rendering DuckDuckGo's `/html/` results: its second
 * stylesheet (`lc.<hash>.css`) is served with a leading UTF-8 byte-order-mark (`EF BB BF`,
 * i.e. the single Unicode char U+FEFF once decoded) immediately followed by `body{...}` - a
 * common real-world quirk of CDN/build-tool-emitted CSS. Left unstripped, that stray BOM
 * character sat in front of the first selector's text, turning `body` into the unmatchable
 * tag name `"﻿body"` - the rule parsed without error (so the rest of the file was
 * unaffected) but could never match any real `<body>` element.
 */
class CssParserBomTest {

    @Test
    fun leadingBomCharacterIsSkippedSoFirstRuleStillMatches() {
        val css = "﻿body{margin:0}"
        val rules = CssParser(css).parseRules()
        assertEquals(1, rules.size)
        val tagSelector = rules[0].selector as TagSelector
        assertEquals("body", tagSelector.tag)
        assertEquals("0", rules[0].properties["margin"])
    }

    @Test
    fun bomOnlyAffectsTheVeryFirstCharacter() {
        // A BOM-looking sequence that isn't actually leading (e.g. re-appearing mid-file due to
        // concatenation) is just an unmatchable tag name for that one rule, same as before -
        // this only special-cases position 0.
        val css = "a{color:red}﻿body{margin:0}"
        val rules = CssParser(css).parseRules()
        assertEquals(2, rules.size)
        assertTrue((rules[0].selector as TagSelector).tag == "a")
        assertEquals("﻿body", (rules[1].selector as TagSelector).tag)
    }

    @Test
    fun realLcCssBomBytesParseTheBodyRuleCorrectly() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "body{margin:0;padding:0}a{color:blue}".toByteArray(Charsets.UTF_8)
        // Simulates decoding raw response bytes the way Url.kt's fetch() does, BEFORE its
        // BOM-stripping fix - CssParser itself must also cope, as a defense-in-depth measure,
        // since it's fed text from other places (inline <style>, tests) besides Url.fetch().
        val decoded = String(bytes, Charsets.UTF_8)
        assertEquals(0xFEFF, decoded[0].code)
        val rules = CssParser(decoded).parseRules()
        assertEquals(2, rules.size)
        assertEquals("body", (rules[0].selector as TagSelector).tag)
        assertEquals("0", rules[0].properties["margin"])
    }
}
