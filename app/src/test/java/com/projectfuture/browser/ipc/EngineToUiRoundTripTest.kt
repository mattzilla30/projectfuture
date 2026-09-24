package com.projectfuture.browser.ipc

import com.projectfuture.browser.html.ElementNode
import com.projectfuture.browser.layout.DrawRect
import com.projectfuture.browser.layout.DrawText
import com.projectfuture.browser.layout.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A JVM-level simulation of both sides of the sandboxed-tab IPC protocol
 * without any real Android process, Binder call, or Robolectric: builds a
 * real "engine-side" `DisplayCommand` list against real `ElementNode`s
 * (exactly like `Tab.displayList` would), runs it through the same
 * converters/codecs [TabEngineServiceBase]/[TabEngineClient] use, and
 * checks what comes out the other side - a shadow-element display list
 * whose tap-routing metadata (tag/input-type) matches the original DOM,
 * which is exactly what [com.projectfuture.browser.MainActivity]'s
 * `handleElementTap` depends on (see [UiDisplayListConverter]'s class doc
 * and [ElementMetaCodec]'s).
 */
class EngineToUiRoundTripTest {

    private fun style() = TextStyle(16f, false, false, false, -0x1000000, false, false, null, null)

    @Test fun sourceElementIdentityAndMetadataSurviveTheRoundTrip() {
        val selectEl = ElementNode(tag = "select")
        val passwordEl = ElementNode(tag = "input").apply { attributes["type"] = "password" }
        val linkEl = ElementNode(tag = "a")

        val engineCommands = listOf(
            DrawRect(0f, 0f, 100f, 20f, -0x1, false, selectEl),
            DrawText(5f, 40f, "secret", style(), 0f, 100f, 20f, 40f, false, passwordEl),
            DrawText(5f, 60f, "click me", style(), 0f, 100f, 40f, 60f, false, linkEl)
        )

        // Engine-process side.
        val elementIds = ElementIdRegistry()
        val engineConverter = EngineToUiConverter(elementIds)
        val (wireCommands, newImages, newMeta) = engineConverter.convert(engineCommands)
        assertTrue("no bitmaps were used, so nothing should need MSG_IMAGE_DATA", newImages.isEmpty())
        assertEquals(3, newMeta.size)

        // "Cross the Binder call": encode to bytes and back, same as the real Bundle payload.
        val decodedCommands = DisplayListCodec.decode(DisplayListCodec.encode(wireCommands))
        val decodedMeta = ElementMetaCodec.decode(ElementMetaCodec.encode(newMeta))

        // UI-process side.
        val uiConverter = UiDisplayListConverter()
        uiConverter.onElementMetaReceived(decodedMeta)
        val uiCommands = uiConverter.convert(decodedCommands)

        val shadowSelect = uiCommands[0].sourceElement
        val shadowPassword = uiCommands[1].sourceElement
        val shadowLink = uiCommands[2].sourceElement

        assertNotNull(shadowSelect)
        assertEquals("select", shadowSelect!!.tag)
        assertNull(shadowSelect.attr("type"))

        assertNotNull(shadowPassword)
        assertEquals("input", shadowPassword!!.tag)
        assertEquals("password", shadowPassword.attr("type"))

        assertNotNull(shadowLink)
        assertEquals("a", shadowLink!!.tag)

        // A tap on a shadow element must translate back to the same elementId the engine assigned -
        // this is how TabEngineClient.dispatchTap/setFieldValue/etc find their way back to the real
        // DOM node in the engine process.
        assertEquals(elementIds.idFor(passwordEl), uiConverter.elementIdFor(shadowPassword))
        assertEquals(elementIds.idFor(selectEl), uiConverter.elementIdFor(shadowSelect))
    }

    @Test fun repaintingTheSameElementsDoesNotResendMetadata() {
        val el = ElementNode(tag = "button")
        val elementIds = ElementIdRegistry()
        val converter = EngineToUiConverter(elementIds)

        val first = converter.convert(listOf(DrawRect(0f, 0f, 10f, 10f, 0, false, el)))
        val second = converter.convert(listOf(DrawRect(0f, 0f, 10f, 10f, 0, false, el)))

        assertEquals(1, first.third.size)
        assertTrue("a relayout of the same element shouldn't resend its metadata", second.third.isEmpty())
    }

    @Test fun elementWithNoSourceGetsElementIdZeroAndNoShadow() {
        val elementIds = ElementIdRegistry()
        val engineConverter = EngineToUiConverter(elementIds)
        val (wire, _, meta) = engineConverter.convert(listOf(DrawRect(0f, 0f, 10f, 10f, 0, false, null)))
        assertTrue(meta.isEmpty())

        val uiConverter = UiDisplayListConverter()
        val uiCommands = uiConverter.convert(DisplayListCodec.decode(DisplayListCodec.encode(wire)))
        assertNull(uiCommands.single().sourceElement)
    }

    @Test fun selectOptionsMetadataFlowMirrorsTheDisplayListOne() {
        // Simulates MSG_REQUEST_SELECT_OPTIONS / MSG_SELECT_OPTIONS - the select's own options never
        // appear in a display list (only the closed <select> box itself does), so their metadata is
        // learned purely through EngineToUiConverter.metaFor/SelectOptionsCodec, not convert().
        val redOption = ElementNode(tag = "option").apply { attributes["value"] = "red" }
        val elementIds = ElementIdRegistry()
        val engineConverter = EngineToUiConverter(elementIds)
        val (id, meta) = engineConverter.metaFor(redOption)
        assertNotNull(meta)
        assertEquals("option", meta!!.tag)

        val wireOptions = listOf(WireSelectOption(id, "Red", selected = true))
        val decodedOptions = SelectOptionsCodec.decode(SelectOptionsCodec.encode(wireOptions))

        val uiConverter = UiDisplayListConverter()
        uiConverter.onElementMetaReceived(listOf(meta))
        val shadow = uiConverter.shadowElementFor(decodedOptions.single().elementId)
        assertEquals("option", shadow.tag)
        assertSame(shadow, uiConverter.shadowElementFor(id)) // stable identity across repeated lookups
    }
}
