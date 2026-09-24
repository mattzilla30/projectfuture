package com.projectfuture.browser.browser

import com.projectfuture.browser.js.Interpreter
import com.projectfuture.browser.js.JsArray
import com.projectfuture.browser.js.JsBoolean
import com.projectfuture.browser.js.JsFunction
import com.projectfuture.browser.js.JsNumber
import com.projectfuture.browser.js.JsObject
import com.projectfuture.browser.js.JsPromise
import com.projectfuture.browser.js.JsString
import com.projectfuture.browser.js.JsUndefined
import com.projectfuture.browser.js.JsValue
import com.projectfuture.browser.js.Lexer
import com.projectfuture.browser.js.NativeFunction
import com.projectfuture.browser.js.Parser
import com.projectfuture.browser.js.parseJsonToJsValue
import com.projectfuture.browser.js.toJsString
import com.projectfuture.browser.js.toNumber

/** A `Response`-shaped value, in and out of Cache Storage and `event.respondWith()`. */
data class SwResponse(val status: Int = 200, val statusText: String = "OK", val body: String = "")

/**
 * Runs one Service Worker's script against a fresh [Interpreter] to
 * dispatch `install`/`activate`/`fetch` events - the same "genuinely
 * separate JS execution context" idea as `installWorker` in Tab.kt, with
 * one deliberate difference explained below.
 *
 * A real browser keeps one running worker process alive for a service
 * worker's whole (possibly long) lifetime, dispatching events into that
 * same running context, and only terminates/respawns it after an idle
 * timeout. This engine has no background process that survives past a
 * single Kotlin method call (there's no OS-level service, and the JS
 * interpreter itself holds no state worth keeping alive - see Tab.kt's
 * Worker for the one case that *does* keep a thread alive, for the
 * duration of a page). So instead, every dispatch here - `install`,
 * `activate`, and each intercepted `fetch` - re-parses and re-runs the
 * worker script from scratch in a brand new `Interpreter`, registers its
 * `self.addEventListener` handlers, fires exactly one event at it, and
 * discards the interpreter. This is honest about what it does and
 * doesn't provide: any state a script keeps in its own top-level
 * variables between events (a request counter, an in-memory cache map)
 * is NOT preserved across dispatches - only `caches` (backed by
 * [CacheStorageStore]) and the registry's `installed` flag persist. For
 * the standard "install: warm the cache; fetch: serve from cache" pattern
 * this is functionally indistinguishable from a real service worker,
 * which is by far the dominant real-world use case.
 *
 * `install`/`activate` only ever run once per scope (gated by
 * [ServiceWorkerRegistry.installed], persisted so a killed-and-reopened
 * app doesn't re-run them) - matching the real spec's one-time lifecycle.
 * `event.waitUntil(promise)` is accepted for API compatibility, but since
 * every promise this engine's own APIs produce settles synchronously
 * (see Promise.kt's class doc), there is nothing left pending to actually
 * await by the time the listener returns - this is a real limitation
 * versus the spec (a script whose install handler waited on a *pending*
 * promise across a Kotlin-side callback boundary wouldn't be waited for),
 * but it is not a gap this engine's `fetch`/`caches` APIs can ever
 * trigger, since none of them are backed by anything that stays pending
 * past the call that created the promise.
 */
class ServiceWorkerHost(
    private val scriptCode: String,
    private val origin: String,
    private val scope: String,
    private val registry: ServiceWorkerRegistry,
    private val cacheStore: CacheStorageStore,
    /** Real network access for `self.fetch()`/`cache.addAll()` inside the worker - injected so this class stays unit-testable without real sockets. Returns null on failure. */
    private val networkFetcher: (String) -> SwResponse? = { null }
) {
    private fun requestUrlFrom(v: JsValue): String = when (v) {
        is JsObject -> v.get("url").let { if (it == JsUndefined) toJsString(v) else toJsString(it) }
        else -> toJsString(v)
    }

    private fun jsToSwResponse(v: JsValue): SwResponse? {
        val obj = v as? JsObject ?: return null
        val bodyVal = obj.get("body")
        val body = if (bodyVal is JsString) bodyVal.value else if (bodyVal == JsUndefined) "" else toJsString(bodyVal)
        val statusVal = obj.get("status")
        val status = if (statusVal is JsNumber) statusVal.value.toInt() else 200
        val statusText = toJsString(obj.get("statusText")).ifBlank { "OK" }
        return SwResponse(status, statusText, body)
    }

    private fun makeResponseObject(sw: SwResponse): JsObject {
        val o = JsObject()
        o.set("status", JsNumber(sw.status.toDouble()))
        o.set("statusText", JsString(sw.statusText))
        o.set("ok", JsBoolean(sw.status in 200..299))
        o.set("body", JsString(sw.body))
        o.set("text", NativeFunction("text", 0) { _, _, _ -> JsPromise().apply { resolve(JsString(sw.body)) } })
        o.set("json", NativeFunction("json", 0) { _, _, _ ->
            JsPromise().apply {
                try { resolve(parseJsonToJsValue(sw.body)) } catch (_: Exception) { reject(JsString("Invalid JSON")) }
            }
        })
        o.set("clone", NativeFunction("clone", 0) { _, _, _ -> makeResponseObject(sw) })
        return o
    }

    private fun makeCacheObject(cacheName: String): JsObject {
        val cache = JsObject()
        cache.set("put", NativeFunction("put", 2) { _, _, args ->
            val reqUrl = requestUrlFrom(args.getOrElse(0) { JsUndefined })
            val sw = jsToSwResponse(args.getOrElse(1) { JsUndefined }) ?: SwResponse()
            cacheStore.put(origin, cacheName, reqUrl, sw.status, sw.statusText, sw.body)
            JsPromise().apply { resolve(JsUndefined) }
        })
        cache.set("match", NativeFunction("match", 1) { _, _, args ->
            val reqUrl = requestUrlFrom(args.getOrElse(0) { JsUndefined })
            val entry = cacheStore.match(origin, cacheName, reqUrl)
            JsPromise().apply { resolve(entry?.let { makeResponseObject(SwResponse(it.status, it.statusText, it.body)) } ?: JsUndefined) }
        })
        cache.set("delete", NativeFunction("delete", 1) { _, _, args ->
            val reqUrl = requestUrlFrom(args.getOrElse(0) { JsUndefined })
            JsPromise().apply { resolve(JsBoolean(cacheStore.deleteEntry(origin, cacheName, reqUrl))) }
        })
        cache.set("keys", NativeFunction("keys", 0) { _, _, _ ->
            JsPromise().apply {
                resolve(JsArray(cacheStore.keys(origin, cacheName).map { JsObject().apply { set("url", JsString(it)) } as JsValue }.toMutableList()))
            }
        })
        cache.set("addAll", NativeFunction("addAll", 1) { _, _, args ->
            (args.getOrNull(0) as? JsArray)?.elements?.forEach { u ->
                val url = toJsString(u)
                networkFetcher(url)?.let { cacheStore.put(origin, cacheName, url, it.status, it.statusText, it.body) }
            }
            JsPromise().apply { resolve(JsUndefined) }
        })
        return cache
    }

    private fun makeCachesObject(): JsObject {
        val caches = JsObject()
        caches.set("open", NativeFunction("open", 1) { _, _, args ->
            val name = toJsString(args.getOrElse(0) { JsUndefined })
            cacheStore.open(origin, name)
            JsPromise().apply { resolve(makeCacheObject(name)) }
        })
        caches.set("match", NativeFunction("match", 1) { _, _, args ->
            val reqUrl = requestUrlFrom(args.getOrElse(0) { JsUndefined })
            val entry = cacheStore.matchAny(origin, reqUrl)
            JsPromise().apply { resolve(entry?.let { makeResponseObject(SwResponse(it.status, it.statusText, it.body)) } ?: JsUndefined) }
        })
        caches.set("delete", NativeFunction("delete", 1) { _, _, args ->
            JsPromise().apply { resolve(JsBoolean(cacheStore.deleteCache(origin, toJsString(args.getOrElse(0) { JsUndefined })))) }
        })
        caches.set("keys", NativeFunction("keys", 0) { _, _, _ ->
            JsPromise().apply { resolve(JsArray(cacheStore.cacheNames(origin).map { JsString(it) as JsValue }.toMutableList())) }
        })
        return caches
    }

    private fun makeFetchFn(): NativeFunction = NativeFunction("fetch", 1) { _, _, args ->
        val url = toJsString(args.getOrElse(0) { JsUndefined })
        val promise = JsPromise()
        val resp = networkFetcher(url)
        if (resp != null) promise.resolve(makeResponseObject(resp)) else promise.reject(JsString("Failed to fetch '$url'"))
        promise
    }

    /** Builds a fresh interpreter with `self`/`addEventListener`/`caches`/`fetch`/`Response` declared, and runs [scriptCode] in it to register listeners. Returns the listener map, or null if the script failed to parse/run. */
    private fun prepare(): Pair<Interpreter, Map<String, List<JsFunction>>>? {
        val interpreter = Interpreter()
        val listeners = HashMap<String, MutableList<JsFunction>>()
        val addListener = NativeFunction("addEventListener", 2) { _, _, args ->
            val type = toJsString(args.getOrElse(0) { JsUndefined })
            (args.getOrElse(1) { JsUndefined } as? JsFunction)?.let { listeners.getOrPut(type) { mutableListOf() }.add(it) }
            JsUndefined
        }
        val cachesObj = makeCachesObject()
        val fetchFn = makeFetchFn()
        val responseCtor = NativeFunction("Response", 2) { _, _, args ->
            val body = if (args.isEmpty() || args[0] == JsUndefined) "" else toJsString(args[0])
            val init = args.getOrNull(1) as? JsObject
            val status = init?.get("status")?.takeIf { it != JsUndefined }?.let { toNumber(it).toInt() } ?: 200
            val statusText = init?.get("statusText")?.takeIf { it != JsUndefined }?.let { toJsString(it) } ?: "OK"
            makeResponseObject(SwResponse(status, statusText, body))
        }

        val selfObj = JsObject()
        selfObj.set("addEventListener", addListener)
        selfObj.set("caches", cachesObj)
        selfObj.set("fetch", fetchFn)
        selfObj.set("skipWaiting", NativeFunction("skipWaiting", 0) { _, _, _ -> JsPromise().apply { resolve(JsUndefined) } })
        val clientsObj = JsObject()
        clientsObj.set("claim", NativeFunction("claim", 0) { _, _, _ -> JsPromise().apply { resolve(JsUndefined) } })
        selfObj.set("clients", clientsObj)
        selfObj.set("registration", JsObject().apply { set("scope", JsString(scope)) })

        interpreter.globalEnv.declare("self", selfObj)
        interpreter.globalEnv.declare("addEventListener", addListener)
        interpreter.globalEnv.declare("caches", cachesObj)
        interpreter.globalEnv.declare("fetch", fetchFn)
        interpreter.globalEnv.declare("Response", responseCtor)

        return try {
            interpreter.run(Parser(Lexer(scriptCode).tokenize()).parseProgram())
            interpreter to listeners
        } catch (_: Exception) {
            null
        }
    }

    private fun dispatchLifecycle(type: String) {
        val (interpreter, listeners) = prepare() ?: return
        val event = JsObject()
        event.set("waitUntil", NativeFunction("waitUntil", 1) { _, _, _ -> JsUndefined })
        for (fn in listeners[type].orEmpty()) {
            try { fn.call(interpreter, JsUndefined, listOf(event)) } catch (_: Exception) { }
        }
    }

    /** Runs `install` then `activate` exactly once ever for this scope (persisted via [registry]); a no-op on every later call. */
    fun ensureInstalledAndActivated() {
        val reg = registry.all(origin).firstOrNull { it.scope == scope } ?: return
        if (reg.installed) return
        dispatchLifecycle("install")
        dispatchLifecycle("activate")
        registry.markInstalled(origin, scope)
    }

    /**
     * Dispatches a `fetch` event for [requestUrl]/[method]. Returns the
     * response the worker produced via `event.respondWith(...)`, or null
     * if no `fetch` listener called `respondWith` (the caller should then
     * fall through to a real network request, matching the spec's
     * default "network passthrough" behavior for an unhandled fetch
     * event). Only GET is intercepted, matching this engine's other
     * cache-eligible-request subset (see Url.fetch's HttpCache gating).
     */
    fun handleFetch(requestUrl: String, method: String): SwResponse? {
        if (!method.equals("GET", ignoreCase = true)) return null
        ensureInstalledAndActivated()
        val (interpreter, listeners) = prepare() ?: return null
        val fetchListeners = listeners["fetch"].orEmpty()
        if (fetchListeners.isEmpty()) return null

        var handled = false
        var result: SwResponse? = null
        val requestObj = JsObject().apply { set("url", JsString(requestUrl)); set("method", JsString(method)) }
        val event = JsObject()
        event.set("request", requestObj)
        event.set("respondWith", NativeFunction("respondWith", 1) { _, _, args ->
            handled = true
            when (val arg = args.getOrElse(0) { JsUndefined }) {
                is JsPromise -> arg.subscribe(fulfilled = { v -> result = jsToSwResponse(v) }, rejected = { })
                else -> result = jsToSwResponse(arg)
            }
            JsUndefined
        })
        for (fn in fetchListeners) {
            try { fn.call(interpreter, JsUndefined, listOf(event)) } catch (_: Exception) { }
        }
        return if (handled) result else null
    }
}
