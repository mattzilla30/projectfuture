package com.projectfuture.browser.html

/**
 * A small hand-written "tag soup" HTML parser: tokenizes `<...>` markup and
 * builds a DOM tree, inserting the usual implicit html/head/body the way
 * real browsers tolerate malformed documents. It does not implement the
 * full HTML5 parsing spec (no full set of special parsing states), but it
 * is enough to render ordinary pages. No platform HTML/webview parser is used.
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
                val tagEnd = source.indexOf('>', i)
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

        implicitTags(tag)

        val parent = unfinished.lastOrNull()
        val node = ElementNode(tag, attributes, parent)
        if (tag in SELF_CLOSING_TAGS || content.endsWith("/")) {
            parent?.children?.add(node)
        } else {
            unfinished.add(node)
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
        val BLOCK_ELEMENTS = setOf(
            "html", "body", "article", "section", "nav", "aside", "h1", "h2", "h3", "h4", "h5", "h6",
            "hgroup", "header", "footer", "address", "p", "hr", "pre", "blockquote", "ol", "ul", "menu",
            "li", "dl", "dt", "dd", "figure", "figcaption", "main", "div", "table", "form", "fieldset",
            "legend", "details", "summary", "thead", "tbody", "tfoot", "tr", "th", "td"
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
                return String(Character.toChars(code))
            }
            if (entity.startsWith("#")) {
                val code = entity.substring(1).toIntOrNull() ?: return null
                return String(Character.toChars(code))
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
    }
}
