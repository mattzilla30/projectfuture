package com.projectfuture.browser.js

/**
 * Map/Set backed by a plain Kotlin LinkedHashMap/LinkedHashSet keyed
 * directly by JsValue. This works out correctly with no extra key-
 * normalization logic because JsValue's existing equals/hashCode already
 * match JS's key semantics: JsNumber/JsString/JsBoolean are Kotlin data
 * classes (structural equality, i.e. by value - matching JS primitives),
 * while JsObject/JsArray/JsFunction use default reference equality
 * (matching JS objects, which are always keyed by identity).
 */
/** Map/Set keys use SameValueZero, which (unlike JsNumber's plain data-class equals used elsewhere,
 * e.g. by strictEquals) treats +0 and -0 as the same key. NaN already round-trips correctly through
 * a plain Kotlin/JVM `HashMap`/`equals` (Double.equals defines NaN.equals(NaN) == true, unlike `==`),
 * but +0.0 and -0.0 have distinct bit patterns and so hash/equal differently - normalize -0 to +0 here. */
private fun mapKey(v: JsValue): JsValue = if (v is JsNumber && v.value == 0.0) JsNumber(0.0) else v

class JsMap : JsObject() {
    private val map = LinkedHashMap<JsValue, JsValue>()

    fun putEntry(key: JsValue, value: JsValue) {
        map[mapKey(key)] = value
    }

    /** Backs `for (const [k, v] of someMap)` - see Interpreter.execForIn. */
    fun entryPairs(): List<JsValue> = map.entries.map { JsArray(mutableListOf(it.key, it.value)) }

    override fun get(name: String): JsValue = when (name) {
        "size" -> JsNumber(map.size.toDouble())
        "set" -> NativeFunction("set", 2) { _, thisArg, args ->
            map[mapKey(args.getOrElse(0) { JsUndefined })] = args.getOrElse(1) { JsUndefined }
            thisArg
        }
        "get" -> NativeFunction("get", 1) { _, _, args -> map[mapKey(args.getOrElse(0) { JsUndefined })] ?: JsUndefined }
        "has" -> NativeFunction("has", 1) { _, _, args -> JsBoolean(map.containsKey(mapKey(args.getOrElse(0) { JsUndefined }))) }
        "delete" -> NativeFunction("delete", 1) { _, _, args -> JsBoolean(map.remove(mapKey(args.getOrElse(0) { JsUndefined })) != null) }
        "clear" -> NativeFunction("clear", 0) { _, _, _ -> map.clear(); JsUndefined }
        "forEach" -> NativeFunction("forEach", 1) { interpreter, thisArg, args ->
            val fn = args.getOrNull(0) as? JsFunction
            if (fn != null) for ((k, v) in map.entries.toList()) fn.call(interpreter, JsUndefined, listOf(v, k, thisArg))
            JsUndefined
        }
        "keys" -> NativeFunction("keys", 0) { _, _, _ -> JsArray(map.keys.toMutableList()) }
        "values" -> NativeFunction("values", 0) { _, _, _ -> JsArray(map.values.toMutableList()) }
        "entries" -> NativeFunction("entries", 0) { _, _, _ ->
            JsArray(map.entries.map { JsArray(mutableListOf(it.key, it.value)) as JsValue }.toMutableList())
        }
        else -> super.get(name)
    }
}

class JsSet : JsObject() {
    private val values = LinkedHashSet<JsValue>()

    fun addValue(v: JsValue) {
        values.add(mapKey(v))
    }

    /** Backs `for (const v of someSet)` - see Interpreter.execForIn. */
    fun valuesList(): List<JsValue> = values.toList()

    override fun get(name: String): JsValue = when (name) {
        "size" -> JsNumber(values.size.toDouble())
        "add" -> NativeFunction("add", 1) { _, thisArg, args -> values.add(mapKey(args.getOrElse(0) { JsUndefined })); thisArg }
        "has" -> NativeFunction("has", 1) { _, _, args -> JsBoolean(values.contains(mapKey(args.getOrElse(0) { JsUndefined }))) }
        "delete" -> NativeFunction("delete", 1) { _, _, args -> JsBoolean(values.remove(mapKey(args.getOrElse(0) { JsUndefined }))) }
        "clear" -> NativeFunction("clear", 0) { _, _, _ -> values.clear(); JsUndefined }
        "forEach" -> NativeFunction("forEach", 1) { interpreter, thisArg, args ->
            val fn = args.getOrNull(0) as? JsFunction
            if (fn != null) for (v in values.toList()) fn.call(interpreter, JsUndefined, listOf(v, v, thisArg))
            JsUndefined
        }
        "values" -> NativeFunction("values", 0) { _, _, _ -> JsArray(values.toMutableList()) }
        else -> super.get(name)
    }
}

/** `new Map()` / `new Map([[k,v], ...])`. */
fun makeMapCtor(): JsFunction = object : JsFunction("Map") {
    override fun call(interpreter: Interpreter, thisArg: JsValue, args: List<JsValue>): JsValue {
        val map = JsMap()
        val init = args.getOrNull(0) as? JsArray
        init?.elements?.forEach { entry -> if (entry is JsArray && entry.elements.size >= 2) map.putEntry(entry.elements[0], entry.elements[1]) }
        return map
    }
}

/** `new Set()` / `new Set([...])`. */
fun makeSetCtor(): JsFunction = object : JsFunction("Set") {
    override fun call(interpreter: Interpreter, thisArg: JsValue, args: List<JsValue>): JsValue {
        val set = JsSet()
        (args.getOrNull(0) as? JsArray)?.elements?.forEach { set.addValue(it) }
        return set
    }
}
