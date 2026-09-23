package com.projectfuture.browser.browser

import android.content.Context

/**
 * Persists the set of open (non-private) tabs - just their current URLs
 * and which one was active - so they survive the app being killed and
 * relaunched, matching how mobile browsers restore your tabs rather than
 * starting fresh every time. Private tabs are never included, matching
 * real incognito semantics (nothing about them should outlive the
 * session). This is a session of plain URLs, not a true suspend/resume
 * snapshot: restoring re-navigates each tab to its last URL rather than
 * recreating scroll position, form input, or JS state.
 */
class TabSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("tab_session", Context.MODE_PRIVATE)

    fun save(urls: List<String>, activeIndex: Int) {
        prefs.edit()
            .putString(KEY_URLS, urls.joinToString("\n"))
            .putInt(KEY_ACTIVE, activeIndex)
            .apply()
    }

    /** Returns the saved tab URLs and which index was active (0 if unknown/out of range). */
    fun load(): Pair<List<String>, Int> {
        val raw = prefs.getString(KEY_URLS, null)
        val urls = if (raw.isNullOrBlank()) emptyList() else raw.split("\n").filter { it.isNotBlank() }
        val active = prefs.getInt(KEY_ACTIVE, 0).coerceIn(0, (urls.size - 1).coerceAtLeast(0))
        return urls to active
    }

    companion object {
        private const val KEY_URLS = "urls"
        private const val KEY_ACTIVE = "active"
    }
}
