package com.projectfuture.browser.css

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for `@media` condition evaluation in [CssParser]. The parser deliberately
 * fails a whole query closed on anything it can't parse (see the class doc for the rationale),
 * but two legacy/logical pieces of the media-query grammar were being swallowed by that same
 * fail-closed path instead of being handled: the `only` keyword (a no-op for modern parsers -
 * it should never make an otherwise-matching query fail) and `not` (which must invert the whole
 * query/condition, not get silently dropped or, worse, silently matched as if it weren't there).
 */
class CssMediaQueryTest {

    private fun rulesFor(css: String, viewportWidth: Float): List<CssRule> =
        CssParser(css, viewportWidth).parseRules()

    // --- `only` is a legacy no-op keyword: `only screen` must behave exactly like `screen`. ---

    @Test
    fun onlyScreenKeywordDoesNotSuppressAnOtherwiseMatchingQuery() {
        // Before the fix: "only screen" was compared as a literal string against "screen" and
        // "all", never matched either, fell through to the media-feature regex (which found
        // nothing to match in "only screen"), and was rejected as an "unrecognized feature" -
        // silently excluding every `@media only screen {...}` block, even though `only` is
        // supposed to be a complete no-op on a parser that understands the query it prefixes.
        val rules = rulesFor("@media only screen { div { color: red; } }", 360f)
        assertEquals(1, rules.size)
        assertEquals("red", rules[0].properties["color"])
    }

    @Test
    fun onlyScreenWithFeatureConditionStillEvaluatesTheFeature() {
        val matching = rulesFor("@media only screen and (max-width: 600px) { div { color: red; } }", 360f)
        assertEquals(1, matching.size)

        val nonMatching = rulesFor("@media only screen and (max-width: 600px) { div { color: red; } }", 800f)
        assertTrue(nonMatching.isEmpty())
    }

    // --- `not` must invert the whole query, including a parenthesized feature condition. ---

    @Test
    fun notWithFeatureConditionInvertsTheFeatureResult() {
        // Before the fix: matchesFeature() ran a regex `find` (not a full-string match) over
        // "not (min-width: 600px)". The regex located "(min-width: 600px)" inside the string and
        // evaluated *that*, silently ignoring the leading "not " - so `not (min-width: 600px)`
        // behaved identically to the un-negated `(min-width: 600px)` instead of its logical
        // opposite. At a 800px viewport the un-negated condition is true, so the negated query
        // must be false.
        val atWideViewport = rulesFor("@media not (min-width: 600px) { div { color: red; } }", 800f)
        assertTrue(atWideViewport.isEmpty())

        // ...and at a viewport where the un-negated condition is false, the negated query must
        // match.
        val atNarrowViewport = rulesFor("@media not (min-width: 600px) { div { color: red; } }", 300f)
        assertEquals(1, atNarrowViewport.size)
    }

    @Test
    fun notPrintMatchesInNormalScreenRendering() {
        // Before the fix: "not print" isn't the literal string "print" and contains no
        // parenthesized feature, so it fell through matchesFeature's fail-closed regex path and
        // was always rejected - even though this engine only ever evaluates media queries in a
        // screen-rendering context, where "not print" should always be true.
        val rules = rulesFor("@media not print { div { color: red; } }", 360f)
        assertEquals(1, rules.size)
    }

    @Test
    fun notScreenNeverMatchesInScreenRenderingContext() {
        val rules = rulesFor("@media not screen { div { color: red; } }", 360f)
        assertTrue(rules.isEmpty())
    }

    // --- sanity checks on behavior this pass confirmed was already correct. ---

    @Test
    fun boundaryWidthMatchesBothMinAndMaxWidthInclusively() {
        val minRules = rulesFor("@media (min-width: 600px) { div { color: red; } }", 600f)
        val maxRules = rulesFor("@media (max-width: 600px) { div { color: red; } }", 600f)
        assertEquals(1, minRules.size)
        assertEquals(1, maxRules.size)
    }

    @Test
    fun commaSeparatedQueriesAreOrNotAnd() {
        // (max-width: 100px), print -- at 360px wide the max-width branch is false, but the
        // whole list must still match because the two conditions are OR'd, not AND'd, and
        // matching a screen-only "all" fallback isn't needed here: the first branch alone at a
        // narrow viewport suffices for the OR to prove itself.
        val rules = rulesFor("@media (max-width: 100px), (min-width: 300px) { div { color: red; } }", 360f)
        assertEquals(1, rules.size)
    }
}
