package com.projectfuture.browser.css

class CssRule(val selector: Selector, val properties: Map<String, String>)

/**
 * A small hand-written CSS parser: enough for tag/class/id/descendant
 * selectors, comma-separated selector lists, and flat property:value
 * declarations. At-rules (@media, @import, ...) are skipped wholesale
 * rather than interpreted. No platform CSS engine is used.
 */
class CssParser(private val source: String) {

    fun parseRules(): List<CssRule> {
        val rules = ArrayList<CssRule>()
        var i = 0
        val n = source.length
        while (i < n) {
            i = skipWhitespace(i)
            if (i >= n) break
            if (source[i] == '@') {
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
