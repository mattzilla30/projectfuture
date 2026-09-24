package com.projectfuture.browser.js

import java.net.URLDecoder
import java.util.Base64

/**
 * The parts of the standard library that real scripts assume exist everywhere: `Array.prototype`
 * and friends, `fn.call/apply/bind`, `obj.hasOwnProperty`, `Object.defineProperty`, the global
 * object, and small globals like `encodeURIComponent`. The engine keeps dispatching built-in
 * methods by name ([builtinMethodCall]); these prototype objects make the same methods reachable as
 * values (`Array.prototype.slice.call(x)`, `typeof "".trim`) and carry methods a page adds itself.
 */

val ARRAY_METHOD_NAMES = setOf(
    "push", "pop", "shift", "unshift", "slice", "splice", "indexOf", "lastIndexOf", "includes", "join", "reverse",
    "concat", "forEach", "map", "filter", "find", "findIndex", "findLast", "findLastIndex", "reduce", "sort", "flat",
    "flatMap", "some", "every", "reduceRight", "at", "fill", "keys", "values", "entries", "toString", "toLocaleString",
    "toReversed", "toSorted", "with"
)
val STRING_METHOD_NAMES = setOf(
    "toUpperCase", "toLowerCase", "trim", "trimStart", "trimEnd", "indexOf", "lastIndexOf", "includes", "startsWith",
    "endsWith", "slice", "substring", "charAt", "charCodeAt", "split", "replace", "replaceAll", "match", "repeat",
    "padStart", "padEnd", "concat", "at", "search", "substr", "codePointAt", "localeCompare", "normalize", "toString",
    "valueOf", "trimLeft", "trimRight", "toLocaleLowerCase", "toLocaleUpperCase"
)
val NUMBER_METHOD_NAMES = setOf("toFixed", "toString", "toPrecision", "toLocaleString", "valueOf")
private val OBJECT_METHOD_NAMES = setOf("hasOwnProperty", "isPrototypeOf", "propertyIsEnumerable", "toString", "toLocaleString", "valueOf")
private val FUNCTION_METHOD_NAMES = setOf("call", "apply", "bind", "toString")
private val BOOLEAN_METHOD_NAMES = setOf("toString", "valueOf")

/** A built-in prototype object, e.g. `Array.prototype`. Methods it doesn't hold itself are the engine's built-ins. */
class BuiltinProto(val kind: String, private val methodNames: Set<String>, parent: JsObject?) : JsObject() {
    private val methodCache = HashMap<String, NativeFunction>()

    init { proto = parent }

    override fun get(name: String): JsValue {
        properties[name]?.let { return it }
        if (name in methodNames) {
            return methodCache.getOrPut(name) {
                NativeFunction(name) { interp, thisArg, args -> callBuiltinMethod(interp, kind, thisArg, name, args) }
            }
        }
        return proto?.get(name) ?: JsUndefined
    }

    override fun has(name: String): Boolean = name in methodNames || super.has(name)
}

class BuiltinPrototypes {
    val obj = BuiltinProto("Object", OBJECT_METHOD_NAMES, null)
    val function = BuiltinProto("Function", FUNCTION_METHOD_NAMES, obj)
    val array = BuiltinProto("Array", ARRAY_METHOD_NAMES, obj)
    val string = BuiltinProto("String", STRING_METHOD_NAMES, obj)
    val number = BuiltinProto("Number", NUMBER_METHOD_NAMES, obj)
    val boolean = BuiltinProto("Boolean", BOOLEAN_METHOD_NAMES, obj)

    /** The prototype a lookup on [value] falls back to once its own chain has nothing. */
    fun forValue(value: JsValue): JsObject? = when (value) {
        is JsFunction -> function
        is JsArray -> array
        is JsString -> string
        is JsNumber -> number
        is JsBoolean -> boolean
        is JsObject -> obj
        else -> null
    }
}

private fun callBuiltinMethod(interp: Interpreter, kind: String, thisArg: JsValue, name: String, args: List<JsValue>): JsValue {
    when (kind) {
        "Function" -> return functionMethod(interp, thisArg, name, args)
        "Object" -> return objectMethod(thisArg, name, args)
    }
    val target: JsValue = when (kind) {
        "Array" -> toArrayLike(thisArg)
        "String" -> JsString(toJsString(thisArg))
        "Number" -> JsNumber(toNumber(thisArg))
        "Boolean" -> JsBoolean(isTruthy(thisArg))
        else -> thisArg
    }
    if (target is JsBoolean) return if (name == "valueOf") target else JsString(target.value.toString())
    builtinMethodCall(interp, target, name, args)?.let { return it }
    if (name == "toString" || name == "toLocaleString") return JsString(toJsString(target))
    if (name == "valueOf") return target
    throw jsError("$kind.prototype.$name is not supported here", "TypeError")
}

/** `Array.prototype.x.call(arrayLike)`: arguments objects, NodeLists and `{length: n}` work like arrays. */
private fun toArrayLike(v: JsValue): JsValue = when (v) {
    is JsArray -> v
    is JsString -> JsArray(v.value.map { JsString(it.toString()) as JsValue }.toMutableList())
    is JsObject -> {
        val len = toNumber(v.get("length")).let { if (it.isNaN()) 0 else it.toInt().coerceAtLeast(0) }
        JsArray(MutableList(len) { v.get(it.toString()) })
    }
    else -> JsArray()
}

fun arrayLikeToList(v: JsValue): List<JsValue> = when (val a = toArrayLike(v)) {
    is JsArray -> a.elements
    else -> emptyList()
}

private fun functionMethod(interp: Interpreter, thisArg: JsValue, name: String, args: List<JsValue>): JsValue {
    val fn = thisArg as? JsFunction ?: throw jsError("Function.prototype.$name called on a non-function", "TypeError")
    return when (name) {
        "call" -> fn.call(interp, args.getOrElse(0) { JsUndefined }, args.drop(1))
        "apply" -> fn.call(interp, args.getOrElse(0) { JsUndefined }, args.getOrNull(1)?.let { arrayLikeToList(it) } ?: emptyList())
        "bind" -> {
            val boundThis = args.getOrElse(0) { JsUndefined }
            val boundArgs = args.drop(1)
            NativeFunction("bound ${fn.name}") { i, _, rest -> fn.call(i, boundThis, boundArgs + rest) }.also { bound ->
                // `new` on a bound constructor still builds instances of the original.
                bound.properties["prototype"] = fn.get("prototype")
            }
        }
        else -> JsString("function ${fn.name}() { [native code] }")
    }
}

/** The `[object Tag]` string `Object.prototype.toString.call(x)` returns; libraries use it for type checks. */
fun objectTag(v: JsValue): String = when (v) {
    JsUndefined -> "Undefined"
    JsNull -> "Null"
    is JsArray -> "Array"
    is JsFunction -> "Function"
    is JsString -> "String"
    is JsNumber -> "Number"
    is JsBoolean -> "Boolean"
    is JsDate -> "Date"
    is JsRegExp -> "RegExp"
    is JsMap -> "Map"
    is JsSet -> "Set"
    is JsPromise -> "Promise"
    is JsObject -> (v.get("@@toStringTag") as? JsString)?.value ?: if (v.classChain.any { it is ErrorConstructor }) "Error" else "Object"
    else -> "Object"
}

private fun objectMethod(thisArg: JsValue, name: String, args: List<JsValue>): JsValue {
    val key = args.getOrNull(0)?.let { propertyKeyOf(it) } ?: "undefined"
    return when (name) {
        "hasOwnProperty", "propertyIsEnumerable" -> JsBoolean(
            when (thisArg) {
                is JsObject -> thisArg.properties.containsKey(key) || (thisArg !is BuiltinProto && thisArg.javaClass != JsObject::class.java && thisArg.has(key))
                is JsString -> key == "length" || key.toIntOrNull()?.let { it in thisArg.value.indices } == true
                else -> false
            }
        )
        "isPrototypeOf" -> {
            var p = (args.getOrNull(0) as? JsObject)?.proto
            var found = false
            while (p != null) { if (p === thisArg) { found = true; break }; p = p.proto }
            JsBoolean(found)
        }
        "toString" -> JsString("[object ${objectTag(thisArg)}]")
        "toLocaleString" -> JsString(toJsString(thisArg))
        else -> thisArg // valueOf
    }
}

/** Array methods the engine's main dispatch table ([builtinMethodCall]) doesn't have. */
fun extraArrayMethod(interp: Interpreter, arr: JsArray, key: String, args: List<JsValue>): JsValue? {
    val els = arr.elements
    fun relIndex(i: Int) = if (i < 0) els.size + i else i
    return when (key) {
        "at" -> els.getOrNull(relIndex(toNumber(args.getOrElse(0) { JsNumber(0.0) }).toInt())) ?: JsUndefined
        "fill" -> {
            val v = args.getOrElse(0) { JsUndefined }
            val start = relIndex(args.getOrNull(1)?.let { toNumber(it).toInt() } ?: 0).coerceIn(0, els.size)
            val end = relIndex(args.getOrNull(2)?.takeIf { it != JsUndefined }?.let { toNumber(it).toInt() } ?: els.size).coerceIn(0, els.size)
            for (i in start until end) els[i] = v
            arr
        }
        "keys" -> JsArray(els.indices.map { JsNumber(it.toDouble()) as JsValue }.toMutableList())
        "values" -> JsArray(els.toMutableList())
        "entries" -> JsArray(els.mapIndexed { i, v -> JsArray(mutableListOf(JsNumber(i.toDouble()), v)) as JsValue }.toMutableList())
        "toString", "toLocaleString" -> JsString(toJsString(arr))
        "toReversed" -> JsArray(els.reversed().toMutableList())
        "toSorted" -> builtinMethodCall(interp, JsArray(els.toMutableList()), "sort", args)
        "with" -> JsArray(els.toMutableList()).also { copy ->
            val i = relIndex(toNumber(args.getOrElse(0) { JsNumber(0.0) }).toInt())
            if (i in copy.elements.indices) copy.elements[i] = args.getOrElse(1) { JsUndefined }
        }
        else -> null
    }
}

/** String methods the main dispatch table doesn't have. */
fun extraStringMethod(s: JsString, key: String, args: List<JsValue>): JsValue? {
    val v = s.value
    return when (key) {
        "at" -> {
            val i = toNumber(args.getOrElse(0) { JsNumber(0.0) }).toInt().let { if (it < 0) v.length + it else it }
            v.getOrNull(i)?.let { JsString(it.toString()) } ?: JsUndefined
        }
        "search" -> {
            val pattern = args.getOrNull(0)
            val regex = (pattern as? JsRegExp)?.kotlinRegex ?: Regex(Regex.escape(toJsString(pattern ?: JsUndefined)))
            JsNumber((regex.find(v)?.range?.first ?: -1).toDouble())
        }
        "substr" -> {
            var start = toNumber(args.getOrElse(0) { JsNumber(0.0) }).toInt()
            if (start < 0) start = (v.length + start).coerceAtLeast(0)
            start = start.coerceAtMost(v.length)
            val len = args.getOrNull(1)?.takeIf { it != JsUndefined }?.let { toNumber(it).toInt() } ?: (v.length - start)
            JsString(v.substring(start, (start + len.coerceAtLeast(0)).coerceAtMost(v.length)))
        }
        "codePointAt" -> {
            val i = toNumber(args.getOrElse(0) { JsNumber(0.0) }).toInt()
            if (i in v.indices) JsNumber(v.codePointAt(i).toDouble()) else JsUndefined
        }
        "localeCompare" -> JsNumber(v.compareTo(toJsString(args.getOrElse(0) { JsUndefined })).coerceIn(-1, 1).toDouble())
        "normalize" -> JsString(java.text.Normalizer.normalize(v, java.text.Normalizer.Form.valueOf(args.getOrNull(0)?.let { toJsString(it) } ?: "NFC")))
        "toString", "valueOf" -> s
        "trimLeft" -> JsString(v.trimStart())
        "trimRight" -> JsString(v.trimEnd())
        "toLocaleLowerCase" -> JsString(v.lowercase())
        "toLocaleUpperCase" -> JsString(v.uppercase())
        else -> null
    }
}

fun extraNumberMethod(n: JsNumber, key: String): JsValue? = when (key) {
    "toLocaleString" -> JsString(
        if (n.value.isFinite() && n.value == Math.floor(n.value) && Math.abs(n.value) < 1e15) String.format(java.util.Locale.US, "%,d", n.value.toLong())
        else formatNumber(n.value)
    )
    "valueOf" -> n
    else -> null
}

/**
 * `window` / `globalThis` / `self`: reads and writes go straight to the global scope, so
 * `window.foo = 1` creates a global `foo` and `window.Promise` finds the built-in.
 */
class GlobalObject(private val env: Environment) : JsObject() {
    override fun get(name: String): JsValue = if (env.has(name)) env.get(name) else proto?.get(name) ?: JsUndefined
    override fun set(name: String, value: JsValue) = env.declare(name, value)
    override fun has(name: String): Boolean = env.has(name)
    override fun ownKeys(): List<String> = env.names().filter { !it.startsWith("__") && it != "this" }
}

private fun arg0(args: List<JsValue>) = args.getOrElse(0) { JsUndefined }

/** Installs the compatibility globals. Called at the end of [installGlobals]. */
fun installCompat(env: Environment, interp: Interpreter) {
    val protos = interp.prototypes
    val global = GlobalObject(env)
    env.declare("globalThis", global)
    env.declare("this", global)

    // `Object` is callable (`Object(x)`, `new Object()`) and carries the reflection statics.
    val oldObject = env.get("Object") as JsObject
    val objectCtor = NativeFunction("Object", 1) { _, _, args -> arg0(args) as? JsObject ?: JsObject() }
    objectCtor.properties.putAll(oldObject.properties)
    installObjectStatics(objectCtor, protos)
    objectCtor.properties["prototype"] = protos.obj
    protos.obj.properties["constructor"] = objectCtor
    env.declare("Object", objectCtor)

    for ((name, proto) in listOf("Array" to protos.array, "String" to protos.string, "Number" to protos.number, "Boolean" to protos.boolean)) {
        val ctor = env.get(name) as JsObject
        ctor.set("prototype", proto)
        proto.properties["constructor"] = ctor
    }
    (env.get("String") as JsObject).set("fromCharCode", NativeFunction("fromCharCode", 1) { _, _, args ->
        JsString(args.joinToString("") { toNumber(it).toInt().toChar().toString() })
    })
    (env.get("String") as JsObject).set("fromCodePoint", NativeFunction("fromCodePoint", 1) { _, _, args ->
        JsString(args.joinToString("") { String(Character.toChars(toNumber(it).toInt())) })
    })

    // `Function(...)` / `new Function("a", "b", "return a + b")`: compiled in the global scope.
    val functionCtor = NativeFunction("Function", 1) { i, _, args ->
        val body = args.lastOrNull()?.let { toJsString(it) } ?: ""
        val params = args.dropLast(1).joinToString(",") { toJsString(it) }
        val program = Parser(Lexer("(function anonymous($params\n) {\n$body\n})").tokenize()).parseProgram()
        i.evalExpr((program.first() as ExprStmt).expr, i.globalEnv)
    }
    functionCtor.properties["prototype"] = protos.function
    protos.function.properties["constructor"] = functionCtor
    env.declare("Function", functionCtor)

    // Weak collections behave like their strong versions here: this engine has no garbage-collection hooks.
    env.declare("WeakMap", makeMapCtor())
    env.declare("WeakSet", makeSetCtor())
    env.declare("WeakRef", NativeFunction("WeakRef", 1) { _, thisArg, args ->
        val target = arg0(args)
        (thisArg as? JsObject ?: JsObject()).also { it.set("deref", NativeFunction("deref", 0) { _, _, _ -> target }) }
    })

    env.declare("isFinite", NativeFunction("isFinite", 1) { _, _, args -> JsBoolean(toNumber(arg0(args)).isFinite()) })
    env.declare("encodeURIComponent", NativeFunction("encodeURIComponent", 1) { _, _, args -> JsString(encodeUri(toJsString(arg0(args)), URI_COMPONENT_SAFE)) })
    env.declare("encodeURI", NativeFunction("encodeURI", 1) { _, _, args -> JsString(encodeUri(toJsString(arg0(args)), URI_SAFE)) })
    env.declare("decodeURIComponent", NativeFunction("decodeURIComponent", 1) { _, _, args -> JsString(decodeUri(toJsString(arg0(args)))) })
    env.declare("decodeURI", NativeFunction("decodeURI", 1) { _, _, args -> JsString(decodeUri(toJsString(arg0(args)))) })
    env.declare("escape", NativeFunction("escape", 1) { _, _, args -> JsString(encodeUri(toJsString(arg0(args)), "@*_+-./")) })
    env.declare("unescape", NativeFunction("unescape", 1) { _, _, args -> JsString(decodeUri(toJsString(arg0(args)))) })
    env.declare("btoa", NativeFunction("btoa", 1) { _, _, args ->
        JsString(Base64.getEncoder().encodeToString(toJsString(arg0(args)).toByteArray(Charsets.ISO_8859_1)))
    })
    env.declare("atob", NativeFunction("atob", 1) { _, _, args ->
        val cleaned = toJsString(arg0(args)).filterNot { it.isWhitespace() }
        val bytes = try { Base64.getDecoder().decode(cleaned) } catch (_: IllegalArgumentException) {
            throw jsError("The string to be decoded is not correctly encoded.", "InvalidCharacterError")
        }
        JsString(String(bytes, Charsets.ISO_8859_1))
    })
    env.declare("structuredClone", NativeFunction("structuredClone", 1) { _, _, args ->
        val v = arg0(args)
        if (v is JsObject) parseJsonToJsValue(jsonStringify(v)) else v
    })
    // Promise callbacks here run synchronously, so a microtask runs right away as well.
    env.declare("queueMicrotask", NativeFunction("queueMicrotask", 1) { i, _, args ->
        (arg0(args) as? JsFunction)?.call(i, JsUndefined, emptyList()); JsUndefined
    })
}

private fun installObjectStatics(ctor: JsObject, protos: BuiltinPrototypes) {
    fun def(name: String, arity: Int, impl: (Interpreter, List<JsValue>) -> JsValue) =
        ctor.set(name, NativeFunction(name, arity) { i, _, args -> impl(i, args) })
    def("create", 2) { i, args ->
        JsObject().also { o ->
            val p = arg0(args)
            if (p is JsObject) o.setPrototype(p) else o.proto = null
            (args.getOrNull(1) as? JsObject)?.let { defineProperties(i, o, it) }
        }
    }
    def("getPrototypeOf", 1) { _, args ->
        when (val o = arg0(args)) {
            is JsObject -> o.proto ?: (if (o is BuiltinProto && o.kind == "Object") null else protos.forValue(o)?.takeIf { it !== o }) ?: JsNull
            else -> protos.forValue(o) ?: JsNull
        }
    }
    def("setPrototypeOf", 2) { _, args ->
        val o = arg0(args)
        if (o is JsObject) o.setPrototype(args.getOrNull(1) as? JsObject)
        o
    }
    def("defineProperty", 3) { i, args ->
        val o = arg0(args) as? JsObject ?: throw jsError("Object.defineProperty called on non-object", "TypeError")
        defineProperty(i, o, propertyKeyOf(args.getOrElse(1) { JsUndefined }), args.getOrNull(2) as? JsObject)
        o
    }
    def("defineProperties", 2) { i, args ->
        val o = arg0(args) as? JsObject ?: throw jsError("Object.defineProperties called on non-object", "TypeError")
        (args.getOrNull(1) as? JsObject)?.let { defineProperties(i, o, it) }
        o
    }
    def("getOwnPropertyNames", 1) { _, args ->
        JsArray((arg0(args) as? JsObject)?.let { o -> (o.properties.keys.filterNot { it.startsWith(SYMBOL_KEY_PREFIX) } + (if (o is JsArray) o.elements.indices.map { it.toString() } + "length" else emptyList())).distinct() }
            ?.map { JsString(it) as JsValue }?.toMutableList() ?: mutableListOf())
    }
    def("getOwnPropertySymbols", 1) { _, _ -> JsArray() }
    def("getOwnPropertyDescriptor", 2) { i, args ->
        val o = arg0(args) as? JsObject ?: return@def JsUndefined
        val key = propertyKeyOf(args.getOrElse(1) { JsUndefined })
        val getter = o.getters?.get(key)
        val setter = o.setters?.get(key)
        when {
            getter != null || setter != null -> JsObject().also {
                it.set("get", getter ?: JsUndefined); it.set("set", setter ?: JsUndefined)
                it.set("enumerable", JsBoolean(true)); it.set("configurable", JsBoolean(true))
            }
            o.properties.containsKey(key) || (o is JsArray && o.has(key)) -> JsObject().also {
                it.set("value", i.getProperty(o, key)); it.set("writable", JsBoolean(true))
                it.set("enumerable", JsBoolean(true)); it.set("configurable", JsBoolean(true))
            }
            else -> JsUndefined
        }
    }
    def("fromEntries", 1) { _, args ->
        JsObject().also { o -> for (e in arrayLikeToList(arg0(args))) { val pair = arrayLikeToList(e); o.set(propertyKeyOf(pair.getOrElse(0) { JsUndefined }), pair.getOrElse(1) { JsUndefined }) } }
    }
    def("is", 2) { _, args ->
        val a = arg0(args); val b = args.getOrElse(1) { JsUndefined }
        JsBoolean(if (a is JsNumber && b is JsNumber) a.value.equals(b.value) else strictEqualsCompat(a, b))
    }
    // Objects can't be made immutable in this engine; these return the object as real code expects.
    for (name in listOf("freeze", "seal", "preventExtensions")) def(name, 1) { _, args -> arg0(args) }
    for (name in listOf("isFrozen", "isSealed")) def(name, 1) { _, _ -> JsBoolean(false) }
    def("isExtensible", 1) { _, args -> JsBoolean(arg0(args) is JsObject) }
}

private fun strictEqualsCompat(a: JsValue, b: JsValue): Boolean = when {
    a is JsObject || b is JsObject -> a === b
    else -> a == b
}

private fun defineProperties(interp: Interpreter, o: JsObject, props: JsObject) {
    for (k in props.ownKeys()) defineProperty(interp, o, k, props.get(k) as? JsObject)
}

/** `Object.defineProperty`: `value` becomes a plain property; `get`/`set` become accessors. Attribute flags aren't modeled. */
private fun defineProperty(interp: Interpreter, o: JsObject, key: String, descriptor: JsObject?) {
    if (descriptor == null) return
    val getter = descriptor.get("get") as? JsFunction
    val setter = descriptor.get("set") as? JsFunction
    if (getter != null || setter != null) {
        getter?.let { (o.getters ?: HashMap<String, JsFunction>().also { m -> o.getters = m })[key] = it }
        setter?.let { (o.setters ?: HashMap<String, JsFunction>().also { m -> o.setters = m })[key] = it }
    } else if (descriptor.has("value") || !o.has(key)) {
        interp.setProperty(o, key, descriptor.get("value"))
    }
}

private const val URI_COMPONENT_SAFE = "-_.!~*'()"
private const val URI_SAFE = "-_.!~*'();/?:@&=+$,#"

private fun encodeUri(s: String, safe: String): String {
    val sb = StringBuilder()
    for (b in s.toByteArray(Charsets.UTF_8)) {
        val c = (b.toInt() and 0xff)
        if (c < 128 && (c.toChar().isLetterOrDigit() || c.toChar() in safe)) sb.append(c.toChar())
        else sb.append('%').append("%02X".format(c))
    }
    return sb.toString()
}

private fun decodeUri(s: String): String = try {
    URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
} catch (_: Exception) {
    throw jsError("URI malformed", "URIError")
}
