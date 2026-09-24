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
    val kotlinRegex: Regex = try {
        Regex(translateJsRegex(source, flags.contains('u')), regexOptions(flags))
    } catch (_: IllegalArgumentException) {
        throw jsError("Invalid regular expression: /$source/", "SyntaxError")
    }
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
        return buildMatchArray(this, input, m)
    }

    /** Names declared by `(?<name>...)` in [source], in the order they appear. Extracted from the
     * source text itself, since Kotlin's `Regex`/`MatchNamedGroupCollection` can look a name up but
     * doesn't expose the full set of names a pattern declares. The identifier-start restriction
     * (`[A-Za-z_$]` first char) means this never mistakes a `(?<=` lookbehind or `(?<!` negative
     * lookbehind - whose second char is `=`/`!`, not a legal identifier char - for a named group. */
    val groupNames: List<String> by lazy {
        NAMED_GROUP.findAll(source).map { it.groupValues[1] }.toList()
    }

    companion object {
        private val NAMED_GROUP = Regex("\\(\\?<([A-Za-z_$][A-Za-z0-9_$]*)>")

        private val QUANTIFIER = Regex("^\\{\\d+(,\\d*)?\\}")

        /**
         * Rewrites JS regex syntax that Java's engine reads differently: an unescaped `[` inside a
         * character class (JS takes it literally, Java opens a nested class), `[^]` (any character)
         * and `[]` (never matches), a `{` that isn't a quantifier, `&&` inside a class (Java's
         * intersection operator), and `\u{...}` code point escapes.
         */
        fun translateJsRegex(src: String, unicode: Boolean): String {
            val out = StringBuilder()
            var i = 0
            var inClass = false
            while (i < src.length) {
                val c = src[i]
                if (c == '\\' && i + 1 < src.length) {
                    if (unicode && src[i + 1] == 'u' && i + 2 < src.length && src[i + 2] == '{') {
                        val end = src.indexOf('}', i + 3)
                        if (end != -1) { out.append("\\x{").append(src, i + 3, end).append('}'); i = end + 1; continue }
                    }
                    // `\0` is NUL in JS; Java reads `\0` as the start of an octal escape and needs digits after it.
                    if (src[i + 1] == '0' && (i + 2 >= src.length || !src[i + 2].isDigit())) { out.append("\\x00"); i += 2; continue }
                    out.append(c).append(src[i + 1])
                    i += 2
                    continue
                }
                if (inClass) {
                    when {
                        c == '[' -> out.append("\\[")
                        c == '&' && i + 1 < src.length && src[i + 1] == '&' -> { out.append("\\&\\&"); i++ }
                        c == ']' -> { inClass = false; out.append(c) }
                        else -> out.append(c)
                    }
                    i++
                    continue
                }
                when {
                    src.startsWith("[^]", i) -> { out.append("[\\s\\S]"); i += 3; continue }
                    src.startsWith("[]", i) -> { out.append("(?!)"); i += 2; continue }
                    c == '[' -> {
                        inClass = true
                        out.append('[')
                        if (i + 1 < src.length && src[i + 1] == '^') { out.append('^'); i++ }
                    }
                    c == '{' && !QUANTIFIER.containsMatchIn(src.substring(i)) -> out.append("\\{")
                    c == '}' && !isQuantifierEnd(src, i) -> out.append("\\}")
                    else -> out.append(c)
                }
                i++
            }
            return out.toString()
        }

        private fun isQuantifierEnd(src: String, i: Int): Boolean {
            val open = src.lastIndexOf('{', i)
            return open != -1 && QUANTIFIER.matches(src.substring(open, i + 1))
        }

        private fun regexOptions(flags: String): Set<RegexOption> {
            val options = HashSet<RegexOption>()
            if (flags.contains('i')) options.add(RegexOption.IGNORE_CASE)
            if (flags.contains('m')) options.add(RegexOption.MULTILINE)
            if (flags.contains('s')) options.add(RegexOption.DOT_MATCHES_ALL)
            return options
        }
    }
}

/** Builds the array `exec()`/non-global `match()` return: the matched substrings (whole match plus
 * capture groups, like [MatchResult.groupValues]) with `index`/`input`/`groups` attached, per spec.
 * `groups` is `undefined` when the pattern declares no named capture groups, and otherwise an object
 * mapping each declared name to its captured text (or `undefined` if that group didn't participate). */
fun buildMatchArray(re: JsRegExp, input: String, m: MatchResult): JsArray {
    val result = JsArray(m.groupValues.map { JsString(it) as JsValue }.toMutableList())
    result.set("index", JsNumber(m.range.first.toDouble()))
    result.set("input", JsString(input))
    val names = re.groupNames
    result.set(
        "groups",
        if (names.isEmpty()) {
            JsUndefined
        } else {
            val groups = JsObject()
            val named = m.groups as? MatchNamedGroupCollection
            for (n in names) {
                val g = named?.get(n)
                groups.set(n, if (g != null) JsString(g.value) else JsUndefined)
            }
            groups
        }
    )
    return result
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

    // Plain string: expand $-patterns by hand against each match, rather than handing the string to
    // Kotlin/Java's own replacement-string parser - that parser uses *Java's* replacement syntax
    // (`\$` for a literal `$`, no `$$`/$<name>/$`/$' support), which isn't JS's and previously caused
    // a literal "$$" in the replacement to throw ("Illegal group reference") instead of becoming "$",
    // and "$1" to silently vanish (empty) instead of resolving against the right match once global.
    val template = toJsString(replacement)
    val matches: Iterable<MatchResult> = if (re.global) re.kotlinRegex.findAll(input).asIterable() else listOfNotNull(re.kotlinRegex.find(input))
    val sb = StringBuilder()
    var lastEnd = 0
    for (m in matches) {
        sb.append(input, lastEnd, m.range.first)
        sb.append(expandReplacementTemplate(template, m, input, re.groupNames.isNotEmpty()))
        lastEnd = m.range.last + 1
    }
    sb.append(input, lastEnd, input.length)
    return sb.toString()
}

/** Expands a JS `String.replace` replacement template (`$$`, `$&`, `` $` ``, `$'`, `$<name>`, `$n`/`$nn`)
 * against one match, per the spec's `GetSubstitution`. An unrecognized `$`-sequence - e.g. `$9` when the
 * pattern has fewer than 9 groups, or `$<name>` when it declares no such named group - is left as the
 * literal text it is in JS, not treated as a (possibly out-of-range) backreference. */
private fun expandReplacementTemplate(template: String, match: MatchResult, input: String, hasNamedGroups: Boolean): String {
    val sb = StringBuilder()
    var i = 0
    while (i < template.length) {
        val c = template[i]
        if (c != '$' || i == template.length - 1) {
            sb.append(c)
            i++
            continue
        }
        when (val next = template[i + 1]) {
            '$' -> { sb.append('$'); i += 2 }
            '&' -> { sb.append(match.value); i += 2 }
            '`' -> { sb.append(input, 0, match.range.first); i += 2 }
            '\'' -> { sb.append(input, match.range.last + 1, input.length); i += 2 }
            '<' -> {
                val end = template.indexOf('>', i + 2)
                if (end < 0 || !hasNamedGroups) {
                    // No named-capturing groups at all: per spec `$<...>` is left as literal text
                    // rather than parsed as a substitution (there's no named-captures object to
                    // resolve it against).
                    sb.append(c); i++
                } else {
                    val name = template.substring(i + 2, end)
                    val named = match.groups as? MatchNamedGroupCollection
                    // A named group that exists on the pattern but didn't match this name resolves
                    // to the empty string (like reading a missing property), not literal text.
                    val g = try { named?.get(name) } catch (e: IllegalArgumentException) { null }
                    if (g != null) sb.append(g.value)
                    i = end + 1
                }
            }
            else -> {
                if (next.isDigit()) {
                    // Greedily prefer two digits (e.g. $12) if that group exists, else fall back to one.
                    val twoDigits = if (i + 2 < template.length && template[i + 2].isDigit()) template.substring(i + 1, i + 3) else null
                    val twoNum = twoDigits?.toIntOrNull()
                    if (twoNum != null && twoNum in 1 until match.groups.size) {
                        sb.append(match.groups[twoNum]?.value ?: "")
                        i += 3
                    } else {
                        val oneNum = next.toString().toInt()
                        if (oneNum in 1 until match.groups.size) {
                            sb.append(match.groups[oneNum]?.value ?: "")
                            i += 2
                        } else {
                            sb.append(c)
                            i++
                        }
                    }
                } else {
                    sb.append(c)
                    i++
                }
            }
        }
    }
    return sb.toString()
}
