package com.projectfuture.browser.js

import java.nio.ByteBuffer
import java.nio.ByteOrder

class JsArrayBuffer(val bytes: ByteArray) : JsObject() {
    constructor(size: Int) : this(ByteArray(size.coerceAtLeast(0)))

    override fun get(name: String): JsValue = when (name) {
        "byteLength" -> JsNumber(bytes.size.toDouble())
        "slice" -> NativeFunction("slice", 2) { _, _, args ->
            val len = bytes.size
            val start = clampIndex(args.getOrNull(0), len, 0)
            val end = clampIndex(args.getOrNull(1), len, len)
            JsArrayBuffer(if (start < end) bytes.copyOfRange(start, end) else ByteArray(0))
        }
        else -> super.get(name)
    }
}

private fun clampIndex(v: JsValue?, len: Int, default: Int): Int {
    if (v == null || v == JsUndefined) return default
    var i = toNumber(v).toInt()
    if (i < 0) i += len
    return i.coerceIn(0, len)
}

fun makeArrayBufferCtor(): JsFunction = object : JsFunction("ArrayBuffer") {
    override fun call(interpreter: Interpreter, thisArg: JsValue, args: List<JsValue>): JsValue =
        JsArrayBuffer(toNumber(args.getOrElse(0) { JsNumber(0.0) }).toInt().coerceAtLeast(0))

    override fun get(name: String): JsValue = when (name) {
        "isView" -> NativeFunction("isView", 1) { _, _, a -> JsBoolean(a.getOrNull(0) is JsTypedArray) }
        else -> super.get(name)
    }
}

enum class TypedArrayKind(val bytesPer: Int, val label: String) {
    INT8(1, "Int8Array"), UINT8(1, "Uint8Array"), UINT8_CLAMPED(1, "Uint8ClampedArray"),
    INT16(2, "Int16Array"), UINT16(2, "Uint16Array"),
    INT32(4, "Int32Array"), UINT32(4, "Uint32Array"),
    FLOAT32(4, "Float32Array"), FLOAT64(8, "Float64Array")
}

/**
 * A typed-array view over a [JsArrayBuffer]. Numeric indices are handled
 * directly in [get]/[set] (the same "index-as-property-name" approach
 * JsArray itself uses - see JsValue.kt's class doc); everything else falls
 * back to plain property storage on this JsObject, the same simplification
 * the rest of this flat-object model makes everywhere else.
 */
class JsTypedArray(val buffer: JsArrayBuffer, val byteOffset: Int, val length: Int, val kind: TypedArrayKind) : JsObject() {
    private val bb = ByteBuffer.wrap(buffer.bytes).order(ByteOrder.LITTLE_ENDIAN)

    fun toList(): List<JsValue> = (0 until length).map { readAt(it) }

    private fun readAt(idx: Int): JsValue {
        val off = byteOffset + idx * kind.bytesPer
        val n = when (kind) {
            TypedArrayKind.INT8 -> bb.get(off).toDouble()
            TypedArrayKind.UINT8, TypedArrayKind.UINT8_CLAMPED -> (bb.get(off).toInt() and 0xFF).toDouble()
            TypedArrayKind.INT16 -> bb.getShort(off).toDouble()
            TypedArrayKind.UINT16 -> (bb.getShort(off).toInt() and 0xFFFF).toDouble()
            TypedArrayKind.INT32 -> bb.getInt(off).toDouble()
            TypedArrayKind.UINT32 -> (bb.getInt(off).toLong() and 0xFFFFFFFFL).toDouble()
            TypedArrayKind.FLOAT32 -> bb.getFloat(off).toDouble()
            TypedArrayKind.FLOAT64 -> bb.getDouble(off)
        }
        return JsNumber(n)
    }

    private fun writeAt(idx: Int, value: Double) {
        val off = byteOffset + idx * kind.bytesPer
        when (kind) {
            TypedArrayKind.INT8 -> bb.put(off, value.toInt().toByte())
            TypedArrayKind.UINT8 -> bb.put(off, (value.toInt() and 0xFF).toByte())
            TypedArrayKind.UINT8_CLAMPED -> bb.put(off, value.toInt().coerceIn(0, 255).toByte())
            TypedArrayKind.INT16 -> bb.putShort(off, value.toInt().toShort())
            TypedArrayKind.UINT16 -> bb.putShort(off, (value.toInt() and 0xFFFF).toShort())
            TypedArrayKind.INT32 -> bb.putInt(off, value.toInt())
            TypedArrayKind.UINT32 -> bb.putInt(off, value.toLong().toInt())
            TypedArrayKind.FLOAT32 -> bb.putFloat(off, value.toFloat())
            TypedArrayKind.FLOAT64 -> bb.putDouble(off, value)
        }
    }

    override fun get(name: String): JsValue {
        when (name) {
            "length" -> return JsNumber(length.toDouble())
            "byteLength" -> return JsNumber((length * kind.bytesPer).toDouble())
            "byteOffset" -> return JsNumber(byteOffset.toDouble())
            "BYTES_PER_ELEMENT" -> return JsNumber(kind.bytesPer.toDouble())
            "buffer" -> return buffer
        }
        name.toIntOrNull()?.let { idx -> return if (idx in 0 until length) readAt(idx) else JsUndefined }
        return super.get(name)
    }

    override fun set(name: String, value: JsValue) {
        name.toIntOrNull()?.let { idx -> if (idx in 0 until length) writeAt(idx, toNumber(value)); return }
        super.set(name, value)
    }

    override fun has(name: String): Boolean =
        name == "length" || name.toIntOrNull()?.let { it in 0 until length } == true || super.has(name)

    override fun ownKeys(): List<String> = (0 until length).map { it.toString() } + super.ownKeys()
}

fun makeTypedArray(kind: TypedArrayKind, length: Int): JsTypedArray = JsTypedArray(JsArrayBuffer(length * kind.bytesPer), 0, length, kind)

/** `new Int8Array(...)` etc.: length, an array-like/iterable to copy, or `(buffer, byteOffset, length)`. */
fun makeTypedArrayCtor(kind: TypedArrayKind): JsFunction {
    val ctor = object : JsFunction(kind.label) {
        override fun call(interpreter: Interpreter, thisArg: JsValue, args: List<JsValue>): JsValue {
            val a0 = args.getOrNull(0)
            return when (a0) {
                null -> makeTypedArray(kind, 0)
                is JsArrayBuffer -> {
                    val offset = (args.getOrNull(1) as? JsNumber)?.value?.toInt() ?: 0
                    val remaining = (a0.bytes.size - offset) / kind.bytesPer
                    val len = (args.getOrNull(2) as? JsNumber)?.value?.toInt() ?: remaining
                    JsTypedArray(a0, offset, len, kind)
                }
                is JsNumber -> makeTypedArray(kind, a0.value.toInt().coerceAtLeast(0))
                is JsArray -> makeTypedArray(kind, a0.elements.size).also { out -> a0.elements.forEachIndexed { i, v -> out.set(i.toString(), v) } }
                is JsTypedArray -> makeTypedArray(kind, a0.length).also { out -> a0.toList().forEachIndexed { i, v -> out.set(i.toString(), v) } }
                else -> makeTypedArray(kind, 0)
            }
        }

        override fun get(name: String): JsValue = when (name) {
            "BYTES_PER_ELEMENT" -> JsNumber(kind.bytesPer.toDouble())
            "from" -> NativeFunction("from", 2) { interp, _, a ->
                val src = a.getOrNull(0)
                val mapFn = a.getOrNull(1) as? JsFunction
                val items: List<JsValue> = when (src) {
                    is JsArray -> src.elements
                    is JsTypedArray -> src.toList()
                    is JsString -> src.value.map { JsString(it.toString()) }
                    else -> emptyList()
                }
                makeTypedArray(kind, items.size).also { out ->
                    items.forEachIndexed { i, v -> out.set(i.toString(), mapFn?.call(interp, JsUndefined, listOf(v, JsNumber(i.toDouble()))) ?: v) }
                }
            }
            "of" -> NativeFunction("of", 0) { _, _, a -> makeTypedArray(kind, a.size).also { out -> a.forEachIndexed { i, v -> out.set(i.toString(), v) } } }
            else -> super.get(name)
        }
    }
    return ctor
}

/** Resolves `typedArr.key(...)` for common Array-like methods; mirrors [arrayMethod] in Builtins.kt but reads/writes through the typed view. `map`/`filter` return a plain [JsArray] rather than a same-kind typed array - a deliberate simplification. */
fun typedArrayMethod(interpreter: Interpreter, ta: JsTypedArray, key: String, args: List<JsValue>): JsValue? = when (key) {
    "set" -> {
        val offset = (args.getOrNull(1) as? JsNumber)?.value?.toInt() ?: 0
        val items: List<JsValue> = when (val src = args.getOrNull(0)) {
            is JsArray -> src.elements
            is JsTypedArray -> src.toList()
            else -> emptyList()
        }
        items.forEachIndexed { i, v -> if (offset + i in 0 until ta.length) ta.set((offset + i).toString(), v) }
        JsUndefined
    }
    "fill" -> {
        val v = toNumber(args.getOrElse(0) { JsNumber(0.0) })
        val len = ta.length
        val start = clampIndex(args.getOrNull(1), len, 0)
        val end = clampIndex(args.getOrNull(2), len, len)
        for (i in start until end) ta.set(i.toString(), JsNumber(v))
        ta
    }
    "slice" -> {
        val len = ta.length
        val start = clampIndex(args.getOrNull(0), len, 0)
        val end = clampIndex(args.getOrNull(1), len, len)
        makeTypedArray(ta.kind, (end - start).coerceAtLeast(0)).also { out -> for (i in start until end) out.set((i - start).toString(), ta.get(i.toString())) }
    }
    "subarray" -> {
        val len = ta.length
        val start = clampIndex(args.getOrNull(0), len, 0)
        val end = clampIndex(args.getOrNull(1), len, len)
        JsTypedArray(ta.buffer, ta.byteOffset + start * ta.kind.bytesPer, (end - start).coerceAtLeast(0), ta.kind)
    }
    "join" -> JsString(ta.toList().joinToString(if (args.isEmpty()) "," else toJsString(args[0])) { toJsString(it) })
    "forEach" -> {
        val fn = args.getOrNull(0) as? JsFunction
        if (fn != null) ta.toList().forEachIndexed { idx, v -> fn.call(interpreter, JsUndefined, listOf(v, JsNumber(idx.toDouble()), ta)) }
        JsUndefined
    }
    "map" -> {
        val fn = args.getOrNull(0) as? JsFunction
        JsArray(ta.toList().mapIndexed { idx, v -> fn?.call(interpreter, JsUndefined, listOf(v, JsNumber(idx.toDouble()), ta)) ?: JsUndefined }.toMutableList())
    }
    "filter" -> {
        val fn = args.getOrNull(0) as? JsFunction
        JsArray(ta.toList().filterIndexed { idx, v -> fn != null && isTruthy(fn.call(interpreter, JsUndefined, listOf(v, JsNumber(idx.toDouble()), ta))) }.toMutableList())
    }
    "reduce" -> {
        val fn = args.getOrNull(0) as? JsFunction
        val list = ta.toList()
        var acc: JsValue
        var startIdx: Int
        if (args.size > 1) { acc = args[1]; startIdx = 0 } else { acc = list.getOrElse(0) { JsUndefined }; startIdx = 1 }
        for (idx in startIdx until list.size) acc = fn?.call(interpreter, JsUndefined, listOf(acc, list[idx], JsNumber(idx.toDouble()), ta)) ?: acc
        acc
    }
    "indexOf" -> JsNumber(ta.toList().indexOfFirst { strictEquals(it, args.getOrElse(0) { JsUndefined }) }.toDouble())
    "includes" -> JsBoolean(ta.toList().any { strictEquals(it, args.getOrElse(0) { JsUndefined }) })
    "reverse" -> {
        val vals = ta.toList().reversed()
        vals.forEachIndexed { i, v -> ta.set(i.toString(), v) }
        ta
    }
    else -> null
}
