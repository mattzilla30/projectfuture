package com.projectfuture.browser.css

class CssRule(val selector: Selector, val properties: Map<String, String>)

/**
 * A small hand-written CSS parser: enough for tag/class/id/descendant
 * selectors, comma-separated selector lists, and flat property:value
 * declarations. Most at-rules (@import, @supports, @container, ...) are
 * skipped wholesale rather than interpreted; `@font-face` (collected into
 * [fontFaceRules]) and `@media` are the two exceptions. `@media` supports
 * `min-width`/`max-width` against [viewportWidth] (by far the most common
 * real-world usage - responsive breakpoints) plus the `screen`/`all`/
 * `print`/`speech` media types combined with `and`/comma-separated lists;
 * any other feature (`prefers-color-scheme`, `orientation`, `hover`, ...)
 * makes that query not match, fail-safe, rather than guessing. Container
 * queries (`@container`) aren't implemented. No platform CSS engine is used.
 *
 * Media queries are evaluated once, against the viewport size at parse
 * time - there's no re-evaluation on rotation/resize, since that would
 * require re-running the whole CSS cascade (not just layout) on a size
 * change, which Tab doesn't currently do.
 */
class CssParser(private val source: String, private val viewportWidth: Float = 360f) {

    val fontFaceRules: List<Map<String, String>> get() = _fontFaceRules
    private val _fontFaceRules = ArrayList<Map<String, String>>()

    fun parseRules(): List<CssRule> {
        val rules = ArrayList<CssRule>()
        // Defense in depth: Url.kt's fetch() already strips a leading UTF-8 BOM (U+FEFF) from
        // any decoded response body, but CssParser is also fed text from other places (inline
        // `<style>` bodies, tests, ...) that don't go through that path - and a stray BOM here
        // would otherwise corrupt just the first selector of the stylesheet (see NeverMatchSelector's
        // sibling fix and Url.kt's decodeBody doc for the full story).
        var i = if (source.isNotEmpty() && source[0] == '﻿') 1 else 0
        val n = source.length
        while (i < n) {
            i = skipWhitespace(i)
            if (i >= n) break
            if (source[i] == '@') {
                if (source.startsWith("@font-face", i)) {
                    val brace = source.indexOf('{', i)
                    val bodyEnd = if (brace != -1) matchingBrace(brace) else -1
                    if (brace != -1 && bodyEnd != -1) {
                        _fontFaceRules.add(parseDeclarations(source.substring(brace + 1, bodyEnd)))
                        i = bodyEnd + 1
                        continue
                    }
                }
                if (source.startsWith("@media", i)) {
                    val brace = source.indexOf('{', i)
                    val bodyEnd = if (brace != -1) matchingBrace(brace) else -1
                    if (brace != -1 && bodyEnd != -1) {
                        val condition = source.substring(i + 6, brace).trim()
                        if (mediaQueryMatches(condition, viewportWidth)) {
                            val nested = CssParser(source.substring(brace + 1, bodyEnd), viewportWidth)
                            rules.addAll(nested.parseRules())
                            _fontFaceRules.addAll(nested.fontFaceRules)
                        }
                        i = bodyEnd + 1
                        continue
                    }
                }
                val brace = source.indexOf('{', i)
                val semi = source.indexOf(';', i)
                i = when {
                    brace != -1 && (semi == -1 || brace < semi) -> skipBalanced(brace)
                    semi != -1 -> semi + 1
                    else -> n
                }
                continue
            }
            val selectorEnd = source.indexOf('{', i)
            if (selectorEnd == -1) break
            val selectorText = source.substring(i, selectorEnd).trim()
            val bodyEnd = matchingBrace(selectorEnd)
            if (bodyEnd == -1) break
            val bodyText = source.substring(selectorEnd + 1, bodyEnd)
            val props = parseDeclarations(bodyText)
            if (props.isNotEmpty() && selectorText.isNotEmpty()) {
                for (part in selectorText.split(',')) {
                    parseSelectorChain(part.trim())?.let { rules.add(CssRule(it, props)) }
                }
            }
            i = bodyEnd + 1
        }
        return rules
    }

    private fun skipWhitespace(from: Int): Int {
        var i = from
        while (i < source.length) {
            if (source[i].isWhitespace()) { i++; continue }
            if (source.startsWith("/*", i)) {
                val end = source.indexOf("*/", i + 2)
                i = if (end == -1) source.length else end + 2
                continue
            }
            break
        }
        return i
    }

    private fun skipBalanced(openBraceIdx: Int): Int {
        var depth = 0
        var i = openBraceIdx
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i + 1 }
            }
            i++
        }
        return source.length
    }

    private fun matchingBrace(openBraceIdx: Int): Int {
        var depth = 0
        var i = openBraceIdx
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return -1
    }

    private fun parseDeclarations(body: String): Map<String, String> {
        val props = LinkedHashMap<String, String>()
        for (decl in body.split(';')) {
            val colon = decl.indexOf(':')
            if (colon == -1) continue
            val key = decl.substring(0, colon).trim().lowercase()
            val value = stripImportant(decl.substring(colon + 1).trim())
            if (key.isNotEmpty() && value.isNotEmpty()) props[key] = value
        }
        return props
    }

    /** Parses one CSS selector independent of any stylesheet body - used by `document.querySelector`. */
    fun parseSingleSelector(text: String): Selector? = parseSelectorChain(text.trim())

    private fun parseSelectorChain(text: String): Selector? {
        val tokens = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        // This engine only implements the descendant combinator (plain whitespace) - see the
        // class doc. `>` (child), `+` (adjacent sibling) and `~` (general sibling) aren't
        // implemented, and dropping them from the token list (as this used to do) silently
        // reinterpreted e.g. `div > li` as the much broader `div li` descendant selector -
        // matching grandchildren, great-grandchildren etc. an author explicitly scoped to direct
        // children only. Per this file's NeverMatchSelector precedent for unsupported
        // pseudo-classes, the safe behavior for an unsupported combinator is "never matches", not
        // silently widening the selector.
        if (tokens.any { it == ">" || it == "+" || it == "~" }) return NeverMatchSelector
        var acc = parseToken(tokens[0]) ?: return null
        for (t in tokens.drop(1)) {
            val next = parseToken(t) ?: continue
            acc = DescendantSelector(acc, next)
        }
        return acc
    }

    private fun parseToken(raw: String): Selector? {
        // A pseudo-class/pseudo-element this engine doesn't implement (`:hover`, `:visited`,
        // `::before`, ...) makes the whole selector never match - see NeverMatchSelector's doc
        // for why that's the safe choice, unlike silently treating the pseudo as always satisfied.
        if (':' in raw) return NeverMatchSelector
        val token = raw
        if (token.isEmpty()) return null
        val parts = ArrayList<Selector>()
        var idx = 0
        if (token[0] != '.' && token[0] != '#') {
            var end = idx
            while (end < token.length && token[end] != '.' && token[end] != '#') end++
            val tagName = token.substring(idx, end)
            if (tagName.isNotEmpty() && tagName != "*") parts.add(TagSelector(tagName))
            idx = end
        }
        while (idx < token.length) {
            val marker = token[idx]
            idx++
            val start = idx
            while (idx < token.length && token[idx] != '.' && token[idx] != '#') idx++
            val name = token.substring(start, idx)
            if (name.isNotEmpty()) {
                parts.add(if (marker == '.') ClassSelector(name) else IdSelector(name))
            }
        }
        return when {
            parts.isEmpty() -> UniversalSelector
            parts.size == 1 -> parts[0]
            else -> CompoundSelector(parts)
        }
    }

    companion object {
        private val MEDIA_FEATURE = Regex("^\\(\\s*(min-width|max-width)\\s*:\\s*([0-9.]+)(px)?\\s*\\)$")
        private val NOT_PREFIX = Regex("^not\\s+", RegexOption.IGNORE_CASE)
        private val ONLY_PREFIX = Regex("^only\\s+", RegexOption.IGNORE_CASE)

        /** OR across comma-separated queries. */
        private fun mediaQueryMatches(condition: String, viewportWidth: Float): Boolean =
            condition.split(',').map { it.trim() }.any { matchesSingleQuery(it, viewportWidth) }

        /**
         * AND across `and`-separated conditions within one query, after stripping the leading
         * `only` (a legacy no-op for a parser that already understands the query it prefixes -
         * dropping it here, rather than comparing it as a literal media type and rejecting it,
         * matches real browsers) and `not` (which inverts the *whole* query - type and every
         * `and`-joined feature together - not just whichever single feature happens to come
         * right after it).
         */
        private fun matchesSingleQuery(query: String, viewportWidth: Float): Boolean {
            var q = query.trim()
            var negate = false
            NOT_PREFIX.find(q)?.let { negate = true; q = q.substring(it.value.length).trim() }
            ONLY_PREFIX.find(q)?.let { q = q.substring(it.value.length).trim() }
            val result = q.split(Regex("\\band\\b", RegexOption.IGNORE_CASE)).map { it.trim() }
                .all { matchesFeature(it, viewportWidth) }
            return if (negate) !result else result
        }

        private fun matchesFeature(part: String, viewportWidth: Float): Boolean {
            val trimmed = part.trim()
            if (trimmed.isEmpty() || trimmed.equals("screen", true) || trimmed.equals("all", true)) return true
            if (trimmed.equals("print", true) || trimmed.equals("speech", true)) return false
            // Anchored (^...$) full-string match, not `find`: a `find` here would happily locate
            // a `(min-width: ...)` feature buried inside a string like "not (min-width: 600px)"
            // and evaluate just that fragment, silently ignoring the leading "not " that's
            // supposed to invert it (that inversion is handled by the caller, one level up, once
            // the whole condition text - not just a feature substring - has been matched).
            val match = MEDIA_FEATURE.find(trimmed) ?: return false // unrecognized feature: fail-safe exclude
            val value = match.groupValues[2].toFloatOrNull() ?: return false
            return when (match.groupValues[1]) {
                "min-width" -> viewportWidth >= value
                "max-width" -> viewportWidth <= value
                else -> false
            }
        }

        /** Parses a `style="..."` attribute value into a flat declaration map. */
        fun parseInlineDeclarations(text: String): Map<String, String> {
            val props = LinkedHashMap<String, String>()
            for (decl in text.split(';')) {
                val colon = decl.indexOf(':')
                if (colon == -1) continue
                val key = decl.substring(0, colon).trim().lowercase()
                val value = stripImportant(decl.substring(colon + 1).trim())
                if (key.isNotEmpty() && value.isNotEmpty()) props[key] = value
            }
            return props
        }

        /**
         * Strips a trailing `!important` annotation (with or without a space before `!`, any
         * casing/internal whitespace, per the CSS grammar) from a declaration's value text. This
         * engine's cascade doesn't track importance for override purposes (see the class doc's
         * "approximate specificity-based cascade" framing) - but leaving the literal
         * `"!important"` text stuck onto the value corrupts it for every downstream parser
         * (parseCssColor, lengthValue, resolveFontSize, keyword matches, ...), which none of them
         * expect and none of them ignore, so the whole declaration would silently fail to apply
         * instead of just losing its (already out-of-scope) elevated priority.
         */
        private val IMPORTANT_SUFFIX = Regex("!\\s*important\\s*$", RegexOption.IGNORE_CASE)
        private fun stripImportant(value: String): String = value.replace(IMPORTANT_SUFFIX, "").trim()
    }
}
