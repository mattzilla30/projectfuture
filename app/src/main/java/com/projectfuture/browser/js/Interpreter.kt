package com.projectfuture.browser.js

/** Lexical scope. No block-scoping distinction between var/let/const beyond const's write-protection - "var" doesn't hoist to function scope, it just declares in the current block's Environment, a simplification versus real JS. */
class Environment(val parent: Environment?) {
    private val vars = HashMap<String, JsValue>()
    private val consts = HashSet<String>()

    fun declare(name: String, value: JsValue, isConst: Boolean = false) {
        vars[name] = value
        if (isConst) consts.add(name)
    }

    fun get(name: String): JsValue {
        var env: Environment? = this
        while (env != null) {
            if (env.vars.containsKey(name)) return env.vars[name]!!
            env = env.parent
        }
        throw jsError("$name is not defined")
    }

    fun assign(name: String, value: JsValue) {
        var env: Environment? = this
        while (env != null) {
            if (env.vars.containsKey(name)) {
                if (name in env.consts) throw jsError("Assignment to constant variable.")
                env.vars[name] = value
                return
            }
            env = env.parent
        }
        var top = this
        while (top.parent != null) top = top.parent!!
        top.vars[name] = value // implicit global, matching sloppy-mode JS rather than throwing
    }

    fun has(name: String): Boolean {
        var env: Environment? = this
        while (env != null) {
            if (env.vars.containsKey(name)) return true
            env = env.parent
        }
        return false
    }
}

private class ReturnSignal(val value: JsValue) : RuntimeException(null, null, false, false)
private class BreakException : RuntimeException(null, null, false, false)
private class ContinueException : RuntimeException(null, null, false, false)

/**
 * A tree-walking interpreter for the language Parser.kt accepts. See its
 * class doc for the language subset, and JsValue.kt's class doc for the
 * flat (no-prototype-chain) object model. Additional interpreter-level
 * gaps, all deliberate: no `arguments` object, no real `instanceof`
 * (always false), `new` produces a plain object rather than following a
 * constructor's `.prototype` (so `Foo.prototype.x = ...` methods aren't
 * inherited by instances - only `this.x = ...` assignments inside the
 * constructor work), and `==`/`!=` coercion is bounded (numbers/strings/
 * booleans coerce to number; anything else falls back to strict equality)
 * rather than the full abstract equality algorithm.
 */
class Interpreter {
    val globalEnv = Environment(null)

    init {
        globalEnv.declare("this", JsUndefined)
        installGlobals(globalEnv, this)
    }

    fun run(program: List<Stmt>) {
        for (stmt in program) execStmt(stmt, globalEnv)
    }

    fun execBlock(stmts: List<Stmt>, env: Environment) {
        for (stmt in stmts) execStmt(stmt, env)
    }

    fun execStmt(stmt: Stmt, env: Environment) {
        when (stmt) {
            is ExprStmt -> evalExpr(stmt.expr, env)
            is VarDecl -> for ((pattern, initExpr) in stmt.declarations) {
                val value = initExpr?.let { evalExpr(it, env) } ?: JsUndefined
                bindPattern(pattern, value, env, stmt.kind)
            }
            is Block -> execBlock(stmt.body, Environment(env))
            is If -> if (isTruthy(evalExpr(stmt.test, env))) execStmt(stmt.consequent, env) else stmt.alternate?.let { execStmt(it, env) }
            is While -> {
                while (isTruthy(evalExpr(stmt.test, env))) {
                    try {
                        execStmt(stmt.body, env)
                    } catch (b: BreakException) {
                        break
                    } catch (c: ContinueException) {
                        continue
                    }
                }
            }
            is DoWhile -> {
                do {
                    try {
                        execStmt(stmt.body, env)
                    } catch (b: BreakException) {
                        break
                    } catch (c: ContinueException) {
                    }
                } while (isTruthy(evalExpr(stmt.test, env)))
            }
            is For -> {
                val forEnv = Environment(env)
                stmt.init?.let { execStmt(it, forEnv) }
                while (stmt.test == null || isTruthy(evalExpr(stmt.test, forEnv))) {
                    try {
                        execStmt(stmt.body, forEnv)
                    } catch (b: BreakException) {
                        break
                    } catch (c: ContinueException) {
                    }
                    stmt.update?.let { evalExpr(it, forEnv) }
                }
            }
            is ForIn -> execForIn(stmt, env)
            is FunctionDecl -> env.declare(stmt.name, Closure(stmt.name, stmt.params, stmt.body, env, isArrow = false))
            is Return -> throw ReturnSignal(stmt.argument?.let { evalExpr(it, env) } ?: JsUndefined)
            BreakStmt -> throw BreakException()
            ContinueStmt -> throw ContinueException()
            is TryStmt -> execTry(stmt, env)
            is ThrowStmt -> throw JsException(evalExpr(stmt.argument, env))
            is SwitchStmt -> execSwitch(stmt, env)
            is ClassDecl -> execClassDecl(stmt, env)
        }
    }

    private fun execClassDecl(stmt: ClassDecl, env: Environment) {
        val superFn = stmt.superClass?.let { evalExpr(it, env) as? JsFunction }
        val ctor = stmt.methods.firstOrNull { it.name == "constructor" && !it.isStatic }
        val instanceMethods = stmt.methods.filter { !it.isStatic && it.name != "constructor" }
        val staticMethods = stmt.methods.filter { it.isStatic }
        val classFn = ClassConstructor(stmt.name, superFn, ctor?.params, ctor?.body, instanceMethods, env)
        for (m in staticMethods) {
            val fn = Closure(m.name, m.params, m.body, env, isArrow = false)
            when (m.kind) {
                MethodKind.GET -> (classFn.getters ?: HashMap<String, JsFunction>().also { classFn.getters = it })[m.name] = fn
                MethodKind.SET -> (classFn.setters ?: HashMap<String, JsFunction>().also { classFn.setters = it })[m.name] = fn
                MethodKind.NORMAL -> classFn.set(m.name, fn)
            }
        }
        env.declare(stmt.name, classFn)
    }

    /**
     * Fallthrough matches real JS: once a `case` matches (or, if none do,
     * once `default` is reached), every subsequent clause's body runs in
     * source order regardless of its own test, until `break` or the end of
     * the switch. There's no separate scope per case, just one shared block
     * scope for the whole switch (matching `{}`-less `case` bodies in real
     * JS, where `let`s across cases share a single lexical environment).
     */
    private fun execSwitch(stmt: SwitchStmt, env: Environment) {
        val discVal = evalExpr(stmt.discriminant, env)
        val switchEnv = Environment(env)
        try {
            var matched = false
            for (case in stmt.cases) {
                if (!matched && case.test != null && strictEquals(discVal, evalExpr(case.test, switchEnv))) matched = true
                if (matched) execBlock(case.body, switchEnv)
            }
            if (!matched) {
                var afterDefault = false
                for (case in stmt.cases) {
                    if (case.test == null) afterDefault = true
                    if (afterDefault) execBlock(case.body, switchEnv)
                }
            }
        } catch (b: BreakException) {
            // break exits the switch
        }
    }

    private fun execForIn(stmt: ForIn, env: Environment) {
        val obj = evalExpr(stmt.obj, env)
        val iterationValues: List<JsValue> = if (stmt.isOf) {
            when (obj) {
                is JsArray -> obj.elements.toList()
                is JsString -> obj.value.map { JsString(it.toString()) }
                is JsMap -> obj.entryPairs()
                is JsSet -> obj.valuesList()
                else -> emptyList()
            }
        } else {
            (obj as? JsObject)?.ownKeys()?.map { JsString(it) } ?: emptyList()
        }
        for (v in iterationValues) {
            val loopEnv = Environment(env)
            bindPattern(stmt.pattern, v, loopEnv, stmt.declKind ?: "let")
            try {
                execStmt(stmt.body, loopEnv)
            } catch (b: BreakException) {
                break
            } catch (c: ContinueException) {
                continue
            }
        }
    }

    private fun execTry(stmt: TryStmt, env: Environment) {
        try {
            try {
                execBlock(stmt.block.body, Environment(env))
            } catch (e: JsException) {
                if (stmt.catchBlock == null) throw e // no catch clause: finally still runs, then this propagates
                val catchEnv = Environment(env)
                stmt.catchParam?.let { catchEnv.declare(it, e.value) }
                execBlock(stmt.catchBlock.body, catchEnv)
            }
        } finally {
            stmt.finallyBlock?.let { execBlock(it.body, Environment(env)) }
        }
    }

    fun evalExpr(expr: Expr, env: Environment): JsValue = when (expr) {
        is NumberLit -> JsNumber(expr.value)
        is StringLit -> JsString(expr.value)
        is BoolLit -> JsBoolean(expr.value)
        NullLit -> JsNull
        UndefinedLit -> JsUndefined
        ThisExpr -> if (env.has("this")) env.get("this") else JsUndefined
        is Identifier -> env.get(expr.name)
        is ArrayLit -> JsArray(evalArgs(expr.elements, env).toMutableList())
        is ObjectLit -> {
            val obj = JsObject()
            for ((k, v) in expr.properties) {
                if (k == null) {
                    // `...expr` spread: merge the spread object's own properties in.
                    (evalExpr(v, env) as? JsObject)?.let { spread -> for (key in spread.ownKeys()) obj.set(key, spread.get(key)) }
                } else {
                    val key = if (k is StringLit) k.value else toJsString(evalExpr(k, env))
                    obj.set(key, evalExpr(v, env))
                }
            }
            for (accessor in expr.accessors) {
                val fn = Closure(accessor.key, accessor.params, accessor.body, env, isArrow = false)
                if (accessor.isGetter) {
                    (obj.getters ?: HashMap<String, JsFunction>().also { obj.getters = it })[accessor.key] = fn
                } else {
                    (obj.setters ?: HashMap<String, JsFunction>().also { obj.setters = it })[accessor.key] = fn
                }
            }
            obj
        }
        is SpreadElement -> evalExpr(expr.argument, env) // only reached if spread appears somewhere evalArgs doesn't pre-expand it
        SuperExpr -> if (env.has("__superclassctor__")) env.get("__superclassctor__") else JsUndefined
        is TemplateLit -> {
            val sb = StringBuilder()
            for (i in expr.quasis.indices) {
                sb.append(expr.quasis[i])
                if (i < expr.expressions.size) sb.append(toJsString(evalExpr(expr.expressions[i], env)))
            }
            JsString(sb.toString())
        }
        is RegexLit -> try {
            JsRegExp(expr.pattern, expr.flags)
        } catch (e: Exception) {
            throw jsError("Invalid regular expression: ${e.message}")
        }
        is Unary -> evalUnary(expr, env)
        is UpdateExpr -> evalUpdate(expr, env)
        is Binary -> evalBinary(expr.op, evalExpr(expr.left, env), evalExpr(expr.right, env))
        is Logical -> evalLogical(expr, env)
        is Assign -> evalAssign(expr, env)
        is Conditional -> if (isTruthy(evalExpr(expr.test, env))) evalExpr(expr.consequent, env) else evalExpr(expr.alternate, env)
        is Call -> evalCall(expr, env)
        is New -> evalNew(expr, env)
        is Member -> memberKey(expr, env).let { getProperty(evalExpr(expr.obj, env), it) }
        is FunctionExpr -> {
            val capturedThis = if (expr.isArrow && env.has("this")) env.get("this") else null
            Closure(expr.name ?: "", expr.params, expr.body, env, expr.isArrow, capturedThis)
        }
    }

    private fun memberKey(expr: Member, env: Environment): String =
        if (expr.computed) toJsString(evalExpr(expr.property, env)) else (expr.property as StringLit).value

    fun getProperty(obj: JsValue, key: String): JsValue = when (obj) {
        is JsString -> {
            if (key == "length") JsNumber(obj.value.length.toDouble())
            else key.toIntOrNull()?.let { idx -> obj.value.getOrNull(idx)?.let { JsString(it.toString()) } } ?: JsUndefined
        }
        is JsObject -> {
            val getter = obj.getters?.get(key)
            if (getter != null) getter.call(this, obj, emptyList()) else obj.get(key)
        }
        else -> JsUndefined
    }

    fun setProperty(obj: JsValue, key: String, value: JsValue) {
        if (obj !is JsObject) return
        val setter = obj.setters?.get(key)
        if (setter != null) setter.call(this, obj, listOf(value)) else obj.set(key, value)
    }

    /** Evaluates a list of expressions that may contain `...expr` spreads, flattening each spread array/string in. */
    private fun evalArgs(exprs: List<Expr>, env: Environment): List<JsValue> {
        val result = ArrayList<JsValue>()
        for (e in exprs) {
            if (e is SpreadElement) {
                when (val v = evalExpr(e.argument, env)) {
                    is JsArray -> result.addAll(v.elements)
                    is JsString -> v.value.forEach { result.add(JsString(it.toString())) }
                    else -> {}
                }
            } else {
                result.add(evalExpr(e, env))
            }
        }
        return result
    }

    private fun evalCall(expr: Call, env: Environment): JsValue {
        if (expr.callee is SuperExpr) {
            val superFn = env.get("__superclassctor__") as? JsFunction
                ?: throw jsError("'super' keyword is only valid inside a derived class's constructor")
            return superFn.call(this, env.get("this"), evalArgs(expr.args, env))
        }
        if (expr.callee is Member) {
            val obj = evalExpr(expr.callee.obj, env)
            val key = memberKey(expr.callee, env)
            val args = evalArgs(expr.args, env)
            builtinMethodCall(this, obj, key, args)?.let { return it }
            val fn = getProperty(obj, key)
            if (fn !is JsFunction) throw jsError("$key is not a function")
            return fn.call(this, obj, args)
        }
        val callee = evalExpr(expr.callee, env)
        val args = evalArgs(expr.args, env)
        if (callee !is JsFunction) throw jsError("value is not a function")
        return callee.call(this, JsUndefined, args)
    }

    private fun evalNew(expr: New, env: Environment): JsValue {
        val callee = evalExpr(expr.callee, env)
        val args = evalArgs(expr.args, env)
        if (callee !is JsFunction) throw jsError("not a constructor")
        val instance = JsObject()
        val result = callee.call(this, instance, args)
        return if (result is JsObject) result else instance
    }

    fun callClosure(closure: Closure, thisArg: JsValue, args: List<JsValue>): JsValue {
        val callEnv = Environment(closure.closureEnv)
        val effectiveThis = if (closure.isArrow) (closure.capturedThis ?: JsUndefined) else thisArg
        callEnv.declare("this", effectiveThis)
        bindParams(closure.params, args, callEnv)
        return try {
            execBlock(closure.body, callEnv)
            JsUndefined
        } catch (r: ReturnSignal) {
            r.value
        }
    }

    fun bindParams(params: List<Param>, args: List<JsValue>, env: Environment) {
        var argIdx = 0
        for (param in params) {
            if (param.rest) {
                val restItems = if (argIdx < args.size) ArrayList(args.subList(argIdx, args.size)) else ArrayList()
                env.declare((param.pattern as IdentifierPattern).name, JsArray(restItems))
            } else {
                bindPattern(param.pattern, args.getOrElse(argIdx) { JsUndefined }, env, "let")
                argIdx++
            }
        }
    }

    /**
     * Binds a value to a pattern (a plain name, or a destructured array/
     * object shape) by declaring into [env] - shared by `var`/`let`/`const`
     * declarations, `for-of`/`for-in` loop targets, and function parameter
     * binding. [pattern]'s own `default` is substituted only when the
     * incoming value is `undefined`, matching real JS default-value
     * semantics (an explicit `null` does NOT trigger the default).
     */
    private fun bindPattern(pattern: Pattern, valueIn: JsValue, env: Environment, kind: String) {
        val value = if (valueIn == JsUndefined && pattern.default != null) evalExpr(pattern.default!!, env) else valueIn
        when (pattern) {
            is IdentifierPattern -> env.declare(pattern.name, value, isConst = kind == "const")
            is ArrayPattern -> {
                val arr = (value as? JsArray)?.elements ?: emptyList()
                for ((i, p) in pattern.elements.withIndex()) {
                    if (p == null) continue // elision - e.g. `[, b] = arr`
                    bindPattern(p, arr.getOrElse(i) { JsUndefined }, env, kind)
                }
                pattern.restName?.let { restName ->
                    val restItems = if (arr.size > pattern.elements.size) ArrayList(arr.subList(pattern.elements.size, arr.size)) else ArrayList()
                    env.declare(restName, JsArray(restItems), isConst = kind == "const")
                }
            }
            is ObjectPattern -> {
                val obj = value as? JsObject
                val used = HashSet<String>()
                for ((key, p) in pattern.props) {
                    used.add(key)
                    bindPattern(p, obj?.get(key) ?: JsUndefined, env, kind)
                }
                pattern.restName?.let { restName ->
                    val restObj = JsObject()
                    obj?.ownKeys()?.forEach { k -> if (k !in used) restObj.set(k, obj.get(k)) }
                    env.declare(restName, restObj, isConst = kind == "const")
                }
            }
        }
    }

    private fun evalAssign(expr: Assign, env: Environment): JsValue {
        val newValue = if (expr.op == "=") {
            evalExpr(expr.value, env)
        } else {
            val current = evalExpr(expr.target, env)
            val rhs = evalExpr(expr.value, env)
            evalBinary(expr.op.removeSuffix("="), current, rhs)
        }
        assignTo(expr.target, newValue, env)
        return newValue
    }

    private fun assignTo(target: Expr, value: JsValue, env: Environment) {
        when (target) {
            is Identifier -> env.assign(target.name, value)
            is Member -> setProperty(evalExpr(target.obj, env), memberKey(target, env), value)
            else -> throw jsError("Invalid assignment target")
        }
    }

    private fun evalUpdate(expr: UpdateExpr, env: Environment): JsValue {
        val old = toNumber(evalExpr(expr.argument, env))
        val updated = if (expr.op == "++") old + 1 else old - 1
        assignTo(expr.argument, JsNumber(updated), env)
        return JsNumber(if (expr.prefix) updated else old)
    }

    private fun evalUnary(expr: Unary, env: Environment): JsValue = when (expr.op) {
        "!" -> JsBoolean(!isTruthy(evalExpr(expr.argument, env)))
        "-" -> JsNumber(-toNumber(evalExpr(expr.argument, env)))
        "+" -> JsNumber(toNumber(evalExpr(expr.argument, env)))
        "typeof" -> if (expr.argument is Identifier && !env.has(expr.argument.name)) {
            JsString("undefined")
        } else {
            JsString(typeOf(evalExpr(expr.argument, env)))
        }
        "void" -> { evalExpr(expr.argument, env); JsUndefined }
        "~" -> JsNumber(toInt32(toNumber(evalExpr(expr.argument, env))).inv().toDouble())
        "delete" -> {
            if (expr.argument is Member) {
                val obj = evalExpr(expr.argument.obj, env)
                if (obj is JsObject) obj.properties.remove(memberKey(expr.argument, env))
            }
            JsBoolean(true)
        }
        else -> JsUndefined
    }

    fun evalBinary(op: String, l: JsValue, r: JsValue): JsValue = when (op) {
        "+" -> if (l is JsString || r is JsString) JsString(toJsString(l) + toJsString(r)) else JsNumber(toNumber(l) + toNumber(r))
        "-" -> JsNumber(toNumber(l) - toNumber(r))
        "*" -> JsNumber(toNumber(l) * toNumber(r))
        "/" -> JsNumber(toNumber(l) / toNumber(r))
        "%" -> JsNumber(toNumber(l) % toNumber(r))
        "**" -> JsNumber(Math.pow(toNumber(l), toNumber(r)))
        "==" -> JsBoolean(looseEquals(l, r))
        "!=" -> JsBoolean(!looseEquals(l, r))
        "===" -> JsBoolean(strictEquals(l, r))
        "!==" -> JsBoolean(!strictEquals(l, r))
        "<" -> compareValues(l, r) { a, b -> a < b }
        ">" -> compareValues(l, r) { a, b -> a > b }
        "<=" -> compareValues(l, r) { a, b -> a <= b }
        ">=" -> compareValues(l, r) { a, b -> a >= b }
        "instanceof" -> JsBoolean(r is JsFunction && l is JsObject && r in l.classChain) // real for `class` instances; always false against plain constructor functions (no prototype chain)
        "in" -> JsBoolean(r is JsObject && r.has(toJsString(l)))
        "&" -> JsNumber((toInt32(toNumber(l)) and toInt32(toNumber(r))).toDouble())
        "|" -> JsNumber((toInt32(toNumber(l)) or toInt32(toNumber(r))).toDouble())
        "^" -> JsNumber((toInt32(toNumber(l)) xor toInt32(toNumber(r))).toDouble())
        "<<" -> JsNumber((toInt32(toNumber(l)) shl (toInt32(toNumber(r)) and 31)).toDouble())
        ">>" -> JsNumber((toInt32(toNumber(l)) shr (toInt32(toNumber(r)) and 31)).toDouble())
        ">>>" -> JsNumber((toUInt32(toNumber(l)) ushr (toInt32(toNumber(r)) and 31)).toDouble())
        else -> JsUndefined
    }

    /** JS's ToInt32/ToUint32 abstract operations, simplified: NaN/Infinity map to 0 rather than going through the full spec algorithm. */
    private fun toInt32(d: Double): Int = if (d.isNaN() || d.isInfinite()) 0 else d.toLong().toInt()
    private fun toUInt32(d: Double): Long = if (d.isNaN() || d.isInfinite()) 0L else d.toLong() and 0xFFFFFFFFL

    private fun compareValues(l: JsValue, r: JsValue, cmp: (Double, Double) -> Boolean): JsValue {
        if (l is JsString && r is JsString) return JsBoolean(cmp(l.value.compareTo(r.value).toDouble(), 0.0))
        return JsBoolean(cmp(toNumber(l), toNumber(r)))
    }

    private fun looseEquals(l: JsValue, r: JsValue): Boolean {
        val lNullish = l == JsNull || l == JsUndefined
        val rNullish = r == JsNull || r == JsUndefined
        if (lNullish || rNullish) return lNullish && rNullish
        if (l::class == r::class) return strictEquals(l, r)
        return toNumber(l) == toNumber(r)
    }

    private fun evalLogical(expr: Logical, env: Environment): JsValue {
        val left = evalExpr(expr.left, env)
        return when (expr.op) {
            "&&" -> if (!isTruthy(left)) left else evalExpr(expr.right, env)
            "||" -> if (isTruthy(left)) left else evalExpr(expr.right, env)
            "??" -> if (left != JsUndefined && left != JsNull) left else evalExpr(expr.right, env)
            else -> JsUndefined
        }
    }
}

/**
 * The constructor function a `class` declaration produces (see
 * Interpreter.execClassDecl). `new`-ing it, or a subclass constructor
 * calling `super(...)`, builds up shared instance state on one JsObject:
 * when there's no explicit constructor, the superclass is called
 * automatically with the same arguments (matching real JS's implicit
 * default derived-class constructor); when there IS one, it must call
 * `super(...)` itself if it wants the superclass's instance methods/state -
 * this class does not auto-call it in that case, matching the real spec
 * rule that a derived class's own constructor is responsible for that.
 * Either way, this class's own instance methods are (re-)bound onto the
 * instance afterward, so they correctly override same-named inherited
 * ones. Static methods live as ordinary properties on this object itself
 * (it's a JsObject), so `Foo.method()` needs no special handling.
 *
 * Known gap: `super.method()` (an instance-method super-call, as opposed
 * to `super(...)`) isn't supported - `super` used as a bare expression
 * resolves to the superclass *constructor function*, so `super.method()`
 * would look up `method` as a static property of it, not as an inherited
 * instance method. `super(...)` itself is fully supported.
 */
class ClassConstructor(
    name: String,
    val superClassFn: JsFunction?,
    val ctorParams: List<Param>?,
    val ctorBody: List<Stmt>?,
    val instanceMethods: List<MethodDef>,
    val declEnv: Environment
) : JsFunction(name) {
    override fun call(interpreter: Interpreter, thisArg: JsValue, args: List<JsValue>): JsValue {
        val instance = thisArg as? JsObject ?: JsObject()
        if (this !in instance.classChain) instance.classChain = instance.classChain + this
        fun bindOwnMethods() {
            for (m in instanceMethods) {
                val fn = Closure(m.name, m.params, m.body, declEnv, isArrow = false)
                when (m.kind) {
                    MethodKind.GET -> (instance.getters ?: HashMap<String, JsFunction>().also { instance.getters = it })[m.name] = fn
                    MethodKind.SET -> (instance.setters ?: HashMap<String, JsFunction>().also { instance.setters = it })[m.name] = fn
                    MethodKind.NORMAL -> instance.set(m.name, fn)
                }
            }
        }
        if (ctorBody == null) {
            // No own constructor: implicit default forwards args to super (if any), then this
            // class's methods are bound to override whatever the superclass just bound.
            superClassFn?.call(interpreter, instance, args)
            bindOwnMethods()
        } else {
            // Bind first so `this.ownMethod()` works during construction, then re-bind after the
            // body runs - a `super(...)` call partway through would otherwise re-bind the
            // superclass's same-named methods afterward and silently un-override this class's own.
            bindOwnMethods()
            val callEnv = Environment(declEnv)
            callEnv.declare("this", instance)
            superClassFn?.let { callEnv.declare("__superclassctor__", it) }
            interpreter.bindParams(ctorParams ?: emptyList(), args, callEnv)
            try {
                interpreter.execBlock(ctorBody, callEnv)
            } catch (r: ReturnSignal) {
                // a constructor `return;` (no value) is legal and just ends early; a `return <object>` would
                // override the constructed instance in real JS, a corner case not implemented here
            }
            bindOwnMethods()
        }
        return instance
    }
}

/** Convenience: tokenize, parse, and run a script against a fresh or given interpreter. */
fun runScript(source: String, interpreter: Interpreter = Interpreter()): Interpreter {
    val tokens = Lexer(source).tokenize()
    val program = Parser(tokens).parseProgram()
    interpreter.run(program)
    return interpreter
}
