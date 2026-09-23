package com.projectfuture.browser.browser

import com.projectfuture.browser.css.CssParser
import com.projectfuture.browser.html.ElementNode
import com.projectfuture.browser.html.HtmlParser
import com.projectfuture.browser.html.Node
import com.projectfuture.browser.html.TextNode
import com.projectfuture.browser.html.walkElements
import com.projectfuture.browser.js.Environment
import com.projectfuture.browser.js.Interpreter
import com.projectfuture.browser.js.JsArray
import com.projectfuture.browser.js.JsBoolean
import com.projectfuture.browser.js.JsFunction
import com.projectfuture.browser.js.JsNull
import com.projectfuture.browser.js.JsObject
import com.projectfuture.browser.js.JsString
import com.projectfuture.browser.js.JsUndefined
import com.projectfuture.browser.js.JsValue
import com.projectfuture.browser.js.Lexer
import com.projectfuture.browser.js.NativeFunction
import com.projectfuture.browser.js.Parser
import com.projectfuture.browser.js.toJsString

/**
 * Bridges the JS interpreter to this engine's own DOM (ElementNode tree).
 * Deliberately narrow, documented here rather than pretending otherwise:
 * - Click dispatch ([dispatchClick]) is real: Tab calls it when
 *   BrowserView hit-tests a tap to a source element, and it bubbles
 *   through `addEventListener('click', ...)` listeners AND inline
 *   `onclick="..."` attributes up the ancestor chain (real DOM bubbling,
 *   with no way to `stopPropagation()` since that isn't modeled). No
 *   other event types are dispatched from real input yet (no keyboard,
 *   no `input`/`change`/`submit`), and `DOMContentLoaded` is the only
 *   thing that fires on its own, synchronously right after all
 *   `<script>` tags finish running on page load.
 * - `fetch`/`setTimeout`/`setInterval` are real but live in Tab
 *   (installBrowserRuntime), not here, since they need Tab's background
 *   executor/main-thread Handler - see that method's doc. No
 *   `XMLHttpRequest`. `window.alert` just logs; there's no dialog
 *   plumbing yet.
 * - `innerHTML` read isn't implemented (no HTML serializer); innerHTML
 *   write re-parses the assigned string via the existing HtmlParser and
 *   replaces the element's children.
 * - `children`/`querySelectorAll` return a plain snapshot array, not a
 *   live NodeList that updates as the DOM changes later.
 */
class DomBridge(private val root: ElementNode) {
    private val wrappers = HashMap<ElementNode, DomElement>()
    private val domContentLoadedListeners = ArrayList<JsFunction>()

    fun wrap(node: ElementNode): DomElement = wrappers.getOrPut(node) { DomElement(node, this) }

    /**
     * Bubbles a click from [startNode] up through every ancestor
     * (inclusive), running each one's inline `onclick="..."` attribute (if
     * any) and any `addEventListener('click', ...)` listeners. Returns
     * true if anything actually ran, so the caller knows whether a
     * re-style/re-layout is worth doing.
     */
    fun dispatchClick(startNode: ElementNode, interpreter: Interpreter): Boolean {
        var current: ElementNode? = startNode
        var handled = false
        while (current != null) {
            val onclickAttr = current.attr("onclick")
            if (!onclickAttr.isNullOrBlank()) {
                try {
                    interpreter.run(Parser(Lexer(onclickAttr).tokenize()).parseProgram())
                    handled = true
                } catch (_: Exception) {
                    // A broken inline handler shouldn't block bubbling to ancestors.
                }
            }
            if (wrappers[current]?.dispatchEvent("click", interpreter) == true) handled = true
            current = current.parent
        }
        return handled
    }

    fun install(env: Environment) {
        val document = JsObject()
        document.set("documentElement", wrap(root))
        document.set("body", findFirst(root, "body")?.let { wrap(it) } ?: JsNull)
        document.set("getElementById", NativeFunction("getElementById", 1) { _, _, args ->
            val id = toJsString(args.getOrElse(0) { JsUndefined })
            var found: ElementNode? = null
            root.walkElements { if (found == null && it.attr("id") == id) found = it }
            found?.let { wrap(it) } ?: JsNull
        })
        document.set("querySelector", NativeFunction("querySelector", 1) { _, _, args ->
            querySelectorAll(toJsString(args.getOrElse(0) { JsUndefined })).firstOrNull()?.let { wrap(it) } ?: JsNull
        })
        document.set("querySelectorAll", NativeFunction("querySelectorAll", 1) { _, _, args ->
            JsArray(querySelectorAll(toJsString(args.getOrElse(0) { JsUndefined })).map { wrap(it) as JsValue }.toMutableList())
        })
        document.set("createElement", NativeFunction("createElement", 1) { _, _, args ->
            wrap(ElementNode(toJsString(args.getOrElse(0) { JsString("div") }).lowercase()))
        })
        document.set("addEventListener", NativeFunction("addEventListener", 2) { _, _, args ->
            val type = toJsString(args.getOrElse(0) { JsUndefined })
            val fn = args.getOrNull(1) as? JsFunction
            if (type == "DOMContentLoaded" && fn != null) domContentLoadedListeners.add(fn)
            JsUndefined
        })
        env.declare("document", document)

        val window = JsObject()
        window.set("document", document)
        window.set("alert", NativeFunction("alert", 1) { _, _, args ->
            println("[alert] " + toJsString(args.getOrElse(0) { JsUndefined }))
            JsUndefined
        })
        env.declare("window", window)
    }

    fun fireDomContentLoaded(interpreter: Interpreter) {
        for (fn in domContentLoadedListeners) fn.call(interpreter, JsUndefined, emptyList())
    }

    private fun findFirst(node: ElementNode, tag: String): ElementNode? {
        var found: ElementNode? = null
        node.walkElements { if (found == null && it.tag == tag) found = it }
        return found
    }

    private fun querySelectorAll(selectorText: String): List<ElementNode> {
        val selector = CssParser("").parseSingleSelector(selectorText) ?: return emptyList()
        val results = ArrayList<ElementNode>()
        root.walkElements { if (selector.matches(it)) results.add(it) }
        return results
    }
}

class DomElement(val node: ElementNode, private val bridge: DomBridge) : JsObject() {
    private val listeners = HashMap<String, MutableList<JsFunction>>()

    /** Invokes every listener registered for [type] (see DomBridge.dispatchClick), `this`-bound to this element. */
    fun dispatchEvent(type: String, interpreter: Interpreter): Boolean {
        val fns = listeners[type] ?: return false
        for (fn in fns.toList()) fn.call(interpreter, this, emptyList())
        return fns.isNotEmpty()
    }

    override fun get(name: String): JsValue = when (name) {
        "tagName" -> JsString(node.tag.uppercase())
        "id" -> JsString(node.attr("id") ?: "")
        "className" -> JsString(node.attr("class") ?: "")
        "textContent" -> JsString(collectText(node))
        "innerHTML" -> JsString("") // read not implemented - see class doc
        "style" -> DomStyle(node)
        "children" -> JsArray(node.children.filterIsInstance<ElementNode>().map { bridge.wrap(it) as JsValue }.toMutableList())
        "parentNode", "parentElement" -> node.parent?.let { bridge.wrap(it) } ?: JsNull
        "classList" -> makeClassList(node)
        "setAttribute" -> NativeFunction("setAttribute", 2) { _, _, args ->
            node.attributes[toJsString(args.getOrElse(0) { JsUndefined })] = toJsString(args.getOrElse(1) { JsUndefined })
            JsUndefined
        }
        "getAttribute" -> NativeFunction("getAttribute", 1) { _, _, args ->
            node.attr(toJsString(args.getOrElse(0) { JsUndefined }))?.let { JsString(it) } ?: JsNull
        }
        "removeAttribute" -> NativeFunction("removeAttribute", 1) { _, _, args ->
            node.attributes.remove(toJsString(args.getOrElse(0) { JsUndefined }))
            JsUndefined
        }
        "appendChild" -> NativeFunction("appendChild", 1) { _, _, args ->
            val child = args.getOrNull(0) as? DomElement
            if (child != null) {
                child.node.parent = node
                node.children.add(child.node)
            }
            args.getOrElse(0) { JsUndefined }
        }
        "remove" -> NativeFunction("remove", 0) { _, _, _ ->
            node.parent?.children?.remove(node)
            JsUndefined
        }
        "addEventListener" -> NativeFunction("addEventListener", 2) { _, _, args ->
            val type = toJsString(args.getOrElse(0) { JsUndefined })
            (args.getOrNull(1) as? JsFunction)?.let { listeners.getOrPut(type) { ArrayList() }.add(it) }
            JsUndefined
        }
        else -> super.get(name)
    }

    override fun set(name: String, value: JsValue) {
        when (name) {
            "textContent" -> {
                node.children.clear()
                node.children.add(TextNode(toJsString(value), node))
            }
            "id" -> node.attributes["id"] = toJsString(value)
            "className" -> node.attributes["class"] = toJsString(value)
            "innerHTML" -> setInnerHtml(toJsString(value))
            else -> super.set(name, value)
        }
    }

    private fun setInnerHtml(html: String) {
        node.children.clear()
        // Parse as a full document and pull the fragment back out of the implicit html>body>div wrapper.
        val doc = HtmlParser("<div>$html</div>").parse()
        val body = doc.children.filterIsInstance<ElementNode>().firstOrNull { it.tag == "body" }
        val wrapper = body?.children?.filterIsInstance<ElementNode>()?.firstOrNull { it.tag == "div" }
        wrapper?.children?.forEach { child ->
            if (child is ElementNode) child.parent = node
            if (child is TextNode) child.parent = node
            node.children.add(child)
        }
    }

    private fun collectText(n: Node): String = when (n) {
        is TextNode -> n.text
        is ElementNode -> n.children.joinToString("") { collectText(it) }
    }

    private fun makeClassList(node: ElementNode): JsObject {
        val obj = JsObject()
        fun classes(): MutableList<String> = (node.attr("class") ?: "").split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        fun save(list: List<String>) { node.attributes["class"] = list.joinToString(" ") }
        obj.set("add", NativeFunction("add", 1) { _, _, args ->
            val c = classes()
            val name = toJsString(args.getOrElse(0) { JsUndefined })
            if (name !in c) c.add(name)
            save(c)
            JsUndefined
        })
        obj.set("remove", NativeFunction("remove", 1) { _, _, args ->
            val c = classes()
            c.remove(toJsString(args.getOrElse(0) { JsUndefined }))
            save(c)
            JsUndefined
        })
        obj.set("toggle", NativeFunction("toggle", 1) { _, _, args ->
            val c = classes()
            val name = toJsString(args.getOrElse(0) { JsUndefined })
            if (!c.remove(name)) c.add(name)
            save(c)
            JsBoolean(name in c)
        })
        obj.set("contains", NativeFunction("contains", 1) { _, _, args ->
            JsBoolean(toJsString(args.getOrElse(0) { JsUndefined }) in classes())
        })
        return obj
    }
}

/** `element.style.property = value` writes into the inline `style="..."` attribute, so it survives the next cascade re-run. */
class DomStyle(private val node: ElementNode) : JsObject() {
    override fun get(name: String): JsValue = currentDeclarations()[cssPropertyName(name)]?.let { JsString(it) } ?: JsString("")

    override fun set(name: String, value: JsValue) {
        val decls = currentDeclarations().toMutableMap()
        decls[cssPropertyName(name)] = toJsString(value)
        node.attributes["style"] = decls.entries.joinToString("; ") { (k, v) -> "$k: $v" }
    }

    private fun currentDeclarations(): Map<String, String> = CssParser.parseInlineDeclarations(node.attr("style") ?: "")

    /** JS uses camelCase (backgroundColor); CSS uses kebab-case (background-color). */
    private fun cssPropertyName(js: String): String = js.replace(Regex("[A-Z]")) { "-" + it.value.lowercase() }
}
