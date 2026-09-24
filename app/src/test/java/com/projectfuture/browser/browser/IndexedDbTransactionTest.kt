package com.projectfuture.browser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Before this test existed, `Tab.kt`'s `db.transaction(...)` wrapper built a
 * `tx` object with an `objectStore()` method but never fired `oncomplete`/
 * `onabort` at all - a script's `tx.oncomplete = ...` was dead code. These
 * tests exercise the pure event-sequencing logic ([IndexedDbTransactionTracker])
 * that now drives that firing.
 */
class IndexedDbTransactionTest {

    @Test fun aTransactionWithNoRequestsCompletesOnceFinalized() {
        val tracker = IndexedDbTransactionTracker()
        // A script did `db.transaction("store")` but never actually issued
        // a get/put/etc - real IndexedDB still fires oncomplete for this.
        assertEquals(TransactionOutcome.COMPLETE, tracker.finalizeIfIdle())
    }

    @Test fun doesNotCompleteWhileARequestIsStillInFlight() {
        val tracker = IndexedDbTransactionTracker()
        tracker.requestStarted()
        // Finalize runs (synchronous setup done) before the deferred
        // request's callback has fired - must not complete yet.
        assertNull(tracker.finalizeIfIdle())
    }

    @Test fun completesOnlyAfterEveryIssuedRequestSettles() {
        val tracker = IndexedDbTransactionTracker()
        tracker.requestStarted()
        tracker.requestStarted()
        assertNull(tracker.finalizeIfIdle())
        assertNull(tracker.requestFinished(success = true)) // one of two still pending
        assertEquals(TransactionOutcome.COMPLETE, tracker.requestFinished(success = true))
    }

    @Test fun aFailedRequestAbortsTheTransactionInsteadOfCompletingIt() {
        val tracker = IndexedDbTransactionTracker()
        tracker.requestStarted()
        tracker.finalizeIfIdle()
        assertEquals(TransactionOutcome.ABORT, tracker.requestFinished(success = false))
    }

    @Test fun oneFailedRequestAbortsEvenIfOtherRequestsInTheSameTransactionSucceeded() {
        val tracker = IndexedDbTransactionTracker()
        tracker.requestStarted()
        tracker.requestStarted()
        tracker.finalizeIfIdle()
        assertNull(tracker.requestFinished(success = true))
        assertEquals(TransactionOutcome.ABORT, tracker.requestFinished(success = false))
    }

    @Test fun theOutcomeFiresOnlyOnceEvenIfCalledAgainAfterSettling() {
        val tracker = IndexedDbTransactionTracker()
        assertEquals(TransactionOutcome.COMPLETE, tracker.finalizeIfIdle())
        assertNull(tracker.finalizeIfIdle())
    }

    @Test fun orderIndependentFinalizeAfterRequestAlreadySettled() {
        // Guards against a naive implementation that only checks "pending == 0"
        // at requestFinished time and ignores finalize ordering.
        val tracker = IndexedDbTransactionTracker()
        tracker.requestStarted()
        assertNull(tracker.requestFinished(success = true)) // settles before finalize() has run
        assertEquals(TransactionOutcome.COMPLETE, tracker.finalizeIfIdle())
    }

    @Test fun aFreshConnectionStartsOpen() {
        val guard = IndexedDbConnectionGuard()
        assertTrue(guard.isOpen)
    }

    @Test fun closingAConnectionMarksItNotOpen() {
        val guard = IndexedDbConnectionGuard()
        guard.close()
        assertTrue(!guard.isOpen)
    }
}
