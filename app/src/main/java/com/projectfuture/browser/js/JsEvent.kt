package com.projectfuture.browser.js

/**
 * The object passed to event listeners (click/input/change/submit).
 * Supports `preventDefault()` - checked by Tab.dispatchClick to decide
 * whether a link navigation or form submission should actually happen -
 * but not `stopPropagation()` (bubbling already doesn't support that
 * anywhere in this engine, per DomBridge's class doc) or `bubbles`/
 * `cancelable` flags.
 */
class JsEvent(type: String, target: JsValue) : JsObject() {
    var defaultPrevented: Boolean = false
        private set

    /** Kotlin-only bookkeeping (not exposed to scripts): did any inline handler or listener actually run? */
    var listenersRan: Boolean = false

    init {
        set("type", JsString(type))
        set("target", target)
        set("currentTarget", target)
    }

    override fun get(name: String): JsValue = when (name) {
        "preventDefault" -> NativeFunction("preventDefault", 0) { _, _, _ -> defaultPrevented = true; JsUndefined }
        "defaultPrevented" -> JsBoolean(defaultPrevented)
        else -> super.get(name)
    }
}
