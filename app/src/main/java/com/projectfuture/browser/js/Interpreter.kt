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
        throw jsError("$name is not defined", "ReferenceError")
    }

    fun assign(name: String, value: JsValue) {
        var env: Environment? = this
        while (env != null) {
            if (env.vars.containsKey(name)) {
                if (name in env.consts) throw jsError("Assignment to constant variable.", "TypeError")
                env.vars[name] = value
                return
            }
            env = env.parent
        }
        var top = this
        while (top.parent != null) top = top.parent!!
        top.vars[name] = value // implicit global, matching sloppy-mode JS rather than throwing
    }

    fun hasOwn(name: String): Boolean = vars.containsKey(name)

    /** Names declared directly in this scope. */
    fun names(): Set<String> = vars.keys

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
    val prototypes = BuiltinPrototypes()

    init {
        globalEnv.declare("this", JsUndefined)
        installGlobals(globalEnv, this)
    }

    fun run(program: List<Stmt>) {
        hoistVars(program, globalEnv)
        execBlock(program, globalEnv)
    }

    /**
     * Function declarations are created before any statement in their block runs, so a function
     * can be called above the line that declares it.
     */
    fun execBlock(stmts: List<Stmt>, env: Environment) {
        var hasFunctions = false
        for (stmt in stmts) if (stmt is FunctionDecl) { hasFunctions = true; execStmt(stmt, env) }
        for (stmt in stmts) if (!hasFunctions || stmt !is FunctionDecl) execStmt(stmt, env)
    }

    private val hoistCache = java.util.IdentityHashMap<List<Stmt>, List<String>>()

    /** Declares every `var` in [body] (outside nested functions) as undefined in [env], as JS hoisting does. */
    private fun hoistVars(body: List<Stmt>, env: Environment) {
        val names = synchronized(hoistCache) { hoistCache.getOrPut(body) { LinkedHashSet<String>().also { collectVarNames(body, it) }.toList() } }
        for (name in names) if (!env.hasOwn(name)) env.declare(name, JsUndefined)
    }

    private fun collectVarNames(stmts: List<Stmt>, out: MutableSet<String>) { for (s in stmts) collectVarNames(s, out) }

    private fun collectVarNames(stmt: Stmt?, out: MutableSet<String>) {
        when (stmt) {
            is VarDecl -> if (stmt.kind == "var") stmt.declarations.forEach { out.addAll(patternNames(it.first)) }
            is Block -> collectVarNames(stmt.body, out)
            is If -> { collectVarNames(stmt.consequent, out); collectVarNames(stmt.alternate, out) }
            is While -> collectVarNames(stmt.body, out)
            is DoWhile -> collectVarNames(stmt.body, out)
            is For -> { collectVarNames(stmt.init, out); collectVarNames(stmt.body, out) }
            is ForIn -> { if (stmt.declKind == "var") out.addAll(patternNames(stmt.pattern)); collectVarNames(stmt.body, out) }
            is TryStmt -> { collectVarNames(stmt.block, out); collectVarNames(stmt.catchBlock, out); collectVarNames(stmt.finallyBlock, out) }
            is SwitchStmt -> stmt.cases.forEach { collectVarNames(it.body, out) }
            else -> {}
        }
    }

    fun execStmt(stmt: Stmt, env: Environment) {
        when (stmt) {
            is ExprStmt -> evalExpr(stmt.expr, env)
            is VarDecl -> for ((pattern, initExpr) in stmt.declarations) {
                if (stmt.kind == "var" && pattern is IdentifierPattern && pattern.default == null) {
                    // A hoisted `var`: assign the function-level binding; `var x;` alone never resets it.
                    if (initExpr != null) env.assign(pattern.name, evalExpr(initExpr, env))
                    else if (!env.has(pattern.name)) env.declare(pattern.name, JsUndefined)
                    continue
                }
                val value = initExpr?.let { evalExpr(it, env) } ?: JsUndefined
                bindPattern(pattern, value, env, stmt.kind)
            }
            is Block -> execBlock(stmt.body, Environment(env))
            is Labeled -> try { execStmt(stmt.body, env) } catch (_: BreakException) {}
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
                // `for (let i ...)`/`for (const i ...)` must give each iteration its own binding of the
                // loop variable(s), so a closure created in the body captures that iteration's value
                // rather than one binding shared (and mutated) across every iteration - the classic
                // `for (let i...)` vs `for (var i...)` closure-capture distinction. Achieved by copying
                // the lexical loop variables into a fresh Environment before each iteration's body/update
                // run, and copying their post-update values forward into the next iteration's copy.
                val isLexical = stmt.init is VarDecl && stmt.init.kind != "var"
                val loopVarNames = if (isLexical) (stmt.init as VarDecl).declarations.flatMap { patternNames(it.first) } else emptyList()
                var iterEnv = Environment(env)
                stmt.init?.let { execStmt(it, iterEnv) }
                while (stmt.test == null || isTruthy(evalExpr(stmt.test, iterEnv))) {
                    try {
                        execStmt(stmt.body, iterEnv)
                    } catch (b: BreakException) {
                        break
                    } catch (c: ContinueException) {
                    }
                    if (isLexical) {
                        val nextEnv = Environment(env)
                        for (name in loopVarNames) nextEnv.declare(name, iterEnv.get(name))
                        iterEnv = nextEnv
                    }
                    stmt.update?.let { evalExpr(it, iterEnv) }
                }
            }
            is ForIn -> execForIn(stmt, env)
            is FunctionDecl -> env.declare(stmt.name, Closure(stmt.name, stmt.params, stmt.body, env, isArrow = false, isGenerator = stmt.isGenerator, isAsync = stmt.isAsync))
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
        env.declare(stmt.name, buildClass(stmt.name, stmt.superClass, stmt.methods, stmt.staticFields, env))
    }

    /** Shared by class declarations and class expressions. */
    private fun buildClass(name: String, superClass: Expr?, methods: List<MethodDef>, staticFields: List<FieldDef>, env: Environment): ClassConstructor {
        val superFn = superClass?.let { evalExpr(it, env) as? JsFunction }
        val ctor = methods.firstOrNull { it.name == "constructor" && !it.isStatic }
        val instanceMethods = methods.filter { !it.isStatic && it.name != "constructor" }
        val staticMethods = methods.filter { it.isStatic }
        // A dedicated environment layer between the class body and its declaration site, holding
        // `__superclassctor__` so instance/static method closures (not just the constructor, which
        // used to declare it only in its own per-call env) can resolve `super`/`super.method()` too.
        val classBodyEnv = Environment(env)
        superFn?.let { classBodyEnv.declare("__superclassctor__", it) }
        val classFn = ClassConstructor(name, superFn, ctor?.params, ctor?.body, instanceMethods, classBodyEnv)
        for (m in staticMethods) {
            val fn = Closure(m.name, m.params, m.body, classBodyEnv, isArrow = false, isGenerator = m.isGenerator, isAsync = m.isAsync)
            when (m.kind) {
                MethodKind.GET -> (classFn.getters ?: HashMap<String, JsFunction>().also { classFn.getters = it })[m.name] = fn
                MethodKind.SET -> (classFn.setters ?: HashMap<String, JsFunction>().also { classFn.setters = it })[m.name] = fn
                MethodKind.NORMAL -> classFn.set(m.name, fn)
            }
        }
        if (staticFields.isNotEmpty()) {
            val staticEnv = Environment(classBodyEnv).also { it.declare("this", classFn) }
            for (field in staticFields) classFn.set(field.name, field.value?.let { evalExpr(it, staticEnv) } ?: JsUndefined)
        }
        return classFn
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

    /** All identifier names a pattern binds, including nested destructuring and rest names - used by the `For` loop's per-iteration `let`/`const` rebinding above. */
    private fun patternNames(pattern: Pattern): List<String> = when (pattern) {
        is IdentifierPattern -> listOf(pattern.name)
        is ArrayPattern -> pattern.elements.filterNotNull().flatMap { patternNames(it) } + listOfNotNull(pattern.restName)
        is ObjectPattern -> pattern.props.flatMap { patternNames(it.second) } + listOfNotNull(pattern.restName)
    }

    private fun execForIn(stmt: ForIn, env: Environment) {
        val obj = evalExpr(stmt.obj, env)
        val iterationValues: Iterable<JsValue> = if (stmt.isOf) {
            Iterable { iterableToSequence(obj) }
        } else {
            (obj as? JsObject)?.ownKeys()?.map { JsString(it) } ?: emptyList()
        }
        for (v in iterationValues) {
            val loopEnv = Environment(env)
            val pattern = stmt.pattern
            // `for (k in o)` / `for (var k in o)` assign the existing (hoisted) variable; `let`/`const` get a fresh one per iteration.
            if ((stmt.declKind == null || stmt.declKind == "var") && pattern is IdentifierPattern) env.assign(pattern.name, v)
            else bindPattern(pattern, v, loopEnv, stmt.declKind ?: "let")
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
                    val key = if (k is StringLit) k.value else propertyKeyOf(evalExpr(k, env))
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
        is Sequence -> {
            var result: JsValue = JsUndefined
            for (e in expr.expressions) result = evalExpr(e, env)
            result
        }
        is Call -> evalCall(expr, env)
        is New -> evalNew(expr, env)
        is Member -> {
            val obj = evalExpr(expr.obj, env)
            if (expr.optional && (obj == JsNull || obj == JsUndefined)) throw OptionalShortCircuit
            getProperty(obj, memberKey(expr, env))
        }
        is OptionalChain -> try { evalExpr(expr.expression, env) } catch (_: OptionalShortCircuit) { JsUndefined }
        is TemplateStrings -> JsArray(expr.parts.map { JsString(it) as JsValue }.toMutableList()).also { strings ->
            strings.set("raw", JsArray(expr.parts.map { JsString(it) as JsValue }.toMutableList()))
        }
        is ClassExpr -> buildClass(expr.name ?: "", expr.superClass, expr.methods, expr.staticFields, env)
        is FunctionExpr -> {
            val capturedThis = if (expr.isArrow && env.has("this")) env.get("this") else null
            Closure(expr.name ?: "", expr.params, expr.body, env, expr.isArrow, capturedThis, isGenerator = expr.isGenerator, isAsync = expr.isAsync)
        }
        is YieldExpr -> evalYield(expr, env)
        is AwaitExpr -> evalAwait(expr, env)
    }

    /** `yield`/`yield*`: only meaningful with a `__coroutine__` in scope, which [callClosure] declares for a `function*` body - see Coroutines.kt. */
    private fun evalYield(expr: YieldExpr, env: Environment): JsValue {
        if (!env.has("__coroutine__")) throw jsError("yield is only valid inside a generator function")
        val coroutine = (env.get("__coroutine__") as CoroutineRef).coroutine
        if (!expr.delegate) return coroutine.pause(expr.argument?.let { evalExpr(it, env) } ?: JsUndefined)
        // yield*: re-yield every value the delegate produces, then evaluate to whatever value was
        // last sent back in - real JS instead evaluates to the delegate's own final return value,
        // a narrow simplification here since iterableToSequence only exposes yielded values.
        val delegate = expr.argument?.let { evalExpr(it, env) } ?: JsUndefined
        var last: JsValue = JsUndefined
        for (v in iterableToSequence(delegate)) last = coroutine.pause(v)
        return last
    }

    /** `await`: suspends the enclosing async function's coroutine until [value] settles - see [runAsync]. Outside of one, just evaluates its argument (this interpreter's Promises resolve synchronously anyway - see Promise.kt), so a stray top-level `await` degrades gracefully instead of erroring. */
    private fun evalAwait(expr: AwaitExpr, env: Environment): JsValue {
        val value = evalExpr(expr.argument, env)
        if (!env.has("__coroutine__")) return value
        return (env.get("__coroutine__") as CoroutineRef).coroutine.pause(value)
    }

    private fun memberKey(expr: Member, env: Environment): String =
        if (expr.computed) propertyKeyOf(evalExpr(expr.property, env)) else (expr.property as StringLit).value

    fun getProperty(obj: JsValue, key: String): JsValue {
        val own = ownOrChainProperty(obj, key)
        if (own != JsUndefined) return own
        // Nothing on the value itself or its prototype chain: fall back to the built-in prototype for
        // its kind (`fn.call`, `"".trim`, `obj.hasOwnProperty`, methods a page added to Array.prototype).
        if (obj is JsObject && obj.has(key)) return own
        val builtin = prototypes.forValue(obj) ?: return own
        return if (builtin === obj) own else builtin.get(key)
    }

    private fun protoChainContains(obj: JsObject, target: JsValue): Boolean {
        if (target !is JsObject) return false
        var p = obj.proto
        while (p != null) { if (p === target) return true; p = p.proto }
        return prototypes.forValue(obj) === target
    }

    private fun ownOrChainProperty(obj: JsValue, key: String): JsValue = when (obj) {
        is JsString -> {
            if (key == "length") JsNumber(obj.value.length.toDouble())
            else key.toIntOrNull()?.let { idx -> obj.value.getOrNull(idx)?.let { JsString(it.toString()) } } ?: JsUndefined
        }
        is JsObject -> {
            val getter = obj.getters?.get(key)
            if (getter != null) {
                getter.call(this, obj, emptyList())
            } else if (obj is ClassConstructor && !obj.has(key)) {
                // Static members (methods, fields, get/set) live as ordinary properties directly on the
                // `class`'s own JsObject (see ClassConstructor/execClassDecl) rather than through any
                // prototype-chain delegation, so unlike instance methods - which get (re-)bound flat onto
                // every instance, inherited or not, by ClassConstructor.call's bindOwnMethods - a subclass
                // that doesn't itself redefine a static member has no copy of it at all. Real JS inherits
                // static members through the constructor function's own prototype chain (`Derived.__proto__
                // === Base`), so walk `superClassFn` here to find one, same as `findSuperStaticMethod` does
                // for an explicit `super.staticMethod()` call, but for plain `Derived.bar` access too.
                var cur = obj.superClassFn
                var found: JsValue = JsUndefined
                while (cur is ClassConstructor) {
                    val superGetter = cur.getters?.get(key)
                    if (superGetter != null) { found = superGetter.call(this, obj, emptyList()); break }
                    if (cur.has(key)) { found = cur.get(key); break }
                    cur = cur.superClassFn
                }
                found
            } else {
                obj.get(key)
            }
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
                    else -> iterableToSequence(v).forEach { result.add(it) }
                }
            } else {
                result.add(evalExpr(e, env))
            }
        }
        return result
    }

    private fun evalCall(expr: Call, env: Environment): JsValue {
        if (expr.callee is SuperExpr) {
            // A class extending a built-in this engine can't call as a constructor (`extends Object`,
            // `extends HTMLElement`) records no superclass constructor; its `super(...)` does nothing.
            val superFn = (if (env.has("__superclassctor__")) env.get("__superclassctor__") else null) as? JsFunction
                ?: run { evalArgs(expr.args, env); return JsUndefined }
            return superFn.call(this, env.get("this"), evalArgs(expr.args, env))
        }
        if (expr.callee is Member && expr.callee.obj is SuperExpr) {
            val superCtor = (if (env.has("__superclassctor__")) env.get("__superclassctor__") else null) as? ClassConstructor
                ?: throw jsError("'super' keyword is only valid inside a derived class's method")
            val key = memberKey(expr.callee, env)
            val thisVal = env.get("this")
            // Inside a *static* method, `this` is the class constructor itself (see evalCall's plain
            // Member-call path, which passes the callee object as thisArg) rather than an instance, so
            // `super.foo()` there must resolve `foo` as a static method up the superclass chain instead
            // of searching instanceMethods - otherwise a static method's own `super.staticMethod()` call
            // never finds anything, even though the superclass plainly has one.
            val method = (if (thisVal is JsFunction) findSuperStaticMethod(superCtor, key) else findSuperMethod(superCtor, key))
                ?: throw jsError("super.$key is not a function", "TypeError")
            return method.call(this, thisVal, evalArgs(expr.args, env))
        }
        if (expr.callee is Member) {
            val obj = evalExpr(expr.callee.obj, env)
            if (expr.callee.optional && (obj == JsNull || obj == JsUndefined)) throw OptionalShortCircuit
            val key = memberKey(expr.callee, env)
            if (expr.optional) {
                val candidate = getProperty(obj, key)
                if (candidate == JsNull || candidate == JsUndefined) throw OptionalShortCircuit
            }
            val args = evalArgs(expr.args, env)
            builtinMethodCall(this, obj, key, args)?.let { return it }
            val fn = getProperty(obj, key)
            if (fn !is JsFunction) throw jsError("$key is not a function", "TypeError")
            return fn.call(this, obj, args)
        }
        val callee = evalExpr(expr.callee, env)
        if (expr.optional && (callee == JsNull || callee == JsUndefined)) throw OptionalShortCircuit
        val args = evalArgs(expr.args, env)
        if (callee !is JsFunction) throw jsError("value is not a function", "TypeError")
        return callee.call(this, JsUndefined, args)
    }

    /** `super.method()`: walks up the superclass chain (a class's `superClassFn`, if it's itself a `class`) looking for the first same-named instance method, matching real JS's prototype-chain walk since this model doesn't otherwise have one. */
    private fun findSuperMethod(ctor: ClassConstructor, name: String): JsFunction? {
        val m = ctor.instanceMethods.firstOrNull { it.name == name && it.kind == MethodKind.NORMAL }
        if (m != null) return Closure(m.name, m.params, m.body, ctor.declEnv, isArrow = false, isGenerator = m.isGenerator, isAsync = m.isAsync)
        val parentCtor = ctor.superClassFn as? ClassConstructor ?: return null
        return findSuperMethod(parentCtor, name)
    }

    /** `super.staticMethod()` from within a static method - walks the superclass chain looking for a same-named static method (an ordinary property on the class constructor object itself, see [ClassConstructor]'s static-method handling in execClassDecl), mirroring [findSuperMethod]'s instance-method walk. */
    private fun findSuperStaticMethod(ctor: ClassConstructor, name: String): JsFunction? {
        (ctor.get(name) as? JsFunction)?.let { return it }
        val parentCtor = ctor.superClassFn as? ClassConstructor ?: return null
        return findSuperStaticMethod(parentCtor, name)
    }

    private fun evalNew(expr: New, env: Environment): JsValue {
        val callee = evalExpr(expr.callee, env)
        val args = evalArgs(expr.args, env)
        if (callee !is JsFunction) throw jsError("not a constructor", "TypeError")
        val instance = JsObject()
        // A plain constructor function's instances inherit from its `prototype` (classes set up their own instances).
        if (callee !is ClassConstructor) (getProperty(callee, "prototype") as? JsObject)?.let { instance.setPrototype(it) }
        val result = callee.call(this, instance, args)
        return if (result is JsObject) result else instance
    }

    fun callClosure(closure: Closure, thisArg: JsValue, args: List<JsValue>): JsValue {
        val callEnv = Environment(closure.closureEnv)
        val effectiveThis = if (closure.isArrow) (closure.capturedThis ?: JsUndefined) else thisArg
        callEnv.declare("this", effectiveThis)
        if (!closure.isArrow) callEnv.declare("arguments", JsArray(args.toMutableList()))
        bindParams(closure.params, args, callEnv)
        hoistVars(closure.body, callEnv)
        return when {
            closure.isGenerator -> makeGenerator(closure, callEnv)
            closure.isAsync -> runAsync(closure, callEnv)
            else -> runFunctionBody(closure, callEnv)
        }
    }

    private fun runFunctionBody(closure: Closure, callEnv: Environment): JsValue = try {
        execBlock(closure.body, callEnv)
        JsUndefined
    } catch (r: ReturnSignal) {
        r.value
    }

    /** `function*`: wraps a [JsCoroutine] running the body (see Coroutines.kt) in a [JsGenerator], without starting it - a generator only begins running on its first `.next()` call, matching real JS. */
    private fun makeGenerator(closure: Closure, callEnv: Environment): JsValue {
        val coroutine = JsCoroutine { co ->
            callEnv.declare("__coroutine__", CoroutineRef(co))
            runFunctionBody(closure, callEnv)
        }
        return JsGenerator(coroutine)
    }

    /**
     * `async function`: runs the body on a [JsCoroutine] exactly like a
     * generator does, but drives it itself instead of handing that job to
     * user code's `.next()` calls - each `await` pauses the coroutine with
     * the awaited value, which this loop subscribes to and, once it
     * settles, resumes (or throws into) the coroutine with the result,
     * looping until the body completes or throws. Returns the promise for
     * the function's eventual result/rejection, exactly like real
     * `async function`.
     */
    fun runAsync(closure: Closure, callEnv: Environment): JsPromise {
        val resultPromise = JsPromise()
        val coroutine = JsCoroutine { co ->
            callEnv.declare("__coroutine__", CoroutineRef(co))
            runFunctionBody(closure, callEnv)
        }
        fun step(msg: CoroutineOutMsg) {
            when (msg) {
                is Completed -> resultPromise.resolve(msg.value)
                is Failed -> resultPromise.reject(msg.error)
                is Yielded -> subscribeAwaited(
                    msg.value,
                    onFulfilled = { v -> step(coroutine.resume(v)) },
                    onRejected = { e -> step(coroutine.throwIn(e)) }
                )
            }
        }
        step(coroutine.resume(JsUndefined))
        return resultPromise
    }

    /** Resolves whatever `await` was given the way real JS's `Await` abstract operation would: a native Promise subscribes directly, a thenable's `.then` is called with fresh resolve/reject callbacks, and anything else is treated as already-settled (this interpreter's Promises settle synchronously - see Promise.kt - so there's no real microtask queue to defer a plain value's "resolution" onto anyway). */
    private fun subscribeAwaited(value: JsValue, onFulfilled: (JsValue) -> Unit, onRejected: (JsValue) -> Unit) {
        when {
            value is JsPromise -> value.subscribe(onFulfilled, onRejected)
            value is JsObject && value.get("then") is JsFunction -> {
                val then = value.get("then") as JsFunction
                var settled = false
                val resolveFn = NativeFunction("resolve", 1) { _, _, a -> if (!settled) { settled = true; onFulfilled(a.getOrElse(0) { JsUndefined }) }; JsUndefined }
                val rejectFn = NativeFunction("reject", 1) { _, _, a -> if (!settled) { settled = true; onRejected(a.getOrElse(0) { JsUndefined }) }; JsUndefined }
                try {
                    then.call(this, value, listOf(resolveFn, rejectFn))
                } catch (e: JsException) {
                    if (!settled) { settled = true; onRejected(e.value) }
                }
            }
            else -> onFulfilled(value)
        }
    }

    /**
     * The general iterable protocol backing `for-of`, spread, and
     * `yield*`: arrays/strings/Map/Set/typed arrays are special-cased for
     * directness (matching execForIn's pre-existing behavior), and
     * anything else - a generator, a Proxy, or a plain object implementing
     * `[Symbol.iterator]()` - is driven through that method the way real
     * JS would, lazily via Kotlin's `iterator{}` builder so an infinite
     * generator works fine as long as the consumer doesn't try to
     * materialize it. Iterators aren't `.return()`-closed on early
     * termination (e.g. a `break` mid for-of) - see Coroutines.kt's class
     * doc for why that's an accepted gap here.
     */
    fun iterableToSequence(obj: JsValue): Iterator<JsValue> = when (obj) {
        is JsArray -> obj.elements.toList().iterator()
        // Per spec, String's iterator (unlike `.length`/bracket-indexing, which stay UTF-16-code-unit-based
        // - see JsValue's `length` handling) walks whole Unicode codepoints: an astral character stored as a
        // surrogate pair yields one iteration, not two. `codePoints()` groups each surrogate pair back
        // together before re-splitting into (one- or two-char) JS string values.
        is JsString -> obj.value.codePoints().toArray()
            .map { cp -> JsString(String(Character.toChars(cp))) }.iterator()
        is JsMap -> obj.entryPairs().iterator()
        is JsSet -> obj.valuesList().iterator()
        is JsTypedArray -> obj.toList().iterator()
        is JsObject -> {
            val iterFn = getProperty(obj, SYMBOL_ITERATOR_KEY) as? JsFunction
            if (iterFn != null) objectIteratorSequence(iterFn.call(this, obj, emptyList())) else emptyList<JsValue>().iterator()
        }
        else -> emptyList<JsValue>().iterator()
    }

    private fun objectIteratorSequence(iterObj: JsValue): Iterator<JsValue> = iterator {
        val nextFn = (iterObj as? JsObject)?.get("next") as? JsFunction ?: return@iterator
        while (true) {
            val res = nextFn.call(this@Interpreter, iterObj, emptyList()) as? JsObject ?: break
            if (isTruthy(res.get("done"))) break
            yield(res.get("value"))
        }
    }

    fun bindParams(params: List<Param>, args: List<JsValue>, env: Environment) {
        var argIdx = 0
        for (param in params) {
            if (param.rest) {
                val restItems = if (argIdx < args.size) ArrayList(args.subList(argIdx, args.size)) else ArrayList()
                bindPattern(param.pattern, JsArray(restItems), env, "let")
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
                for ((index, entry) in pattern.props.withIndex()) {
                    val p = entry.second
                    val key = pattern.computedKeys[index]?.let { propertyKeyOf(evalExpr(it, env)) } ?: entry.first
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
        // `a &&= b`, `a ||= b`, `a ??= b` only evaluate and assign `b` when the test calls for it.
        if (expr.op == "&&=" || expr.op == "||=" || expr.op == "??=") {
            val current = evalExpr(expr.target, env)
            val keep = when (expr.op) {
                "&&=" -> !isTruthy(current)
                "||=" -> isTruthy(current)
                else -> current != JsNull && current != JsUndefined
            }
            if (keep) return current
            val value = evalExpr(expr.value, env)
            assignTo(expr.target, value, env)
            return value
        }
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
                val key = memberKey(expr.argument, env)
                if (obj is JsProxy) obj.deleteProperty(key) else if (obj is JsObject) obj.properties.remove(key)
            }
            JsBoolean(true)
        }
        else -> JsUndefined
    }

    fun evalBinary(op: String, l: JsValue, r: JsValue): JsValue = when (op) {
        "+" -> {
            // ES `+`: ToPrimitive both operands *first*, then decide string-concat vs numeric-add from the
            // primitive results - not the original operand types. Without this, `[] + []` (each array's
            // ToPrimitive is the string "") would wrongly take the numeric path (`toNumber(JsArray)` on the
            // original array) instead of concatenating to "", and `[] + {}` would miss "[object Object]".
            val lp = toPrimitive(l)
            val rp = toPrimitive(r)
            if (lp is JsString || rp is JsString) JsString(toJsString(lp) + toJsString(rp)) else JsNumber(toNumber(lp) + toNumber(rp))
        }
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
        "instanceof" -> JsBoolean(r is JsFunction && l is JsObject && (r in l.classChain || protoChainContains(l, getProperty(r, "prototype")))) // real for `class` instances and Error-family constructors (see ErrorConstructor in JsValue.kt), both of which stamp classChain; always false against a plain constructor function (no prototype chain)
        "in" -> JsBoolean(r is JsObject && r.has(propertyKeyOf(l)))
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
 * `super.method()` (an instance-method super-call, as opposed to
 * `super(...)`) is handled by Interpreter.evalCall's dedicated
 * `Call(Member(SuperExpr, ...))` case and `findSuperMethod`, which walks
 * `superClassFn` for the first same-named instance method - see that
 * method's doc. `super` used as a bare (non-call) expression still just
 * resolves to the superclass constructor function itself, so a plain
 * `super.someProp` read looks up a static property of it rather than an
 * inherited instance property - a narrower, less commonly hit remaining
 * gap than the method-call case used to be.
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
        // Per spec, a class constructor can only be invoked via `new` (or as a `super(...)` call from
        // a derived class, which passes the instance-under-construction as `thisArg`) - calling it as a
        // plain function (`Foo()`) is a TypeError. `new`/`super(...)` always pass a JsObject `thisArg`
        // (see evalNew and the ClassConstructor `super(...)` call site above); a bare call passes
        // JsUndefined, which is what this distinguishes.
        if (thisArg !is JsObject) throw jsError("Class constructor $name cannot be invoked without 'new'", "TypeError")
        val instance = thisArg
        if (this !in instance.classChain) instance.classChain = instance.classChain + this
        fun bindOwnMethods() {
            // Real JS replaces a property's *entire* descriptor per class body: a subclass that
            // redefines `name` (with a plain method, or with only one of get/set) fully overrides
            // whatever descriptor an ancestor class installed for it, rather than layering onto it.
            // Without this, an inherited getter/setter map entry from a superclass call earlier in
            // this same construction (or an ancestor's own instance-method binding) would linger
            // and incorrectly keep taking priority - e.g. a subclass's plain `x() {}` masked by a
            // base class's leftover `get x()`, or `d.x = v` silently still calling a base `set x()`
            // that the subclass's own `get x()`-only override was meant to replace.
            for (name in instanceMethods.map { it.name }.toSet()) {
                val ownKinds = instanceMethods.filter { it.name == name }.map { it.kind }.toSet()
                if (MethodKind.NORMAL in ownKinds) {
                    instance.getters?.remove(name)
                    instance.setters?.remove(name)
                } else {
                    if (MethodKind.GET !in ownKinds) instance.getters?.remove(name)
                    if (MethodKind.SET !in ownKinds) instance.setters?.remove(name)
                }
            }
            for (m in instanceMethods) {
                val fn = Closure(m.name, m.params, m.body, declEnv, isArrow = false, isGenerator = m.isGenerator, isAsync = m.isAsync)
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

/** Thrown by a `?.` link whose object is null or undefined; caught by the enclosing [OptionalChain]. */
private object OptionalShortCircuit : RuntimeException(null, null, false, false) {
    private fun readResolve(): Any = OptionalShortCircuit
}
