package com.projectfuture.browser.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayListCodecTest {

    private fun style(link: String? = null, font: String? = null) = WireTextStyle(
        sizePx = 16f, bold = false, italic = true, monospace = false,
        colorArgb = -0x1000000, underline = true, strikethrough = false,
        linkHref = link, fontFamilyName = font
    )

    @Test fun roundTripsAnEmptyList() {
        val decoded = DisplayListCodec.decode(DisplayListCodec.encode(emptyList()))
        assertTrue(decoded.isEmpty())
    }

    @Test fun roundTripsEveryCommandKind() {
        val commands = listOf(
            WireDrawRect(0f, 0f, 100f, 20f, 0xFF0000FF.toInt(), fixed = false, elementId = 3),
            WireDrawText(5f, 15f, "hello world", style(link = "https://example.com/"), 0f, 100f, 0f, 20f, fixed = false, elementId = 7),
            WireDrawImage(0f, 20f, 50f, 70f, imageId = 42, fixed = false, elementId = 0),
            WireDrawFormControl(0f, 70f, 200f, 100f, controlType = 0, value = "hi", checked = true, placeholder = "type here", style = style(), fixed = true, elementId = 9)
        )

        val decoded = DisplayListCodec.decode(DisplayListCodec.encode(commands))

        assertEquals(commands, decoded)
    }

    @Test fun preservesNullFieldsInsteadOfCoercingThemToEmptyStrings() {
        val commands = listOf(WireDrawText(0f, 0f, "x", style(link = null, font = null), 0f, 10f, 0f, 10f, false, 0))
        val decoded = DisplayListCodec.decode(DisplayListCodec.encode(commands))
        val text = decoded.single() as WireDrawText
        assertEquals(null, text.style.linkHref)
        assertEquals(null, text.style.fontFamilyName)
    }

    @Test fun roundTripsStringsLongerThan64KB() {
        // DataOutputStream.writeUTF throws past 64KB of encoded bytes; a long <pre> run or data: link can exceed that.
        val longText = "é".repeat(50_000)
        val longHref = "data:text/plain," + "x".repeat(100_000)
        val commands = listOf(WireDrawText(0f, 0f, longText, style(link = longHref), 0f, 10f, 0f, 10f, false, 1))
        val decoded = DisplayListCodec.decode(DisplayListCodec.encode(commands)).single() as WireDrawText
        assertEquals(longText, decoded.text)
        assertEquals(longHref, decoded.style.linkHref)
    }

    @Test fun roundTripsUnicodeText() {
        val commands = listOf(WireDrawText(0f, 0f, "héllo 世界 😀", style(), 0f, 10f, 0f, 10f, false, 1))
        val decoded = DisplayListCodec.decode(DisplayListCodec.encode(commands))
        assertEquals("héllo 世界 😀", (decoded.single() as WireDrawText).text)
    }

    @Test fun preservesCommandOrder() {
        val commands = (0 until 20).map { i -> WireDrawRect(i.toFloat(), 0f, i + 1f, 1f, 0, false, i) }
        val decoded = DisplayListCodec.decode(DisplayListCodec.encode(commands))
        assertEquals(commands.map { it.elementId }, decoded.map { (it as WireDrawRect).elementId })
    }
}
