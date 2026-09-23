package com.projectfuture.browser.js

/**
 * The JS value model for this interpreter. Objects are flat property bags
 * (no prototype chain) - built-in methods on arrays/strings/etc. are
 * special-cased in the interpreter's member/call evaluation instead of
 * living on a prototype object, which is a deliberate simplification: it
 * covers normal method calls (`arr.push(x)`, `"a".toUpperCase()`) without
 * needing to model prototypal inheritance, `Object.create`, or
 * `__proto__` at all.
 */
sealed class JsValue

object JsUndefined : JsValue() {
    override fun toString() = "undefined"
}

object JsNull : JsValue() {
    override fun toString() = "null"
}

data class JsBoolean(val value: Boolean) : JsValue()
data class JsNumber(val value: Double) : JsValue()
data class JsString(val value: String) : JsValue()

open class JsObject(val properties: MutableMap<String, JsValue> = LinkedHashMap()) : JsValue() {
    open fun get(name: String): JsValue = properties[name] ?: JsUndefined
    open fun set(name: String, value: JsValue) {
        properties[name] = value
    }
    open fun has(name: String): Boolean = properties.containsKey(name)
    open fun ownKeys(): List<String> = properties.keys.toList()

    /** Kotlin-only bookkeeping for `instanceof` on `class`-declared instances - see ClassConstructor in Interpreter.kt. Empty for ordinary objects. */
    var classChain: List<JsFunction> = emptyList()
}

class JsArray(val elements: MutableList<JsValue> = ArrayList()) : JsObject() {
    override fun get(name: String): JsValue {
        if (name == "length") return JsNumber(elements.size.toDouble())
        name.toIntOrNull()?.let { idx -> return elements.getOrNull(idx) ?: JsUndefined }
        return super.get(name)
    }

    override fun set(name: String, value: JsValue) {
        if (name == "length") {
            val newLen = toNumber(value).toInt().coerceAtLeast(0)
            while (elements.size > newLen) elements.removeAt(elements.size - 1)
            while (elements.size < newLen) elements.add(JsUndefined)
            return
        }
        name.toIntOrNull()?.let { idx ->
            while (elements.size <= idx) elements.add(JsUndefined)
            elements[idx] = value
            return
        }
        super.set(name, value)
    }

    override fun has(name: String): Boolean =
        name == "length" || name.toIntOrNull()?.let { it in elements.indices } == true || super.has(name)

    override fun ownKeys(): List<String> = elements.indices.map { it.toString() } + super.ownKeys()
}

/** A callable value. [NativeFunction] wraps built-ins; [Closure] wraps a user-defined function with its captured scope. */
abstract class JsFunction(val name: String) : JsObject() {
    abstract fun call(interpreter: Interpreter, thisArg: JsValue, args: List<JsValue>): JsValue
}

class NativeFunction(name: String, private val arity: Int = 0, private val impl: (Interpreter, JsValue, List<JsValue>) -> JsValue) :
    JsFunction(name) {
    override fun call(interpreter: Interpreter, thisArg: JsValue, args: List<JsValue>): JsValue = impl(interpreter, thisArg, args)
    override fun get(name: String): JsValue = if (name == "length") JsNumber(arity.toDouble()) else super.get(name)
}

class Closure(
    name: String,
    val params: List<Param>,
    val body: List<Stmt>,
    val closureEnv: Environment,
    val isArrow: Boolean,
    val capturedThis: JsValue? = null
) : JsFunction(name) {
    override fun call(interpreter: Interpreter, thisArg: JsValue, args: List<JsValue>): JsValue =
        interpreter.callClosure(this, thisArg, args)
}

fun toNumber(v: JsValue): Double = when (v) {
    is JsNumber -> v.value
    is JsString -> {
        val t = v.value.trim()
        if (t.isEmpty()) 0.0 else t.toDoubleOrNull() ?: Double.NaN
    }
    is JsBoolean -> if (v.value) 1.0 else 0.0
    JsNull -> 0.0
    JsUndefined -> Double.NaN
    else -> Double.NaN
}

fun formatNumber(d: Double): String = when {
    d.isNaN() -> "NaN"
    d == Double.POSITIVE_INFINITY -> "Infinity"
    d == Double.NEGATIVE_INFINITY -> "-Infinity"
    d == 0.0 -> "0"
    d == Math.floor(d) && Math.abs(d) < 1e21 -> d.toLong().toString()
    else -> d.toString()
}

fun toJsString(v: JsValue): String = when (v) {
    is JsString -> v.value
    is JsNumber -> formatNumber(v.value)
    is JsBoolean -> v.value.toString()
    JsNull -> "null"
    JsUndefined -> "undefined"
    is JsArray -> v.elements.joinToString(",") { if (it == JsUndefined || it == JsNull) "" else toJsString(it) }
    is JsFunction -> "function ${v.name}() { [native or user code] }"
    is JsObject -> "[object Object]"
}

fun isTruthy(v: JsValue): Boolean = when (v) {
    JsUndefined, JsNull -> false
    is JsBoolean -> v.value
    is JsNumber -> v.value != 0.0 && !v.value.isNaN()
    is JsString -> v.value.isNotEmpty()
    else -> true
}

fun strictEquals(l: JsValue, r: JsValue): Boolean = when {
    l is JsNumber && r is JsNumber -> l.value == r.value
    l is JsString && r is JsString -> l.value == r.value
    l is JsBoolean && r is JsBoolean -> l.value == r.value
    l == JsNull && r == JsNull -> true
    l == JsUndefined && r == JsUndefined -> true
    else -> l === r
}

fun typeOf(v: JsValue): String = when (v) {
    JsUndefined -> "undefined"
    JsNull -> "object" // matches real JS's famous quirk
    is JsBoolean -> "boolean"
    is JsNumber -> "number"
    is JsString -> "string"
    is JsFunction -> "function"
    is JsObject -> "object"
}

/** Thrown for a JS-level `throw` and for interpreter runtime errors (wrapped as an Error-shaped JsObject or JsString). */
class JsException(val value: JsValue) : RuntimeException(toJsString(value), null, false, false)

fun jsError(message: String): JsException {
    val obj = JsObject()
    obj.set("message", JsString(message))
    obj.set("name", JsString("Error"))
    return JsException(obj)
}
