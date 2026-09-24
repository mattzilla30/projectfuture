package com.projectfuture.browser.html

/**
 * A small hand-written "tag soup" HTML parser: tokenizes `<...>` markup and
 * builds a DOM tree, inserting the usual implicit html/head/body the way
 * real browsers tolerate malformed documents. It does not implement the
 * full HTML5 parsing spec - no tokenizer state machine with RCDATA/RAWTEXT
 * states beyond script/style, no adoption agency algorithm for misnested
 * formatting elements (`<b>a <i>b</b> c</i>` won't get spec-correct
 * reconstruction), no foster parenting for stray table content. Those are
 * mostly long-tail malformed-markup edge cases. What real pages actually
 * lean on constantly - unclosed `<p>`/`<li>`/`<tr>`/`<td>` tags, and `>`
 * appearing inside a quoted attribute value - is handled via
 * [findTagEnd] and [autoCloseForNewTag]. No platform HTML/webview parser
 * is used.
 */
class HtmlParser(private val source: String) {

    private val unfinished = ArrayList<ElementNode>()

    fun parse(): ElementNode {
        var i = 0
        val text = StringBuilder()
        val n = source.length

        while (i < n) {
            val c = source[i]
            if (c == '<') {
                if (source.startsWith("<!--", i)) {
                    val end = source.indexOf("-->", i + 4)
                    i = if (end == -1) n else end + 3
                    continue
                }
                if (source.startsWith("<!", i)) {
                    // Doctype or other declaration: skip to '>'.
                    val end = source.indexOf('>', i)
                    i = if (end == -1) n else end + 1
                    continue
                }
                if (text.isNotEmpty()) {
                    addText(text.toString())
                    text.setLength(0)
                }
                val tagEnd = findTagEnd(source, i + 1)
                if (tagEnd == -1) break
                val tagContent = source.substring(i + 1, tagEnd)
                addTag(tagContent)
                i = tagEnd + 1

                val tagName = tagContent.trim().substringBefore(' ').lowercase().removePrefix("/")
                if (tagName == "script" || tagName == "style") {
                    val closeTag = "</$tagName"
                    val closeIdx = indexOfIgnoreCase(source, closeTag, i)
                    val rawEnd = if (closeIdx == -1) n else closeIdx
                    if (rawEnd > i) addText(source.substring(i, rawEnd))
                    if (closeIdx != -1) {
                        val closeTagEnd = source.indexOf('>', closeIdx)
                        if (closeTagEnd != -1) {
                            addTag(source.substring(closeIdx + 1, closeTagEnd))
                            i = closeTagEnd + 1
                        } else {
                            i = n
                        }
                    } else {
                        i = n
                    }
                }
                continue
            } else {
                text.append(c)
                i++
            }
        }
        if (text.isNotEmpty()) addText(text.toString())
        return finish()
    }

    private fun addText(rawText: String) {
        if (rawText.isBlank() && unfinished.isEmpty()) return
        if (unfinished.isEmpty()) implicitTags("")
        val decoded = decodeEntities(rawText)
        val parent = unfinished.lastOrNull() ?: return
        parent.children.add(TextNode(decoded, parent))
    }

    private fun addTag(rawTagContent: String) {
        val content = rawTagContent.trim()
        if (content.isEmpty() || content.startsWith("!")) return

        val isClosing = content.startsWith("/")
        val body = if (isClosing) content.substring(1) else content
        val (tag, attributes) = parseTagAndAttributes(body)
        if (tag.isEmpty()) return

        if (isClosing) {
            closeTag(tag)
            return
        }

        autoCloseForNewTag(tag)
        implicitTags(tag)

        val parent = unfinished.lastOrNull()
        val node = ElementNode(tag, attributes, parent)
        if (tag in SELF_CLOSING_TAGS || content.endsWith("/")) {
            parent?.children?.add(node)
        } else {
            unfinished.add(node)
        }
    }

    /**
     * A bounded version of the spec's "implied end tags": closes the
     * innermost open element when a new tag would otherwise nest illegally
     * inside it, covering the unclosed `<p>`/`<li>`/`<dt>`/`<dd>`/table-row
     * tags that real-world (especially hand-written or older CMS) HTML is
     * full of. Not the full per-spec scope-chain check.
     */
    private fun autoCloseForNewTag(tag: String) {
        if (unfinished.isEmpty()) return
        val closesTags = AUTO_CLOSE_RULES[tag]
        if (closesTags != null) {
            // Scan outward from the innermost open element - not just the
            // innermost element itself - so an intervening, still-open tag
            // (e.g. an unclosed inline `<b>` inside a `<li>`, or an unclosed
            // `<td>` inside a `<tr>`) doesn't block the implicit close. Stop
            // at a scope boundary (e.g. `<table>` for row/cell tags) so this
            // never reaches past the container the rule is scoped to.
            val boundary = AUTO_CLOSE_BOUNDARY[tag] ?: emptySet()
            for (idx in unfinished.indices.reversed()) {
                val openTag = unfinished[idx].tag
                if (openTag in closesTags) {
                    closeTag(openTag)
                    return
                }
                if (openTag in boundary) return
            }
        } else if (tag in BLOCK_ELEMENTS) {
            for (idx in unfinished.indices.reversed()) {
                val openTag = unfinished[idx].tag
                if (openTag == "p") {
                    closeTag("p")
                    return
                }
                if (openTag in BLOCK_ELEMENTS) return
            }
        }
    }

    private fun closeTag(tag: String) {
        if (unfinished.size <= 1) return
        // Find a matching open element; if none, ignore the stray close tag.
        val idx = unfinished.indexOfLast { it.tag == tag }
        if (idx == -1) return
        while (unfinished.size - 1 >= idx) {
            val node = unfinished.removeAt(unfinished.size - 1)
            val parent = unfinished.lastOrNull()
            parent?.children?.add(node)
            if (unfinished.size - 1 < idx) break
        }
    }

    private fun implicitTags(nextTag: String) {
        while (true) {
            val openTags = unfinished.map { it.tag }
            when {
                openTags.isEmpty() -> {
                    if (nextTag == "html") return
                    unfinished.add(ElementNode("html", parent = null))
                }
                openTags == listOf("html") && nextTag !in listOf("head", "body", "/html") -> {
                    unfinished.add(ElementNode(if (nextTag in HEAD_TAGS) "head" else "body", parent = unfinished.last()))
                }
                openTags == listOf("html", "head") && nextTag !in HEAD_TAGS && nextTag != "/head" -> {
                    val node = unfinished.removeAt(unfinished.size - 1)
                    unfinished.last().children.add(node)
                }
                else -> return
            }
        }
    }

    private fun finish(): ElementNode {
        if (unfinished.isEmpty()) unfinished.add(ElementNode("html", parent = null))
        while (unfinished.size > 1) {
            val node = unfinished.removeAt(unfinished.size - 1)
            unfinished.last().children.add(node)
        }
        return unfinished.removeAt(0)
    }

    private fun parseTagAndAttributes(text: String): Pair<String, MutableMap<String, String>> {
        val parts = splitRespectingQuotes(text.removeSuffix("/").trim())
        if (parts.isEmpty()) return "" to LinkedHashMap()
        val tag = parts[0].lowercase()
        val attributes = LinkedHashMap<String, String>()
        for (part in parts.drop(1)) {
            if (part.isEmpty()) continue
            val eq = part.indexOf('=')
            if (eq == -1) {
                attributes[part.lowercase()] = ""
            } else {
                val key = part.substring(0, eq).lowercase()
                var value = part.substring(eq + 1)
                if (value.length >= 2 && (value.first() == '"' || value.first() == '\'') && value.last() == value.first()) {
                    value = value.substring(1, value.length - 1)
                }
                attributes[key] = decodeEntities(value)
            }
        }
        return tag to attributes
    }

    private fun splitRespectingQuotes(text: String): List<String> {
        val parts = ArrayList<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (c in text) {
            when {
                quote != null -> {
                    current.append(c)
                    if (c == quote) quote = null
                }
                c == '"' || c == '\'' -> {
                    quote = c
                    current.append(c)
                }
                c.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        parts.add(current.toString())
                        current.setLength(0)
                    }
                }
                else -> current.append(c)
            }
        }
        if (current.isNotEmpty()) parts.add(current.toString())
        return parts
    }

    /** Finds a tag's closing `>`, ignoring one inside a quoted attribute value (e.g. `title="a > b"`). */
    private fun findTagEnd(source: String, start: Int): Int {
        var i = start
        var quote: Char? = null
        while (i < source.length) {
            val c = source[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '>' -> return i
            }
            i++
        }
        return -1
    }

    private fun indexOfIgnoreCase(haystack: String, needle: String, from: Int): Int {
        val lowerHaystack = haystack
        var i = from
        while (i <= haystack.length - needle.length) {
            if (haystack.regionMatches(i, needle, 0, needle.length, ignoreCase = true)) return i
            i++
        }
        return -1
    }

    companion object {
        val SELF_CLOSING_TAGS = setOf(
            "area", "base", "br", "col", "embed", "hr", "img", "input",
            "link", "meta", "param", "source", "track", "wbr"
        )
        val HEAD_TAGS = setOf(
            "base", "basefont", "bgsound", "noscript", "link", "meta", "title", "style", "script"
        )
        /** Tag -> the set of currently-open innermost tags it implicitly closes. See [autoCloseForNewTag]. */
        val AUTO_CLOSE_RULES: Map<String, Set<String>> = mapOf(
            "li" to setOf("li"),
            "dt" to setOf("dt", "dd"),
            "dd" to setOf("dt", "dd"),
            "tr" to setOf("tr"),
            "td" to setOf("td", "th"),
            "th" to setOf("td", "th"),
            "option" to setOf("option"),
            "thead" to setOf("thead", "tbody", "tfoot", "tr", "td", "th"),
            "tbody" to setOf("thead", "tbody", "tfoot", "tr", "td", "th"),
            "tfoot" to setOf("thead", "tbody", "tfoot", "tr", "td", "th")
        )
        /** Tag -> the set of open tags [autoCloseForNewTag] must not scan past while looking for a match. */
        val AUTO_CLOSE_BOUNDARY: Map<String, Set<String>> = mapOf(
            "li" to setOf("ul", "ol", "menu"),
            "dt" to setOf("dl"),
            "dd" to setOf("dl"),
            "tr" to setOf("table"),
            "td" to setOf("table"),
            "th" to setOf("table"),
            "option" to setOf("select", "optgroup"),
            "thead" to setOf("table"),
            "tbody" to setOf("table"),
            "tfoot" to setOf("table")
        )
        val BLOCK_ELEMENTS = setOf(
            "html", "body", "article", "section", "nav", "aside", "h1", "h2", "h3", "h4", "h5", "h6",
            "hgroup", "header", "footer", "address", "p", "hr", "pre", "blockquote", "ol", "ul", "menu",
            "li", "dl", "dt", "dd", "figure", "figcaption", "main", "div", "table", "caption", "form",
            "fieldset", "legend", "details", "summary", "thead", "tbody", "tfoot", "tr", "th", "td"
        )

        fun decodeEntities(text: String): String {
            if ('&' !in text) return text
            val sb = StringBuilder()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '&') {
                    val semi = text.indexOf(';', i)
                    if (semi != -1 && semi - i <= 12) {
                        val entity = text.substring(i + 1, semi)
                        val replacement = namedEntity(entity)
                        if (replacement != null) {
                            sb.append(replacement)
                            i = semi + 1
                            continue
                        }
                    }
                }
                sb.append(c)
                i++
            }
            return sb.toString()
        }

        private fun namedEntity(entity: String): String? {
            if (entity.startsWith("#x") || entity.startsWith("#X")) {
                val code = entity.substring(2).toIntOrNull(16) ?: return null
                return codePointToString(code)
            }
            if (entity.startsWith("#")) {
                val code = entity.substring(1).toIntOrNull() ?: return null
                return codePointToString(code)
            }
            return when (entity) {
                "lt" -> "<"
                "gt" -> ">"
                "amp" -> "&"
                "quot" -> "\""
                "apos" -> "'"
                "nbsp" -> " "
                "copy" -> "©"
                "mdash" -> "—"
                "ndash" -> "–"
                "hellip" -> "…"
                "rsquo" -> "’"
                "lsquo" -> "‘"
                "rdquo" -> "”"
                "ldquo" -> "“"
                else -> null
            }
        }

        /**
         * `&#...;` numeric references can carry a codepoint outside the
         * valid Unicode range (e.g. `&#x110000;`), which crashes
         * [Character.toChars] with an [IllegalArgumentException] - one
         * malformed reference anywhere on a page must not abort parsing
         * the whole document. Per spec, an invalid numeric reference
         * becomes U+FFFD instead.
         */
        private fun codePointToString(code: Int): String =
            if (Character.isValidCodePoint(code)) String(Character.toChars(code)) else "�"
    }
}
