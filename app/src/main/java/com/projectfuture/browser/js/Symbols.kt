package com.projectfuture.browser.js

/**
 * `Symbol` support, approximated on top of this interpreter's flat,
 * string-keyed property bags (see JsValue.kt's class doc): there's no
 * separate "symbol key" space like real JS has, so a symbol used as a
 * property key (`obj[sym]`) is converted to a unique, stable string via
 * [propertyKeyOf] and stored as an ordinary property under that string -
 * [JsObject.ownKeys] then filters those out of enumeration
 * (`Object.keys`/`for-in`/`JSON.stringify`) to approximate symbols being
 * non-enumerable-by-default real properties. A Proxy trap or `Reflect`
 * call that receives such a key back only sees that internal string, not
 * the original JsSymbol identity - a real but narrow gap from a system
 * that doesn't model symbols as first-class property keys.
 */
const val SYMBOL_KEY_PREFIX = "@@sym:"

class JsSymbol(val symId: Long, val description: String?) : JsObject() {
    val internalKey: String = "$SYMBOL_KEY_PREFIX$symId"

    override fun get(name: String): JsValue = when (name) {
        "description" -> description?.let { JsString(it) } ?: JsUndefined
        "toString" -> NativeFunction("toString", 0) { _, _, _ -> JsString(toString()) }
        else -> super.get(name)
    }

    override fun toString() = "Symbol(${description ?: ""})"
}

/** The property key that `Symbol.iterator`-keyed properties are actually stored/looked up under - see [propertyKeyOf]. */
val WELL_KNOWN_SYMBOL_ITERATOR = JsSymbol(-1L, "Symbol.iterator")
val WELL_KNOWN_SYMBOL_ASYNC_ITERATOR = JsSymbol(-2L, "Symbol.asyncIterator")
val SYMBOL_ITERATOR_KEY: String = WELL_KNOWN_SYMBOL_ITERATOR.internalKey

/** The string a value should be stored/looked-up under as an object property key - a JsSymbol's own stable internal key, or the ordinary ToString-based key for everything else. Used everywhere a computed member key (`obj[x]`) is resolved. */
fun propertyKeyOf(v: JsValue): String = if (v is JsSymbol) v.internalKey else toJsString(v)

fun makeSymbolCtor(): JsFunction {
    var counter = 1L
    val registry = HashMap<String, JsSymbol>()
    val ctor = object : JsFunction("Symbol") {
        override fun call(interpreter: Interpreter, thisArg: JsValue, args: List<JsValue>): JsValue {
            val desc = args.getOrNull(0)?.takeIf { it != JsUndefined }?.let { toJsString(it) }
            return JsSymbol(counter++, desc)
        }
    }
    ctor.set("iterator", WELL_KNOWN_SYMBOL_ITERATOR)
    ctor.set("asyncIterator", WELL_KNOWN_SYMBOL_ASYNC_ITERATOR)
    ctor.set(
        "for",
        NativeFunction("for", 1) { _, _, a ->
            val key = toJsString(a.getOrElse(0) { JsUndefined })
            registry.getOrPut(key) { JsSymbol(counter++, key) }
        }
    )
    return ctor
}
