package com.projectfuture.browser.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Exercises [nextActiveIndexAfterClose] directly - `TabManager` itself
 * can't be constructed in a plain JUnit test (see that function's doc).
 */
class TabManagerTest {

    @Test fun closingATabBeforeTheActiveOneShiftsTheActiveIndexDownWithIt() {
        // tabs = [A, B, C, D, E], C (index 2) is active. Closing A (index 0) must
        // leave C - now at index 1 - active, not silently switch to D.
        assertEquals(1, nextActiveIndexAfterClose(oldActiveIndex = 2, removedIndex = 0, newSize = 4))
    }

    @Test fun closingTheActiveTabItselfStaysAtTheSameIndexSoTheNextTabTakesOver() {
        // tabs = [A, B, C], B (index 1) is active and gets closed -> [A, C], C (now index 1) takes over.
        assertEquals(1, nextActiveIndexAfterClose(oldActiveIndex = 1, removedIndex = 1, newSize = 2))
    }

    @Test fun closingTheLastTabWhileItWasActiveClampsBackIntoRange() {
        // tabs = [A, B, C], C (index 2) is active and gets closed -> [A, B], B (index 1) takes over.
        assertEquals(1, nextActiveIndexAfterClose(oldActiveIndex = 2, removedIndex = 2, newSize = 2))
    }

    @Test fun closingATabAfterTheActiveOneDoesNotMoveTheActiveIndex() {
        // tabs = [A, B, C, D], A (index 0) is active. Closing D (index 3) leaves A active.
        assertEquals(0, nextActiveIndexAfterClose(oldActiveIndex = 0, removedIndex = 3, newSize = 3))
    }

    @Test fun closingTheOnlyTabClampsToZero() {
        assertEquals(0, nextActiveIndexAfterClose(oldActiveIndex = 0, removedIndex = 0, newSize = 0))
    }
}
