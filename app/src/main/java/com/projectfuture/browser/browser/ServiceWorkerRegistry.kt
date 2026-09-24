package com.projectfuture.browser.browser

import com.projectfuture.browser.js.JsArray
import com.projectfuture.browser.js.JsObject
import com.projectfuture.browser.js.JsString
import com.projectfuture.browser.js.StorageBacking
import com.projectfuture.browser.js.jsonStringify
import com.projectfuture.browser.js.parseJsonToJsValue
import com.projectfuture.browser.js.toJsString

/**
 * One `navigator.serviceWorker.register(scriptUrl, {scope})` registration.
 * [installed] tracks whether the worker's `install`/`activate` events have
 * already run for this scope - a real service worker only runs those
 * lifecycle events once ever (until the script changes), not on every
 * page load, and this flag (persisted alongside everything else here) is
 * what makes that true across app restarts too.
 */
data class ServiceWorkerRegistration(
    val scope: String,
    val scriptUrl: String,
    val installed: Boolean
)

/**
 * Persists `navigator.serviceWorker` registrations, origin-scoped, via any
 * [StorageBacking] - in production that's a SharedPreferences-backed
 * instance (see [sharedServiceWorkerStorage]), the same durability
 * mechanism `localStorage` uses (LocalStorageStore), so a registration
 * really does survive killing and reopening the app. In tests it's an
 * `InMemoryStorageBacking`, which is all this class itself depends on -
 * no Android classes here, so it's directly unit-testable on the host JVM.
 *
 * Registrations are stored as a single JSON array per origin under a
 * fixed key, reusing this engine's own hand-written JSON codec
 * (`jsonStringify`/`parseJsonToJsValue`) instead of writing a second one
 * just for persistence.
 */
class ServiceWorkerRegistry(private val backing: StorageBacking) {
    private val key = "__service_worker_registrations__"

    private fun load(origin: String): MutableList<ServiceWorkerRegistration> {
        val raw = backing.get(origin, key) ?: return mutableListOf()
        return try {
            val arr = parseJsonToJsValue(raw) as? JsArray ?: return mutableListOf()
            arr.elements.mapNotNull { entry ->
                val obj = entry as? JsObject ?: return@mapNotNull null
                val scope = toJsString(obj.get("scope"))
                val scriptUrl = toJsString(obj.get("scriptUrl"))
                val installed = toJsString(obj.get("installed")) == "true"
                ServiceWorkerRegistration(scope, scriptUrl, installed)
            }.toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun save(origin: String, regs: List<ServiceWorkerRegistration>) {
        val arr = JsArray(regs.map { reg ->
            JsObject().apply {
                set("scope", JsString(reg.scope))
                set("scriptUrl", JsString(reg.scriptUrl))
                set("installed", JsString(reg.installed.toString()))
            }
        }.toMutableList())
        backing.set(origin, key, jsonStringify(arr))
    }

    /** Registers (or re-registers, replacing the script URL of) [scope] for [origin]. Returns the resulting registration. */
    fun register(origin: String, scope: String, scriptUrl: String): ServiceWorkerRegistration {
        val regs = load(origin)
        val existing = regs.firstOrNull { it.scope == scope }
        val reg = if (existing != null && existing.scriptUrl == scriptUrl) {
            existing // same script re-registered: keep its installed flag, matching a real browser's no-op re-register
        } else {
            regs.removeAll { it.scope == scope }
            ServiceWorkerRegistration(scope, scriptUrl, installed = false)
        }
        regs.removeAll { it.scope == scope }
        regs.add(reg)
        save(origin, regs)
        return reg
    }

    fun markInstalled(origin: String, scope: String) {
        val regs = load(origin)
        val idx = regs.indexOfFirst { it.scope == scope }
        if (idx >= 0) {
            regs[idx] = regs[idx].copy(installed = true)
            save(origin, regs)
        }
    }

    fun unregister(origin: String, scope: String): Boolean {
        val regs = load(origin)
        val removed = regs.removeAll { it.scope == scope }
        if (removed) save(origin, regs)
        return removed
    }

    fun all(origin: String): List<ServiceWorkerRegistration> = load(origin)

    /**
     * The registration that controls [pagePath] under [origin]: the
     * longest matching `scope` prefix, matching the real spec's
     * "most specific scope wins" rule.
     */
    fun findControlling(origin: String, pagePath: String): ServiceWorkerRegistration? =
        load(origin).filter { pagePath.startsWith(it.scope) }.maxByOrNull { it.scope.length }
}
