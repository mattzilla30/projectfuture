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
class JsMap : JsObject() {
    private val map = LinkedHashMap<JsValue, JsValue>()

    fun putEntry(key: JsValue, value: JsValue) {
        map[key] = value
    }

    override fun get(name: String): JsValue = when (name) {
        "size" -> JsNumber(map.size.toDouble())
        "set" -> NativeFunction("set", 2) { _, thisArg, args ->
            map[args.getOrElse(0) { JsUndefined }] = args.getOrElse(1) { JsUndefined }
            thisArg
        }
        "get" -> NativeFunction("get", 1) { _, _, args -> map[args.getOrElse(0) { JsUndefined }] ?: JsUndefined }
        "has" -> NativeFunction("has", 1) { _, _, args -> JsBoolean(map.containsKey(args.getOrElse(0) { JsUndefined })) }
        "delete" -> NativeFunction("delete", 1) { _, _, args -> JsBoolean(map.remove(args.getOrElse(0) { JsUndefined }) != null) }
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
        values.add(v)
    }

    override fun get(name: String): JsValue = when (name) {
        "size" -> JsNumber(values.size.toDouble())
        "add" -> NativeFunction("add", 1) { _, thisArg, args -> values.add(args.getOrElse(0) { JsUndefined }); thisArg }
        "has" -> NativeFunction("has", 1) { _, _, args -> JsBoolean(values.contains(args.getOrElse(0) { JsUndefined })) }
        "delete" -> NativeFunction("delete", 1) { _, _, args -> JsBoolean(values.remove(args.getOrElse(0) { JsUndefined })) }
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
