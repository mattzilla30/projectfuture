package com.projectfuture.browser.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for DialogGuard, which MainActivity uses to stop a
 * rapid double-tap on a menu item (tab switcher, bookmarks, history,
 * settings, passwords, certificate-error interstitial, field/select-value
 * prompts) from stacking two AlertDialog instances. The actual
 * AlertDialog/MaterialAlertDialogBuilder wiring can't be unit-tested without
 * Robolectric, but the guard itself - the part that actually decides whether
 * a second dialog may open - is plain state and fully testable here.
 */
class DialogGuardTest {

    @Test fun firstAcquireSucceedsAndMarksTheGuardHeld() {
        val guard = DialogGuard()
        assertFalse(guard.isHeld)

        assertTrue(guard.tryAcquire())
        assertTrue(guard.isHeld)
    }

    @Test fun secondAcquireFailsWhileTheFirstIsStillHeld() {
        val guard = DialogGuard()
        assertTrue(guard.tryAcquire())

        // Simulates a rapid double-tap: the second dialog-opening call must be refused, not stack
        // a second dialog on top of the first.
        assertFalse(guard.tryAcquire())
        assertFalse(guard.tryAcquire())
        assertTrue("a failed acquire must not disturb the existing hold", guard.isHeld)
    }

    @Test fun acquireSucceedsAgainAfterRelease() {
        val guard = DialogGuard()
        assertTrue(guard.tryAcquire())

        guard.release()
        assertFalse(guard.isHeld)

        assertTrue(guard.tryAcquire())
        assertTrue(guard.isHeld)
    }

    @Test fun releaseIsSafeToCallWhenNotHeld() {
        val guard = DialogGuard()
        guard.release()
        guard.release()
        assertFalse(guard.isHeld)

        // Still acquirable afterwards - an extra release() must not corrupt later state.
        assertTrue(guard.tryAcquire())
    }
}
