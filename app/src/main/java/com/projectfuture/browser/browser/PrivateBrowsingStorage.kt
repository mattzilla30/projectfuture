package com.projectfuture.browser.browser

/**
 * Picks which backing store a piece of per-origin state (`localStorage`,
 * Service Worker registrations, Cache Storage) should use for a tab.
 *
 * A **private** tab must never fall through to [shared] - the app-wide,
 * disk-persisted instance every regular tab reads and writes - since doing
 * so would both leak the private tab's data into every regular tab's view
 * of that same origin and leave it sitting on disk after the private tab
 * closes, which is exactly the persistence private browsing promises not
 * to have. It always gets a throwaway instance from [fresh] instead. A
 * regular tab uses [shared] if it's been set up (see `MainActivity`'s
 * doc for [sharedLocalStorage] et al.), falling back to [fresh] itself
 * only when it hasn't (e.g. a test harness that builds a [Tab] directly).
 *
 * Extracted as its own pure function - like `FormSubmission.kt`'s
 * `collectFormParams` - so the private/shared selection can be exercised
 * directly on the host JVM; [Tab] itself can't be constructed in a plain
 * JUnit test (see that file's doc).
 */
fun <T> tabStorageBacking(isPrivate: Boolean, shared: T?, fresh: () -> T): T =
    if (isPrivate) fresh() else shared ?: fresh()
