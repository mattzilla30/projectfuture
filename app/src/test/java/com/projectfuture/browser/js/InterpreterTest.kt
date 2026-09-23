package com.projectfuture.browser.js

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InterpreterTest {

    private fun result(src: String): JsValue = runScript(src).globalEnv.get("result")
    private fun str(src: String): String = (result(src) as JsString).value
    private fun bool(src: String): Boolean = (result(src) as JsBoolean).value
    private fun assertNum(expected: Double, src: String) = assertEquals(expected, (result(src) as JsNumber).value, 1e-9)

    @Test fun arithmetic() {
        assertNum(7.0, "var result = 1 + 2 * 3;")
        assertNum(9.0, "var result = (1 + 2) * 3;")
        assertNum(1.0, "var result = 10 % 3;")
        assertNum(8.0, "var result = 2 ** 3;")
        assertNum(-5.0, "var result = -5;")
    }

    @Test fun stringConcatAndCoercion() {
        assertEquals("ab", str("var result = 'a' + 'b';"))
        assertEquals("a1", str("var result = 'a' + 1;"))
        assertEquals("5", str("var result = String(5);"))
        assertNum(5.0, "var result = Number('5');")
    }

    @Test fun templateLiteral() {
        assertEquals("x=5!", str("var x = 5; var result = `x=\${x}!`;"))
        assertEquals("1+1=2", str("var result = `1+1=\${1+1}`;"))
    }

    @Test fun varLetConstScoping() {
        assertNum(3.0, "let a = 1; { let a = 2; } var result = a + 2 - 1 + 1;")
        var threw = false
        try {
            runScript("const a = 1; a = 2;")
        } catch (e: JsException) {
            threw = true
        }
        assertTrue("assigning to const should throw", threw)
    }

    @Test fun ifElseAndTernary() {
        assertEquals("yes", str("var result = (1 < 2) ? 'yes' : 'no';"))
        assertNum(10.0, "var x = 5; var result; if (x > 3) { result = 10; } else { result = 20; }")
    }

    @Test fun logicalOperators() {
        assertNum(2.0, "var result = 0 || 2;")
        assertNum(0.0, "var result = 0 && 2;")
        assertNum(5.0, "var result = null ?? 5;")
        assertTrue(bool("var result = true && !false;"))
    }

    @Test fun whileAndForLoops() {
        assertNum(10.0, "var i = 0, sum = 0; while (i < 5) { sum += i; i++; } var result = sum;")
        assertNum(45.0, "var sum = 0; for (var i = 0; i < 10; i++) { sum += i; } var result = sum;")
        // i runs 0..3 (breaks when i reaches 4); even i's are skipped by continue, so only 1 and 3 are added: 1+3=4.
        assertNum(
            4.0,
            "var sum = 0; for (var i = 0; i < 10; i++) { if (i >= 4) break; if (i % 2 == 0) continue; sum += i; } var result = sum;"
        )
    }

    @Test fun functionsAndClosures() {
        assertNum(
            120.0,
            """
            function fact(n) { if (n <= 1) return 1; return n * fact(n - 1); }
            var result = fact(5);
            """.trimIndent()
        )
        assertNum(
            15.0,
            """
            function makeAdder(x) { return function(y) { return x + y; }; }
            var add5 = makeAdder(5);
            var result = add5(10);
            """.trimIndent()
        )
    }

    @Test fun arrowFunctions() {
        assertNum(6.0, "var f = (a, b) => a + b; var result = f(2, 4);")
        assertNum(4.0, "var f = x => x * 2; var result = f(2);")
        // [1,2,3].map(x=>x*2) = [2,4,6]; .filter(x=>x>2) keeps 4 and 6 (2 doesn't satisfy >2) = length 2.
        assertNum(2.0, "var arr = [1,2,3]; var result = arr.map(x => x * 2).filter(x => x > 2).length;")
    }

    @Test fun arraysAndMethods() {
        assertNum(3.0, "var arr = [1,2]; arr.push(3); var result = arr.length;")
        assertNum(6.0, "var result = [1,2,3].reduce((a,b) => a + b, 0);")
        assertEquals("1,2,3", str("var result = [1,2,3].join(',');"))
        assertNum(2.0, "var result = [1,2,3].indexOf(3);")
        assertTrue(bool("var result = [1,2,3].includes(2);"))
        assertNum(4.0, "var mapped = [1,2,3].map(function(x){ return x + 1; }); var result = mapped[2];")
    }

    @Test fun objectsAndMembers() {
        assertEquals("Ann", str("var o = { name: 'Ann', age: 30 }; var result = o.name;"))
        assertNum(30.0, "var o = { name: 'Ann', age: 30 }; var result = o['age'];")
        assertNum(2.0, "var o = {a:1,b:2}; var result = Object.keys(o).length;")
    }

    @Test fun thisBindingInMethodsVsArrows() {
        assertNum(
            5.0,
            """
            var obj = { value: 5, getValue: function() { return this.value; } };
            var result = obj.getValue();
            """.trimIndent()
        )
    }

    @Test fun constructorFunctionsAndNew() {
        assertNum(
            3.0,
            """
            function Point(x, y) { this.x = x; this.y = y; }
            var p = new Point(1, 2);
            var result = p.x + p.y;
            """.trimIndent()
        )
    }

    @Test fun stringMethods() {
        assertEquals("HELLO", str("var result = 'hello'.toUpperCase();"))
        assertEquals("ell", str("var result = 'hello'.slice(1, 4);"))
        assertTrue(bool("var result = 'hello world'.includes('world');"))
        assertNum(5.0, "var result = 'hello'.length;")
    }

    @Test fun jsonRoundTrip() {
        assertNum(3.0, "var o = JSON.parse('{\"a\":1,\"b\":2}'); var result = o.a + o.b;")
        assertEquals("{\"a\":1}", str("var result = JSON.stringify({a: 1});"))
    }

    @Test fun tryCatchThrow() {
        assertEquals(
            "caught: boom",
            str(
                """
                var result;
                try { throw 'boom'; } catch (e) { result = 'caught: ' + e; }
                """.trimIndent()
            )
        )
    }

    @Test fun finallyRunsAndExceptionStillPropagatesWithoutCatch() {
        var finallyRan = false
        val interp = Interpreter()
        interp.globalEnv.declare("markFinally", NativeFunction("markFinally") { _, _, _ -> finallyRan = true; JsUndefined })
        var propagated = false
        try {
            val tokens = Lexer("try { throw 'x'; } finally { markFinally(); }").tokenize()
            interp.run(Parser(tokens).parseProgram())
        } catch (e: JsException) {
            propagated = true
        }
        assertTrue("finally should run even without a catch clause", finallyRan)
        assertTrue("the exception should still propagate past a plain try/finally", propagated)
    }

    @Test fun typeofOperator() {
        assertEquals("number", str("var result = typeof 5;"))
        assertEquals("string", str("var result = typeof 'x';"))
        assertEquals("undefined", str("var result = typeof undefinedVar;"))
        assertEquals("function", str("var result = typeof function(){};"))
    }

    @Test fun forOfAndForIn() {
        assertNum(6.0, "var sum = 0; for (var x of [1,2,3]) { sum += x; } var result = sum;")
        assertNum(2.0, "var o = {a:1,b:2}; var count = 0; for (var k in o) { count++; } var result = count;")
    }

    @Test fun equalityOperators() {
        assertTrue(bool("var result = (1 == '1');"))
        assertTrue(!bool("var result = (1 === '1');"))
        assertTrue(bool("var result = (null == undefined);"))
        assertTrue(!bool("var result = (null === undefined);"))
    }
}
