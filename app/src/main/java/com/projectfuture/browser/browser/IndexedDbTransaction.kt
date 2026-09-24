package com.projectfuture.browser.browser

/**
 * Pure event-sequencing logic for the JS-facing `IDBTransaction` wrapper in
 * Tab.kt's `installIndexedDb` (the storage engine itself, [IndexedDbStore],
 * has no notion of a transaction - this is purely about when the JS-visible
 * `tx.oncomplete`/`tx.onabort` handlers should fire). Factored out as a
 * plain Kotlin class - no `Tab`/`Handler`/`Looper` involved - so it can be
 * unit tested directly; see [IndexedDbConnectionGuard] below for the
 * `db.close()` half of the same wrapper.
 *
 * Mirrors (a bounded subset of) real `IDBTransaction` semantics: a
 * transaction completes once every request issued through it has settled
 * (successfully or not) - including the "no requests were ever issued"
 * case, which still completes rather than hanging forever - and aborts
 * instead of completing if any of its requests failed.
 */
class IndexedDbTransactionTracker {
    private var pending = 0
    private var hadError = false
    private var finalized = false
    private var settled = false

    /** Call synchronously when a request (get/put/delete/add/getAll/clear) is issued through this transaction. */
    fun requestStarted() {
        pending++
    }

    /**
     * Call when a previously-started request finishes, successfully or not.
     * Returns the outcome to fire now, or null if the transaction is still
     * waiting (either on other in-flight requests, or because
     * [finalizeIfIdle] hasn't run yet) or has already settled.
     */
    fun requestFinished(success: Boolean): TransactionOutcome? {
        pending--
        if (!success) hadError = true
        return maybeSettle()
    }

    /**
     * Call once, after the transaction's synchronous setup (the script's
     * `tx.objectStore(...).get(...)`-style calls) has run, so a transaction
     * that never issued any request - or whose requests all already
     * finished synchronously - still completes instead of never firing.
     * Safe to call before, interleaved with, or after [requestFinished]
     * calls; the outcome only ever fires once.
     */
    fun finalizeIfIdle(): TransactionOutcome? {
        finalized = true
        return maybeSettle()
    }

    private fun maybeSettle(): TransactionOutcome? {
        if (settled) return null
        if (pending <= 0 && finalized) {
            settled = true
            return if (hadError) TransactionOutcome.ABORT else TransactionOutcome.COMPLETE
        }
        return null
    }
}

enum class TransactionOutcome { COMPLETE, ABORT }

/**
 * Tracks a single `IDBDatabase` connection's open/closed state for
 * `db.close()`. Real IndexedDB lets in-flight transactions finish after
 * `close()` and only rejects *new* ones; this bounded engine has no
 * cross-tick transaction queueing to preserve, so closing simply blocks any
 * further `db.transaction(...)` call from that connection object.
 */
class IndexedDbConnectionGuard {
    var isOpen: Boolean = true
        private set

    fun close() {
        isOpen = false
    }
}
