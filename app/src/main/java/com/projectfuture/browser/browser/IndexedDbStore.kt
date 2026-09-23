package com.projectfuture.browser.browser

import com.projectfuture.browser.js.JsValue

/**
 * The storage engine behind `indexedDB` (see Tab.kt's installIndexedDb for
 * the JS-facing request/event wiring). A bounded, session-only subset:
 * - Never persisted - unlike real IndexedDB, nothing here survives the
 *   tab being closed or the app restarting. Values are held as live
 *   JsValue references (no serialization at all), which is what makes
 *   storing arbitrary structured values trivial but is also exactly why
 *   persistence isn't attempted - there's no JsValue-to-bytes codec in
 *   this engine to persist them with.
 * - Per-tab, not per-origin: real IndexedDB is shared across every tab of
 *   the same origin; this engine's instance lives on one Tab, matching
 *   `sessionStorage`'s isolation instead.
 * - No indexes, no cursors, no key ranges, no versioned migrations beyond
 *   a single first-open `onupgradeneeded` - just named object stores
 *   holding key -> value pairs, enough for the common "structured local
 *   cache" use case.
 */
class IndexedDbStore {
    private val databases = HashMap<String, HashMap<String, LinkedHashMap<String, JsValue>>>()
    private val everOpened = HashSet<String>()

    /** True the first time [name] is opened this session - the caller fires `onupgradeneeded` exactly then. */
    fun isFirstOpen(name: String): Boolean = everOpened.add(name)

    fun createObjectStore(dbName: String, storeName: String) {
        databases.getOrPut(dbName) { HashMap() }.getOrPut(storeName) { LinkedHashMap() }
    }

    fun put(dbName: String, storeName: String, key: String, value: JsValue) {
        databases.getOrPut(dbName) { HashMap() }.getOrPut(storeName) { LinkedHashMap() }[key] = value
    }

    fun get(dbName: String, storeName: String, key: String): JsValue? = databases[dbName]?.get(storeName)?.get(key)

    fun delete(dbName: String, storeName: String, key: String) {
        databases[dbName]?.get(storeName)?.remove(key)
    }

    fun getAll(dbName: String, storeName: String): List<JsValue> = databases[dbName]?.get(storeName)?.values?.toList() ?: emptyList()

    fun clear(dbName: String, storeName: String) {
        databases[dbName]?.get(storeName)?.clear()
    }
}
