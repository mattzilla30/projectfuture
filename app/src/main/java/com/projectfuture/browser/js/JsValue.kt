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
    /** The [[Prototype]]: a lookup that misses this object's own properties continues here. Never cyclic (see setPrototype). */
    var proto: JsObject? = null

    /** Sets [proto] unless that would create a cycle, which would make every missed lookup recurse forever. */
    fun setPrototype(p: JsObject?): Boolean {
        var cur = p
        while (cur != null) { if (cur === this) return false; cur = cur.proto }
        proto = p
        return true
    }

    open fun get(name: String): JsValue = properties[name] ?: proto?.get(name) ?: JsUndefined
    open fun set(name: String, value: JsValue) {
        properties[name] = value
    }
    open fun has(name: String): Boolean = properties.containsKey(name)

    /** Symbol-keyed properties (see Symbols.kt's [propertyKeyOf]) are stored as ordinary string-keyed entries internally, but excluded from enumeration here to approximate real JS's separate symbol key space without actually modeling one. */
    open fun ownKeys(): List<String> = properties.keys.filterNot { it.startsWith(SYMBOL_KEY_PREFIX) }

    /** Kotlin-only bookkeeping for `instanceof` on `class`-declared instances - see ClassConstructor in Interpreter.kt. Empty for ordinary objects. */
    var classChain: List<JsFunction> = emptyList()

    /**
     * Accessor properties (`{ get x() {} }` / class `get`/`set` methods).
     * Checked by Interpreter.getProperty/setProperty *before* falling back
     * to plain [get]/[set] - accessor invocation needs a live Interpreter
     * to run the getter/setter function's body with, which only
     * Interpreter-level code has access to (this class's own get/set have
     * no such reference), so the accessor check has to live at that call
     * site rather than inside get()/set() themselves.
     */
    var getters: MutableMap<String, JsFunction>? = null
    var setters: MutableMap<String, JsFunction>? = null
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
    val capturedThis: JsValue? = null,
    val isGenerator: Boolean = false,
    val isAsync: Boolean = false
) : JsFunction(name) {
    override fun call(interpreter: Interpreter, thisArg: JsValue, args: List<JsValue>): JsValue =
        interpreter.callClosure(this, thisArg, args)

    /** Every non-arrow function gets a `prototype` object on first use, with `constructor` pointing back. */
    override fun get(name: String): JsValue {
        if (name == "prototype" && !isArrow && !properties.containsKey("prototype")) {
            properties["prototype"] = JsObject().also { it.properties["constructor"] = this }
        }
        if (name == "name" && !properties.containsKey("name")) return JsString(this.name)
        if (name == "length" && !properties.containsKey("length")) return JsNumber(params.count { !it.rest && it.pattern.default == null }.toDouble())
        return super.get(name)
    }
}

/**
 * ES ToPrimitive, restricted to what this flat-property-bag object model can actually express:
 * there is no general `valueOf`/`Symbol.toPrimitive` override mechanism (see JsObject's class doc), so the
 * only case that matters is the plain default-hint algorithm's fallback - `Object.prototype.valueOf`
 * returns `this` (non-primitive) for a plain object and is therefore skipped, and the result is always
 * the object's string form. That also happens to be correct for [JsArray], whose own `toString`-equivalent
 * ([toJsString]'s array branch) is exactly what real JS's `Array.prototype.valueOf` (inherited, so also
 * skipped) plus `Array.prototype.toString`/`join` would produce.
 */
fun toPrimitive(v: JsValue): JsValue = if (v is JsObject) JsString(toJsString(v)) else v

fun toNumber(v: JsValue): Double = when (val p = toPrimitive(v)) {
    is JsNumber -> p.value
    is JsString -> stringToNumber(p.value)
    is JsBoolean -> if (p.value) 1.0 else 0.0
    JsNull -> 0.0
    JsUndefined -> Double.NaN
    else -> Double.NaN
}

/** ES StringToNumber: trims whitespace, treats an empty/whitespace-only string as `0`, and - unlike
 * `String.toDoubleOrNull()` - also recognizes the `0x`/`0o`/`0b` integer literal prefixes ES2015 added
 * to this algorithm (`Number("0x1F")` is `31`, not `NaN`). */
private fun stringToNumber(s: String): Double {
    val t = s.trim()
    if (t.isEmpty()) return 0.0
    val negative = t.startsWith("-")
    val unsigned = if (negative || t.startsWith("+")) t.substring(1) else t
    if (unsigned.length > 2 && unsigned[0] == '0') {
        val radix = when (unsigned[1]) {
            'x', 'X' -> 16
            'o', 'O' -> 8
            'b', 'B' -> 2
            else -> 0
        }
        if (radix != 0) {
            // A sign is not permitted before 0x/0o/0b per spec, only before decimal literals.
            if (negative || t.startsWith("+")) return Double.NaN
            val digits = unsigned.substring(2)
            return digits.toLongOrNull(radix)?.toDouble()
                ?: (if (digits.isNotEmpty() && digits.all { Character.digit(it, radix) >= 0 }) {
                    java.math.BigInteger(digits, radix).toDouble()
                } else Double.NaN)
        }
    }
    return t.toDoubleOrNull() ?: Double.NaN
}

fun formatNumber(d: Double): String = when {
    d.isNaN() -> "NaN"
    d == Double.POSITIVE_INFINITY -> "Infinity"
    d == Double.NEGATIVE_INFINITY -> "-Infinity"
    d == 0.0 -> "0"
    d == Math.floor(d) && Math.abs(d) < 1e21 -> d.toLong().toString()
    else -> formatNonIntegerNumber(d)
}

/**
 * ES Number::toString for the cases [formatNumber] doesn't shortcut (non-integers, and integers with
 * magnitude >= 1e21). `Double.toString()` already produces the same shortest round-trip *digits* JS's
 * algorithm would (both guarantee a unique shortest decimal that reads back to the same double), but
 * Java's notation rules differ from JS's (scientific-notation threshold, `E`/no sign vs `e`/`+`, no
 * `.0` mantissa suffix), so this reformats those digits per the actual ECMA-262 7.1.12.1 rules instead
 * of using Java's rendering directly.
 */
private fun formatNonIntegerNumber(d: Double): String {
    val negative = d < 0
    val javaStr = Math.abs(d).toString()
    val eIdx = javaStr.indexOf('E')
    val mantissa = if (eIdx >= 0) javaStr.substring(0, eIdx) else javaStr
    val exp = if (eIdx >= 0) javaStr.substring(eIdx + 1).toInt() else 0
    val dotIdx = mantissa.indexOf('.')
    val intPart = mantissa.substring(0, dotIdx)
    val fracPart = mantissa.substring(dotIdx + 1)
    var allDigits = intPart + fracPart
    var pointPos = intPart.length + exp
    val firstNonZero = allDigits.indexOfFirst { it != '0' }
    if (firstNonZero == -1) return "0"
    pointPos -= firstNonZero
    allDigits = allDigits.substring(firstNonZero).trimEnd('0')
    if (allDigits.isEmpty()) allDigits = "0"
    val k = allDigits.length
    val n = pointPos
    val sign = if (negative) "-" else ""
    val body = if (n in k..21) {
        allDigits + "0".repeat(n - k)
    } else if (n in 1 until k) {
        allDigits.substring(0, n) + "." + allDigits.substring(n)
    } else if (n in -5..0) {
        "0." + "0".repeat(-n) + allDigits
    } else {
        val e = n - 1
        val mant = if (k == 1) allDigits else allDigits[0] + "." + allDigits.substring(1)
        mant + "e" + (if (e >= 0) "+" else "") + e
    }
    return sign + body
}

fun toJsString(v: JsValue): String = when (v) {
    is JsString -> v.value
    is JsNumber -> formatNumber(v.value)
    is JsBoolean -> v.value.toString()
    JsNull -> "null"
    JsUndefined -> "undefined"
    is JsArray -> v.elements.joinToString(",") { if (it == JsUndefined || it == JsNull) "" else toJsString(it) }
    is JsFunction -> "function ${v.name}() { [native or user code] }"
    // Real JS throws on implicit Symbol->string coercion; simplified here to a plain description string,
    // consistent with this interpreter's general preference for a usable result over a spec-accurate throw.
    is JsSymbol -> v.toString()
    is JsObject -> if (v is JsStringConvertible) v.jsString() else "[object Object]"
}

/** A host object with its own string form, the way `String(location)` gives the URL instead of "[object Object]". */
interface JsStringConvertible {
    fun jsString(): String
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

/** SameValueZero: like strictEquals, but NaN equals itself and +0/-0 are treated as the same value.
 * Used by Array.prototype.includes (unlike indexOf, which uses strict equality) and by Map/Set key lookup. */
fun sameValueZero(l: JsValue, r: JsValue): Boolean = when {
    l is JsNumber && r is JsNumber -> (l.value.isNaN() && r.value.isNaN()) || l.value == r.value
    else -> strictEquals(l, r)
}

fun typeOf(v: JsValue): String = when (v) {
    JsUndefined -> "undefined"
    JsNull -> "object" // matches real JS's famous quirk
    is JsBoolean -> "boolean"
    is JsNumber -> "number"
    is JsString -> "string"
    is JsSymbol -> "symbol"
    is JsFunction -> "function"
    is JsObject -> "object"
}

/** Thrown for a JS-level `throw` and for interpreter runtime errors (wrapped as an Error-shaped JsObject or JsString). */
class JsException(val value: JsValue) : RuntimeException(toJsString(value), null, false, false)

/**
 * `Error`/`TypeError`/`RangeError`/etc. constructors. Modeled the same way `class`-declared
 * constructors are (see ClassConstructor in Interpreter.kt): calling one - via `new`, via a bare
 * call (real JS allows `TypeError("x")` without `new` too - both paths here just build a fresh
 * object when `thisArg` isn't already an instance, e.g. when driven through `super(...)` from a
 * user `class ... extends Error`), or as the implicit superclass of such a subclass - stamps the
 * constructor (and its ancestors, so `new TypeError() instanceof Error` is true too) onto the
 * instance's [JsObject.classChain], which is exactly what the interpreter's `instanceof` operator
 * checks. Sharing one singleton instance per error type (below) across every [Interpreter] is what
 * makes that check work at all: `instanceof` compares by reference, so the constructor a script
 * looks up by name (from its Environment) has to be the same object this class stamps onto
 * instances, including the ones [jsError] builds for the interpreter's own internally-thrown
 * errors - otherwise a catch block's `e instanceof TypeError` could never match them.
 */
class ErrorConstructor(name: String, val parent: ErrorConstructor? = null) : JsFunction(name) {
    override fun call(interpreter: Interpreter, thisArg: JsValue, args: List<JsValue>): JsValue = buildError(this, thisArg, args)
}

private fun buildError(ctor: ErrorConstructor, thisArg: JsValue, args: List<JsValue>): JsObject {
    val instance = thisArg as? JsObject ?: JsObject()
    var chain = instance.classChain
    var c: ErrorConstructor? = ctor
    while (c != null) {
        if (c !in chain) chain = chain + c
        c = c.parent
    }
    instance.classChain = chain
    val messageArg = args.getOrNull(0)
    if (messageArg != null && messageArg != JsUndefined) instance.set("message", JsString(toJsString(messageArg)))
    else if (!instance.has("message")) instance.set("message", JsString(""))
    instance.set("name", JsString(ctor.name))
    instance.set("stack", JsString("${ctor.name}: ${toJsString(instance.get("message"))}"))
    return instance
}

val ERROR_CTOR = ErrorConstructor("Error")
val TYPE_ERROR_CTOR = ErrorConstructor("TypeError", ERROR_CTOR)
val RANGE_ERROR_CTOR = ErrorConstructor("RangeError", ERROR_CTOR)
val SYNTAX_ERROR_CTOR = ErrorConstructor("SyntaxError", ERROR_CTOR)
val REFERENCE_ERROR_CTOR = ErrorConstructor("ReferenceError", ERROR_CTOR)

/** Builds a real, catchable Error-family instance (`e instanceof TypeError`/`instanceof Error` work - see
 * [ErrorConstructor]) for the interpreter's own internally-thrown errors, e.g. `jsError("x is not a
 * function", "TypeError")`. [name] defaults to plain `Error` for messages with no more specific type. */
fun jsError(message: String, name: String = "Error"): JsException {
    val ctor = when (name) {
        "TypeError" -> TYPE_ERROR_CTOR
        "RangeError" -> RANGE_ERROR_CTOR
        "SyntaxError" -> SYNTAX_ERROR_CTOR
        "ReferenceError" -> REFERENCE_ERROR_CTOR
        else -> ERROR_CTOR
    }
    return JsException(buildError(ctor, JsUndefined, listOf(JsString(message))))
}
