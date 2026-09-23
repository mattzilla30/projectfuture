package com.projectfuture.browser.layout

import com.projectfuture.browser.html.ElementNode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * textStyleForElement() only touches parseCssColor/parsePx and Android's
 * Color.BLACK/Typeface constants (no real Canvas/Paint drawing), so it's
 * directly unit-testable - unlike FontCache, which actually needs a live
 * Typeface/Paint and so isn't exercised here.
 */
class TextStyleTest {

    @Test fun textScaleFactorMultipliesResolvedFontSize() {
        val node = ElementNode("p").apply { style["font-size"] = "20px" }
        try {
            textScaleFactor = 1f
            assertEquals(20f, textStyleForElement(node, null).sizePx, 0.01f)

            textScaleFactor = 1.5f
            assertEquals(30f, textStyleForElement(node, null).sizePx, 0.01f)
        } finally {
            textScaleFactor = 1f // don't leak into other tests sharing this JVM
        }
    }

    @Test fun defaultFontSizeIsSixteenPxBeforeScaling() {
        textScaleFactor = 1f
        val node = ElementNode("p")
        assertEquals(16f, textStyleForElement(node, null).sizePx, 0.01f)
    }
}
