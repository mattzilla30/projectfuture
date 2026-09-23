package com.projectfuture.browser.js

/**
 * Global objects/functions and the Array/String instance-method dispatch
 * table. Since JsObject has no prototype chain (see JsValue.kt), instance
 * methods like `arr.push()`/`"x".toUpperCase()` are resolved here rather
 * than living on a prototype object - see [builtinMethodCall].
 */
fun installGlobals(env: Environment, interpreter: Interpreter) {
    env.declare("console", makeConsole())
    env.declare("Math", makeMath())
    env.declare("JSON", makeJson())
    env.declare("Object", makeObjectCtor())
    env.declare("Array", makeArrayCtor())
    env.declare("Promise", makePromiseCtor())
    env.declare("RegExp", makeRegExpCtor())
    env.declare("Map", makeMapCtor())
    env.declare("Set", makeSetCtor())
    env.declare("NaN", JsNumber(Double.NaN))
    env.declare("Infinity", JsNumber(Double.POSITIVE_INFINITY))

    env.declare("parseInt", NativeFunction("parseInt", 2) { _, _, args ->
        val s = toJsString(arg(args, 0)).trim()
        val radix = (arg(args, 1) as? JsNumber)?.value?.toInt()?.takeIf { it in 2..36 } ?: 10
        val match = Regex("^[+-]?[0-9a-zA-Z]+").find(s)
        JsNumber(match?.value?.toLongOrNull(radix)?.toDouble() ?: Double.NaN)
    })
    env.declare("parseFloat", NativeFunction("parseFloat", 1) { _, _, args ->
        val s = toJsString(arg(args, 0)).trim()
        val match = Regex("^[+-]?([0-9]*\\.[0-9]+|[0-9]+\\.?)([eE][+-]?[0-9]+)?").find(s)
        JsNumber(match?.value?.toDoubleOrNull() ?: Double.NaN)
    })
    env.declare("isNaN", NativeFunction("isNaN", 1) { _, _, args -> JsBoolean(toNumber(arg(args, 0)).isNaN()) })
    env.declare("String", NativeFunction("String", 1) { _, _, args -> JsString(if (args.isEmpty()) "" else toJsString(args[0])) })
    val numberCtor = NativeFunction("Number", 1) { _, _, args -> JsNumber(if (args.isEmpty()) 0.0 else toNumber(args[0])) }
    numberCtor.set(
        "isInteger",
        NativeFunction("isInteger", 1) { _, _, args ->
            val v = args.getOrNull(0)
            JsBoolean(v is JsNumber && !v.value.isNaN() && !v.value.isInfinite() && v.value == Math.floor(v.value))
        }
    )
    numberCtor.set(
        "isFinite",
        NativeFunction("isFinite", 1) { _, _, args -> val v = args.getOrNull(0); JsBoolean(v is JsNumber && !v.value.isNaN() && !v.value.isInfinite()) }
    )
    numberCtor.set("isNaN", NativeFunction("isNaN", 1) { _, _, args -> val v = args.getOrNull(0); JsBoolean(v is JsNumber && v.value.isNaN()) })
    env.declare("Number", numberCtor)
    env.declare("Boolean", NativeFunction("Boolean", 1) { _, _, args -> JsBoolean(isTruthy(arg(args, 0))) })
}

private fun arg(args: List<JsValue>, i: Int): JsValue = args.getOrElse(i) { JsUndefined }

private fun makeConsole(): JsObject {
    val obj = JsObject()
    val logFn = NativeFunction("log") { _, _, args -> println(args.joinToString(" ") { toJsString(it) }); JsUndefined }
    obj.set("log", logFn)
    obj.set("warn", logFn)
    obj.set("error", logFn)
    obj.set("info", logFn)
    return obj
}

private fun makeMath(): JsObject {
    val obj = JsObject()
    obj.set("PI", JsNumber(Math.PI))
    obj.set("E", JsNumber(Math.E))
    fun fn(name: String, f: (Double) -> Double) = obj.set(name, NativeFunction(name, 1) { _, _, args -> JsNumber(f(toNumber(arg(args, 0)))) })
    fn("abs") { Math.abs(it) }
    fn("floor") { Math.floor(it) }
    fn("ceil") { Math.ceil(it) }
    fn("round") { Math.floor(it + 0.5) }
    fn("sqrt") { Math.sqrt(it) }
    fn("trunc") { if (it < 0) Math.ceil(it) else Math.floor(it) }
    fn("sign") { Math.signum(it) }
    obj.set("pow", NativeFunction("pow", 2) { _, _, args -> JsNumber(Math.pow(toNumber(arg(args, 0)), toNumber(arg(args, 1)))) })
    obj.set("min", NativeFunction("min") { _, _, args -> JsNumber(if (args.isEmpty()) Double.POSITIVE_INFINITY else args.minOf { toNumber(it) }) })
    obj.set("max", NativeFunction("max") { _, _, args -> JsNumber(if (args.isEmpty()) Double.NEGATIVE_INFINITY else args.maxOf { toNumber(it) }) })
    obj.set("random", NativeFunction("random") { _, _, _ -> JsNumber(Math.random()) })
    return obj
}

/** Public so Tab.kt's Web Worker bridge can reuse it for a structured-clone approximation - see installWorker's doc. */
fun jsonStringify(v: JsValue): String = when (v) {
    JsUndefined -> "null" // JSON has no undefined; simplified to null everywhere rather than omitting keys
    JsNull -> "null"
    is JsBoolean -> v.value.toString()
    is JsNumber -> formatNumber(v.value)
    is JsString -> "\"" + v.value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    is JsArray -> "[" + v.elements.joinToString(",") { jsonStringify(it) } + "]"
    is JsFunction -> "null"
    is JsObject -> "{" + v.ownKeys().joinToString(",") { k -> "\"$k\":" + jsonStringify(v.get(k)) } + "}"
}

private fun makeJson(): JsObject {
    val obj = JsObject()
    obj.set("stringify", NativeFunction("stringify", 1) { _, _, args -> JsString(jsonStringify(arg(args, 0))) })
    obj.set("parse", NativeFunction("parse", 1) { _, _, args ->
        try {
            parseJsonToJsValue(toJsString(arg(args, 0)))
        } catch (_: Exception) {
            throw jsError("Unexpected token in JSON")
        }
    })
    return obj
}

private fun makeObjectCtor(): JsObject {
    val obj = JsObject()
    obj.set("keys", NativeFunction("keys", 1) { _, _, args ->
        JsArray((arg(args, 0) as? JsObject)?.ownKeys()?.map { JsString(it) as JsValue }?.toMutableList() ?: mutableListOf())
    })
    obj.set("values", NativeFunction("values", 1) { _, _, args ->
        val o = arg(args, 0) as? JsObject
        JsArray(o?.ownKeys()?.map { o.get(it) }?.toMutableList() ?: mutableListOf())
    })
    obj.set("entries", NativeFunction("entries", 1) { _, _, args ->
        val o = arg(args, 0) as? JsObject
        JsArray(o?.ownKeys()?.map { k -> JsArray(mutableListOf(JsString(k), o.get(k))) as JsValue }?.toMutableList() ?: mutableListOf())
    })
    obj.set("assign", NativeFunction("assign", 2) { _, _, args ->
        val target = arg(args, 0) as? JsObject ?: JsObject()
        for (src in args.drop(1)) if (src is JsObject) for (k in src.ownKeys()) target.set(k, src.get(k))
        target
    })
    return obj
}

private fun makeArrayCtor(): JsFunction = object : JsFunction("Array") {
    override fun call(interpreter: Interpreter, thisArg: JsValue, args: List<JsValue>): JsValue {
        if (args.size == 1 && args[0] is JsNumber) {
            return JsArray(MutableList((args[0] as JsNumber).value.toInt().coerceAtLeast(0)) { JsUndefined })
        }
        return JsArray(args.toMutableList())
    }

    override fun get(name: String): JsValue = when (name) {
        "isArray" -> NativeFunction("isArray", 1) { _, _, a -> JsBoolean(arg(a, 0) is JsArray) }
        else -> super.get(name)
    }
}

/** Resolves `obj.key(...)` for built-in Array/String methods; null means "not a built-in, fall back to normal property lookup". */
fun builtinMethodCall(interpreter: Interpreter, obj: JsValue, key: String, args: List<JsValue>): JsValue? = when (obj) {
    is JsArray -> arrayMethod(interpreter, obj, key, args)
    is JsString -> stringMethod(interpreter, obj, key, args)
    is JsNumber -> numberMethod(obj, key, args)
    else -> null
}

private fun numberMethod(n: JsNumber, key: String, args: List<JsValue>): JsValue? = when (key) {
    "toFixed" -> {
        val digits = (args.getOrNull(0) as? JsNumber)?.value?.toInt() ?: 0
        JsString(String.format(java.util.Locale.US, "%.${digits.coerceIn(0, 100)}f", n.value))
    }
    "toString" -> {
        val radix = (args.getOrNull(0) as? JsNumber)?.value?.toInt() ?: 10
        if (radix == 10) JsString(formatNumber(n.value)) else JsString(n.value.toLong().toString(radix))
    }
    "toPrecision" -> {
        val precision = (args.getOrNull(0) as? JsNumber)?.value?.toInt()
        if (precision == null) {
            JsString(formatNumber(n.value))
        } else {
            JsString(java.math.BigDecimal(n.value).round(java.math.MathContext(precision.coerceIn(1, 100))).toPlainString())
        }
    }
    else -> null
}

private fun normalizeIndex(v: JsValue, len: Int, default: Int): Int {
    if (v == JsUndefined) return default
    var i = toNumber(v).toInt()
    if (i < 0) i += len
    return i.coerceIn(0, len)
}

private fun arrayMethod(interpreter: Interpreter, arr: JsArray, key: String, args: List<JsValue>): JsValue? = when (key) {
    "push" -> { arr.elements.addAll(args); JsNumber(arr.elements.size.toDouble()) }
    "pop" -> if (arr.elements.isEmpty()) JsUndefined else arr.elements.removeAt(arr.elements.size - 1)
    "shift" -> if (arr.elements.isEmpty()) JsUndefined else arr.elements.removeAt(0)
    "unshift" -> { arr.elements.addAll(0, args); JsNumber(arr.elements.size.toDouble()) }
    "slice" -> {
        val len = arr.elements.size
        val start = normalizeIndex(arg(args, 0), len, 0)
        val end = normalizeIndex(arg(args, 1), len, len)
        JsArray(if (start < end) arr.elements.subList(start, end).toMutableList() else mutableListOf())
    }
    "splice" -> {
        val len = arr.elements.size
        val start = normalizeIndex(arg(args, 0), len, 0)
        val deleteCount = if (args.size > 1) toNumber(args[1]).toInt().coerceIn(0, len - start) else len - start
        val removed = ArrayList<JsValue>()
        repeat(deleteCount) { removed.add(arr.elements.removeAt(start)) }
        arr.elements.addAll(start, args.drop(2))
        JsArray(removed)
    }
    "indexOf" -> JsNumber(arr.elements.indexOfFirst { strictEquals(it, arg(args, 0)) }.toDouble())
    "includes" -> JsBoolean(arr.elements.any { strictEquals(it, arg(args, 0)) })
    "join" -> {
        val sep = if (args.isNotEmpty()) toJsString(args[0]) else ","
        JsString(arr.elements.joinToString(sep) { if (it == JsUndefined || it == JsNull) "" else toJsString(it) })
    }
    "reverse" -> { arr.elements.reverse(); arr }
    "concat" -> {
        val result = ArrayList(arr.elements)
        for (a in args) if (a is JsArray) result.addAll(a.elements) else result.add(a)
        JsArray(result)
    }
    "forEach" -> {
        val fn = args.getOrNull(0) as? JsFunction
        if (fn != null) arr.elements.toList().forEachIndexed { idx, v -> fn.call(interpreter, JsUndefined, listOf(v, JsNumber(idx.toDouble()), arr)) }
        JsUndefined
    }
    "map" -> {
        val fn = args.getOrNull(0) as? JsFunction
        JsArray(arr.elements.toList().mapIndexed { idx, v -> fn?.call(interpreter, JsUndefined, listOf(v, JsNumber(idx.toDouble()), arr)) ?: JsUndefined }.toMutableList())
    }
    "filter" -> {
        val fn = args.getOrNull(0) as? JsFunction
        JsArray(
            arr.elements.toList().filterIndexed { idx, v ->
                fn != null && isTruthy(fn.call(interpreter, JsUndefined, listOf(v, JsNumber(idx.toDouble()), arr)))
            }.toMutableList()
        )
    }
    "find" -> {
        val fn = args.getOrNull(0) as? JsFunction
        arr.elements.toList().withIndex().firstOrNull { (idx, v) ->
            fn != null && isTruthy(fn.call(interpreter, JsUndefined, listOf(v, JsNumber(idx.toDouble()), arr)))
        }?.value ?: JsUndefined
    }
    "findIndex" -> {
        val fn = args.getOrNull(0) as? JsFunction
        JsNumber(
            arr.elements.toList().withIndex().indexOfFirst { (idx, v) ->
                fn != null && isTruthy(fn.call(interpreter, JsUndefined, listOf(v, JsNumber(idx.toDouble()), arr)))
            }.toDouble()
        )
    }
    "reduce" -> {
        val fn = args.getOrNull(0) as? JsFunction
        var acc: JsValue
        var startIdx: Int
        if (args.size > 1) {
            acc = args[1]
            startIdx = 0
        } else {
            acc = arr.elements.getOrElse(0) { JsUndefined }
            startIdx = 1
        }
        for (idx in startIdx until arr.elements.size) {
            acc = fn?.call(interpreter, JsUndefined, listOf(acc, arr.elements[idx], JsNumber(idx.toDouble()), arr)) ?: acc
        }
        acc
    }
    "sort" -> {
        val fn = args.getOrNull(0) as? JsFunction
        val sorted = arr.elements.sortedWith { a, b ->
            if (fn != null) {
                val n = toNumber(fn.call(interpreter, JsUndefined, listOf(a, b)))
                if (n < 0) -1 else if (n > 0) 1 else 0
            } else {
                toJsString(a).compareTo(toJsString(b))
            }
        }
        arr.elements.clear()
        arr.elements.addAll(sorted)
        arr
    }
    "flat" -> JsArray(arr.elements.flatMap { if (it is JsArray) it.elements else listOf(it) }.toMutableList())
    else -> null
}

private fun stringMethod(interpreter: Interpreter, s: JsString, key: String, args: List<JsValue>): JsValue? {
    val v = s.value
    return when (key) {
        "toUpperCase" -> JsString(v.uppercase())
        "toLowerCase" -> JsString(v.lowercase())
        "trim" -> JsString(v.trim())
        "trimStart" -> JsString(v.trimStart())
        "trimEnd" -> JsString(v.trimEnd())
        "indexOf" -> JsNumber(v.indexOf(toJsString(arg(args, 0))).toDouble())
        "lastIndexOf" -> JsNumber(v.lastIndexOf(toJsString(arg(args, 0))).toDouble())
        "includes" -> JsBoolean(v.contains(toJsString(arg(args, 0))))
        "startsWith" -> JsBoolean(v.startsWith(toJsString(arg(args, 0))))
        "endsWith" -> JsBoolean(v.endsWith(toJsString(arg(args, 0))))
        "slice" -> {
            val len = v.length
            val start = normalizeIndex(arg(args, 0), len, 0)
            val end = normalizeIndex(arg(args, 1), len, len)
            JsString(if (start < end) v.substring(start, end) else "")
        }
        "substring" -> {
            val len = v.length
            var start = toNumber(arg(args, 0)).toInt().coerceIn(0, len)
            var end = if (args.size > 1) toNumber(args[1]).toInt().coerceIn(0, len) else len
            if (start > end) { val t = start; start = end; end = t }
            JsString(v.substring(start, end))
        }
        "charAt" -> JsString(v.getOrNull(toNumber(arg(args, 0)).toInt())?.toString() ?: "")
        "charCodeAt" -> v.getOrNull(toNumber(arg(args, 0)).toInt())?.let { JsNumber(it.code.toDouble()) } ?: JsNumber(Double.NaN)
        "split" -> {
            val sep = args.getOrNull(0)
            when {
                args.isEmpty() || sep == JsUndefined -> JsArray(mutableListOf(JsString(v)))
                sep is JsRegExp -> JsArray(sep.kotlinRegex.split(v).map { JsString(it) as JsValue }.toMutableList())
                else -> {
                    val sepStr = toJsString(sep!!)
                    val parts = if (sepStr.isEmpty()) v.map { it.toString() } else v.split(sepStr)
                    JsArray(parts.map { JsString(it) as JsValue }.toMutableList())
                }
            }
        }
        "replace" -> {
            val pattern = args.getOrNull(0)
            if (pattern is JsRegExp) {
                JsString(regexReplace(interpreter, v, pattern, arg(args, 1)))
            } else {
                JsString(v.replaceFirst(toJsString(arg(args, 0)), toJsString(arg(args, 1))))
            }
        }
        "replaceAll" -> {
            val pattern = args.getOrNull(0)
            if (pattern is JsRegExp) {
                JsString(regexReplace(interpreter, v, JsRegExp(pattern.source, pattern.flags + if (pattern.global) "" else "g"), arg(args, 1)))
            } else {
                JsString(v.replace(toJsString(arg(args, 0)), toJsString(arg(args, 1))))
            }
        }
        "match" -> {
            val re = args.getOrNull(0) as? JsRegExp ?: return null
            if (re.global) {
                val matches = re.kotlinRegex.findAll(v).map { JsString(it.value) as JsValue }.toMutableList()
                if (matches.isEmpty()) JsNull else JsArray(matches)
            } else {
                val m = re.kotlinRegex.find(v) ?: return JsNull
                JsArray(m.groupValues.map { JsString(it) as JsValue }.toMutableList())
            }
        }
        "repeat" -> JsString(v.repeat(toNumber(arg(args, 0)).toInt().coerceAtLeast(0)))
        "padStart" -> JsString(padStr(v, toNumber(arg(args, 0)).toInt(), if (args.size > 1) toJsString(args[1]) else " ", start = true))
        "padEnd" -> JsString(padStr(v, toNumber(arg(args, 0)).toInt(), if (args.size > 1) toJsString(args[1]) else " ", start = false))
        "concat" -> JsString(v + args.joinToString("") { toJsString(it) })
        else -> null
    }
}

private fun padStr(s: String, targetLen: Int, pad: String, start: Boolean): String {
    if (s.length >= targetLen || pad.isEmpty()) return s
    val sb = StringBuilder()
    while (sb.length < targetLen - s.length) sb.append(pad)
    val padding = sb.substring(0, targetLen - s.length)
    return if (start) padding + s else s + padding
}

/** Exposed for reuse outside JSON.parse itself - e.g. a fetch() Response's `.json()`. */
fun parseJsonToJsValue(text: String): JsValue = JsonParser(text).parse()

/** A small hand-written JSON parser backing `JSON.parse`. */
private class JsonParser(private val s: String) {
    private var i = 0

    fun parse(): JsValue {
        skipWs()
        val v = parseValue()
        return v
    }

    private fun skipWs() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun parseValue(): JsValue {
        skipWs()
        return when {
            s.startsWith("null", i) -> { i += 4; JsNull }
            s.startsWith("true", i) -> { i += 4; JsBoolean(true) }
            s.startsWith("false", i) -> { i += 5; JsBoolean(false) }
            s[i] == '"' -> JsString(parseString())
            s[i] == '[' -> parseArray()
            s[i] == '{' -> parseObject()
            else -> parseNumber()
        }
    }

    private fun parseString(): String {
        i++ // opening quote
        val sb = StringBuilder()
        while (s[i] != '"') {
            if (s[i] == '\\') {
                i++
                when (s[i]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'u' -> { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                    else -> sb.append(s[i])
                }
                i++
            } else {
                sb.append(s[i])
                i++
            }
        }
        i++ // closing quote
        return sb.toString()
    }

    private fun parseNumber(): JsValue {
        val start = i
        if (s[i] == '-') i++
        while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
        return JsNumber(s.substring(start, i).toDouble())
    }

    private fun parseArray(): JsValue {
        i++ // '['
        val arr = ArrayList<JsValue>()
        skipWs()
        if (s[i] == ']') { i++; return JsArray(arr) }
        while (true) {
            arr.add(parseValue())
            skipWs()
            if (s[i] == ',') { i++; skipWs(); continue }
            break
        }
        i++ // ']'
        return JsArray(arr)
    }

    private fun parseObject(): JsValue {
        i++ // '{'
        val obj = JsObject()
        skipWs()
        if (s[i] == '}') { i++; return obj }
        while (true) {
            skipWs()
            val key = parseString()
            skipWs()
            i++ // ':'
            obj.set(key, parseValue())
            skipWs()
            if (s[i] == ',') { i++; continue }
            break
        }
        i++ // '}'
        return obj
    }
}
