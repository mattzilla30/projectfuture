package com.projectfuture.browser.browser

import com.projectfuture.browser.html.HtmlParser
import com.projectfuture.browser.js.Interpreter
import com.projectfuture.browser.js.Lexer
import com.projectfuture.browser.js.Parser
import com.projectfuture.browser.js.toJsString
import org.junit.Assert.assertEquals
import org.junit.Test

/** Standard-library and DOM behavior real page scripts depend on. */
class WebPlatformTest {
    private fun page(html: String = "<html><head><title>T</title></head><body><div id=a class='x y'>hi</div></body></html>"): Pair<Interpreter, DomBridge> {
        val interp = Interpreter()
        val bridge = DomBridge(HtmlParser(html).parse())
        bridge.install(interp.globalEnv)
        return interp to bridge
    }

    private fun run(code: String, setup: Pair<Interpreter, DomBridge> = page()): String {
        val (interp, _) = setup
        interp.run(Parser(Lexer(code).tokenize()).parseProgram())
        return toJsString(interp.globalEnv.get("r"))
    }

    @Test fun functionCallApplyBind() {
        assertEquals("1|3|2", run("function f(a, b) { return this.v + (b || 0) } var r = [f.call({v: 1}), f.apply({v: 1}, [0, 2]), f.bind({v: 2})()].join('|');"))
        assertEquals("[object Array]|[object Null]", run("var r = Object.prototype.toString.call([]) + '|' + Object.prototype.toString.call(null);"))
        assertEquals("2,3", run("function g() { return Array.prototype.slice.call(arguments, 1) } var r = g(1, 2, 3).join(',');"))
        assertEquals("function", run("var r = typeof ''.trim;"))
    }

    @Test fun constructorPrototypesAndInstanceof() {
        assertEquals("hi Ann|true|true", run(
            "function P(n) { this.n = n } P.prototype.greet = function () { return 'hi ' + this.n };" +
                "var p = new P('Ann'); var r = p.greet() + '|' + (p instanceof P) + '|' + p.hasOwnProperty('n');"
        ))
        assertEquals("2", run("Array.prototype.last = function () { return this[this.length - 1] }; var r = [1, 2].last();"))
        assertEquals("proto|true", run("var base = { kind: 'proto' }; var o = Object.create(base); var r = o.kind + '|' + (Object.getPrototypeOf(o) === base);"))
        assertEquals("computed", run("var o = {}; Object.defineProperty(o, 'x', { get: function () { return 'computed' } }); var r = o.x;"))
    }

    @Test fun varAndFunctionHoisting() {
        assertEquals("undefined|5|ok", run("var a = typeof early; var early = 1; if (true) { var inner = 5 } var r = a + '|' + inner + '|' + later(); function later() { return 'ok' }"))
        assertEquals("c", run("var r; for (r in { a: 1, b: 2, c: 3 }) {}"))
    }

    @Test fun globalObjectIsTheGlobalScope() {
        assertEquals("1|true|true|2", run("window.foo = 1; var bar = 2; var r = foo + '|' + (window.Promise === Promise) + '|' + (globalThis === window) + '|' + window.bar;"))
    }

    @Test fun domTreeEditing() {
        assertEquals(
            "<b>b</b>start<i>i</i>|3|x-1",
            run(
                """
                var d = document.getElementById('a');
                d.textContent = '';
                d.appendChild(document.createTextNode('start'));
                d.insertBefore(document.createElement('b'), d.firstChild).textContent = 'b';
                var i = document.createElement('i'); i.textContent = 'i'; d.append(i);
                d.dataset.userId = 'x-1';
                var r = d.innerHTML + '|' + d.childNodes.length + '|' + d.getAttribute('data-user-id');
                """.trimIndent()
            )
        )
        assertEquals("1|0|HEAD", run("var d = document.getElementById('a'); var n = document.getElementsByTagName('div').length; d.parentNode.removeChild(d); var r = n + '|' + document.querySelectorAll('div').length + '|' + document.head.tagName;"))
        assertEquals("red|12px", run("var s = document.getElementById('a').style; s.setProperty('color', 'red'); s.fontSize = '12px'; var r = s.getPropertyValue('color') + '|' + s.fontSize;"))
        assertEquals("true|false", run("var c = document.getElementById('a').classList; c.add('p', 'q'); c.remove('x'); var r = c.contains('q') + '|' + c.contains('x');"))
        assertEquals("true", run("var r = document.getElementById('a') instanceof HTMLElement;"))
    }

    @Test fun lifecycleEventsAndObservers() {
        val setup = page()
        run("var log = []; document.addEventListener('DOMContentLoaded', function () { log.push('dcl:' + document.readyState) });" +
            "window.addEventListener('load', function () { log.push('load:' + document.readyState) });" +
            "new IntersectionObserver(function (entries) { log.push('io:' + entries[0].isIntersecting) }).observe(document.getElementById('a'));" +
            "var r = document.readyState;", setup)
        setup.second.fireDomContentLoaded(setup.first)
        assertEquals("dcl:interactive,load:complete,io:true", run("var r = log.join(',');", setup))
    }

    @Test fun cookiesGoThroughTheBridge() {
        val setup = page()
        var stored = ""
        setup.second.cookieReader = { stored }
        setup.second.cookieWriter = { stored = it.substringBefore(';') }
        assertEquals("a=1", run("document.cookie = 'a=1; path=/'; var r = document.cookie;", setup))
    }

    @Test fun encodingAndUrls() {
        assertEquals("a%20b%26c|a b&c|aGk=|hi", run("var e = encodeURIComponent('a b&c'); var r = [e, decodeURIComponent(e), btoa('hi'), atob('aGk=')].join('|');"))
        assertEquals("example.com|/p|2", run("var u = new URL('/p?x=1&y=2', 'https://example.com/a'); var r = u.hostname + '|' + u.pathname + '|' + u.searchParams.get('y');"))
    }

    @Test fun labeledBlocksAndJsOnlyRegexSyntax() {
        assertEquals("1", run("var r = 0; out: { r = 1; break out; r = 2; }"))
        assertEquals("true|true|true", run("var r = [/[\\0-\\x1f]/.test('\\x01'), /[[]/.test('['), /a{/.test('a{')].join('|');"))
    }
}
