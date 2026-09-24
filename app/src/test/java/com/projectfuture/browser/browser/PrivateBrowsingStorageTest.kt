package com.projectfuture.browser.browser

import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * A private tab must never fall through to the shared, disk-persisted
 * backing that every regular tab uses for `localStorage`/Service
 * Worker registrations/Cache Storage - see [tabStorageBacking]'s doc.
 */
class PrivateBrowsingStorageTest {

    @Test fun aRegularTabReusesTheSharedBackingWhenOneIsSet() {
        val shared = Any()
        val result = tabStorageBacking(isPrivate = false, shared = shared) { Any() }
        assertSame(shared, result)
    }

    @Test fun aPrivateTabNeverUsesTheSharedBackingEvenWhenOneIsSet() {
        val shared = Any()
        val result = tabStorageBacking(isPrivate = true, shared = shared) { Any() }
        assertNotSame("a private tab must not touch the shared/persisted backing", shared, result)
    }

    @Test fun twoPrivateTabsDoNotShareTheSameFreshBackingWithEachOther() {
        val shared = Any()
        val first = tabStorageBacking(isPrivate = true, shared = shared) { Any() }
        val second = tabStorageBacking(isPrivate = true, shared = shared) { Any() }
        assertNotSame(first, second)
    }

    @Test fun aRegularTabFallsBackToFreshWhenNothingIsSharedYet() {
        val result = tabStorageBacking<Any?>(isPrivate = false, shared = null) { "fresh" }
        assertSame("fresh", result)
    }
}
