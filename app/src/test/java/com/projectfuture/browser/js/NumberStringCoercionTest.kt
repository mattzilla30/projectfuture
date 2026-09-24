package com.projectfuture.browser.js

import org.junit.Assert.assertEquals
import org.junit.Test

class NumberStringCoercionTest {

    private fun result(src: String): JsValue = runScript(src).globalEnv.get("result")
    private fun str(src: String): String = (result(src) as JsString).value
    private fun num(src: String): Double = (result(src) as JsNumber).value

    @Test fun parseIntHexAutoDetect() {
        assertEquals(31.0, num("var result = parseInt('0x1F');"), 0.0)
    }

    @Test fun parseIntStopsAtFirstNonDigit() {
        assertEquals(42.0, num("var result = parseInt('  42abc');"), 0.0)
    }

    @Test fun parseIntWithRadix() {
        assertEquals(34.0, num("var result = parseInt('42', 8);"), 0.0)
    }

    @Test fun arrayPlusArrayIsEmptyString() {
        assertEquals("", str("var result = [] + [];"))
    }

    @Test fun arrayPlusObjectIsObjectString() {
        assertEquals("[object Object]", str("var result = [] + {};"))
    }

    @Test fun arrayMinusNumberCoercesViaToString() {
        assertEquals(2.0, num("var result = [5] - 3;"), 0.0)
    }

    @Test fun numberHexString() {
        assertEquals(31.0, num("var result = Number('0x1F');"), 0.0)
    }

    @Test fun toStringRadixFractional() {
        assertEquals("ff.8", str("var result = (255.5).toString(16);"))
    }

    @Test fun toFixedRoundsHalfUp() {
        assertEquals("2", str("var result = (1.5).toFixed(0);"))
    }

    @Test fun toFixedFamousQuirk() {
        assertEquals("1.00", str("var result = (1.005).toFixed(2);"))
    }

    @Test fun scientificNotationSmall() {
        assertEquals("1e-7", str("var result = (0.0000001).toString();"))
    }

    @Test fun scientificNotationLarge() {
        assertEquals("1e+21", str("var result = (1e21).toString();"))
    }

    @Test fun forOfStringIteratesByCodepoint() {
        assertEquals(
            1.0,
            num("var count = 0; for (const ch of '𝌆') { count++; } var result = count;"),
            0.0
        )
    }

    @Test fun stringLengthIsUtf16CodeUnits() {
        assertEquals(2.0, num("var result = '𝌆'.length;"), 0.0)
    }

    @Test fun parseFloatInfinityLiteral() {
        assertEquals(Double.POSITIVE_INFINITY, num("var result = parseFloat('Infinity');"), 0.0)
        assertEquals(Double.NEGATIVE_INFINITY, num("var result = parseFloat('-Infinity');"), 0.0)
    }

    @Test fun toPrecisionUsesExponentialWhenNeeded() {
        assertEquals("1.2e+5", str("var result = (123456).toPrecision(2);"))
        assertEquals("123.5", str("var result = (123.456).toPrecision(4);"))
    }

    @Test fun arrayFromStringIsCodepointAware() {
        assertEquals(1.0, num("var result = Array.from('𝌆').length;"), 0.0)
    }
}
