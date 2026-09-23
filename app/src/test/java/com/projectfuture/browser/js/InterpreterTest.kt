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

    @Test fun promiseResolveThenChain() {
        assertNum(
            6.0,
            """
            var result;
            new Promise(function(resolve) { resolve(3); })
                .then(function(v) { return v * 2; })
                .then(function(v) { result = v; });
            """.trimIndent()
        )
    }

    @Test fun promiseRejectCatch() {
        assertEquals(
            "caught: nope",
            str(
                """
                var result;
                new Promise(function(resolve, reject) { reject('nope'); })
                    .then(function(v) { result = 'should not run'; })
                    .catch(function(e) { result = 'caught: ' + e; });
                """.trimIndent()
            )
        )
    }

    @Test fun promiseAll() {
        assertNum(
            6.0,
            """
            var result;
            Promise.all([Promise.resolve(1), Promise.resolve(2), Promise.resolve(3)])
                .then(function(vals) { result = vals[0] + vals[1] + vals[2]; });
            """.trimIndent()
        )
    }

    @Test fun promiseAlreadySettledThenStillRuns() {
        // .then() attached after the promise already settled should still fire (not silently dropped).
        assertNum(
            10.0,
            """
            var p = Promise.resolve(10);
            var result;
            p.then(function(v) { result = v; });
            """.trimIndent()
        )
    }

    @Test fun regexTestAndExec() {
        assertTrue(bool("var result = /^[0-9]+${'$'}/.test('12345');"))
        assertTrue(!bool("var result = /^[0-9]+${'$'}/.test('abc');"))
        assertNum(3.0, "var m = /(\\d)-(\\d)/.exec('a5-9b'); var result = m.length;")
        assertEquals("5", str("var m = /(\\d)-(\\d)/.exec('a5-9b'); var result = m[1];"))
    }

    @Test fun regexMatchGlobalVsFirst() {
        assertNum(3.0, "var result = 'a1 b2 c3'.match(/[0-9]/g).length;")
        assertEquals("1", str("var result = 'a1 b2 c3'.match(/[0-9]/)[0];"))
    }

    @Test fun regexReplaceWithFunctionCallback() {
        assertEquals(
            "A-B-C",
            str("var result = 'a-b-c'.replace(/[a-z]/g, function(m) { return m.toUpperCase(); });")
        )
    }

    @Test fun regexReplaceWithBackreferences() {
        assertEquals(
            "15/01/2023",
            str(
                """
                var result = '2023-01-15'.replace(/(\d+)-(\d+)-(\d+)/, '${'$'}3/${'$'}2/${'$'}1');
                """.trimIndent()
            )
        )
    }

    @Test fun regexSplit() {
        assertNum(3.0, "var result = 'a1b22c'.split(/[0-9]+/).length;")
    }

    @Test fun regexConstructorForm() {
        assertTrue(bool("var re = new RegExp('^abc${'$'}', 'i'); var result = re.test('ABC');"))
    }

    @Test fun divisionStillWorksAfterRegexSupportAdded() {
        // Guards against the regex-vs-division lexer heuristic breaking ordinary division.
        assertNum(2.0, "var a = 10; var b = 5; var result = a / b;")
        assertNum(3.0, "var arr = [9]; var result = arr[0] / 3;")
    }

    @Test fun mapBasics() {
        assertNum(
            2.0,
            """
            var m = new Map();
            m.set('a', 1).set('b', 2);
            var result = m.size;
            """.trimIndent()
        )
        assertNum(1.0, "var m = new Map(); m.set('a', 1); var result = m.get('a');")
        assertTrue(bool("var m = new Map(); m.set('a', 1); var result = m.has('a');"))
        assertTrue(!bool("var m = new Map([['x',1]]); m.delete('x'); var result = m.has('x');"))
    }

    @Test fun mapObjectKeysUseIdentityNotStringification() {
        // Object keys must be distinguished by identity, not stringified - a real gap for a
        // plain string-keyed property bag, which is exactly what Map exists to cover here.
        assertNum(
            3.0,
            """
            var k1 = {}, k2 = {};
            var m = new Map();
            m.set(k1, 1);
            m.set(k2, 2);
            var result = m.get(k1) + m.get(k2);
            """.trimIndent()
        )
    }

    @Test fun setBasics() {
        assertNum(2.0, "var s = new Set([1,2,2,3]); s.delete(3); var result = s.size;")
        assertTrue(bool("var s = new Set(); s.add('x'); var result = s.has('x');"))
    }

    @Test fun numberFormatting() {
        assertEquals("3.14", str("var result = (3.14159).toFixed(2);"))
        assertEquals("ff", str("var result = (255).toString(16);"))
        assertTrue(bool("var result = Number.isInteger(5);"))
        assertTrue(!bool("var result = Number.isInteger(5.5);"))
    }

    @Test fun bitwiseOperators() {
        assertNum(4.0, "var result = 12 & 5;")
        assertNum(13.0, "var result = 12 | 1;")
        assertNum(9.0, "var result = 12 ^ 5;")
        assertNum(-13.0, "var result = ~12;")
        assertNum(48.0, "var result = 12 << 2;")
        assertNum(3.0, "var result = 12 >> 2;")
        assertNum(1073741821.0, "var result = -12 >>> 2;") // unsigned shift of a negative ToInt32 value
    }

    @Test fun switchStatementFallthroughAndBreak() {
        assertNum(2.0, """
            var x = 2, result = 0;
            switch (x) {
                case 1: result = 1; break;
                case 2: result = 2; break;
                default: result = -1;
            }
            """.trimIndent()
        )
        // no break after case 1: falls through into case 2's body too
        assertNum(12.0, """
            var result = 0;
            switch (1) {
                case 1: result += 10;
                case 2: result += 2; break;
                case 3: result += 100;
            }
            """.trimIndent()
        )
        // no matching case: runs from default onward
        assertNum(-1.0, """
            var result = 0;
            switch (99) {
                case 1: result = 1; break;
                default: result = -1;
            }
            """.trimIndent()
        )
    }

    @Test fun arrayDestructuring() {
        assertNum(3.0, "var [a, b] = [1, 2]; var result = a + b;")
        assertNum(1.0, "var [a, , c] = [1, 2, 3]; var result = a - (c - 3);") // elision skips index 1
        assertNum(7.0, "var [a, ...rest] = [2, 1, 2]; var result = a + rest.length + rest[0] + rest[1];") // 2 + 2 + 1 + 2
        assertNum(9.0, "var [a = 9] = []; var result = a;") // default used only when the slot is missing/undefined
    }

    @Test fun objectDestructuring() {
        assertNum(3.0, "var {x, y} = {x: 1, y: 2}; var result = x + y;")
        assertNum(5.0, "var {x: renamed = 5} = {}; var result = renamed;")
        assertNum(1.0, "var {a, ...rest} = {a: 1, b: 2, c: 3}; var result = Object.keys(rest).length - 1;")
    }

    @Test fun spreadInCallsArraysAndObjects() {
        assertNum(6.0, "function sum(a,b,c) { return a+b+c; } var result = sum(...[1,2,3]);")
        assertNum(4.0, "var result = [...[1,2], ...[3,4]].length;")
        assertNum(2.0, "var result = Object.keys({...{a:1}, ...{b:2}}).length;")
    }

    @Test fun defaultAndRestParameters() {
        assertNum(9.0, "function f(a, b = 4) { return a + b; } var result = f(5);")
        assertNum(4.0, "function f(a, b = 4) { return a + b; } var result = f(1, 3);")
        assertNum(6.0, "function sum(...nums) { var t = 0; for (var i=0;i<nums.length;i++) t += nums[i]; return t; } var result = sum(1,2,3);")
    }

    @Test fun destructuringInForOfAndArrowParams() {
        assertNum(3.0, """
            var m = new Map(); m.set('a', 1); m.set('b', 2);
            var result = 0;
            for (var [k, v] of m) { result += v; }
            """.trimIndent()
        )
        assertNum(3.0, "var f = ({a, b}) => a + b; var result = f({a: 1, b: 2});")
    }
}
