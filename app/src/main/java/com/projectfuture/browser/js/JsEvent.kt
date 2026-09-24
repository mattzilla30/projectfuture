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

    /** Set by `stopPropagation()`; DomBridge stops bubbling to further ancestors once it's true. */
    var propagationStopped: Boolean = false

    override fun get(name: String): JsValue = when (name) {
        "preventDefault" -> NativeFunction("preventDefault", 0) { _, _, _ -> defaultPrevented = true; JsUndefined }
        "stopPropagation", "stopImmediatePropagation" -> NativeFunction(name, 0) { _, _, _ -> propagationStopped = true; JsUndefined }
        "composedPath" -> NativeFunction("composedPath", 0) { _, _, _ -> JsArray(mutableListOf(super.get("target"))) }
        "defaultPrevented" -> JsBoolean(defaultPrevented)
        "bubbles", "cancelable" -> super.get(name).takeIf { it != JsUndefined } ?: JsBoolean(true)
        "timeStamp" -> JsNumber(System.currentTimeMillis().toDouble())
        else -> super.get(name)
    }
}
