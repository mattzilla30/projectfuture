package com.projectfuture.browser.js

/**
 * A recursive-descent parser for an ES5-ish subset plus arrow functions,
 * template literals, `let`/`const`, regex literals (see Lexer.kt's doc for
 * the regex-vs-division heuristic), bitwise operators, switch statements,
 * array/object destructuring (in `var`/`let`/`const`, `for-of`, and
 * function parameters), spread/rest, default parameters, `class`/
 * `extends`/`super`/`static`, getter/setter accessors (in object literals
 * and classes), generators (`function*`/`yield`/`yield*`) and
 * `async`/`await`. Not implemented: labeled statements. `async`/`await`/
 * `yield`/`static` are recognized by identifier text rather than added to
 * the lexer's keyword set - same reasoning as `class`/`switch` (see
 * [checkIdentText]) - so using one of those words as an ordinary
 * identifier in code that isn't actually using the feature is a real,
 * if narrow, misparse risk this parser accepts. See Interpreter.kt's
 * class doc for the fuller picture of what this engine covers.
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

    /** `switch`/`case`/`default` aren't in the lexer's KEYWORDS set (kept that way to avoid touching regex-vs-division heuristics for a niche win) - checked by IDENT text instead. */
    private fun checkIdentText(text: String) = check(TokenType.IDENT) && peek().text == text
    private fun matchIdentText(text: String): Boolean { if (checkIdentText(text)) { advance(); return true }; return false }

    private fun expectPunct(text: String): Token {
        if (!checkPunct(text)) throw parseError("expected '$text' but found '${peek().text}'")
        return advance()
    }

    /** A parse error naming the tokens around the failure, so a log line shows which construct broke. */
    private fun parseError(message: String): JsException {
        val from = maxOf(0, pos - 8)
        val near = (from..minOf(tokens.size - 1, pos + 3)).joinToString(" ") { i ->
            val t = tokens[i]
            val text = when (t.type) { TokenType.STRING -> "\"${t.text.take(20)}\""; TokenType.TEMPLATE -> "`...`"; TokenType.REGEX -> "/${t.text.take(20)}/"; TokenType.EOF -> "<end>"; else -> t.text }
            if (i == pos) ">>$text<<" else text
        }
        return jsError("Parse error: $message near: $near")
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
        if (checkIdentText("async") && peek(1).type == TokenType.KEYWORD && peek(1).text == "function") { advance(); return parseFunctionDecl(isAsync = true) }
        if (checkKeyword("function")) return parseFunctionDecl()
        if (checkKeyword("return")) return parseReturn()
        // Labels are parsed but not tracked: `break label`/`continue label` act on the innermost loop.
        if (checkKeyword("break")) { advance(); skipJumpLabel(); consumeSemicolon(); return BreakStmt }
        if (checkKeyword("continue")) { advance(); skipJumpLabel(); consumeSemicolon(); return ContinueStmt }
        if (check(TokenType.IDENT) && peek(1).type == TokenType.PUNCT && peek(1).text == ":") {
            advance(); advance()
            val isLoop = checkKeyword("for") || checkKeyword("while") || checkKeyword("do")
            val body = parseStatement()
            return if (isLoop) body else Labeled(body)
        }
        if (checkKeyword("try")) return parseTry()
        if (checkKeyword("throw")) return parseThrow()
        if (checkIdentText("switch")) return parseSwitch()
        if (checkIdentText("class")) return parseClassDecl()
        if (matchPunct(";")) return Block(emptyList()) // empty statement
        val expr = parseExpression()
        consumeSemicolon()
        return ExprStmt(expr)
    }

    private fun skipJumpLabel() {
        if (check(TokenType.IDENT) && !peek().newlineBefore) advance()
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
        val decls = ArrayList<Pair<Pattern, Expr?>>()
        while (true) {
            val pattern = parsePattern()
            var init: Expr? = null
            if (matchPunct("=")) init = parseAssignment()
            decls.add(pattern to init)
            if (!matchPunct(",")) break
        }
        consumeSemicolon()
        return VarDecl(kind, decls)
    }

    /**
     * A binding target with no trailing `= default` consumed here - callers
     * that allow a default at this position (array/object pattern slots,
     * function params) use [parsePatternWithDefault] instead. Keeping the
     * two separate avoids `let x = 5;` misparsing its own initializer as a
     * pattern-level default.
     */
    private fun parsePattern(): Pattern {
        if (checkPunct("[")) return parseArrayPattern()
        if (checkPunct("{")) return parseObjectPattern()
        // Only a name can be bound. Accepting any token let `(()=>{...})()` be misread as an
        // arrow function whose single parameter was named "(".
        if (!check(TokenType.IDENT)) throw parseError("expected a binding name but found '${peek().text}'")
        return IdentifierPattern(advance().text)
    }

    private fun parsePatternWithDefault(): Pattern {
        val base = parsePattern()
        if (!matchPunct("=")) return base
        val def = parseAssignment()
        return when (base) {
            is IdentifierPattern -> base.copy(default = def)
            is ArrayPattern -> base.copy(default = def)
            is ObjectPattern -> base.copy(default = def)
        }
    }

    private fun parseArrayPattern(): Pattern {
        expectPunct("[")
        val elements = ArrayList<Pattern?>()
        var restName: String? = null
        while (!checkPunct("]")) {
            if (checkPunct(",")) { elements.add(null); advance(); continue }
            if (matchPunct("...")) { restName = advance().text; break }
            elements.add(parsePatternWithDefault())
            if (!matchPunct(",")) break
        }
        expectPunct("]")
        return ArrayPattern(elements, restName)
    }

    private fun parseObjectPattern(): Pattern {
        expectPunct("{")
        val props = ArrayList<Pair<String, Pattern>>()
        val computedKeys = HashMap<Int, Expr>()
        var restName: String? = null
        while (!checkPunct("}")) {
            if (matchPunct("...")) { restName = advance().text; break }
            if (matchPunct("[")) {
                computedKeys[props.size] = parseAssignment()
                expectPunct("]")
                expectPunct(":")
                props.add("" to parsePatternWithDefault())
                if (!matchPunct(",")) break
                continue
            }
            val key = advance().text
            val pattern = if (matchPunct(":")) {
                parsePatternWithDefault()
            } else {
                val default = if (matchPunct("=")) parseAssignment() else null
                IdentifierPattern(key, default)
            }
            props.add(key to pattern)
            if (!matchPunct(",")) break
        }
        expectPunct("}")
        return ObjectPattern(props, restName, computedKeys = computedKeys)
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
        matchIdentText("await") // `for await (... of ...)`: iterated like `for...of`, values are not awaited
        expectPunct("(")

        var declKind: String? = null
        var initStmt: Stmt? = null

        if (!checkPunct(";")) {
            if (checkKeyword("var") || checkKeyword("let") || checkKeyword("const")) {
                declKind = peek().text
                advance()
                val firstPattern = parsePattern()
                if (checkKeyword("in") || checkIdentText("of")) {
                    return finishForInOf(declKind, firstPattern)
                }
                val init = if (matchPunct("=")) parseAssignment() else null
                val decls = arrayListOf(firstPattern to init)
                while (matchPunct(",")) {
                    val p = parsePattern()
                    val i = if (matchPunct("=")) parseAssignment() else null
                    decls.add(p to i)
                }
                initStmt = VarDecl(declKind, decls)
            } else if (check(TokenType.IDENT) && ((peek(1).type == TokenType.KEYWORD && peek(1).text == "in") || peek(1).text == "of")) {
                // `for (key in obj)` with an existing variable: parsed here, before `key in obj` could be read as an `in` comparison.
                return finishForInOf(null, IdentifierPattern(advance().text))
            } else {
                val expr = parseExpression()
                if (checkKeyword("in") || checkIdentText("of")) {
                    val name = (expr as? Identifier)?.name ?: throw jsError("Parse error: invalid for-in/of target")
                    return finishForInOf(null, IdentifierPattern(name))
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

    private fun finishForInOf(declKind: String?, pattern: Pattern): Stmt {
        val isOf = checkIdentText("of")
        advance() // 'in' or 'of'
        val obj = parseExpression()
        expectPunct(")")
        return ForIn(declKind, pattern, obj, parseStatement(), isOf)
    }

    private fun parseFunctionDecl(isAsync: Boolean = false): Stmt {
        advance() // 'function'
        val isGenerator = matchPunct("*")
        val name = advance().text
        val params = parseParamList()
        val body = parseBlockStatements()
        return FunctionDecl(name, params, body, isGenerator, isAsync)
    }

    private fun parseParamList(): List<Param> {
        expectPunct("(")
        val params = ArrayList<Param>()
        if (!checkPunct(")")) {
            while (true) {
                if (matchPunct("...")) {
                    params.add(Param(IdentifierPattern(advance().text), rest = true))
                    break // a rest parameter must be last
                }
                params.add(Param(parsePatternWithDefault()))
                if (!matchPunct(",") || checkPunct(")")) break
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

    private fun parseSwitch(): Stmt {
        advance() // 'switch'
        expectPunct("(")
        val disc = parseExpression()
        expectPunct(")")
        expectPunct("{")
        val cases = ArrayList<SwitchCase>()
        while (!checkPunct("}")) {
            val test: Expr?
            if (matchIdentText("case")) {
                test = parseExpression()
                expectPunct(":")
            } else if (matchIdentText("default")) {
                test = null
                expectPunct(":")
            } else {
                throw parseError("expected 'case' or 'default' in switch body, found '${peek().text}'")
            }
            val body = ArrayList<Stmt>()
            while (!checkIdentText("case") && !checkIdentText("default") && !checkPunct("}")) body.add(parseStatement())
            cases.add(SwitchCase(test, body))
        }
        expectPunct("}")
        return SwitchStmt(disc, cases)
    }

    /**
     * `class`/`extends`/`static`/`super` are all recognized by identifier
     * text (see [checkIdentText]) rather than added to the lexer's keyword
     * set, same reasoning as `switch`/`case`/`default`. Only class
     * *declarations* are supported, not class expressions
     * (`const Foo = class {...}`) - a real but narrow gap.
     */
    private fun parseClassDecl(): Stmt {
        advance() // 'class'
        val name = advance().text
        val tail = parseClassTail()
        return ClassDecl(name, tail.superClass, tail.methods, tail.staticFields)
    }

    private fun parseClassExpr(): Expr {
        advance() // 'class'
        val name = if (check(TokenType.IDENT) && peek().text != "extends") advance().text else null
        val tail = parseClassTail()
        return ClassExpr(name, tail.superClass, tail.methods, tail.staticFields)
    }

    private class ClassTail(val superClass: Expr?, val methods: List<MethodDef>, val staticFields: List<FieldDef>)

    /** A class member name: identifier, keyword, string, number, `#private`, or a computed key this engine can resolve statically (null if it can't). */
    private fun parseClassMemberName(): String? {
        if (!matchPunct("[")) return advance().text
        val key = parseAssignment()
        expectPunct("]")
        return when {
            key is StringLit -> key.value
            key is Member && key.obj == Identifier("Symbol") && key.property == StringLit("iterator") -> WELL_KNOWN_SYMBOL_ITERATOR.internalKey
            else -> null
        }
    }

    /**
     * Everything after the class name: `extends`, methods, accessors, and fields. Instance fields
     * are folded into the constructor (right after its `super(...)` call in a derived class), which
     * is when real JS initializes them; static fields are kept for the interpreter to evaluate.
     */
    private fun parseClassTail(): ClassTail {
        val superClass: Expr? = if (matchIdentText("extends")) parseCallMember() else null
        expectPunct("{")
        val methods = ArrayList<MethodDef>()
        val instanceFields = ArrayList<FieldDef>()
        val staticFields = ArrayList<FieldDef>()
        fun endsMemberName(offset: Int) = peek(offset).type == TokenType.PUNCT && peek(offset).text in setOf("(", "=", ";", "}")
        while (!checkPunct("}")) {
            if (matchPunct(";")) continue
            val isStatic = checkIdentText("static") && !endsMemberName(1)
            if (isStatic) advance()
            if (isStatic && checkPunct("{")) { parseBlock(); continue } // static initialization block: parsed, not run
            val isAsyncMethod = checkIdentText("async") && !endsMemberName(1) && !peek(1).newlineBefore
            if (isAsyncMethod) advance()
            val isGeneratorMethod = matchPunct("*")
            val kind = if (looksLikeAccessorPrefix()) {
                if (advance().text == "get") MethodKind.GET else MethodKind.SET
            } else {
                MethodKind.NORMAL
            }
            val name = parseClassMemberName()
            if (checkPunct("(")) {
                val params = parseParamList()
                val body = parseBlockStatements()
                if (name != null) methods.add(MethodDef(name, params, body, isStatic, kind, isGeneratorMethod, isAsyncMethod))
            } else {
                val value = if (matchPunct("=")) parseAssignment() else null
                consumeSemicolon()
                if (name != null) (if (isStatic) staticFields else instanceFields).add(FieldDef(name, value))
            }
        }
        expectPunct("}")
        if (instanceFields.isNotEmpty()) addFieldInitializers(methods, instanceFields, derived = superClass != null)
        return ClassTail(superClass, methods, staticFields)
    }

    private fun addFieldInitializers(methods: MutableList<MethodDef>, fields: List<FieldDef>, derived: Boolean) {
        val inits = fields.map { ExprStmt(Assign("=", Member(ThisExpr, StringLit(it.name), computed = false), it.value ?: UndefinedLit)) }
        val index = methods.indexOfFirst { it.name == "constructor" && !it.isStatic }
        if (index >= 0) {
            val ctor = methods[index]
            val superCall = if (derived) ctor.body.indexOfFirst { isSuperCall(it) } else -1
            val body = if (superCall >= 0) {
                ctor.body.subList(0, superCall + 1) + inits + ctor.body.subList(superCall + 1, ctor.body.size)
            } else {
                inits + ctor.body
            }
            methods[index] = ctor.copy(body = body)
        } else if (derived) {
            val superCall = ExprStmt(Call(SuperExpr, listOf(SpreadElement(Identifier("args")))))
            methods.add(MethodDef("constructor", listOf(Param(IdentifierPattern("args"), rest = true)), listOf(superCall) + inits, isStatic = false))
        } else {
            methods.add(MethodDef("constructor", emptyList(), inits, isStatic = false))
        }
    }

    private fun isSuperCall(stmt: Stmt): Boolean {
        val expr = (stmt as? ExprStmt)?.expr ?: return false
        val first = if (expr is Sequence) expr.expressions.first() else expr
        return first is Call && first.callee is SuperExpr
    }

    // ---- expressions (precedence climbing, low to high) ----

    private fun parseExpression(): Expr {
        val first = parseAssignment()
        if (!checkPunct(",")) return first
        val expressions = arrayListOf(first)
        while (matchPunct(",")) expressions.add(parseAssignment())
        return Sequence(expressions)
    }

    // Matches Lexer.kt's `multiCharPuncts`: every compound-assignment punctuator the lexer tokenizes
    // as a single token must be recognized here too, or valid JS using it fails to parse.
    private val assignOps = setOf("=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "**=", ">>>=", "<<=", ">>=", "&&=", "||=", "??=")

    private fun parseAssignment(): Expr {
        if (checkIdentText("yield")) return parseYield()
        tryParseArrowFunction()?.let { return it }
        val left = parseConditional()
        if (peek().type == TokenType.PUNCT && peek().text in assignOps) {
            val op = advance().text
            val value = parseAssignment()
            return Assign(op, left, value)
        }
        return left
    }

    /** `yield`/`yield*` - only meaningful (checked at runtime, not here) inside a generator function's body. */
    private fun parseYield(): Expr {
        advance() // 'yield'
        val delegate = matchPunct("*")
        val noArgument = checkPunct(")") || checkPunct("]") || checkPunct("}") || checkPunct(";") || checkPunct(",") || checkPunct(":") || peek().newlineBefore || isAtEnd()
        val arg = if (noArgument) null else parseAssignment()
        return YieldExpr(arg, delegate)
    }

    /**
     * An arrow function's parameter list, optionally preceded by `async`
     * (`x => x`, `(a, b) => a+b`, `async x => ...`, `async (a, b) => ...`).
     * Both the plain-identifier and parenthesized forms are tried
     * speculatively and backtracked on failure, same reasoning as before
     * `async` support was added - see [tryParseArrowFunction]'s prior
     * revision in version control for the un-merged original shape.
     */
    private fun tryParseArrowFunction(): Expr? {
        val startPos = pos
        val isAsync = checkIdentText("async") &&
            (peek(1).type == TokenType.IDENT || (peek(1).type == TokenType.PUNCT && peek(1).text == "(")) &&
            !peek(1).newlineBefore
        if (isAsync) advance() // tentatively consume 'async' - restored via startPos if this doesn't pan out

        if (check(TokenType.IDENT) && peek(1).type == TokenType.PUNCT && peek(1).text == "=>") {
            val param = advance().text
            advance() // =>
            return finishArrow(listOf(Param(IdentifierPattern(param))), isAsync)
        }
        if (checkPunct("(")) {
            val saved = pos
            // A speculative, possibly-destructuring-pattern parse of "(...)" - if it turns out
            // not to be followed by "=>", this was some other parenthesized expression (e.g. a
            // destructuring assignment `([a,b] = arr)`), so any parse error here just means
            // "not an arrow function", not a real syntax error - caught and backtracked below.
            // Only the parameter list is speculative. The body is parsed after the `try`, so an
            // error inside an arrow's body is reported as itself instead of being swallowed here
            // and resurfacing as a misleading "unexpected token '=>'".
            var params: List<Param>? = null
            try {
                advance()
                val parsed = ArrayList<Param>()
                if (!checkPunct(")")) {
                    while (true) {
                        if (matchPunct("...")) {
                            parsed.add(Param(parsePattern(), rest = true))
                            break
                        }
                        parsed.add(Param(parsePatternWithDefault()))
                        if (!matchPunct(",") || checkPunct(")")) break
                    }
                }
                if (checkPunct(")") && peek(1).type == TokenType.PUNCT && peek(1).text == "=>") {
                    advance()
                    advance()
                    params = parsed
                }
            } catch (_: Exception) {
                // Not an arrow function parameter list after all.
            }
            if (params != null) return finishArrow(params, isAsync)
            pos = saved
        }
        pos = startPos // not an arrow function (async or otherwise) after all - let 'async' parse normally
        return null
    }

    private fun finishArrow(params: List<Param>, isAsync: Boolean = false): Expr {
        val body = if (checkPunct("{")) parseBlockStatements() else listOf(Return(parseAssignment()))
        return FunctionExpr(null, params, body, isArrow = true, isAsync = isAsync)
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
        var left = parseBitwiseOr()
        while (checkPunct("&&")) { advance(); left = Logical("&&", left, parseBitwiseOr()) }
        return left
    }

    private fun parseBitwiseOr(): Expr {
        var left = parseBitwiseXor()
        while (checkPunct("|")) { advance(); left = Binary("|", left, parseBitwiseXor()) }
        return left
    }

    private fun parseBitwiseXor(): Expr {
        var left = parseBitwiseAnd()
        while (checkPunct("^")) { advance(); left = Binary("^", left, parseBitwiseAnd()) }
        return left
    }

    private fun parseBitwiseAnd(): Expr {
        var left = parseEquality()
        while (checkPunct("&")) { advance(); left = Binary("&", left, parseEquality()) }
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
        var left = parseShift()
        while (true) {
            val isPunct = peek().type == TokenType.PUNCT && peek().text in setOf("<", ">", "<=", ">=")
            val isKeywordOp = checkKeyword("instanceof") || checkKeyword("in")
            if (!isPunct && !isKeywordOp) break
            val op = advance().text
            left = Binary(op, left, parseShift())
        }
        return left
    }

    private fun parseShift(): Expr {
        var left = parseAdditive()
        while (peek().type == TokenType.PUNCT && peek().text in setOf("<<", ">>", ">>>")) {
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

    private val unaryOps = setOf("!", "-", "+", "~")

    private fun parseUnary(): Expr {
        if (checkIdentText("await")) { advance(); return AwaitExpr(parseUnary()) }
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
        var optionalChain = false
        while (true) {
            expr = when {
                matchPunct(".") -> Member(expr, StringLit(advance().text), computed = false)
                matchPunct("?.") -> {
                    optionalChain = true
                    when {
                        checkPunct("(") -> Call(expr, parseArgs(), optional = true)
                        matchPunct("[") -> {
                            val prop = parseExpression()
                            expectPunct("]")
                            Member(expr, prop, computed = true, optional = true)
                        }
                        else -> Member(expr, StringLit(advance().text), computed = false, optional = true)
                    }
                }
                matchPunct("[") -> {
                    val prop = parseExpression()
                    expectPunct("]")
                    Member(expr, prop, computed = true)
                }
                checkPunct("(") -> Call(expr, parseArgs())
                // Tagged template: ``tag`a${x}b` `` calls tag(["a", "b"], x).
                check(TokenType.TEMPLATE) -> {
                    val tok = advance()
                    Call(expr, listOf(TemplateStrings(tok.templateParts)) + parseTemplateExprs(tok))
                }
                else -> return if (optionalChain) OptionalChain(expr) else expr
            }
        }
    }

    private fun parseTemplateExprs(tok: Token): List<Expr> = tok.templateExprs.map { src -> Parser(Lexer(src).tokenize()).parseExpression() }

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
                args.add(if (matchPunct("...")) SpreadElement(parseAssignment()) else parseAssignment())
                if (!matchPunct(",") || checkPunct(")")) break // `f(a, b,)` allows a trailing comma
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
                return TemplateLit(tok.templateParts, parseTemplateExprs(tok))
            }
            TokenType.REGEX -> { advance(); return RegexLit(tok.text, tok.regexFlags) }
            TokenType.IDENT -> {
                if (tok.text == "super") { advance(); return SuperExpr }
                if (tok.text == "class") return parseClassExpr()
                if (tok.text == "async" && peek(1).type == TokenType.KEYWORD && peek(1).text == "function" && !peek(1).newlineBefore) {
                    advance() // 'async'
                    return parseFunctionExpr(isAsync = true)
                }
                advance()
                return Identifier(tok.text)
            }
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
        throw parseError("unexpected token '${tok.text}'")
    }

    private fun parseFunctionExpr(isAsync: Boolean = false): Expr {
        advance() // 'function'
        val isGenerator = matchPunct("*")
        val name = if (check(TokenType.IDENT)) advance().text else null
        val params = parseParamList()
        val body = parseBlockStatements()
        return FunctionExpr(name, params, body, isArrow = false, isGenerator = isGenerator, isAsync = isAsync)
    }

    private fun parseArrayLit(): Expr {
        expectPunct("[")
        val elements = ArrayList<Expr>()
        while (!checkPunct("]")) {
            if (matchPunct(",")) { elements.add(UndefinedLit); continue } // a hole: `[1, , 3]`
            elements.add(if (matchPunct("...")) SpreadElement(parseAssignment()) else parseAssignment())
            if (!matchPunct(",")) break
        }
        expectPunct("]")
        return ArrayLit(elements)
    }

    /** `get`/`set` are only accessor prefixes when followed by a name *and* `(` - `{ get: 5 }` and `{ get() {} }` (a plain method named "get") both fall through to the normal paths below. */
    private fun looksLikeAccessorPrefix(): Boolean =
        check(TokenType.IDENT) && (peek().text == "get" || peek().text == "set") &&
            (peek(1).type == TokenType.IDENT || peek(1).type == TokenType.STRING) &&
            peek(2).type == TokenType.PUNCT && peek(2).text == "("

    private fun parseObjectLit(): Expr {
        expectPunct("{")
        val props = ArrayList<Pair<Expr?, Expr>>()
        val accessors = ArrayList<ObjectAccessor>()
        while (!checkPunct("}")) {
            if (matchPunct("...")) {
                props.add(null to parseAssignment())
                if (!matchPunct(",")) break
                continue
            }
            if (looksLikeAccessorPrefix()) {
                val isGetter = advance().text == "get"
                val name = advance().text
                val params = parseParamList()
                val body = parseBlockStatements()
                accessors.add(ObjectAccessor(name, isGetter, params, body))
                if (!matchPunct(",")) break
                continue
            }
            // `async foo() {}` / `*foo() {}` / `async *foo() {}` shorthand methods - `async` only
            // counts as a modifier here when it isn't itself the property (`{ async: 1 }`) or a
            // method named "async" (`{ async() {} }`).
            val isAsyncMethod = checkIdentText("async") && !(peek(1).type == TokenType.PUNCT && (peek(1).text == ":" || peek(1).text == "("))
            if (isAsyncMethod) advance()
            val isGeneratorMethod = matchPunct("*")
            if (checkPunct("[")) {
                // computed key: `{ [expr]: value }` / `{ [expr]() {} }` - e.g. `{ [Symbol.iterator]: function() {...} }`.
                advance()
                val keyExpr = parseAssignment()
                expectPunct("]")
                if (!isAsyncMethod && !isGeneratorMethod && matchPunct(":")) {
                    props.add(keyExpr to parseAssignment())
                } else {
                    val params = parseParamList()
                    val body = parseBlockStatements()
                    props.add(keyExpr to FunctionExpr(null, params, body, isArrow = false, isGenerator = isGeneratorMethod, isAsync = isAsyncMethod))
                }
                if (!matchPunct(",")) break
                continue
            }
            val keyTok = advance()
            val key: Expr = StringLit(keyTok.text)
            if (!isAsyncMethod && !isGeneratorMethod && matchPunct(":")) {
                props.add(key to parseAssignment())
            } else if (checkPunct("(")) {
                // shorthand method syntax: { foo(a, b) { ... } } -> { foo: function(a, b) { ... } }
                val params = parseParamList()
                val body = parseBlockStatements()
                props.add(key to FunctionExpr(keyTok.text, params, body, isArrow = false, isGenerator = isGeneratorMethod, isAsync = isAsyncMethod))
            } else {
                // shorthand { x } -> { x: x }
                props.add(key to Identifier(keyTok.text))
            }
            if (!matchPunct(",")) break
        }
        expectPunct("}")
        return ObjectLit(props, accessors)
    }
}
