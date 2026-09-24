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

    /**
     * Whether new tabs run their engine in a pooled sandboxed process
     * ([com.projectfuture.browser.ipc.RemoteTabHandle]) or in-process
     * ([LocalTabHandle]) - see `TabManager.newTab`'s doc. Defaults to
     * `true`: the sandboxed path is the real default this browser ships
     * with now, not an opt-in demo. The in-process fallback stays
     * reachable (flip this off) for the one case where IPC latency is a
     * bad fit noted in `TabHandle`'s/`TabEngineClient`'s docs, and simply
     * as an escape hatch if the sandboxed path ever regresses on a device
     * this environment can't test against (no emulator is available here
     * - see the top-level README/report for that standing caveat).
     */
    var sandboxedTabsEnabled: Boolean
        get() = prefs.getBoolean(KEY_SANDBOXED_TABS, true)
        set(value) = prefs.edit().putBoolean(KEY_SANDBOXED_TABS, value).apply()

    companion object {
        private const val KEY_HOME_PAGE = "home_page"
        private const val KEY_SEARCH_TEMPLATE = "search_template"
        private const val KEY_TRACKING_PROTECTION = "tracking_protection"
        private const val KEY_SANDBOXED_TABS = "sandboxed_tabs_enabled"
        const val DEFAULT_HOME_PAGE = "https://example.com/"
        const val DEFAULT_SEARCH_TEMPLATE = "https://duckduckgo.com/html/?q=%s"
    }
}
