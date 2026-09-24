package com.projectfuture.browser.browser

import com.projectfuture.browser.html.HtmlParser
import com.projectfuture.browser.html.walkElements
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Exercises [collectFormParams]/[encodeFormParams] directly - the same
 * pattern ReaderModeTest uses for [extractReaderContent] - since [Tab]
 * itself can't be constructed in a plain JUnit test (its constructor
 * touches real `Handler`/`Looper`).
 */
class FormSubmissionTest {

    private fun form(html: String) = HtmlParser(html).parse().let { doc ->
        var found: com.projectfuture.browser.html.ElementNode? = null
        doc.walkElements { if (it.tag == "form") found = it }
        found!!
    }

    @Test fun aSelectMultipleSubmitsEveryChosenOptionNotJustTheFirst() {
        val f = form(
            """
            <form>
                <select name="colors" multiple>
                    <option value="red" selected>Red</option>
                    <option value="green" selected>Green</option>
                    <option value="blue">Blue</option>
                </select>
            </form>
            """.trimIndent()
        )
        val params = collectFormParams(f)
        assertEquals(listOf("colors" to "red", "colors" to "green"), params)
    }

    @Test fun aPlainSelectStillSubmitsOnlyItsOneSelectedOption() {
        val f = form(
            """
            <form>
                <select name="color">
                    <option value="red" selected>Red</option>
                    <option value="green">Green</option>
                </select>
            </form>
            """.trimIndent()
        )
        val params = collectFormParams(f)
        assertEquals(listOf("color" to "red"), params)
    }

    @Test fun onlyTheCheckedRadioInAGroupIsSubmitted() {
        val f = form(
            """
            <form>
                <input type="radio" name="plan" value="basic">
                <input type="radio" name="plan" value="pro" checked>
                <input type="radio" name="plan" value="enterprise">
            </form>
            """.trimIndent()
        )
        assertEquals(listOf("plan" to "pro"), collectFormParams(f))
    }

    @Test fun bothCheckedCheckboxesInAGroupAreSubmitted() {
        val f = form(
            """
            <form>
                <input type="checkbox" name="topping" value="cheese" checked>
                <input type="checkbox" name="topping" value="olives">
                <input type="checkbox" name="topping" value="mushrooms" checked>
            </form>
            """.trimIndent()
        )
        assertEquals(listOf("topping" to "cheese", "topping" to "mushrooms"), collectFormParams(f))
    }

    @Test fun disabledControlsAreExcludedFromSubmission() {
        val f = form(
            """
            <form>
                <input type="text" name="visible" value="yes">
                <input type="text" name="hidden" value="no" disabled>
            </form>
            """.trimIndent()
        )
        assertEquals(listOf("visible" to "yes"), collectFormParams(f))
    }

    @Test fun specialCharactersArePercentEncodedInFormUrlEncoding() {
        val encoded = encodeFormParams(listOf("q" to "a b&c=d", "name" to "café"))
        // Spaces become '+' (application/x-www-form-urlencoded), reserved chars are percent-escaped,
        // and non-ASCII is UTF-8 percent-encoded - not silently dropped or mangled.
        assertEquals("q=a+b%26c%3Dd&name=caf%C3%A9", encoded)
    }
}
