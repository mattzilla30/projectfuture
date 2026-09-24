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

    @Test fun navigatingToAFreshDocumentDoesNotMisattributeARecycledElementIdsMetadata() {
        // Simulates two full navigations in the same long-lived engine process/UI-client pair
        // (TabEngineServiceBase/TabEngineClient each keep their converter alive across many page
        // loads - see their class docs) - exactly what TabEngineServiceBase.onTabStateChanged's
        // TabState.Loaded branch and TabEngineClient's MSG_STATE_LOADED handler now do on every
        // real navigation (MSG_LOAD_URL, a followed link, reload, back/forward onto a different
        // document).
        val elementIds = ElementIdRegistry()
        val engineConverter = EngineToUiConverter(elementIds)
        val uiConverter = UiDisplayListConverter()

        // Document 1: a single password field. It gets elementId 1, and the UI side learns it's a
        // password input.
        val passwordField = ElementNode(tag = "input").apply { attributes["type"] = "password" }
        val (wire1, _, meta1) = engineConverter.convert(listOf(DrawRect(0f, 0f, 10f, 10f, 0, false, passwordField)))
        assertEquals(1, meta1.size)
        assertEquals(1, meta1[0].elementId)
        uiConverter.onElementMetaReceived(meta1)
        val shadow1 = uiConverter.convert(DisplayListCodec.decode(DisplayListCodec.encode(wire1))).single().sourceElement!!
        assertEquals("password", shadow1.attr("type"))

        // Navigate to document 2 (a fresh document - TabState.Loaded) and reset exactly as
        // TabEngineServiceBase/TabEngineClient now do, in the same order (engine side resets before
        // converting document 2's display list; the UI side resets on MSG_STATE_LOADED, which now
        // arrives before document 2's MSG_ELEMENT_META/MSG_DISPLAY_LIST - see both classes' docs).
        elementIds.reset()
        engineConverter.reset()
        uiConverter.reset()

        // Document 2's first element is an ordinary link, not a password field - but since ids
        // restart at 1 (ElementIdRegistry.reset()), it is *also* assigned elementId 1.
        val linkElement = ElementNode(tag = "a")
        val (wire2, _, meta2) = engineConverter.convert(listOf(DrawRect(0f, 0f, 10f, 10f, 0, false, linkElement)))
        assertEquals(1, wire2.single().elementId)

        // Without EngineToUiConverter.reset() clearing metaSent, this assertion fails: the engine
        // would think elementId 1's metadata was "already sent" (from document 1's password field)
        // and never send WireElementMeta for document 2's link at all.
        assertEquals("elementId 1's metadata for the NEW document must be (re-)sent, not skipped as already-sent", 1, meta2.size)
        assertEquals("a", meta2[0].tag)

        uiConverter.onElementMetaReceived(meta2)
        val shadow2 = uiConverter.convert(DisplayListCodec.decode(DisplayListCodec.encode(wire2))).single().sourceElement!!

        // Without UiDisplayListConverter.reset() clearing shadowElements/metaByElementId, this
        // would still be the stale password-field shadow built for document 1's elementId 1.
        assertEquals("elementId 1 must resolve to document 2's actual element (a link), not a stale shadow left over from document 1's element at the same recycled id", "a", shadow2.tag)
        assertNull("a shadow rebuilt for the new document must not carry over the old document's type attribute", shadow2.attr("type"))
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
