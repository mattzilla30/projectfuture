package com.projectfuture.browser.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the dark-mode persistence bug: `darkModeEnabled`
 * used to live only as a plain in-memory MainActivity field, so it silently
 * reset to off on every cold start even if the user had turned it on last
 * session. It must round-trip through SharedPreferences the same way
 * `sandboxedTabsEnabled`/`trackingProtectionEnabled` already do.
 */
class SettingsTest {

    @Test fun darkModeDefaultsToFalseOnACleanInstall() {
        val settings = Settings(FakePrefsContext())
        assertFalse(settings.darkModeEnabled)
    }

    @Test fun darkModeEnabledRoundTripsThroughSharedPreferences() {
        val ctx = FakePrefsContext()
        val settings = Settings(ctx)

        settings.darkModeEnabled = true
        assertTrue(settings.darkModeEnabled)
        // A fresh Settings instance over the same backing must also see the persisted value -
        // this is the exact scenario a cold app restart hits.
        assertTrue("dark mode should survive a fresh Settings instance over the same prefs", Settings(ctx).darkModeEnabled)

        settings.darkModeEnabled = false
        assertFalse(settings.darkModeEnabled)
        assertFalse(Settings(ctx).darkModeEnabled)
    }
}
