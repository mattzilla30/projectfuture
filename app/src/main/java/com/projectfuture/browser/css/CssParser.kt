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
        var i = 0
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
            val value = decl.substring(colon + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) props[key] = value
        }
        return props
    }

    private fun parseSelectorChain(text: String): Selector? {
        val tokens = text.split(Regex("\\s+"))
            .filter { it.isNotEmpty() && it != ">" && it != "+" && it != "~" }
        if (tokens.isEmpty()) return null
        var acc = parseToken(tokens[0]) ?: return null
        for (t in tokens.drop(1)) {
            val next = parseToken(t) ?: continue
            acc = DescendantSelector(acc, next)
        }
        return acc
    }

    private fun parseToken(raw: String): Selector? {
        val token = raw.substringBefore(':') // drop pseudo-classes/elements, unsupported
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
        private val MEDIA_FEATURE = Regex("\\(\\s*(min-width|max-width)\\s*:\\s*([0-9.]+)(px)?\\s*\\)")

        /** OR across comma-separated queries. */
        private fun mediaQueryMatches(condition: String, viewportWidth: Float): Boolean =
            condition.split(',').map { it.trim() }.any { matchesSingleQuery(it, viewportWidth) }

        /** AND across `and`-separated conditions within one query. */
        private fun matchesSingleQuery(query: String, viewportWidth: Float): Boolean =
            query.split(Regex("\\band\\b", RegexOption.IGNORE_CASE)).map { it.trim() }
                .all { matchesFeature(it, viewportWidth) }

        private fun matchesFeature(part: String, viewportWidth: Float): Boolean {
            val trimmed = part.trim()
            if (trimmed.isEmpty() || trimmed.equals("screen", true) || trimmed.equals("all", true)) return true
            if (trimmed.equals("print", true) || trimmed.equals("speech", true)) return false
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
                val value = decl.substring(colon + 1).trim()
                if (key.isNotEmpty() && value.isNotEmpty()) props[key] = value
            }
            return props
        }
    }
}
