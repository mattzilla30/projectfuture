package com.projectfuture.browser.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip tests for the small codecs backing the newly-proxied
 * [TabEngineProtocol] messages ([TabEngineProtocol.MSG_ELEMENT_META],
 * [TabEngineProtocol.MSG_SELECT_OPTIONS], [TabEngineProtocol.MSG_TAB_INFO])
 * - the same JVM-testable, no-Android-runtime approach as
 * [DisplayListCodecTest] (see [DisplayListCodec]'s class doc for why: a
 * plain `java.io`-stream format rather than `android.os.Parcel`).
 */
class IpcCodecsTest {

    @Test fun elementMetaRoundTripsEmptyList() {
        assertTrue(ElementMetaCodec.decode(ElementMetaCodec.encode(emptyList())).isEmpty())
    }

    @Test fun elementMetaRoundTripsTagAndInputType() {
        val entries = listOf(
            WireElementMeta(1, "select", null),
            WireElementMeta(2, "input", "password"),
            WireElementMeta(3, "input", "checkbox"),
            WireElementMeta(4, "a", null)
        )
        val decoded = ElementMetaCodec.decode(ElementMetaCodec.encode(entries))
        assertEquals(entries, decoded)
    }

    @Test fun elementMetaPreservesNullInputTypeInsteadOfCoercingToEmptyString() {
        val decoded = ElementMetaCodec.decode(ElementMetaCodec.encode(listOf(WireElementMeta(1, "input", null))))
        assertNull(decoded.single().inputType)
    }

    @Test fun selectOptionsRoundTripsEmptyList() {
        assertTrue(SelectOptionsCodec.decode(SelectOptionsCodec.encode(emptyList())).isEmpty())
    }

    @Test fun selectOptionsRoundTripsLabelsAndSelectedFlag() {
        val options = listOf(
            WireSelectOption(1, "Red", selected = true),
            WireSelectOption(2, "Green", selected = false),
            WireSelectOption(3, "Blue – unicode dash", selected = false)
        )
        val decoded = SelectOptionsCodec.decode(SelectOptionsCodec.encode(options))
        assertEquals(options, decoded)
    }

    @Test fun selectOptionsPreservesOrder() {
        val options = (0 until 10).map { WireSelectOption(it, "option-$it", it == 3) }
        val decoded = SelectOptionsCodec.decode(SelectOptionsCodec.encode(options))
        assertEquals(options.map { it.elementId }, decoded.map { it.elementId })
        assertEquals(3, decoded.indexOfFirst { it.selected })
    }

    @Test fun tabInfoRoundTripsEveryField() {
        val info = WireTabInfo(
            url = "https://example.com/page",
            title = "A Page",
            canGoBack = true,
            canGoForward = false,
            desktopMode = true,
            readerModeActive = false,
            textScale = 1.25f,
            isDiscarded = false
        )
        val decoded = TabInfoCodec.decode(TabInfoCodec.encode(info))
        assertEquals(info, decoded)
    }

    @Test fun tabInfoPreservesNullUrlAndTitle() {
        val info = WireTabInfo(null, null, false, false, false, false, 1f, false)
        val decoded = TabInfoCodec.decode(TabInfoCodec.encode(info))
        assertNull(decoded.url)
        assertNull(decoded.title)
    }

    @Test fun tabInfoRoundTripsFreshTabDefaults() {
        // What TabEngineServiceBase would send for a brand-new tab before any load - a real state
        // this engine passes through, not just an arbitrary combination.
        val info = WireTabInfo(null, null, canGoBack = false, canGoForward = false, desktopMode = false, readerModeActive = false, textScale = 1f, isDiscarded = false)
        assertEquals(info, TabInfoCodec.decode(TabInfoCodec.encode(info)))
    }
}
