package com.projectfuture.browser.js

/**
 * `new Proxy(target, handler)`. Traps are looked up on [handler] and, when
 * present, called eagerly from [get]/[set]/[has]/[ownKeys]/[deleteProperty]
 * with the interpreter captured at construction time - the only way to run
 * a trap function from inside these overrides, since JsObject's own get/
 * set/has/ownKeys signatures (shared with every other JsObject) don't take
 * one. Only object traps are supported (get/set/has/deleteProperty/
 * ownKeys); `apply`/`construct` traps for a callable target are not, since
 * that would need JsProxy to also be a JsFunction - a real but narrow gap,
 * left out because proxying data/collection-like objects is by far the
 * common case. A trap's property-key argument is always the internal
 * string key (see [propertyKeyOf]) rather than the original JsSymbol for a
 * symbol-keyed access - the same simplification [propertyKeyOf] documents.
 */
class JsProxy(val target: JsObject, private val handler: JsObject, private val interpreter: Interpreter) : JsObject() {
    private fun trap(name: String): JsFunction? = handler.get(name) as? JsFunction

    override fun get(name: String): JsValue {
        val t = trap("get") ?: return target.get(name)
        return t.call(interpreter, handler, listOf(target, JsString(name), this))
    }

    override fun set(name: String, value: JsValue) {
        val t = trap("set")
        if (t != null) t.call(interpreter, handler, listOf(target, JsString(name), value, this)) else target.set(name, value)
    }

    override fun has(name: String): Boolean {
        val t = trap("has") ?: return target.has(name)
        return isTruthy(t.call(interpreter, handler, listOf(target, JsString(name))))
    }

    override fun ownKeys(): List<String> {
        val t = trap("ownKeys") ?: return target.ownKeys()
        val result = t.call(interpreter, handler, listOf(target))
        return (result as? JsArray)?.elements?.map { toJsString(it) } ?: target.ownKeys()
    }

    /** Not part of JsObject's own interface (which has no delete hook) - called directly by Interpreter's `delete` handling. */
    fun deleteProperty(name: String): Boolean {
        val t = trap("deleteProperty")
        return if (t != null) {
            isTruthy(t.call(interpreter, handler, listOf(target, JsString(name))))
        } else {
            target.properties.remove(name)
            true
        }
    }
}

fun makeProxyCtor(): JsFunction = object : JsFunction("Proxy") {
    override fun call(interpreter: Interpreter, thisArg: JsValue, args: List<JsValue>): JsValue {
        val target = args.getOrNull(0) as? JsObject ?: throw jsError("Cannot create proxy with a non-object as target")
        val handler = args.getOrNull(1) as? JsObject ?: throw jsError("Cannot create proxy with a non-object as handler")
        return JsProxy(target, handler, interpreter)
    }
}

/** `Reflect.*`: the same underlying operations Proxy traps forward to by default, exposed directly. */
fun makeReflectObj(): JsObject {
    val obj = JsObject()
    obj.set(
        "get",
        NativeFunction("get", 2) { interpreter, _, a ->
            val target = a.getOrNull(0) as? JsObject ?: return@NativeFunction JsUndefined
            interpreter.getProperty(target, propertyKeyOf(a.getOrElse(1) { JsUndefined }))
        }
    )
    obj.set(
        "set",
        NativeFunction("set", 3) { interpreter, _, a ->
            val target = a.getOrNull(0) as? JsObject ?: return@NativeFunction JsBoolean(false)
            interpreter.setProperty(target, propertyKeyOf(a.getOrElse(1) { JsUndefined }), a.getOrElse(2) { JsUndefined })
            JsBoolean(true)
        }
    )
    obj.set("has", NativeFunction("has", 2) { _, _, a -> JsBoolean((a.getOrNull(0) as? JsObject)?.has(propertyKeyOf(a.getOrElse(1) { JsUndefined })) == true) })
    obj.set(
        "deleteProperty",
        NativeFunction("deleteProperty", 2) { _, _, a ->
            val key = propertyKeyOf(a.getOrElse(1) { JsUndefined })
            when (val target = a.getOrNull(0)) {
                is JsProxy -> JsBoolean(target.deleteProperty(key))
                is JsObject -> { target.properties.remove(key); JsBoolean(true) }
                else -> JsBoolean(false)
            }
        }
    )
    obj.set("ownKeys", NativeFunction("ownKeys", 1) { _, _, a -> JsArray((a.getOrNull(0) as? JsObject)?.ownKeys()?.map { JsString(it) as JsValue }?.toMutableList() ?: mutableListOf()) })
    obj.set(
        "apply",
        NativeFunction("apply", 3) { interpreter, _, a ->
            val fn = a.getOrNull(0) as? JsFunction ?: throw jsError("Reflect.apply target is not a function")
            val thisArg = a.getOrElse(1) { JsUndefined }
            val argList = (a.getOrNull(2) as? JsArray)?.elements ?: emptyList()
            fn.call(interpreter, thisArg, argList)
        }
    )
    obj.set(
        "construct",
        NativeFunction("construct", 2) { interpreter, _, a ->
            val fn = a.getOrNull(0) as? JsFunction ?: throw jsError("Reflect.construct target is not a function")
            val argList = (a.getOrNull(1) as? JsArray)?.elements ?: emptyList()
            val instance = JsObject()
            val result = fn.call(interpreter, instance, argList)
            if (result is JsObject) result else instance
        }
    )
    obj.set(
        "defineProperty",
        NativeFunction("defineProperty", 3) { _, _, a ->
            val target = a.getOrNull(0) as? JsObject ?: return@NativeFunction JsBoolean(false)
            val key = propertyKeyOf(a.getOrElse(1) { JsUndefined })
            val desc = a.getOrNull(2) as? JsObject
            if (desc != null) {
                if (desc.has("value")) target.set(key, desc.get("value"))
                (desc.get("get") as? JsFunction)?.let { getter -> (target.getters ?: HashMap<String, JsFunction>().also { target.getters = it })[key] = getter }
                (desc.get("set") as? JsFunction)?.let { setter -> (target.setters ?: HashMap<String, JsFunction>().also { target.setters = it })[key] = setter }
            }
            JsBoolean(true)
        }
    )
    obj.set("getPrototypeOf", NativeFunction("getPrototypeOf", 1) { _, _, _ -> JsNull })
    obj.set("isExtensible", NativeFunction("isExtensible", 1) { _, _, _ -> JsBoolean(true) })
    return obj
}
