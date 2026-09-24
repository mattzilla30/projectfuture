package com.projectfuture.browser.browser

import android.content.Context
import com.projectfuture.browser.js.StorageBacking

/**
 * A generic origin-scoped [StorageBacking] over Android SharedPreferences -
 * the same lightweight persistence approach as [LocalStorageStore]/
 * HistoryStore/BookmarkStore/CookieJar. Used for both the Service Worker
 * registry and Cache Storage (see [sharedServiceWorkerStorage]/
 * [sharedCacheStorage] below) so that a `navigator.serviceWorker.register()`
 * call and anything a service worker put in `caches` are still there the
 * next time the app is opened, not just for the rest of this process.
 */
class SharedPrefsStorageBacking(context: Context, prefsName: String) : StorageBacking {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

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

/** Module-level holders, same pattern as [sharedLocalStorage] - set once by MainActivity. */
var sharedServiceWorkerRegistry: ServiceWorkerRegistry? = null
var sharedCacheStorageStore: CacheStorageStore? = null
