package com.projectfuture.browser.browser

import android.content.Context
import com.projectfuture.browser.js.StorageBacking

/**
 * `window.localStorage` backing: a flat, origin-scoped key-value store
 * persisted via SharedPreferences (same lightweight approach as
 * HistoryStore/BookmarkStore/CookieJar), shared across every tab -
 * matching the real spec, where localStorage is per-origin, not per-tab
 * (unlike `sessionStorage`, which is per-tab - see Tab.kt's own
 * InMemoryStorageBacking instance). No storage quota enforcement.
 */
class LocalStorageStore(context: Context) : StorageBacking {
    private val prefs = context.getSharedPreferences("local_storage", Context.MODE_PRIVATE)

    private fun prefKey(origin: String, key: String) = "$origin\u0000$key"

    override fun get(origin: String, key: String): String? = prefs.getString(prefKey(origin, key), null)

    override fun set(origin: String, key: String, value: String) {
        prefs.edit().putString(prefKey(origin, key), value).apply()
    }

    override fun remove(origin: String, key: String) {
        prefs.edit().remove(prefKey(origin, key)).apply()
    }

    override fun clearAll(origin: String) {
        val prefix = "$origin\u0000"
        val editor = prefs.edit()
        for (k in prefs.all.keys) if (k.startsWith(prefix)) editor.remove(k)
        editor.apply()
    }

    override fun keys(origin: String): List<String> {
        val prefix = "$origin\u0000"
        return prefs.all.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
    }
}

/** Module-level holder, same pattern as sharedCookieJar - set once by MainActivity. */
var sharedLocalStorage: LocalStorageStore? = null
