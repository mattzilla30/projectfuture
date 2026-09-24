package com.projectfuture.browser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HistoryStore is documented as "one row per successful page load" - i.e. a
 * log, not a most-recently-visited list, so repeat visits to the same URL
 * are expected to produce separate rows with their own timestamps. That
 * means deleting one row from the history UI (MainActivity's per-entry
 * "onRemove") must remove only that one visit, not every visit ever made to
 * that URL.
 */
class HistoryStoreTest {

    @Test fun removingOneVisitDoesNotDeleteOtherVisitsToTheSameUrl() {
        val store = HistoryStore(FakePrefsContext())
        store.record("https://example.com/", "Example first visit")
        store.record("https://example.com/", "Example second visit")
        val loaded = store.load()
        assertEquals(2, loaded.size)

        // User deletes just the older of the two visits from the history list.
        store.remove(loaded.last())

        val remaining = store.load()
        assertEquals("deleting one visit should not wipe out every visit to the same URL", 1, remaining.size)
        assertEquals("Example second visit", remaining.single().title)
    }

    @Test fun clearRemovesAllEntriesAndTheUnderlyingKey() {
        val ctx = FakePrefsContext()
        val store = HistoryStore(ctx)
        store.record("https://example.com/", "Example")
        store.clear()

        assertTrue(store.load().isEmpty())
        // A fresh store instance over the same backing must also see nothing left over.
        assertTrue(HistoryStore(ctx).load().isEmpty())
    }

    @Test fun malformedStoredLineIsSkippedRatherThanCrashingTheWholeLoad() {
        val ctx = FakePrefsContext()
        val prefs = ctx.getSharedPreferences("history", 0)
        // One well-formed line and one corrupted line (bad timestamp) sharing the same blob.
        prefs.edit().putString("entries", "not-a-number\tBad\thttps://bad.example/\n1000\tGood\thttps://good.example/").apply()

        val store = HistoryStore(ctx)
        val loaded = store.load()

        assertEquals(1, loaded.size)
        assertEquals("https://good.example/", loaded.single().url)
    }
}
