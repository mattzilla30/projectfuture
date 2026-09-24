package com.projectfuture.browser.browser

import com.projectfuture.browser.js.JsArray
import com.projectfuture.browser.js.JsObject
import com.projectfuture.browser.js.JsString
import com.projectfuture.browser.js.StorageBacking
import com.projectfuture.browser.js.jsonStringify
import com.projectfuture.browser.js.parseJsonToJsValue
import com.projectfuture.browser.js.toJsString

/** One cached response, as `caches.open(x).then(c => c.put(request, response))` would store it. */
data class CachedResponse(
    val requestUrl: String,
    val status: Int,
    val statusText: String,
    val body: String
)

/**
 * The `CacheStorage` API (`caches.open`/`.match`/`.keys`/`.delete`, and the
 * per-`Cache` `.put`/`.match`/`.delete`/`.keys`/`.addAll` it returns)
 * behind Service Workers' offline story. Like [ServiceWorkerRegistry], it's
 * origin-scoped, persisted through any [StorageBacking] (SharedPreferences
 * in production - see [sharedCacheStorage] - so entries genuinely survive
 * an app restart, which is the entire point of Cache Storage existing),
 * and has no Android dependency itself so it's directly unit-testable.
 *
 * Bounded relative to the real API: only string bodies (matching this
 * engine's fetch()/XHR, which never handle binary payloads either), no
 * `Vary` header matching, no `ignoreSearch`/`ignoreMethod` match options,
 * one entry per request URL per cache (a second `put()` for the same URL
 * replaces it, same as a real Cache would with default match options).
 */
class CacheStorageStore(private val backing: StorageBacking) {
    private fun namesKey() = "__cache_storage_names__"
    private fun entriesKey(cacheName: String) = "__cache_storage_entries__$cacheName"

    private fun loadNames(origin: String): MutableList<String> {
        val raw = backing.get(origin, namesKey()) ?: return mutableListOf()
        return try {
            (parseJsonToJsValue(raw) as? JsArray)?.elements?.map { toJsString(it) }?.toMutableList() ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun saveNames(origin: String, names: List<String>) {
        backing.set(origin, namesKey(), jsonStringify(JsArray(names.map { JsString(it) as com.projectfuture.browser.js.JsValue }.toMutableList())))
    }

    private fun loadEntries(origin: String, cacheName: String): MutableList<CachedResponse> {
        val raw = backing.get(origin, entriesKey(cacheName)) ?: return mutableListOf()
        return try {
            (parseJsonToJsValue(raw) as? JsArray)?.elements?.mapNotNull { entry ->
                val obj = entry as? JsObject ?: return@mapNotNull null
                CachedResponse(
                    requestUrl = toJsString(obj.get("requestUrl")),
                    status = toJsString(obj.get("status")).toIntOrNull() ?: 200,
                    statusText = toJsString(obj.get("statusText")),
                    body = toJsString(obj.get("body"))
                )
            }?.toMutableList() ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun saveEntries(origin: String, cacheName: String, entries: List<CachedResponse>) {
        val arr = JsArray(entries.map { e ->
            JsObject().apply {
                set("requestUrl", JsString(e.requestUrl))
                set("status", JsString(e.status.toString()))
                set("statusText", JsString(e.statusText))
                set("body", JsString(e.body))
            } as com.projectfuture.browser.js.JsValue
        }.toMutableList())
        backing.set(origin, entriesKey(cacheName), jsonStringify(arr))
    }

    /** `caches.open(name)`: ensures the named cache exists and returns its name for further calls. */
    fun open(origin: String, cacheName: String): String {
        val names = loadNames(origin)
        if (cacheName !in names) {
            names.add(cacheName)
            saveNames(origin, names)
        }
        return cacheName
    }

    fun put(origin: String, cacheName: String, requestUrl: String, status: Int, statusText: String, body: String) {
        open(origin, cacheName)
        val entries = loadEntries(origin, cacheName)
        entries.removeAll { it.requestUrl == requestUrl }
        entries.add(CachedResponse(requestUrl, status, statusText, body))
        saveEntries(origin, cacheName, entries)
    }

    fun match(origin: String, cacheName: String, requestUrl: String): CachedResponse? =
        loadEntries(origin, cacheName).firstOrNull { it.requestUrl == requestUrl }

    /** `caches.match(request)`: searches every cache for this origin, in creation order - matches the real API's no-cache-name-given form. */
    fun matchAny(origin: String, requestUrl: String): CachedResponse? {
        for (name in loadNames(origin)) {
            match(origin, name, requestUrl)?.let { return it }
        }
        return null
    }

    fun deleteEntry(origin: String, cacheName: String, requestUrl: String): Boolean {
        val entries = loadEntries(origin, cacheName)
        val removed = entries.removeAll { it.requestUrl == requestUrl }
        if (removed) saveEntries(origin, cacheName, entries)
        return removed
    }

    fun keys(origin: String, cacheName: String): List<String> = loadEntries(origin, cacheName).map { it.requestUrl }

    fun cacheNames(origin: String): List<String> = loadNames(origin)

    fun deleteCache(origin: String, cacheName: String): Boolean {
        val names = loadNames(origin)
        val removed = names.remove(cacheName)
        if (removed) {
            saveNames(origin, names)
            backing.remove(origin, entriesKey(cacheName))
        }
        return removed
    }
}
