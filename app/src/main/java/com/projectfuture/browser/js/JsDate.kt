package com.projectfuture.browser.js

import java.util.Calendar
import java.util.TimeZone

/**
 * A bounded `Date` implementation backed by `java.util.Calendar` (a
 * platform primitive, same tier as using Android's BitmapFactory for
 * image decoding elsewhere in this project - not "browser engine"
 * logic). Local-time getters/setters only (no `getUTC*`/`setUTC*`
 * variants), and date-string parsing understands only a plain ISO 8601
 * subset (`YYYY-MM-DD`, optionally `THH:mm:ss(.sss)?Z?`) - not RFC 2822
 * or the full grab-bag of formats real engines accept. Each accessor
 * method (`getFullYear`, etc.) is a freshly-built `NativeFunction` per
 * `get()` call rather than a shared singleton, so `date.getFullYear ===
 * date.getFullYear` would be `false` - a harmless deviation from the
 * real spec for the overwhelmingly common case of calling it directly.
 */
class JsDate(epochMillis: Double) : JsObject() {
    private val calendar: Calendar = Calendar.getInstance().apply {
        timeInMillis = if (epochMillis.isNaN()) 0L else epochMillis.toLong()
    }
    var isInvalid: Boolean = epochMillis.isNaN()
        private set

    private fun getter(compute: () -> Double): JsFunction = NativeFunction("", 0) { _, _, _ ->
        JsNumber(if (isInvalid) Double.NaN else compute())
    }

    private fun stringGetter(compute: () -> String): JsFunction = NativeFunction("", 0) { _, _, _ ->
        JsString(if (isInvalid) "Invalid Date" else compute())
    }

    private fun setter(field: Int): JsFunction = NativeFunction("", 1) { _, _, args ->
        calendar.set(field, toNumber(args.getOrElse(0) { JsUndefined }).toInt())
        isInvalid = false
        JsNumber(calendar.timeInMillis.toDouble())
    }

    override fun get(name: String): JsValue = when (name) {
        "getFullYear" -> getter { calendar.get(Calendar.YEAR).toDouble() }
        "getMonth" -> getter { calendar.get(Calendar.MONTH).toDouble() }
        "getDate" -> getter { calendar.get(Calendar.DAY_OF_MONTH).toDouble() }
        "getDay" -> getter { (calendar.get(Calendar.DAY_OF_WEEK) - 1).toDouble() }
        "getHours" -> getter { calendar.get(Calendar.HOUR_OF_DAY).toDouble() }
        "getMinutes" -> getter { calendar.get(Calendar.MINUTE).toDouble() }
        "getSeconds" -> getter { calendar.get(Calendar.SECOND).toDouble() }
        "getMilliseconds" -> getter { calendar.get(Calendar.MILLISECOND).toDouble() }
        "getTime", "valueOf" -> getter { calendar.timeInMillis.toDouble() }
        "getTimezoneOffset" -> getter { (-calendar.timeZone.getOffset(calendar.timeInMillis) / 60000).toDouble() }
        "setFullYear" -> setter(Calendar.YEAR)
        "setMonth" -> setter(Calendar.MONTH)
        "setDate" -> setter(Calendar.DAY_OF_MONTH)
        "setHours" -> setter(Calendar.HOUR_OF_DAY)
        "setMinutes" -> setter(Calendar.MINUTE)
        "setSeconds" -> setter(Calendar.SECOND)
        "setMilliseconds" -> setter(Calendar.MILLISECOND)
        "setTime" -> NativeFunction("setTime", 1) { _, _, args ->
            calendar.timeInMillis = toNumber(args.getOrElse(0) { JsUndefined }).toLong()
            isInvalid = false
            JsNumber(calendar.timeInMillis.toDouble())
        }
        "toISOString", "toJSON" -> stringGetter { formatIso(calendar) }
        "toDateString" -> stringGetter { formatDateOnly(calendar) }
        "toTimeString" -> stringGetter { formatTimeOnly(calendar) }
        "toString" -> stringGetter { "${formatDateOnly(calendar)} ${formatTimeOnly(calendar)}" }
        else -> super.get(name)
    }

    companion object {
        private fun pad(n: Int, width: Int = 2): String = n.toString().padStart(width, '0')
        private val DAY_NAMES = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        private val MONTH_NAMES = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

        private fun formatIso(cal: Calendar): String {
            val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            utc.timeInMillis = cal.timeInMillis
            return "${utc.get(Calendar.YEAR)}-${pad(utc.get(Calendar.MONTH) + 1)}-${pad(utc.get(Calendar.DAY_OF_MONTH))}" +
                "T${pad(utc.get(Calendar.HOUR_OF_DAY))}:${pad(utc.get(Calendar.MINUTE))}:${pad(utc.get(Calendar.SECOND))}." +
                "${pad(utc.get(Calendar.MILLISECOND), 3)}Z"
        }

        private fun formatDateOnly(cal: Calendar): String =
            "${DAY_NAMES[cal.get(Calendar.DAY_OF_WEEK) - 1]} ${MONTH_NAMES[cal.get(Calendar.MONTH)]} " +
                "${pad(cal.get(Calendar.DAY_OF_MONTH))} ${cal.get(Calendar.YEAR)}"

        private fun formatTimeOnly(cal: Calendar): String =
            "${pad(cal.get(Calendar.HOUR_OF_DAY))}:${pad(cal.get(Calendar.MINUTE))}:${pad(cal.get(Calendar.SECOND))}"

        /** ISO 8601 subset only - see class doc. Returns null (-> an "Invalid Date") if it doesn't match. */
        fun parseDateString(s: String): Double? {
            val regex = Regex("""^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,3}))?)?(Z)?)?$""")
            val m = regex.matchEntire(s.trim()) ?: return null
            val g = m.groupValues
            fun part(i: Int) = g[i].ifEmpty { "0" }.toInt()
            val cal = Calendar.getInstance(if (g[8] == "Z") TimeZone.getTimeZone("UTC") else TimeZone.getDefault())
            cal.clear()
            cal.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), part(4), part(5), part(6))
            cal.set(Calendar.MILLISECOND, g[7].ifEmpty { "0" }.padEnd(3, '0').take(3).toInt())
            return cal.timeInMillis.toDouble()
        }
    }
}

fun makeDateCtor(): JsFunction {
    val ctor = object : JsFunction("Date") {
        override fun call(interpreter: Interpreter, thisArg: JsValue, args: List<JsValue>): JsValue {
            val millis = when {
                args.isEmpty() -> System.currentTimeMillis().toDouble()
                args.size == 1 && args[0] is JsString -> JsDate.parseDateString((args[0] as JsString).value) ?: Double.NaN
                args.size == 1 -> toNumber(args[0])
                else -> {
                    val cal = Calendar.getInstance()
                    cal.clear()
                    cal.set(
                        toNumber(args.getOrElse(0) { JsNumber(1970.0) }).toInt(),
                        toNumber(args.getOrElse(1) { JsNumber(0.0) }).toInt(),
                        toNumber(args.getOrElse(2) { JsNumber(1.0) }).toInt(),
                        toNumber(args.getOrElse(3) { JsNumber(0.0) }).toInt(),
                        toNumber(args.getOrElse(4) { JsNumber(0.0) }).toInt(),
                        toNumber(args.getOrElse(5) { JsNumber(0.0) }).toInt()
                    )
                    cal.set(Calendar.MILLISECOND, toNumber(args.getOrElse(6) { JsNumber(0.0) }).toInt())
                    cal.timeInMillis.toDouble()
                }
            }
            return JsDate(millis)
        }
    }
    ctor.set("now", NativeFunction("now", 0) { _, _, _ -> JsNumber(System.currentTimeMillis().toDouble()) })
    ctor.set("parse", NativeFunction("parse", 1) { _, _, args -> JsNumber(JsDate.parseDateString(toJsString(args.getOrElse(0) { JsUndefined })) ?: Double.NaN) })
    return ctor
}
