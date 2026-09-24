package com.projectfuture.browser.js

/**
 * A Promise implementation bounded in one specific way: callbacks run
 * synchronously at the moment a promise settles (or immediately, if it's
 * already settled when `.then()`/`.catch()` is attached), not deferred to
 * a separate microtask queue like real JS. Since this interpreter is
 * single-threaded with nothing else that could interleave, this produces
 * the same observable results for ordinary chains - it just doesn't match
 * the exact tick-by-tick ordering the spec guarantees for edge cases that
 * depend on it. `async`/`await` (see Coroutines.kt's `JsCoroutine`/
 * `Interpreter.runAsync`) are built on top of this same class: an `async`
 * function's `await` subscribes to exactly this synchronous
 * resolve/reject mechanism, and returns a `JsPromise` for its result.
 */
class JsPromise : JsObject() {
    private var state = PromiseState.PENDING
    private var settledValue: JsValue = JsUndefined
    private val onFulfilled = ArrayList<(JsValue) -> Unit>()
    private val onRejected = ArrayList<(JsValue) -> Unit>()

    fun resolve(v: JsValue) {
        if (state != PromiseState.PENDING) return
        if (v is JsPromise) {
            v.subscribe(::resolve, ::reject)
            return
        }
        state = PromiseState.FULFILLED
        settledValue = v
        val callbacks = onFulfilled.toList()
        clearCallbacks()
        for (cb in callbacks) cb(v)
    }

    fun reject(v: JsValue) {
        if (state != PromiseState.PENDING) return
        state = PromiseState.REJECTED
        settledValue = v
        val callbacks = onRejected.toList()
        clearCallbacks()
        for (cb in callbacks) cb(v)
    }

    private fun clearCallbacks() {
        onFulfilled.clear()
        onRejected.clear()
    }

    fun subscribe(fulfilled: (JsValue) -> Unit, rejected: (JsValue) -> Unit) {
        when (state) {
            PromiseState.FULFILLED -> fulfilled(settledValue)
            PromiseState.REJECTED -> rejected(settledValue)
            PromiseState.PENDING -> {
                onFulfilled.add(fulfilled)
                onRejected.add(rejected)
            }
        }
    }

    override fun get(name: String): JsValue = when (name) {
        "then" -> NativeFunction("then", 2) { interpreter, _, args -> then(interpreter, args.getOrNull(0) as? JsFunction, args.getOrNull(1) as? JsFunction) }
        "catch" -> NativeFunction("catch", 1) { interpreter, _, args -> then(interpreter, null, args.getOrNull(0) as? JsFunction) }
        "finally" -> NativeFunction("finally", 1) { interpreter, _, args ->
            val fn = args.getOrNull(0) as? JsFunction
            val next = JsPromise()
            subscribe(
                fulfilled = { v -> runCatching { fn?.call(interpreter, JsUndefined, emptyList()) }; next.resolve(v) },
                rejected = { v -> runCatching { fn?.call(interpreter, JsUndefined, emptyList()) }; next.reject(v) }
            )
            next
        }
        else -> super.get(name)
    }

    private fun then(interpreter: Interpreter, onFulfilledFn: JsFunction?, onRejectedFn: JsFunction?): JsPromise {
        val next = JsPromise()
        subscribe(
            fulfilled = { v ->
                if (onFulfilledFn == null) {
                    next.resolve(v)
                } else {
                    try {
                        next.resolve(onFulfilledFn.call(interpreter, JsUndefined, listOf(v)))
                    } catch (e: JsException) {
                        next.reject(e.value)
                    }
                }
            },
            rejected = { v ->
                if (onRejectedFn == null) {
                    next.reject(v)
                } else {
                    try {
                        next.resolve(onRejectedFn.call(interpreter, JsUndefined, listOf(v)))
                    } catch (e: JsException) {
                        next.reject(e.value)
                    }
                }
            }
        )
        return next
    }
}

private enum class PromiseState { PENDING, FULFILLED, REJECTED }

fun makePromiseCtor(): JsFunction = object : JsFunction("Promise") {
    override fun call(interpreter: Interpreter, thisArg: JsValue, args: List<JsValue>): JsValue {
        val promise = JsPromise()
        val executor = args.getOrNull(0) as? JsFunction
        if (executor != null) {
            val resolveFn = NativeFunction("resolve", 1) { _, _, a -> promise.resolve(a.getOrElse(0) { JsUndefined }); JsUndefined }
            val rejectFn = NativeFunction("reject", 1) { _, _, a -> promise.reject(a.getOrElse(0) { JsUndefined }); JsUndefined }
            try {
                executor.call(interpreter, JsUndefined, listOf(resolveFn, rejectFn))
            } catch (e: JsException) {
                promise.reject(e.value)
            }
        }
        return promise
    }

    override fun get(name: String): JsValue = when (name) {
        "resolve" -> NativeFunction("resolve", 1) { _, _, a -> JsPromise().apply { resolve(a.getOrElse(0) { JsUndefined }) } }
        "reject" -> NativeFunction("reject", 1) { _, _, a -> JsPromise().apply { reject(a.getOrElse(0) { JsUndefined }) } }
        "all" -> NativeFunction("all", 1) { _, _, a -> promiseAll(a.getOrNull(0) as? JsArray) }
        else -> super.get(name)
    }
}

private fun promiseAll(items: JsArray?): JsPromise {
    val result = JsPromise()
    val elements = items?.elements ?: emptyList()
    if (elements.isEmpty()) {
        result.resolve(JsArray(mutableListOf()))
        return result
    }
    val values = arrayOfNulls<JsValue>(elements.size)
    var remaining = elements.size
    var rejected = false
    elements.forEachIndexed { idx, v ->
        val p = v as? JsPromise
        if (p == null) {
            values[idx] = v
            remaining--
        } else {
            p.subscribe(
                fulfilled = { fv ->
                    if (!rejected) {
                        values[idx] = fv
                        remaining--
                        if (remaining == 0) result.resolve(JsArray(values.map { it ?: JsUndefined }.toMutableList()))
                    }
                },
                rejected = { rv -> if (!rejected) { rejected = true; result.reject(rv) } }
            )
        }
    }
    if (remaining == 0 && !rejected) result.resolve(JsArray(values.map { it ?: JsUndefined }.toMutableList()))
    return result
}
