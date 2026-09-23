package com.projectfuture.browser.js

/**
 * Backed by `kotlin.text.Regex` (itself `java.util.regex.Pattern`) - a
 * standard-library regex engine, the same tier as `java.util.zip.Inflater`
 * for WOFF decompression or `BitmapFactory` for images elsewhere in this
 * project, not "browser engine" logic. JS and Java regex syntax are
 * mostly compatible (character classes, quantifiers, groups, lookaheads);
 * differences aren't translated, so an unsupported construct throws at
 * construction time and the regex literal/constructor call fails.
 *
 * `exec()` is simplified: it always finds the first match rather than
 * tracking `lastIndex` for stateful repeated calls on a global regex,
 * which is the one real behavioral gap versus the spec.
 */
class JsRegExp(val source: String, val flags: String) : JsObject() {
    val kotlinRegex: Regex = Regex(source, regexOptions(flags))
    val global: Boolean = flags.contains('g')

    override fun get(name: String): JsValue = when (name) {
        "source" -> JsString(source)
        "flags" -> JsString(flags)
        "global" -> JsBoolean(global)
        "ignoreCase" -> JsBoolean(flags.contains('i'))
        "multiline" -> JsBoolean(flags.contains('m'))
        "test" -> NativeFunction("test", 1) { _, _, args ->
            JsBoolean(kotlinRegex.containsMatchIn(toJsString(args.getOrElse(0) { JsUndefined })))
        }
        "exec" -> NativeFunction("exec", 1) { _, _, args -> execFirst(toJsString(args.getOrElse(0) { JsUndefined })) }
        else -> super.get(name)
    }

    private fun execFirst(input: String): JsValue {
        val m = kotlinRegex.find(input) ?: return JsNull
        val result = JsArray(m.groupValues.map { JsString(it) as JsValue }.toMutableList())
        result.set("index", JsNumber(m.range.first.toDouble()))
        result.set("input", JsString(input))
        return result
    }

    companion object {
        private fun regexOptions(flags: String): Set<RegexOption> {
            val options = HashSet<RegexOption>()
            if (flags.contains('i')) options.add(RegexOption.IGNORE_CASE)
            if (flags.contains('m')) options.add(RegexOption.MULTILINE)
            if (flags.contains('s')) options.add(RegexOption.DOT_MATCHES_ALL)
            return options
        }
    }
}

fun makeRegExpCtor(): JsFunction = object : JsFunction("RegExp") {
    override fun call(interpreter: Interpreter, thisArg: JsValue, args: List<JsValue>): JsValue {
        val first = args.getOrNull(0)
        return if (first is JsRegExp) {
            JsRegExp(first.source, (args.getOrNull(1) as? JsString)?.value ?: first.flags)
        } else {
            val pattern = args.getOrNull(0)?.let { toJsString(it) } ?: ""
            val flags = (args.getOrNull(1) as? JsString)?.value ?: ""
            JsRegExp(pattern, flags)
        }
    }
}

/**
 * Runs a JS regex replace, supporting both a callback function and a plain
 * replacement string with `$1`/`$&`-style backreferences. These two cases
 * need genuinely different handling: a function's return value is spliced
 * in literally, but a plain string's `$1` etc. must be *resolved against
 * the match* rather than inserted verbatim - conflating them (e.g. by
 * always using Kotlin's lambda-based `replace`, whose return value is
 * always literal) would silently break backreferences.
 */
fun regexReplace(interpreter: Interpreter, input: String, re: JsRegExp, replacement: JsValue): String {
    if (replacement is JsFunction) {
        fun invoke(match: MatchResult): String {
            val args = ArrayList<JsValue>()
            args.add(JsString(match.value))
            for (g in match.groupValues.drop(1)) args.add(JsString(g))
            args.add(JsNumber(match.range.first.toDouble()))
            args.add(JsString(input))
            return toJsString(replacement.call(interpreter, JsUndefined, args))
        }
        return if (re.global) {
            re.kotlinRegex.replace(input) { m -> invoke(m) }
        } else {
            val m = re.kotlinRegex.find(input) ?: return input
            input.substring(0, m.range.first) + invoke(m) + input.substring(m.range.last + 1)
        }
    }

    // Plain string: translate JS's $& (whole match) to Kotlin's $0, then let Kotlin's
    // native backreference handling do the rest for the global (whole-string) case.
    val replStr = toJsString(replacement).replace("$&", "\$0")
    return if (re.global) {
        re.kotlinRegex.replace(input, replStr)
    } else {
        val m = re.kotlinRegex.find(input) ?: return input
        input.substring(0, m.range.first) + expandBackreferences(replStr, m) + input.substring(m.range.last + 1)
    }
}

private val BACKREF = Regex("\\$(\\d+)")

/** Kotlin's `Regex.replace` only expands `$1`-style backreferences when replacing across a whole string, not one match - so a non-global JS replace resolves them by hand against that single match. */
private fun expandBackreferences(template: String, match: MatchResult): String =
    BACKREF.replace(template) { mr -> match.groupValues.getOrElse(mr.groupValues[1].toInt()) { "" } }
