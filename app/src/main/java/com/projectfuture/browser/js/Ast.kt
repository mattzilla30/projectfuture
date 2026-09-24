package com.projectfuture.browser.js

sealed class Expr

data class NumberLit(val value: Double) : Expr()
data class StringLit(val value: String) : Expr()
data class BoolLit(val value: Boolean) : Expr()
object NullLit : Expr()
object UndefinedLit : Expr()
object ThisExpr : Expr()
data class Identifier(val name: String) : Expr()
data class ArrayLit(val elements: List<Expr>) : Expr()
data class ObjectLit(val properties: List<Pair<Expr?, Expr>>, val accessors: List<ObjectAccessor> = emptyList()) : Expr() // key expr (StringLit); null key = `...expr` spread
/** `{ get name() {...} }` / `{ set name(v) {...} }` - kept separate from [ObjectLit.properties] rather than folding accessors into that list's shape. */
data class ObjectAccessor(val key: String, val isGetter: Boolean, val params: List<Param>, val body: List<Stmt>)
data class TemplateLit(val quasis: List<String>, val expressions: List<Expr>) : Expr()
data class RegexLit(val pattern: String, val flags: String) : Expr()
data class Unary(val op: String, val argument: Expr) : Expr()
data class UpdateExpr(val op: String, val argument: Expr, val prefix: Boolean) : Expr() // ++/--
data class Binary(val op: String, val left: Expr, val right: Expr) : Expr()
data class Logical(val op: String, val left: Expr, val right: Expr) : Expr() // && || ??
data class Assign(val op: String, val target: Expr, val value: Expr) : Expr()
data class Conditional(val test: Expr, val consequent: Expr, val alternate: Expr) : Expr()
/** The comma operator: `a(), b()` evaluates each in order and yields the last value. */
data class Sequence(val expressions: List<Expr>) : Expr()
/** [optional]: `f?.()`, which yields undefined (via [OptionalChain]) when `f` is null or undefined. */
data class Call(val callee: Expr, val args: List<Expr>, val optional: Boolean = false) : Expr()
data class New(val callee: Expr, val args: List<Expr>) : Expr()
/** [optional]: `a?.b` / `a?.[k]`, which yields undefined (via [OptionalChain]) when `a` is null or undefined. */
data class Member(val obj: Expr, val property: Expr, val computed: Boolean, val optional: Boolean = false) : Expr()
/** Wraps a member/call chain containing `?.`: a nullish link short-circuits the rest of the chain to undefined. */
data class OptionalChain(val expression: Expr) : Expr()
/** The first argument of a tagged template call (``tag`a${x}b` ``): the literal parts as an array, with a `raw` copy. */
data class TemplateStrings(val parts: List<String>) : Expr()
/** `class [Name] [extends X] { ... }` used as a value. */
data class ClassExpr(val name: String?, val superClass: Expr?, val methods: List<MethodDef>, val staticFields: List<FieldDef>) : Expr()
data class FunctionExpr(
    val name: String?,
    val params: List<Param>,
    val body: List<Stmt>,
    val isArrow: Boolean,
    val isGenerator: Boolean = false,
    val isAsync: Boolean = false
) : Expr()
data class SpreadElement(val argument: Expr) : Expr() // `...expr` inside an array/object literal or a call's argument list
object SuperExpr : Expr() // meaningful as a Call callee (`super(...)`) or as a Member's object (`super.method()`) - see Interpreter's ClassConstructor
/** `yield expr` / `yield* expr` - only valid (checked at runtime, not parse time) inside a generator function's body. [delegate] marks `yield*`. */
data class YieldExpr(val argument: Expr?, val delegate: Boolean = false) : Expr()
/** `await expr` - only suspends when evaluated inside an async function's body (see Interpreter.callClosure); elsewhere it just evaluates its argument, since this interpreter's Promises resolve synchronously anyway (see Promise.kt). */
data class AwaitExpr(val argument: Expr) : Expr()

/**
 * A binding target: a plain name, or an array/object destructuring shape.
 * [default] is the value substituted when the thing being bound is
 * `undefined` - used for both destructuring defaults (`{a = 1} = obj`) and
 * default parameters (`function f(a = 1)`), which share this same type.
 * Array-pattern rest (`[a, ...rest]`) and object-pattern rest
 * (`{a, ...rest}`) both bind a plain name rather than a nested pattern - a
 * spec-accurate restriction for object rest, and a deliberate
 * simplification for array rest (real JS allows `[a, ...[b, c]]`).
 */
sealed class Pattern { abstract val default: Expr? }
data class IdentifierPattern(val name: String, override val default: Expr? = null) : Pattern()
data class ArrayPattern(val elements: List<Pattern?>, val restName: String? = null, override val default: Expr? = null) : Pattern()
/** [computedKeys]: prop index -> key expression, for `{ [expr]: target }` entries (whose name in [props] is unused). */
data class ObjectPattern(val props: List<Pair<String, Pattern>>, val restName: String? = null, override val default: Expr? = null, val computedKeys: Map<Int, Expr> = emptyMap()) : Pattern()

/** A single function parameter; [rest] marks a trailing `...name` that collects remaining arguments into an array. */
data class Param(val pattern: Pattern, val rest: Boolean = false)

sealed class Stmt

data class ExprStmt(val expr: Expr) : Stmt()
data class VarDecl(val kind: String, val declarations: List<Pair<Pattern, Expr?>>) : Stmt()
data class Block(val body: List<Stmt>) : Stmt()
data class If(val test: Expr, val consequent: Stmt, val alternate: Stmt?) : Stmt()
data class While(val test: Expr, val body: Stmt) : Stmt()
data class DoWhile(val body: Stmt, val test: Expr) : Stmt()
data class For(val init: Stmt?, val test: Expr?, val update: Expr?, val body: Stmt) : Stmt()
data class ForIn(val declKind: String?, val pattern: Pattern, val obj: Expr, val body: Stmt, val isOf: Boolean) : Stmt()
data class FunctionDecl(val name: String, val params: List<Param>, val body: List<Stmt>, val isGenerator: Boolean = false, val isAsync: Boolean = false) : Stmt()
data class Return(val argument: Expr?) : Stmt()
object BreakStmt : Stmt()
object ContinueStmt : Stmt()
data class TryStmt(val block: Block, val catchParam: String?, val catchBlock: Block?, val finallyBlock: Block?) : Stmt()
data class ThrowStmt(val argument: Expr) : Stmt()
data class SwitchCase(val test: Expr?, val body: List<Stmt>) // test == null is the `default:` clause
data class SwitchStmt(val discriminant: Expr, val cases: List<SwitchCase>) : Stmt()

enum class MethodKind { NORMAL, GET, SET }

/** `constructor`/`static` markers are ordinary method names/flags, not separate AST node kinds. */
data class MethodDef(
    val name: String,
    val params: List<Param>,
    val body: List<Stmt>,
    val isStatic: Boolean,
    val kind: MethodKind = MethodKind.NORMAL,
    val isGenerator: Boolean = false,
    val isAsync: Boolean = false
)
/** A `static name = value` class field; instance fields are folded into the constructor by the parser. */
data class FieldDef(val name: String, val value: Expr?)
data class ClassDecl(val name: String, val superClass: Expr?, val methods: List<MethodDef>, val staticFields: List<FieldDef> = emptyList()) : Stmt()
