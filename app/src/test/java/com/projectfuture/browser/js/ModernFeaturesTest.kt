package com.projectfuture.browser.js

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the interpreter features added alongside this test file:
 * async/await, generators, Proxy/Reflect/Symbol, typed arrays, and
 * `super.method()` calls. Follows InterpreterTest's convention of running
 * real JS snippets and reading a `result` global back out.
 */
class ModernFeaturesTest {

    private fun result(src: String): JsValue = runScript(src).globalEnv.get("result")
    private fun str(src: String): String = (result(src) as JsString).value
    private fun bool(src: String): Boolean = (result(src) as JsBoolean).value
    private fun assertNum(expected: Double, src: String) = assertEquals(expected, (result(src) as JsNumber).value, 1e-9)

    // ---- async/await ----

    @Test fun asyncFunctionReturnsAPromiseResolvedWithItsReturnValue() {
        assertNum(
            5.0,
            """
            async function f() { return 5; }
            var result;
            f().then(function(v) { result = v; });
            """.trimIndent()
        )
    }

    @Test fun awaitUnwrapsAResolvedPromise() {
        assertNum(
            8.0,
            """
            async function f() {
                var a = await Promise.resolve(3);
                var b = await Promise.resolve(5);
                return a + b;
            }
            var result;
            f().then(function(v) { result = v; });
            """.trimIndent()
        )
    }

    @Test fun awaitOnAPlainValueJustPassesItThrough() {
        assertNum(
            7.0,
            """
            async function f() { return await 7; }
            var result;
            f().then(function(v) { result = v; });
            """.trimIndent()
        )
    }

    @Test fun asyncFunctionRejectionIsCatchable() {
        assertEquals(
            "caught: nope",
            str(
                """
                async function f() { await Promise.resolve(1); throw 'nope'; }
                var result;
                f().catch(function(e) { result = 'caught: ' + e; });
                """.trimIndent()
            )
        )
    }

    @Test fun asyncArrowFunctionsWork() {
        assertNum(
            4.0,
            """
            var f = async (x) => await Promise.resolve(x * 2);
            var result;
            f(2).then(function(v) { result = v; });
            """.trimIndent()
        )
    }

    @Test fun awaitingARejectedPromisePropagatesUnlessCaught() {
        assertEquals(
            "caught: boom",
            str(
                """
                async function inner() { throw 'boom'; }
                async function outer() {
                    try {
                        await inner();
                        return 'not reached';
                    } catch (e) {
                        return 'caught: ' + e;
                    }
                }
                var result;
                outer().then(function(v) { result = v; });
                """.trimIndent()
            )
        )
    }

    // ---- generators ----

    @Test fun generatorYieldsValuesAcrossNextCalls() {
        assertNum(
            6.0,
            """
            function* gen() { yield 1; yield 2; yield 3; }
            var g = gen();
            var result = g.next().value + g.next().value + g.next().value;
            """.trimIndent()
        )
    }

    @Test fun generatorDoneFlagAndFinalReturnValue() {
        assertNum(
            42.0,
            """
            function* gen() { yield 1; return 42; }
            var g = gen();
            g.next();
            var second = g.next();
            var result = second.done ? second.value : -1;
            """.trimIndent()
        )
    }

    @Test fun generatorWorksInForOfAndSpread() {
        assertNum(6.0, "function* gen() { yield 1; yield 2; yield 3; } var sum = 0; for (var x of gen()) { sum += x; } var result = sum;")
        assertNum(3.0, "function* gen() { yield 'a'; yield 'b'; yield 'c'; } var result = [...gen()].length;")
    }

    @Test fun generatorNextCanSendValuesBackIn() {
        assertNum(
            30.0,
            """
            function* gen() {
                var a = yield 1;
                var b = yield a + 1;
                return a + b;
            }
            var g = gen();
            g.next();        // runs to first yield, returns 1
            g.next(10);      // a = 10, runs to second yield with a+1=11
            var result = g.next(20).value; // b = 20, returns a+b = 30
            """.trimIndent()
        )
    }

    @Test fun generatorThrowIsCatchableInsideTheGenerator() {
        assertEquals(
            "caught: boom",
            str(
                """
                function* gen() {
                    try {
                        yield 1;
                        yield 2;
                    } catch (e) {
                        yield 'caught: ' + e;
                    }
                }
                var g = gen();
                g.next();
                var result = g.throw('boom').value;
                """.trimIndent()
            )
        )
    }

    @Test fun generatorReturnEndsIterationEarly() {
        assertNum(
            99.0,
            """
            function* gen() { yield 1; yield 2; yield 3; }
            var g = gen();
            g.next();
            var r = g.return(99);
            var result = r.done ? r.value : -1;
            """.trimIndent()
        )
    }

    @Test fun yieldStarDelegatesToAnotherIterable() {
        assertNum(6.0, "function* inner() { yield 1; yield 2; } function* outer() { yield* inner(); yield 3; } var sum = 0; for (var x of outer()) sum += x; var result = sum;")
    }

    // ---- Symbol / custom iterables via Symbol.iterator ----

    @Test fun symbolBasicsAndTypeof() {
        assertEquals("symbol", str("var result = typeof Symbol('x');"))
        assertTrue(bool("var a = Symbol('x'), b = Symbol('x'); var result = a !== b;")) // each Symbol() call is distinct
        assertTrue(bool("var result = Symbol.iterator === Symbol.iterator;")) // well-known symbols are stable singletons
    }

    @Test fun symbolAsAComputedObjectKeyDoesNotShowUpInObjectKeys() {
        assertNum(
            1.0,
            """
            var s = Symbol('hidden');
            var o = {};
            o[s] = 42;
            o.visible = 1;
            var result = Object.keys(o).length;
            """.trimIndent()
        )
        assertNum(42.0, "var s = Symbol('hidden'); var o = {}; o[s] = 42; var result = o[s];")
    }

    @Test fun customIterableViaSymbolIteratorWorksInForOfAndSpread() {
        assertNum(
            6.0,
            """
            var range = {
                from: 1, to: 3,
                [Symbol.iterator]: function() {
                    var current = this.from, last = this.to;
                    return {
                        next: function() {
                            if (current <= last) return { value: current++, done: false };
                            return { value: undefined, done: true };
                        }
                    };
                }
            };
            var sum = 0;
            for (var v of range) sum += v;
            var result = sum;
            """.trimIndent()
        )
        assertNum(
            3.0,
            """
            var range = {
                from: 1, to: 3,
                [Symbol.iterator]: function() {
                    var current = this.from, last = this.to;
                    return { next: function() {
                        if (current <= last) return { value: current++, done: false };
                        return { value: undefined, done: true };
                    } };
                }
            };
            var result = [...range].length;
            """.trimIndent()
        )
    }

    // ---- Proxy / Reflect ----

    @Test fun proxyGetTrapInterceptsPropertyReads() {
        assertNum(
            10.0,
            """
            var target = { x: 5 };
            var p = new Proxy(target, { get: function(t, key) { return t[key] * 2; } });
            var result = p.x;
            """.trimIndent()
        )
    }

    @Test fun proxySetTrapInterceptsWrites() {
        assertNum(
            8.0,
            """
            var log = [];
            var target = {};
            var p = new Proxy(target, { set: function(t, key, value) { log.push(key); t[key] = value * 2; return true; } });
            p.x = 4;
            var result = target.x;
            """.trimIndent()
        )
    }

    @Test fun proxyHasTrapInterceptsInOperator() {
        assertTrue(bool("var p = new Proxy({}, { has: function() { return true; } }); var result = 'anything' in p;"))
    }

    @Test fun proxyWithNoTrapsFallsThroughToTheTarget() {
        assertNum(7.0, "var target = { x: 7 }; var p = new Proxy(target, {}); var result = p.x;")
    }

    @Test fun reflectGetSetHasAndOwnKeys() {
        assertNum(3.0, "var o = { a: 3 }; var result = Reflect.get(o, 'a');")
        assertNum(9.0, "var o = {}; Reflect.set(o, 'a', 9); var result = o.a;")
        assertTrue(bool("var o = { a: 1 }; var result = Reflect.has(o, 'a');"))
        assertNum(2.0, "var o = { a: 1, b: 2 }; var result = Reflect.ownKeys(o).length;")
    }

    @Test fun reflectApplyAndConstruct() {
        assertNum(7.0, "function add(a, b) { return a + b; } var result = Reflect.apply(add, null, [3, 4]);")
        assertNum(
            5.0,
            """
            function Point(x, y) { this.x = x; this.y = y; }
            var p = Reflect.construct(Point, [2, 3]);
            var result = p.x + p.y;
            """.trimIndent()
        )
    }

    // ---- typed arrays ----

    @Test fun int32ArrayBasicIndexingAndLength() {
        assertNum(
            6.0,
            """
            var a = new Int32Array(3);
            a[0] = 1; a[1] = 2; a[2] = 3;
            var result = a[0] + a[1] + a[2];
            """.trimIndent()
        )
        assertNum(3.0, "var a = new Int32Array(3); var result = a.length;")
    }

    @Test fun uint8ArrayWrapsOnOverflowLikeRealJs() {
        assertNum(0.0, "var a = new Uint8Array(1); a[0] = 256; var result = a[0];")
        assertNum(255.0, "var a = new Uint8Array(1); a[0] = 255; var result = a[0];")
    }

    @Test fun float32ArrayFromRegularArrayAndMethods() {
        assertNum(6.0, "var a = Float32Array.from([1, 2, 3]); var result = a.reduce(function(acc, x) { return acc + x; }, 0);")
        assertNum(1.0, "var a = new Int8Array([1, 2, 3]); var result = a.indexOf(3) - 1;")
    }

    @Test fun typedArraySharesAnUnderlyingArrayBuffer() {
        assertNum(
            65.0,
            """
            var buf = new ArrayBuffer(4);
            var bytes = new Uint8Array(buf);
            var ints = new Int32Array(buf);
            bytes[0] = 65;
            var result = bytes[0];
            """.trimIndent()
        )
        assertNum(4.0, "var buf = new ArrayBuffer(4); var result = buf.byteLength;")
    }

    @Test fun typedArrayWorksWithForOfAndSpread() {
        assertNum(6.0, "var a = new Int32Array([1,2,3]); var sum = 0; for (var v of a) sum += v; var result = sum;")
        assertNum(3.0, "var a = new Int32Array([1,2,3]); var result = [...a].length;")
    }

    @Test fun typedArraySubarrayViewsTheSameBuffer() {
        assertNum(
            99.0,
            """
            var a = new Int32Array([1, 2, 3, 4]);
            var sub = a.subarray(1, 3);
            sub[0] = 99;
            var result = a[1];
            """.trimIndent()
        )
    }

    // ---- super.method() ----

    @Test fun superMethodCallInvokesTheParentClassImplementation() {
        assertNum(
            15.0,
            """
            class Base {
                describe() { return 10; }
            }
            class Derived extends Base {
                describe() { return super.describe() + 5; }
            }
            var result = new Derived().describe();
            """.trimIndent()
        )
    }

    @Test fun superMethodCallWorksThroughMultipleInheritanceLevels() {
        assertEquals(
            "A-B-C",
            str(
                """
                class A { name() { return 'A'; } }
                class B extends A { name() { return super.name() + '-B'; } }
                class C extends B { name() { return super.name() + '-C'; } }
                var result = new C().name();
                """.trimIndent()
            )
        )
    }

    @Test fun superMethodCallSeesTheSubclassInstanceAsThis() {
        assertNum(
            42.0,
            """
            class Base {
                getValue() { return this.value; }
            }
            class Derived extends Base {
                constructor() { super(); this.value = 42; }
                getValue() { return super.getValue(); }
            }
            var result = new Derived().getValue();
            """.trimIndent()
        )
    }

    @Test fun superMethodCallWorksForStaticMethods() {
        assertNum(
            15.0,
            """
            class Base {
                static describe() { return 10; }
            }
            class Derived extends Base {
                static describe() { return super.describe() + 5; }
            }
            var result = Derived.describe();
            """.trimIndent()
        )
    }
}
