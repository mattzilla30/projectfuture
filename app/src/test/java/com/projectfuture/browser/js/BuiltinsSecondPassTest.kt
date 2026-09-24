package com.projectfuture.browser.js

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A second, deeper pass over the built-in Array/Object/String/Map/Set/JSON
 * methods in Builtins.kt/Collections.kt, covering spec edge cases the
 * first pass (InterpreterTest/ModernFeaturesTest) didn't reach. Follows
 * their convention of running real JS snippets and reading a `result`
 * global back out.
 */
class BuiltinsSecondPassTest {

    private fun result(src: String): JsValue = runScript(src).globalEnv.get("result")
    private fun str(src: String): String = (result(src) as JsString).value
    private fun bool(src: String): Boolean = (result(src) as JsBoolean).value
    private fun assertNum(expected: Double, src: String) = assertEquals(expected, (result(src) as JsNumber).value, 1e-9)

    // ---- Array.prototype.includes vs indexOf and NaN (SameValueZero vs strict equality) ----

    @Test fun includesFindsNaNButIndexOfDoesNot() {
        assertTrue("includes should find NaN via SameValueZero", bool("var result = [1, NaN, 3].includes(NaN);"))
        assertNum(-1.0, "var result = [1, NaN, 3].indexOf(NaN);")
    }

    // ---- Array.prototype.lastIndexOf ----

    @Test fun lastIndexOfFindsTheLastMatchingIndex() {
        assertNum(3.0, "var result = [1, 2, 1, 2].lastIndexOf(2);")
        assertNum(-1.0, "var result = [1, 2, 3].lastIndexOf(9);")
    }

    // ---- Array.prototype.findLast / findLastIndex ----

    @Test fun findLastAndFindLastIndex() {
        // Evens in [1,4,2,5,3] are 4 (index 1) and 2 (index 2); the *last* one is 2.
        assertNum(2.0, "var result = [1, 4, 2, 5, 3].findLast(x => x % 2 === 0);")
        assertNum(2.0, "var result = [1, 4, 2, 5, 3].findLastIndex(x => x % 2 === 0);")
    }

    @Test fun findLastCallbackArgumentOrderIsValueIndexArray() {
        assertEquals(
            "1:0,4:1,2:2",
            str(
                """
                var seen = [];
                [1, 4, 2].findLast(function(v, i, a) { seen.push(v + ':' + i); return false; });
                var result = seen.join(',');
                """.trimIndent()
            )
        )
    }

    // ---- Array.prototype.reduce / reduceRight on an empty array with no initial value ----

    @Test fun reduceOnEmptyArrayWithNoInitialValueThrows() {
        var threw = false
        try {
            runScript("[].reduce(function(a, b) { return a + b; });")
        } catch (e: JsException) {
            threw = true
        }
        assertTrue("reduce() on an empty array with no initial value should throw", threw)
    }

    @Test fun reduceRightOnEmptyArrayWithNoInitialValueThrows() {
        var threw = false
        try {
            runScript("[].reduceRight(function(a, b) { return a + b; });")
        } catch (e: JsException) {
            threw = true
        }
        assertTrue("reduceRight() on an empty array with no initial value should throw", threw)
    }

    @Test fun reduceWithInitialValueOnEmptyArrayReturnsInitialValue() {
        assertNum(9.0, "var result = [].reduce(function(a, b) { return a + b; }, 9);")
    }

    // ---- Array.prototype.flat depth handling ----

    @Test fun flatDefaultsToDepthOne() {
        assertEquals("[1,2,[3,[4]]]", str("var result = JSON.stringify([1, [2, [3, [4]]]].flat());"))
    }

    @Test fun flatHonorsExplicitDepth() {
        assertEquals("[1,2,3,[4]]", str("var result = JSON.stringify([1, [2, [3, [4]]]].flat(2));"))
        assertEquals("[1,2,3,4]", str("var result = JSON.stringify([1, [2, [3, [4]]]].flat(Infinity));"))
    }

    // ---- JSON.stringify: undefined/function as object property (omitted) vs array element (null) ----

    @Test fun stringifyOmitsUndefinedAndFunctionObjectProperties() {
        assertEquals(
            "{\"a\":1,\"c\":3}",
            str("var result = JSON.stringify({ a: 1, b: undefined, c: 3 });")
        )
        assertEquals(
            "{\"a\":1}",
            str("var result = JSON.stringify({ a: 1, f: function() {} });")
        )
    }

    @Test fun stringifyTurnsUndefinedArrayElementsIntoNull() {
        assertEquals("[1,null,3]", str("var result = JSON.stringify([1, undefined, 3]);"))
    }

    // ---- String.prototype.split with a regex separator that has capture groups ----

    @Test fun splitWithRegexCaptureGroupsIncludesTheGroupsInTheResult() {
        assertEquals(
            "[\"a\",\"1\",\"b\",\"2\",\"c\",\"3\",\"\"]",
            str("var result = JSON.stringify('a1b2c3'.split(/(\\d)/));")
        )
    }

    // ---- Map/Set SameValueZero key semantics: +0/-0 are the same key ----

    @Test fun mapTreatsPositiveAndNegativeZeroAsTheSameKey() {
        assertEquals(
            "pos",
            str(
                """
                var m = new Map();
                m.set(0, 'pos');
                var result = m.get(-0);
                """.trimIndent()
            )
        )
    }

    @Test fun setTreatsPositiveAndNegativeZeroAsTheSameValue() {
        assertNum(
            1.0,
            """
            var s = new Set();
            s.add(0);
            s.add(-0);
            var result = s.size;
            """.trimIndent()
        )
    }

    @Test fun mapAndSetTreatNaNAsAUsableKey() {
        assertNum(
            1.0,
            """
            var m = new Map();
            m.set(NaN, 1);
            var result = m.get(NaN);
            """.trimIndent()
        )
    }
}
