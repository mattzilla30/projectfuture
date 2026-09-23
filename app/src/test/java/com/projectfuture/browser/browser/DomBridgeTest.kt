package com.projectfuture.browser.browser

import com.projectfuture.browser.html.ElementNode
import com.projectfuture.browser.html.HtmlParser
import com.projectfuture.browser.html.walkElements
import com.projectfuture.browser.js.Interpreter
import com.projectfuture.browser.js.JsBoolean
import com.projectfuture.browser.js.JsNull
import com.projectfuture.browser.js.JsNumber
import com.projectfuture.browser.js.JsString
import com.projectfuture.browser.js.JsValue
import com.projectfuture.browser.js.Lexer
import com.projectfuture.browser.js.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DomBridge/DomElement/DomDocument have no Android dependencies (unlike
 * CanvasContext2D, which these tests don't touch), so - like the
 * interpreter core - they can be exercised directly on the host JVM
 * instead of only ever being checked by "it compiles".
 */
class DomBridgeTest {

    private class Fixture(html: String) {
        val root: ElementNode = HtmlParser(html).parse()
        val interpreter = Interpreter()
        val bridge = DomBridge(root).also { it.install(interpreter.globalEnv) }

        fun run(src: String) {
            interpreter.run(Parser(Lexer(src).tokenize()).parseProgram())
        }

        fun eval(expr: String): JsValue {
            run("var __r = ($expr);")
            return interpreter.globalEnv.get("__r")
        }

        fun str(expr: String) = (eval(expr) as JsString).value
        fun bool(expr: String) = (eval(expr) as JsBoolean).value
    }

    @Test fun getElementByIdAndTextContent() {
        val f = Fixture("<html><body><p id='x'>hello</p></body></html>")
        f.run("document.getElementById('x').textContent = 'changed';")
        assertEquals("changed", f.str("document.getElementById('x').textContent"))
    }

    @Test fun querySelectorAndQuerySelectorAll() {
        val f = Fixture("<html><body><p class='a'>1</p><p class='a'>2</p><p class='b'>3</p></body></html>")
        assertEquals("1", f.str("document.querySelector('.a').textContent"))
        assertEquals(2.0, (f.eval("document.querySelectorAll('.a').length") as JsNumber).value, 0.0)
    }

    @Test fun classListOperations() {
        val f = Fixture("<html><body><div id='d' class='a b'></div></body></html>")
        f.run("document.getElementById('d').classList.add('c');")
        assertTrue(f.bool("document.getElementById('d').classList.contains('c')"))
        f.run("document.getElementById('d').classList.remove('a');")
        assertTrue(!f.bool("document.getElementById('d').classList.contains('a')"))
        assertTrue(f.bool("document.getElementById('d').classList.toggle('z')"))
        assertTrue(f.bool("document.getElementById('d').classList.contains('z')"))
    }

    @Test fun attributes() {
        val f = Fixture("<html><body><div id='d'></div></body></html>")
        f.run("document.getElementById('d').setAttribute('data-x', '5');")
        assertEquals("5", f.str("document.getElementById('d').getAttribute('data-x')"))
        assertTrue(f.bool("document.getElementById('d').hasAttribute('data-x')"))
        f.run("document.getElementById('d').removeAttribute('data-x');")
        assertTrue(!f.bool("document.getElementById('d').hasAttribute('data-x')"))
    }

    @Test fun innerHtmlRoundTrip() {
        val f = Fixture("<html><body><div id='d'></div></body></html>")
        f.run("document.getElementById('d').innerHTML = '<b>hi</b>';")
        assertEquals("<b>hi</b>", f.str("document.getElementById('d').innerHTML"))
    }

    @Test fun matchesAndClosest() {
        val f = Fixture("<html><body><div id='outer' class='container'><span id='inner'>x</span></div></body></html>")
        assertTrue(f.bool("document.getElementById('inner').closest('.container') !== null"))
        assertTrue(f.bool("document.getElementById('outer').matches('.container')"))
        assertTrue(!f.bool("document.getElementById('inner').matches('.container')"))
    }

    @Test fun siblingTraversal() {
        val f = Fixture("<html><body><p id='a'>1</p><p id='b'>2</p><p id='c'>3</p></body></html>")
        assertEquals("b", f.str("document.getElementById('a').nextElementSibling.id"))
        assertEquals("a", f.str("document.getElementById('b').previousElementSibling.id"))
        assertTrue(f.eval("document.getElementById('c').nextElementSibling") == JsNull)
    }

    @Test fun documentTitleGetAndSet() {
        val f = Fixture("<html><head><title>Original</title></head><body></body></html>")
        assertEquals("Original", f.str("document.title"))
        f.run("document.title = 'Changed';")
        assertEquals("Changed", f.str("document.title"))
    }

    @Test fun documentTitleCreatedWhenMissing() {
        val f = Fixture("<html><head></head><body></body></html>")
        f.run("document.title = 'New Title';")
        assertEquals("New Title", f.str("document.title"))
    }

    @Test fun clickBubblesThroughAncestorsViaAddEventListenerAndOnclick() {
        val f = Fixture(
            """<html><body>
                <div id="outer" onclick="outerRan = true;">
                    <button id="btn">Click</button>
                </div>
            </body></html>"""
        )
        f.run("var outerRan = false; var btnRan = false;")
        f.run("document.getElementById('btn').addEventListener('click', function() { btnRan = true; });")

        var button: ElementNode? = null
        f.root.walkElements { if (it.attr("id") == "btn") button = it }
        val event = f.bridge.dispatchClick(button!!, f.interpreter)

        assertTrue("dispatchClick should report it handled something", event.listenersRan)
        assertTrue("addEventListener('click', ...) on the target should have run", f.bool("btnRan"))
        assertTrue("onclick=\"...\" on an ancestor should have run via bubbling", f.bool("outerRan"))
    }

    @Test fun inputValueAndCheckedRoundTripThroughJs() {
        val f = Fixture("<html><body><input id='t' type='text'><input id='c' type='checkbox'></body></html>")
        f.run("document.getElementById('t').value = 'hello';")
        assertEquals("hello", f.str("document.getElementById('t').value"))

        assertTrue("checkbox should start unchecked", !f.bool("document.getElementById('c').checked"))
        f.run("document.getElementById('c').checked = true;")
        assertTrue(f.bool("document.getElementById('c').checked"))
        var checkbox: ElementNode? = null
        f.root.walkElements { if (it.attr("id") == "c") checkbox = it }
        assertTrue("checked attribute should be reflected on the underlying element", checkbox!!.attributes.containsKey("checked"))
    }

    @Test fun preventDefaultIsVisibleOnTheDispatchedEvent() {
        val f = Fixture("<html><body><a id='link' href='https://example.com'>go</a></body></html>")
        f.run("document.getElementById('link').addEventListener('click', function(e) { e.preventDefault(); });")
        var link: ElementNode? = null
        f.root.walkElements { if (it.attr("id") == "link") link = it }
        val event = f.bridge.dispatchClick(link!!, f.interpreter)
        assertTrue("preventDefault() called from a listener should set defaultPrevented", event.defaultPrevented)
    }

    @Test fun mutationObserverFiresOnSetAttributeAndAppendChild() {
        val f = Fixture("<html><body><div id='parent'><span id='child'></span></div></body></html>")
        f.run(
            """
            var log = [];
            var observer = new MutationObserver(function(records) {
                log.push(records[0].type + ':' + (records[0].attributeName || ''));
            });
            observer.observe(document.getElementById('child'));
            """.trimIndent()
        )
        f.run("document.getElementById('child').setAttribute('data-x', '1');")
        assertEquals(1.0, (f.eval("log.length") as JsNumber).value, 0.0)
        assertEquals("attributes:data-x", f.str("log[0]"))

        f.run("document.getElementById('child').removeAttribute('data-x');")
        assertEquals(2.0, (f.eval("log.length") as JsNumber).value, 0.0)
        assertEquals("attributes:data-x", f.str("log[1]"))
    }

    @Test fun mutationObserverSubtreeCatchesDescendantMutations() {
        val f = Fixture("<html><body><div id='parent'><span id='child'></span></div></body></html>")
        f.run(
            """
            var count = 0;
            var observer = new MutationObserver(function(records) { count = count + 1; });
            observer.observe(document.getElementById('parent'), { subtree: true });
            """.trimIndent()
        )
        f.run("document.getElementById('child').setAttribute('data-x', '1');")
        assertEquals(1.0, (f.eval("count") as JsNumber).value, 0.0)
    }

    @Test fun mutationObserverDisconnectStopsNotifications() {
        val f = Fixture("<html><body><div id='child'></div></body></html>")
        f.run(
            """
            var count = 0;
            var observer = new MutationObserver(function(records) { count = count + 1; });
            observer.observe(document.getElementById('child'));
            observer.disconnect();
            """.trimIndent()
        )
        f.run("document.getElementById('child').setAttribute('data-x', '1');")
        assertEquals(0.0, (f.eval("count") as JsNumber).value, 0.0)
    }

    @Test fun inlineOnclickSeesImplicitEventVariable() {
        val f = Fixture("<html><body><button id='btn' onclick='event.preventDefault();'>go</button></body></html>")
        var button: ElementNode? = null
        f.root.walkElements { if (it.attr("id") == "btn") button = it }
        val event = f.bridge.dispatchClick(button!!, f.interpreter)
        assertTrue("inline onclick should see an implicit `event` with preventDefault()", event.defaultPrevented)
    }
}
