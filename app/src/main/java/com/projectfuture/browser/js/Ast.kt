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
data class ObjectLit(val properties: List<Pair<Expr, Expr>>) : Expr() // key expr (StringLit or Identifier-as-name), value expr
data class TemplateLit(val quasis: List<String>, val expressions: List<Expr>) : Expr()
data class RegexLit(val pattern: String, val flags: String) : Expr()
data class Unary(val op: String, val argument: Expr) : Expr()
data class UpdateExpr(val op: String, val argument: Expr, val prefix: Boolean) : Expr() // ++/--
data class Binary(val op: String, val left: Expr, val right: Expr) : Expr()
data class Logical(val op: String, val left: Expr, val right: Expr) : Expr() // && || ??
data class Assign(val op: String, val target: Expr, val value: Expr) : Expr()
data class Conditional(val test: Expr, val consequent: Expr, val alternate: Expr) : Expr()
data class Call(val callee: Expr, val args: List<Expr>) : Expr()
data class New(val callee: Expr, val args: List<Expr>) : Expr()
data class Member(val obj: Expr, val property: Expr, val computed: Boolean) : Expr()
data class FunctionExpr(val name: String?, val params: List<String>, val body: List<Stmt>, val isArrow: Boolean) : Expr()

sealed class Stmt

data class ExprStmt(val expr: Expr) : Stmt()
data class VarDecl(val kind: String, val declarations: List<Pair<String, Expr?>>) : Stmt()
data class Block(val body: List<Stmt>) : Stmt()
data class If(val test: Expr, val consequent: Stmt, val alternate: Stmt?) : Stmt()
data class While(val test: Expr, val body: Stmt) : Stmt()
data class DoWhile(val body: Stmt, val test: Expr) : Stmt()
data class For(val init: Stmt?, val test: Expr?, val update: Expr?, val body: Stmt) : Stmt()
data class ForIn(val declKind: String?, val varName: String, val obj: Expr, val body: Stmt, val isOf: Boolean) : Stmt()
data class FunctionDecl(val name: String, val params: List<String>, val body: List<Stmt>) : Stmt()
data class Return(val argument: Expr?) : Stmt()
object BreakStmt : Stmt()
object ContinueStmt : Stmt()
data class TryStmt(val block: Block, val catchParam: String?, val catchBlock: Block?, val finallyBlock: Block?) : Stmt()
data class ThrowStmt(val argument: Expr) : Stmt()
data class SwitchCase(val test: Expr?, val body: List<Stmt>) // test == null is the `default:` clause
data class SwitchStmt(val discriminant: Expr, val cases: List<SwitchCase>) : Stmt()
