package com.projectfuture.browser.js

enum class TokenType { NUMBER, STRING, TEMPLATE, IDENT, KEYWORD, PUNCT, EOF }

/**
 * For TEMPLATE tokens: [templateParts] holds the literal string chunks and
 * [templateExprs] holds the raw (unparsed) source of each `${...}`
 * expression, parsed lazily by the parser via a nested Lexer+Parser.
 */
class Token(
    val type: TokenType,
    val text: String,
    val numberValue: Double = 0.0,
    val templateParts: List<String> = emptyList(),
    val templateExprs: List<String> = emptyList(),
    val newlineBefore: Boolean = false
)

private val KEYWORDS = setOf(
    "var", "let", "const", "function", "return", "if", "else", "while", "do", "for", "in", "of",
    "break", "continue", "true", "false", "null", "undefined", "new", "typeof", "this", "try",
    "catch", "finally", "throw", "delete", "instanceof", "void"
)

/**
 * A hand-written tokenizer covering ES5-ish syntax plus template literals
 * and arrow functions. No regex literal support (`/pattern/`) - `/` is
 * always division, which is unambiguous for the statement/expression forms
 * this interpreter's parser accepts.
 */
class Lexer(private val source: String) {
    private var pos = 0
    private val n = source.length

    fun tokenize(): List<Token> {
        val tokens = ArrayList<Token>()
        while (true) {
            val tok = nextToken()
            tokens.add(tok)
            if (tok.type == TokenType.EOF) break
        }
        return tokens
    }

    private fun nextToken(): Token {
        var sawNewline = false
        while (pos < n) {
            val c = source[pos]
            if (c == '\n') { sawNewline = true; pos++; continue }
            if (c.isWhitespace()) { pos++; continue }
            if (c == '/' && pos + 1 < n && source[pos + 1] == '/') {
                while (pos < n && source[pos] != '\n') pos++
                continue
            }
            if (c == '/' && pos + 1 < n && source[pos + 1] == '*') {
                pos += 2
                while (pos + 1 < n && !(source[pos] == '*' && source[pos + 1] == '/')) pos++
                pos += 2
                continue
            }
            break
        }
        if (pos >= n) return Token(TokenType.EOF, "", newlineBefore = sawNewline)

        val c = source[pos]

        if (c.isDigit() || (c == '.' && pos + 1 < n && source[pos + 1].isDigit())) {
            return readNumber(sawNewline)
        }
        if (c == '"' || c == '\'') return readString(c, sawNewline)
        if (c == '`') return readTemplate(sawNewline)
        if (c.isLetter() || c == '_' || c == '$') return readIdentifier(sawNewline)

        return readPunct(sawNewline)
    }

    private fun readNumber(newlineBefore: Boolean): Token {
        val start = pos
        if (source[pos] == '0' && pos + 1 < n && (source[pos + 1] == 'x' || source[pos + 1] == 'X')) {
            pos += 2
            while (pos < n && (source[pos].isDigit() || source[pos] in 'a'..'f' || source[pos] in 'A'..'F')) pos++
            val text = source.substring(start, pos)
            val value = text.substring(2).toLongOrNull(16)?.toDouble() ?: 0.0
            return Token(TokenType.NUMBER, text, value, newlineBefore = newlineBefore)
        }
        while (pos < n && source[pos].isDigit()) pos++
        if (pos < n && source[pos] == '.') {
            pos++
            while (pos < n && source[pos].isDigit()) pos++
        }
        if (pos < n && (source[pos] == 'e' || source[pos] == 'E')) {
            pos++
            if (pos < n && (source[pos] == '+' || source[pos] == '-')) pos++
            while (pos < n && source[pos].isDigit()) pos++
        }
        val text = source.substring(start, pos)
        return Token(TokenType.NUMBER, text, text.toDoubleOrNull() ?: 0.0, newlineBefore = newlineBefore)
    }

    private fun readString(quote: Char, newlineBefore: Boolean): Token {
        pos++ // opening quote
        val sb = StringBuilder()
        while (pos < n && source[pos] != quote) {
            val c = source[pos]
            if (c == '\\' && pos + 1 < n) {
                pos++
                sb.append(unescape(source[pos]))
                pos++
            } else {
                sb.append(c)
                pos++
            }
        }
        pos++ // closing quote
        return Token(TokenType.STRING, sb.toString(), newlineBefore = newlineBefore)
    }

    private fun unescape(c: Char): Char = when (c) {
        'n' -> '\n'
        't' -> '\t'
        'r' -> '\r'
        'b' -> '\b'
        '0' -> '\u0000'
        else -> c // handles \\, \', \", \` and anything else literally
    }

    private fun readTemplate(newlineBefore: Boolean): Token {
        pos++ // opening backtick
        val parts = ArrayList<String>()
        val exprs = ArrayList<String>()
        val sb = StringBuilder()
        while (pos < n && source[pos] != '`') {
            val c = source[pos]
            if (c == '\\' && pos + 1 < n) {
                pos++
                sb.append(unescape(source[pos]))
                pos++
            } else if (c == '$' && pos + 1 < n && source[pos + 1] == '{') {
                parts.add(sb.toString())
                sb.setLength(0)
                pos += 2
                val exprStart = pos
                var depth = 1
                while (pos < n && depth > 0) {
                    when (source[pos]) {
                        '{' -> depth++
                        '}' -> depth--
                    }
                    if (depth > 0) pos++
                }
                exprs.add(source.substring(exprStart, pos))
                pos++ // closing '}'
            } else {
                sb.append(c)
                pos++
            }
        }
        parts.add(sb.toString())
        pos++ // closing backtick
        return Token(TokenType.TEMPLATE, "", templateParts = parts, templateExprs = exprs, newlineBefore = newlineBefore)
    }

    private fun readIdentifier(newlineBefore: Boolean): Token {
        val start = pos
        while (pos < n && (source[pos].isLetterOrDigit() || source[pos] == '_' || source[pos] == '$')) pos++
        val text = source.substring(start, pos)
        val type = if (text in KEYWORDS) TokenType.KEYWORD else TokenType.IDENT
        return Token(type, text, newlineBefore = newlineBefore)
    }

    private val multiCharPuncts = listOf(
        "===", "!==", ">>>=", "**=", "...", "=>",
        "==", "!=", "<=", ">=", "&&", "||", "??", "++", "--", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=",
        "<<", ">>", "**"
    )

    private fun readPunct(newlineBefore: Boolean): Token {
        for (p in multiCharPuncts) {
            if (source.startsWith(p, pos)) {
                pos += p.length
                return Token(TokenType.PUNCT, p, newlineBefore = newlineBefore)
            }
        }
        val c = source[pos]
        pos++
        return Token(TokenType.PUNCT, c.toString(), newlineBefore = newlineBefore)
    }
}
