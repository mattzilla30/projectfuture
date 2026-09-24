package com.projectfuture.browser.js

import org.junit.Assert.assertEquals
import org.junit.Test

/** Syntax real minified scripts use constantly; each case failed to parse or ran wrong before. */
class ModernSyntaxTest {
    private fun eval(code: String, name: String = "r"): JsValue =
        Interpreter().also { it.run(Parser(Lexer(code).tokenize()).parseProgram()) }.globalEnv.get(name)

    private fun str(code: String) = toJsString(eval(code))
    private fun num(code: String) = toNumber(eval(code))

    @Test fun immediatelyInvokedArrowFunctions() {
        assertEquals(3.0, num("var r = (() => 1 + 2)();"), 0.0)
        assertEquals(5.0, num("var r = ((a, b,) => a + b)(2, 3,);"), 0.0)
        assertEquals("x", str("var r = (() => { try { return 'x' } catch { return 'y' } })();"))
    }

    @Test fun arrowWithDestructuredAndDefaultParams() {
        assertEquals(6.0, num("var f = ({a, ...rest}, [b], c = 3) => a + b + c; var r = f({a: 1, z: 9}, [2]);"), 0.0)
    }

    @Test fun taggedTemplatesPassStringsAndValues() {
        assertEquals("a|b|c:1,2", str("function tag(s, ...v) { return s.join('|') + ':' + v.join(',') } var r = tag`a\${1}b\${2}c`;"))
        assertEquals("raw", str("var o = { css(s) { return s.raw ? 'raw' : 'none' } }; var r = o.css`x`;"))
    }

    @Test fun templatesWithNestedTemplatesAndRegexes() {
        assertEquals("<b>q</b>", str("var t = 'q'; var r = `\${t ? `<b>\${t}</b>` : \"}\"}`;"))
        assertEquals("\"a\\\"b\"", str("var t = 'a\"b'; var r = `\"\${t.replace(/\"/g, '\\\\\"')}\"`;"))
    }

    @Test fun optionalChainingShortCircuits() {
        assertEquals("undefined", str("var a = null; var r = typeof a?.b.c.d;"))
        assertEquals(2.0, num("var a = { b: { c: 2 } }; var r = a?.b?.c;"), 0.0)
        assertEquals("undefined", str("var f; var r = typeof f?.();"))
        assertEquals(4.0, num("var o = { m() { return 4 } }; var r = o.m?.();"), 0.0)
        assertEquals("undefined", str("var o = {}; var r = typeof o.missing?.();"))
        assertEquals(1.0, num("var a = [1]; var r = a?.[0];"), 0.0)
    }

    @Test fun forInWithAnExistingVariableAndLabels() {
        assertEquals("ab", str("var r = '', k; for (k in {a: 1, b: 2}) r += k;"))
        assertEquals(3.0, num("var r = 0; outer: for (var i = 0; i < 3; i++) { r++; continue outer; }"), 0.0)
    }

    @Test fun arrayHolesAndCompoundOperators() {
        assertEquals(4.0, num("var r = [1, , 3, ,].length;"), 0.0)
        assertEquals(8.0, num("var r = 1; r <<= 3;"), 0.0)
        assertEquals(2.0, num("var r = 16; r >>= 3;"), 0.0)
        assertEquals(5.0, num("var r = null; r ??= 5; r ??= 6;"), 0.0)
        assertEquals(7.0, num("var r = 0; r ||= 7;"), 0.0)
        assertEquals(0.0, num("var r = 0; var calls = 0; r &&= (calls++, 9); r = r + calls;"), 0.0)
    }

    @Test fun classExpressionsFieldsAndPrivateMembers() {
        assertEquals(
            "base:1:extra:7",
            str(
                """
                class Base { constructor(x) { this.x = x } describe() { return 'base:' + this.x } }
                const Derived = class extends Base {
                    extra = 7;
                    #secret = 'hidden';
                    static kind = 'derived';
                    describe() { return super.describe() + ':extra:' + this.extra }
                    reveal() { return this.#secret }
                };
                var d = new Derived(1);
                var r = d.describe();
                """.trimIndent()
            )
        )
        assertEquals("hidden|derived", str("const C = class extends Object { #s = 'hidden'; static kind = 'derived'; get s() { return this.#s } }; var r = new C().s + '|' + C.kind;"))
    }

    @Test fun computedKeysInDestructuring() {
        assertEquals("1-3", str("var n = [1, 2, 3]; let {0: first, [n.length - 1]: last} = n; var r = first + '-' + last;"))
    }

    @Test fun ofIsAnOrdinaryName() {
        assertEquals(3.0, num("function of(a, b) { return a + b } var r = of(1, 2);"), 0.0)
        assertEquals(6.0, num("var r = 0; for (const x of [1, 2, 3]) r += x;"), 0.0)
    }

    @Test fun unicodeEscapesAndIdentifiers() {
        assertEquals("é😀", str("var r = '\\u00e9' + '\\u{1F600}';"))
        assertEquals("A", str("var r = '\\x41';"))
        assertEquals("ascr", str("var o = {𝒶: 'ascr', စျ: 'za'}; var r = o['𝒶'];"))
    }
}
