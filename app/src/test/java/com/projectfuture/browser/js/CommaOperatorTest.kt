package com.projectfuture.browser.js

import org.junit.Assert.assertEquals
import org.junit.Test

class CommaOperatorTest {
    private fun run(code: String): Interpreter = Interpreter().also { it.run(Parser(Lexer(code).tokenize()).parseProgram()) }

    @Test fun evaluatesEachExpressionAndYieldsTheLast() {
        val i = run("var a = 0, b = 0; var r = (a = 1, b = 2, a + b);")
        assertEquals(3.0, toNumber(i.globalEnv.get("r")), 0.0)
        assertEquals(1.0, toNumber(i.globalEnv.get("a")), 0.0)
    }

    @Test fun parsesMinifiedStatementSequences() {
        val i = run("var n = 0; function f(){ n++ } f(), f(), f(); for (var x = 0, y = 10; x < y; x++, y--) n++; var t = !0 ? (n++, n) : 0;")
        assertEquals(9.0, toNumber(i.globalEnv.get("n")), 0.0)
        assertEquals(9.0, toNumber(i.globalEnv.get("t")), 0.0)
    }

    @Test fun argumentsAndArrayElementsAreStillSeparate() {
        val i = run("function g(a, b) { return b } var v = g(1, 2); var arr = [1, 2, 3];")
        assertEquals(2.0, toNumber(i.globalEnv.get("v")), 0.0)
        assertEquals(3, (i.globalEnv.get("arr") as JsArray).elements.size)
    }
}
