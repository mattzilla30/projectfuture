package com.projectfuture.browser.browser

/**
 * A simple non-reentrant "is a dialog already showing" guard.
 *
 * MainActivity has many menu items that build and show a
 * `MaterialAlertDialogBuilder`-based dialog (tab switcher, bookmarks,
 * history, settings, passwords, certificate-error interstitial, the
 * field-value edit dialog, the select-value dialog, ...). Without a guard,
 * a rapid double-tap on one of those entries - or two different entries hit
 * in quick succession - can run the handler twice before the first dialog
 * has a chance to actually appear and intercept further input, stacking two
 * (or more) dialog instances on screen at once.
 *
 * Usage: each dialog-opening entry point calls [tryAcquire] before building
 * its dialog, bailing out (showing nothing) if it returns `false`, and calls
 * [release] from the dialog's dismiss listener so a later tap can open a new
 * one. This one instance covers every dialog-opening entry point at once
 * (rather than per-dialog-type flags), matching the actual UX requirement:
 * at most one MainActivity-owned dialog showing at a time.
 *
 * Not thread-safe by design - it's only ever touched from the main/UI
 * thread, same as the `AlertDialog`s it guards, so no synchronization is
 * needed or attempted.
 */
class DialogGuard {
    private var held = false

    /**
     * Attempts to acquire the guard. Returns `true` (and marks the guard
     * held) if it was free; returns `false` without any side effect if a
     * dialog opened through this guard is already showing.
     */
    fun tryAcquire(): Boolean {
        if (held) return false
        held = true
        return true
    }

    /** Releases the guard so a future [tryAcquire] can succeed again. Safe to call even when not currently held. */
    fun release() {
        held = false
    }

    /** Whether the guard is currently held (a dialog is presumed showing). */
    val isHeld: Boolean get() = held
}
