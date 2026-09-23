package com.projectfuture.browser.browser

import android.content.Context

/**
 * The handful of user-configurable preferences this browser exposes:
 * home page and default search engine template (a URL with `%s` standing
 * in for the URL-encoded query, matching Url.fromAddressBar's existing
 * parameter). Persisted via SharedPreferences with the same lightweight
 * approach as HistoryStore/BookmarkStore/CookieJar - no dedicated settings
 * screen framework (PreferenceScreen/PreferenceFragment), just two fields
 * read/written directly, since that's all there currently is to configure.
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var homePage: String
        get() = prefs.getString(KEY_HOME_PAGE, DEFAULT_HOME_PAGE) ?: DEFAULT_HOME_PAGE
        set(value) = prefs.edit().putString(KEY_HOME_PAGE, value.ifBlank { DEFAULT_HOME_PAGE }).apply()

    var searchTemplate: String
        get() = prefs.getString(KEY_SEARCH_TEMPLATE, DEFAULT_SEARCH_TEMPLATE) ?: DEFAULT_SEARCH_TEMPLATE
        set(value) = prefs.edit().putString(KEY_SEARCH_TEMPLATE, value.ifBlank { DEFAULT_SEARCH_TEMPLATE }).apply()

    var trackingProtectionEnabled: Boolean
        get() = prefs.getBoolean(KEY_TRACKING_PROTECTION, false)
        set(value) = prefs.edit().putBoolean(KEY_TRACKING_PROTECTION, value).apply()

    companion object {
        private const val KEY_HOME_PAGE = "home_page"
        private const val KEY_SEARCH_TEMPLATE = "search_template"
        private const val KEY_TRACKING_PROTECTION = "tracking_protection"
        const val DEFAULT_HOME_PAGE = "https://example.com/"
        const val DEFAULT_SEARCH_TEMPLATE = "https://duckduckgo.com/html/?q=%s"
    }
}
