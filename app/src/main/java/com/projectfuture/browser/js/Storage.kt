package com.projectfuture.browser.js

/** Backing for a Web Storage object (`localStorage`/`sessionStorage`), keyed by origin string. */
interface StorageBacking {
    fun get(origin: String, key: String): String?
    fun set(origin: String, key: String, value: String)
    fun remove(origin: String, key: String)
    fun clearAll(origin: String)
    fun keys(origin: String): List<String>
}

/** A plain in-memory StorageBacking - what `sessionStorage` uses, one instance per tab (see Tab.kt), never persisted. */
class InMemoryStorageBacking : StorageBacking {
    private val data = HashMap<String, LinkedHashMap<String, String>>()
    override fun get(origin: String, key: String): String? = data[origin]?.get(key)
    override fun set(origin: String, key: String, value: String) {
        data.getOrPut(origin) { LinkedHashMap() }[key] = value
    }
    override fun remove(origin: String, key: String) {
        data[origin]?.remove(key)
    }
    override fun clearAll(origin: String) {
        data[origin]?.clear()
    }
    override fun keys(origin: String): List<String> = data[origin]?.keys?.toList() ?: emptyList()
}

/**
 * The `window.localStorage`/`window.sessionStorage` object: the standard
 * `getItem`/`setItem`/`removeItem`/`clear`/`key`/`length` methods, plus
 * fallback property-style access (`storage.foo = 'bar'`, `storage.foo`)
 * routed to the same backing - a common real-world usage pattern beyond
 * the official method-based API. No storage quota enforcement and no
 * `storage` event (cross-document notification when another
 * tab/document changes the same origin's storage).
 */
class JsStorage(private val origin: String, private val backing: StorageBacking) : JsObject() {
    override fun get(name: String): JsValue = when (name) {
        "getItem" -> NativeFunction("getItem", 1) { _, _, args ->
            backing.get(origin, toJsString(args.getOrElse(0) { JsUndefined }))?.let { JsString(it) } ?: JsNull
        }
        "setItem" -> NativeFunction("setItem", 2) { _, _, args ->
            backing.set(origin, toJsString(args.getOrElse(0) { JsUndefined }), toJsString(args.getOrElse(1) { JsUndefined }))
            JsUndefined
        }
        "removeItem" -> NativeFunction("removeItem", 1) { _, _, args ->
            backing.remove(origin, toJsString(args.getOrElse(0) { JsUndefined }))
            JsUndefined
        }
        "clear" -> NativeFunction("clear", 0) { _, _, _ -> backing.clearAll(origin); JsUndefined }
        "key" -> NativeFunction("key", 1) { _, _, args ->
            backing.keys(origin).getOrNull(toNumber(args.getOrElse(0) { JsUndefined }).toInt())?.let { JsString(it) } ?: JsNull
        }
        "length" -> JsNumber(backing.keys(origin).size.toDouble())
        else -> backing.get(origin, name)?.let { JsString(it) } ?: JsUndefined
    }

    override fun set(name: String, value: JsValue) {
        backing.set(origin, name, toJsString(value))
    }

    override fun has(name: String): Boolean = backing.get(origin, name) != null
}
