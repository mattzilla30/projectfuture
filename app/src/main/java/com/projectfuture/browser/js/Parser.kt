package com.projectfuture.browser.js

/**
 * A recursive-descent parser for an ES5-ish subset plus arrow functions,
 * template literals, `let`/`const`, and regex literals (see Lexer.kt's
 * doc for the regex-vs-division heuristic). Not implemented: classes,
 * destructuring, spread/rest, generators/async, default parameters,
 * bitwise operators (&|^~<<>>), labeled statements, switch statements.
 * Each of those is a real gap for modern JS, chosen to bound scope - see
 * Interpreter.kt's class doc for the fuller picture of what this engine
 * covers.
 */
class Parser(private val tokens: List<Token>) {
    private var pos = 0

    fun parseProgram(): List<Stmt> {
        val stmts = ArrayList<Stmt>()
        while (!isAtEnd()) stmts.add(parseStatement())
        return stmts
    }

    // ---- token helpers ----

    private fun peek(offset: Int = 0): Token = tokens.getOrElse(pos + offset) { tokens.last() }
    private fun isAtEnd(): Boolean = peek().type == TokenType.EOF
    private fun advance(): Token = tokens[pos].also { if (pos < tokens.size - 1) pos++ }
    private fun check(type: TokenType): Boolean = peek().type == type
    private fun check(type: TokenType, text: String): Boolean = peek().type == type && peek().text == text
    private fun checkPunct(text: String) = check(TokenType.PUNCT, text)
    private fun checkKeyword(text: String) = check(TokenType.KEYWORD, text)

    private fun match(type: TokenType, text: String): Boolean {
        if (check(type, text)) { advance(); return true }
        return false
    }

    private fun matchPunct(text: String) = match(TokenType.PUNCT, text)
    private fun matchKeyword(text: String) = match(TokenType.KEYWORD, text)

    private fun expectPunct(text: String): Token {
        if (!checkPunct(text)) throw jsError("Parse error: expected '$text' but found '${peek().text}'")
        return advance()
    }

    private fun consumeSemicolon() {
        matchPunct(";") // automatic semicolon insertion is otherwise approximated by just not requiring one
    }

    // ---- statements ----

    private fun parseStatement(): Stmt {
        if (checkPunct("{")) return parseBlock()
        if (checkKeyword("var") || checkKeyword("let") || checkKeyword("const")) return parseVarDecl()
        if (checkKeyword("if")) return parseIf()
        if (checkKeyword("while")) return parseWhile()
        if (checkKeyword("do")) return parseDoWhile()
        if (checkKeyword("for")) return parseFor()
        if (checkKeyword("function")) return parseFunctionDecl()
        if (checkKeyword("return")) return parseReturn()
        if (checkKeyword("break")) { advance(); consumeSemicolon(); return BreakStmt }
        if (checkKeyword("continue")) { advance(); consumeSemicolon(); return ContinueStmt }
        if (checkKeyword("try")) return parseTry()
        if (checkKeyword("throw")) return parseThrow()
        if (matchPunct(";")) return Block(emptyList()) // empty statement
        val expr = parseExpression()
        consumeSemicolon()
        return ExprStmt(expr)
    }

    private fun parseBlock(): Block {
        expectPunct("{")
        val stmts = ArrayList<Stmt>()
        while (!checkPunct("}") && !isAtEnd()) stmts.add(parseStatement())
        expectPunct("}")
        return Block(stmts)
    }

    private fun parseBlockStatements(): List<Stmt> = parseBlock().body

    private fun parseVarDecl(): VarDecl {
        val kind = advance().text
        val decls = ArrayList<Pair<String, Expr?>>()
        while (true) {
            val name = advance().text
            var init: Expr? = null
            if (matchPunct("=")) init = parseAssignment()
            decls.add(name to init)
            if (!matchPunct(",")) break
        }
        consumeSemicolon()
        return VarDecl(kind, decls)
    }

    private fun parseIf(): Stmt {
        advance()
        expectPunct("(")
        val test = parseExpression()
        expectPunct(")")
        val consequent = parseStatement()
        val alternate = if (matchKeyword("else")) parseStatement() else null
        return If(test, consequent, alternate)
    }

    private fun parseWhile(): Stmt {
        advance()
        expectPunct("(")
        val test = parseExpression()
        expectPunct(")")
        return While(test, parseStatement())
    }

    private fun parseDoWhile(): Stmt {
        advance()
        val body = parseStatement()
        if (!matchKeyword("while")) throw jsError("Parse error: expected 'while' after do block")
        expectPunct("(")
        val test = parseExpression()
        expectPunct(")")
        consumeSemicolon()
        return DoWhile(body, test)
    }

    private fun parseFor(): Stmt {
        advance()
        expectPunct("(")

        var declKind: String? = null
        var initStmt: Stmt? = null
        var singleVarName: String? = null

        if (!checkPunct(";")) {
            if (checkKeyword("var") || checkKeyword("let") || checkKeyword("const")) {
                declKind = peek().text
                advance()
                singleVarName = advance().text
                if (matchPunct("=")) {
                    val init = parseAssignment()
                    if (checkKeyword("in") || checkKeyword("of")) {
                        return finishForInOf(declKind, singleVarName!!, init)
                    }
                    val decls = arrayListOf(singleVarName to init as Expr?)
                    while (matchPunct(",")) {
                        val n = advance().text
                        val i = if (matchPunct("=")) parseAssignment() else null
                        decls.add(n to i)
                    }
                    initStmt = VarDecl(declKind, decls)
                } else if (checkKeyword("in") || checkKeyword("of")) {
                    return finishForInOf(declKind, singleVarName!!, null)
                } else {
                    val decls = arrayListOf(singleVarName to null as Expr?)
                    while (matchPunct(",")) {
                        val n = advance().text
                        val i = if (matchPunct("=")) parseAssignment() else null
                        decls.add(n to i)
                    }
                    initStmt = VarDecl(declKind, decls)
                }
            } else {
                val expr = parseExpression()
                if (checkKeyword("in") || checkKeyword("of")) {
                    val name = (expr as? Identifier)?.name ?: throw jsError("Parse error: invalid for-in/of target")
                    return finishForInOf(null, name, null)
                }
                initStmt = ExprStmt(expr)
            }
        }
        expectPunct(";")
        val test = if (!checkPunct(";")) parseExpression() else null
        expectPunct(";")
        val update = if (!checkPunct(")")) parseExpression() else null
        expectPunct(")")
        return For(initStmt, test, update, parseStatement())
    }

    private fun finishForInOf(declKind: String?, varName: String, ignoredInit: Expr?): Stmt {
        val isOf = checkKeyword("of")
        advance() // 'in' or 'of'
        val obj = parseExpression()
        expectPunct(")")
        return ForIn(declKind, varName, obj, parseStatement(), isOf)
    }

    private fun parseFunctionDecl(): Stmt {
        advance()
        val name = advance().text
        val params = parseParamList()
        val body = parseBlockStatements()
        return FunctionDecl(name, params, body)
    }

    private fun parseParamList(): List<String> {
        expectPunct("(")
        val params = ArrayList<String>()
        if (!checkPunct(")")) {
            while (true) {
                params.add(advance().text)
                if (!matchPunct(",")) break
            }
        }
        expectPunct(")")
        return params
    }

    private fun parseReturn(): Stmt {
        advance()
        val arg = if (checkPunct(";") || checkPunct("}") || peek().newlineBefore || isAtEnd()) null else parseExpression()
        consumeSemicolon()
        return Return(arg)
    }

    private fun parseTry(): Stmt {
        advance()
        val block = parseBlock()
        var catchParam: String? = null
        var catchBlock: Block? = null
        if (matchKeyword("catch")) {
            if (matchPunct("(")) {
                catchParam = advance().text
                expectPunct(")")
            }
            catchBlock = parseBlock()
        }
        var finallyBlock: Block? = null
        if (matchKeyword("finally")) finallyBlock = parseBlock()
        return TryStmt(block, catchParam, catchBlock, finallyBlock)
    }

    private fun parseThrow(): Stmt {
        advance()
        val arg = parseExpression()
        consumeSemicolon()
        return ThrowStmt(arg)
    }

    // ---- expressions (precedence climbing, low to high) ----

    private fun parseExpression(): Expr = parseAssignment()

    private val assignOps = setOf("=", "+=", "-=", "*=", "/=", "%=")

    private fun parseAssignment(): Expr {
        tryParseArrowFunction()?.let { return it }
        val left = parseConditional()
        if (peek().type == TokenType.PUNCT && peek().text in assignOps) {
            val op = advance().text
            val value = parseAssignment()
            return Assign(op, left, value)
        }
        return left
    }

    private fun tryParseArrowFunction(): Expr? {
        if (check(TokenType.IDENT) && peek(1).type == TokenType.PUNCT && peek(1).text == "=>") {
            val param = advance().text
            advance() // =>
            return finishArrow(listOf(param))
        }
        if (checkPunct("(")) {
            val saved = pos
            advance()
            val params = ArrayList<String>()
            var valid = true
            if (!checkPunct(")")) {
                while (true) {
                    if (!check(TokenType.IDENT)) { valid = false; break }
                    params.add(advance().text)
                    if (matchPunct(",")) continue
                    break
                }
            }
            if (valid && checkPunct(")")) {
                advance()
                if (checkPunct("=>")) {
                    advance()
                    return finishArrow(params)
                }
            }
            pos = saved
        }
        return null
    }

    private fun finishArrow(params: List<String>): Expr {
        val body = if (checkPunct("{")) parseBlockStatements() else listOf(Return(parseAssignment()))
        return FunctionExpr(null, params, body, isArrow = true)
    }

    private fun parseConditional(): Expr {
        val test = parseNullish()
        if (matchPunct("?")) {
            val consequent = parseAssignment()
            expectPunct(":")
            val alternate = parseAssignment()
            return Conditional(test, consequent, alternate)
        }
        return test
    }

    private fun parseNullish(): Expr {
        var left = parseLogicalOr()
        while (checkPunct("??")) { advance(); left = Logical("??", left, parseLogicalOr()) }
        return left
    }

    private fun parseLogicalOr(): Expr {
        var left = parseLogicalAnd()
        while (checkPunct("||")) { advance(); left = Logical("||", left, parseLogicalAnd()) }
        return left
    }

    private fun parseLogicalAnd(): Expr {
        var left = parseEquality()
        while (checkPunct("&&")) { advance(); left = Logical("&&", left, parseEquality()) }
        return left
    }

    private val equalityOps = setOf("==", "!=", "===", "!==")

    private fun parseEquality(): Expr {
        var left = parseRelational()
        while (peek().type == TokenType.PUNCT && peek().text in equalityOps) {
            val op = advance().text
            left = Binary(op, left, parseRelational())
        }
        return left
    }

    private fun parseRelational(): Expr {
        var left = parseAdditive()
        while (true) {
            val isPunct = peek().type == TokenType.PUNCT && peek().text in setOf("<", ">", "<=", ">=")
            val isKeywordOp = checkKeyword("instanceof") || checkKeyword("in")
            if (!isPunct && !isKeywordOp) break
            val op = advance().text
            left = Binary(op, left, parseAdditive())
        }
        return left
    }

    private fun parseAdditive(): Expr {
        var left = parseMultiplicative()
        while (checkPunct("+") || checkPunct("-")) {
            val op = advance().text
            left = Binary(op, left, parseMultiplicative())
        }
        return left
    }

    private fun parseMultiplicative(): Expr {
        var left = parseExponent()
        while (checkPunct("*") || checkPunct("/") || checkPunct("%")) {
            val op = advance().text
            left = Binary(op, left, parseExponent())
        }
        return left
    }

    private fun parseExponent(): Expr {
        val left = parseUnary()
        if (checkPunct("**")) { advance(); return Binary("**", left, parseExponent()) }
        return left
    }

    private val unaryOps = setOf("!", "-", "+")

    private fun parseUnary(): Expr {
        if (peek().type == TokenType.PUNCT && peek().text in unaryOps) {
            val op = advance().text
            return Unary(op, parseUnary())
        }
        if (checkKeyword("typeof") || checkKeyword("void") || checkKeyword("delete")) {
            val op = advance().text
            return Unary(op, parseUnary())
        }
        if (checkPunct("++") || checkPunct("--")) {
            val op = advance().text
            return UpdateExpr(op, parseUnary(), prefix = true)
        }
        return parsePostfix()
    }

    private fun parsePostfix(): Expr {
        var expr = parseCallMember()
        if ((checkPunct("++") || checkPunct("--")) && !peek().newlineBefore) {
            val op = advance().text
            expr = UpdateExpr(op, expr, prefix = false)
        }
        return expr
    }

    private fun parseCallMember(): Expr {
        var expr = if (matchKeyword("new")) parseNewExpr() else parsePrimary()
        while (true) {
            expr = when {
                matchPunct(".") -> Member(expr, StringLit(advance().text), computed = false)
                matchPunct("[") -> {
                    val prop = parseExpression()
                    expectPunct("]")
                    Member(expr, prop, computed = true)
                }
                checkPunct("(") -> Call(expr, parseArgs())
                else -> return expr
            }
        }
    }

    private fun parseNewExpr(): Expr {
        val callee = parseCallMemberNoCallForNew()
        val args = if (checkPunct("(")) parseArgs() else emptyList()
        return New(callee, args)
    }

    // `new Foo.Bar(...)` should bind the member chain to `new`, not to a trailing call - so
    // this stops at the first '(' rather than consuming it as part of the callee chain.
    private fun parseCallMemberNoCallForNew(): Expr {
        var expr = parsePrimary()
        while (true) {
            expr = when {
                matchPunct(".") -> Member(expr, StringLit(advance().text), computed = false)
                matchPunct("[") -> {
                    val prop = parseExpression()
                    expectPunct("]")
                    Member(expr, prop, computed = true)
                }
                else -> return expr
            }
        }
    }

    private fun parseArgs(): List<Expr> {
        expectPunct("(")
        val args = ArrayList<Expr>()
        if (!checkPunct(")")) {
            while (true) {
                args.add(parseAssignment())
                if (!matchPunct(",")) break
            }
        }
        expectPunct(")")
        return args
    }

    private fun parsePrimary(): Expr {
        val tok = peek()
        when (tok.type) {
            TokenType.NUMBER -> { advance(); return NumberLit(tok.numberValue) }
            TokenType.STRING -> { advance(); return StringLit(tok.text) }
            TokenType.TEMPLATE -> {
                advance()
                val exprs = tok.templateExprs.map { src -> Parser(Lexer(src).tokenize()).parseExpression() }
                return TemplateLit(tok.templateParts, exprs)
            }
            TokenType.REGEX -> { advance(); return RegexLit(tok.text, tok.regexFlags) }
            TokenType.IDENT -> { advance(); return Identifier(tok.text) }
            TokenType.KEYWORD -> {
                when (tok.text) {
                    "true" -> { advance(); return BoolLit(true) }
                    "false" -> { advance(); return BoolLit(false) }
                    "null" -> { advance(); return NullLit }
                    "undefined" -> { advance(); return UndefinedLit }
                    "this" -> { advance(); return ThisExpr }
                    "function" -> return parseFunctionExpr()
                }
            }
            TokenType.PUNCT -> {
                when (tok.text) {
                    "(" -> {
                        advance()
                        val expr = parseExpression()
                        expectPunct(")")
                        return expr
                    }
                    "[" -> return parseArrayLit()
                    "{" -> return parseObjectLit()
                }
            }
            TokenType.EOF -> {}
        }
        throw jsError("Parse error: unexpected token '${tok.text}'")
    }

    private fun parseFunctionExpr(): Expr {
        advance() // 'function'
        val name = if (check(TokenType.IDENT)) advance().text else null
        val params = parseParamList()
        val body = parseBlockStatements()
        return FunctionExpr(name, params, body, isArrow = false)
    }

    private fun parseArrayLit(): Expr {
        expectPunct("[")
        val elements = ArrayList<Expr>()
        while (!checkPunct("]")) {
            elements.add(parseAssignment())
            if (!matchPunct(",")) break
        }
        expectPunct("]")
        return ArrayLit(elements)
    }

    private fun parseObjectLit(): Expr {
        expectPunct("{")
        val props = ArrayList<Pair<Expr, Expr>>()
        while (!checkPunct("}")) {
            val keyTok = advance()
            val key: Expr = if (keyTok.type == TokenType.STRING) StringLit(keyTok.text) else StringLit(keyTok.text)
            if (matchPunct(":")) {
                props.add(key to parseAssignment())
            } else {
                // shorthand { x } -> { x: x }
                props.add(key to Identifier(keyTok.text))
            }
            if (!matchPunct(",")) break
        }
        expectPunct("}")
        return ObjectLit(props)
    }
}
