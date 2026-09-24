package com.projectfuture.browser.browser

import android.graphics.Bitmap
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
import com.projectfuture.browser.js.JsEvent
import com.projectfuture.browser.js.JsFunction
import com.projectfuture.browser.js.JsNull
import com.projectfuture.browser.js.JsObject
import com.projectfuture.browser.js.JsString
import com.projectfuture.browser.js.JsUndefined
import com.projectfuture.browser.js.JsValue
import com.projectfuture.browser.js.Lexer
import com.projectfuture.browser.js.isTruthy
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
 * - `children`/`querySelectorAll` return a plain snapshot array, not a
 *   live NodeList that updates as the DOM changes later.
 * - `document.title` reads/writes the actual `<title>` element (creating
 *   one under `<head>` on first write if none exists yet).
 */
class DomBridge(private val root: ElementNode) {
    private val wrappers = HashMap<ElementNode, DomElement>()
    private val domContentLoadedListeners = ArrayList<JsFunction>()
    private val canvasContexts = HashMap<ElementNode, CanvasContext2D>()
    private val windowListeners = HashMap<String, MutableList<JsFunction>>()
    private val mutationObservers = ArrayList<ObserverRegistration>()

    private class ObserverRegistration(val callback: JsFunction, val target: ElementNode, val subtree: Boolean)

    fun wrap(node: ElementNode): DomElement = wrappers.getOrPut(node) { DomElement(node, this) }

    /** `<canvas>` uses width/height HTML attributes for its bitmap resolution (default 300x150 per spec), not CSS. */
    fun getOrCreateCanvasContext(node: ElementNode): CanvasContext2D = canvasContexts.getOrPut(node) {
        val w = (node.attr("width")?.toIntOrNull() ?: 300).coerceAtLeast(1)
        val h = (node.attr("height")?.toIntOrNull() ?: 150).coerceAtLeast(1)
        CanvasContext2D(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888))
    }

    /** For Tab to merge into its image map, since canvases render via the same inline-image path as `<img>`/`<svg>`. */
    fun canvasBitmaps(): Map<ElementNode, Bitmap> = canvasContexts.mapValues { it.value.bitmap }

    /**
     * Bubbles an event of [type] from [startNode] up through every ancestor
     * (inclusive), running each one's inline `on<type>="..."` attribute (if
     * any, with an implicit `event` variable in scope - a sloppy-mode-JS-
     * style simplification, not a real closure over the handler's own
     * scope) and any `addEventListener(type, ...)` listeners, `this`-bound
     * to that ancestor. There's no `stopPropagation()` - once dispatched,
     * an event always reaches the root. Returns the constructed
     * [JsEvent] so the caller can check `defaultPrevented` (e.g. to decide
     * whether a link navigation or form submission should proceed) and
     * whether anything ran at all (worth a re-style/re-layout).
     */
    fun dispatchClick(startNode: ElementNode, interpreter: Interpreter): JsEvent =
        dispatchEvent(startNode, "click", interpreter)

    fun dispatchEvent(startNode: ElementNode, type: String, interpreter: Interpreter): JsEvent {
        val event = JsEvent(type, wrap(startNode))
        interpreter.globalEnv.declare("event", event)
        var current: ElementNode? = startNode
        while (current != null) {
            val onAttr = current.attr("on$type")
            if (!onAttr.isNullOrBlank()) {
                try {
                    interpreter.run(Parser(Lexer(onAttr).tokenize()).parseProgram())
                    event.listenersRan = true
                } catch (_: Exception) {
                    // A broken inline handler shouldn't block bubbling to ancestors.
                }
            }
            if (wrappers[current]?.runListeners(type, interpreter, event) == true) event.listenersRan = true
            current = current.parent
        }
        return event
    }

    fun install(env: Environment) {
        installMutationObserver(env)
        val document = DomDocument(root)
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
        window.set("addEventListener", NativeFunction("addEventListener", 2) { _, _, args ->
            val type = toJsString(args.getOrElse(0) { JsUndefined })
            (args.getOrNull(1) as? JsFunction)?.let { windowListeners.getOrPut(type) { ArrayList() }.add(it) }
            JsUndefined
        })
        env.declare("window", window)
    }

    /** Fires a `window`-level event (currently just `popstate`, from Tab's pushState/back-forward handling) with no bubbling concept - there's only one window. */
    fun dispatchWindowEvent(type: String, interpreter: Interpreter, event: JsEvent) {
        for (fn in (windowListeners[type] ?: emptyList()).toList()) fn.call(interpreter, JsUndefined, listOf(event))
    }

    fun fireDomContentLoaded(interpreter: Interpreter) {
        for (fn in domContentLoadedListeners) fn.call(interpreter, JsUndefined, emptyList())
    }

    /**
     * `new MutationObserver(callback)` / `.observe(target, options)` /
     * `.disconnect()`. A real, working subset: every mutation is reported
     * the instant it happens, as a single-record callback call, rather
     * than the spec's microtask-batched multi-record delivery - consistent
     * with this interpreter's Promises also resolving synchronously rather
     * than through a real microtask queue. `options` is accepted but not
     * used to filter mutation types (any observed target reports
     * attribute/childList/characterData changes alike) - only
     * `subtree` is honored. Only the method-style mutations
     * (`setAttribute`/`removeAttribute`/`appendChild`/`remove`) notify -
     * those run as NativeFunction calls, which receive an Interpreter to
     * invoke the observer callback with. Property-style mutations
     * (`el.textContent = x`, `el.innerHTML = x`, `el.className = x`,
     * `classList.add(...)`) go through `DomElement.set()`, a plain
     * property setter with no Interpreter parameter to call an observer
     * callback with at all - a real, accepted gap rather than
     * restructuring JsObject's base `set()` signature project-wide for
     * this one caller's benefit.
     */
    fun installMutationObserver(env: Environment) {
        val ctor = NativeFunction("MutationObserver", 1) { _, thisArg, args ->
            val callback = args.getOrNull(0) as? JsFunction
            val obj = thisArg as? JsObject ?: JsObject()
            obj.set("observe", NativeFunction("observe", 2) { _, _, observeArgs ->
                val target = (observeArgs.getOrNull(0) as? DomElement)?.node
                val options = observeArgs.getOrNull(1) as? JsObject
                if (target != null && callback != null) {
                    val subtree = options?.get("subtree")?.let { isTruthy(it) } ?: false
                    mutationObservers.add(ObserverRegistration(callback, target, subtree))
                }
                JsUndefined
            })
            obj.set("disconnect", NativeFunction("disconnect", 0) { _, _, _ ->
                mutationObservers.removeAll { it.callback === callback }
                JsUndefined
            })
            obj
        }
        env.declare("MutationObserver", ctor)
    }

    private fun isDescendantOf(node: ElementNode, ancestor: ElementNode): Boolean {
        var current: ElementNode? = node.parent
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent
        }
        return false
    }

    fun notifyMutation(interpreter: Interpreter, target: ElementNode, type: String, attributeName: String? = null) {
        if (mutationObservers.isEmpty()) return
        for (reg in mutationObservers.toList()) {
            val matches = reg.target === target || (reg.subtree && isDescendantOf(target, reg.target))
            if (!matches) continue
            val record = JsObject()
            record.set("type", JsString(type))
            record.set("target", wrap(target))
            record.set("attributeName", attributeName?.let { JsString(it) } ?: JsNull)
            try {
                reg.callback.call(interpreter, JsUndefined, listOf(JsArray(mutableListOf(record))))
            } catch (_: Exception) {
                // A broken observer callback shouldn't block the mutation that triggered it.
            }
        }
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

    /** Invokes every listener registered for [type] (see DomBridge.dispatchEvent), `this`-bound to this element. */
    fun runListeners(type: String, interpreter: Interpreter, event: JsEvent): Boolean {
        val fns = listeners[type] ?: return false
        for (fn in fns.toList()) fn.call(interpreter, this, listOf(event))
        return fns.isNotEmpty()
    }

    /** True if this element (or an ancestor of the same tag) has any listener/attribute wired for [type]. */
    fun hasListenerFor(type: String): Boolean = listeners[type]?.isNotEmpty() == true || !node.attr("on$type").isNullOrBlank()

    override fun get(name: String): JsValue = when (name) {
        "tagName" -> JsString(node.tag.uppercase())
        "id" -> JsString(node.attr("id") ?: "")
        "className" -> JsString(node.attr("class") ?: "")
        "textContent" -> JsString(collectText(node))
        "innerHTML" -> JsString(node.children.joinToString("") { serializeHtml(it) })
        "style" -> DomStyle(node)
        "children" -> JsArray(node.children.filterIsInstance<ElementNode>().map { bridge.wrap(it) as JsValue }.toMutableList())
        "parentNode", "parentElement" -> node.parent?.let { bridge.wrap(it) } ?: JsNull
        "nextElementSibling" -> adjacentElementSibling(1)
        "previousElementSibling" -> adjacentElementSibling(-1)
        "classList" -> makeClassList(node)
        "hasAttribute" -> NativeFunction("hasAttribute", 1) { _, _, args ->
            JsBoolean(node.attr(toJsString(args.getOrElse(0) { JsUndefined })) != null)
        }
        "matches" -> NativeFunction("matches", 1) { _, _, args ->
            val selector = CssParser("").parseSingleSelector(toJsString(args.getOrElse(0) { JsUndefined }))
            JsBoolean(selector?.matches(node) ?: false)
        }
        "closest" -> NativeFunction("closest", 1) { _, _, args ->
            val selector = CssParser("").parseSingleSelector(toJsString(args.getOrElse(0) { JsUndefined }))
            var current: ElementNode? = node
            var result: JsValue = JsNull
            while (current != null && selector != null) {
                if (selector.matches(current)) { result = bridge.wrap(current); break }
                current = current.parent
            }
            result
        }
        "setAttribute" -> NativeFunction("setAttribute", 2) { interp, _, args ->
            val attrName = toJsString(args.getOrElse(0) { JsUndefined })
            node.attributes[attrName] = toJsString(args.getOrElse(1) { JsUndefined })
            bridge.notifyMutation(interp, node, "attributes", attrName)
            JsUndefined
        }
        "getAttribute" -> NativeFunction("getAttribute", 1) { _, _, args ->
            node.attr(toJsString(args.getOrElse(0) { JsUndefined }))?.let { JsString(it) } ?: JsNull
        }
        "removeAttribute" -> NativeFunction("removeAttribute", 1) { interp, _, args ->
            val attrName = toJsString(args.getOrElse(0) { JsUndefined })
            node.attributes.remove(attrName)
            bridge.notifyMutation(interp, node, "attributes", attrName)
            JsUndefined
        }
        "appendChild" -> NativeFunction("appendChild", 1) { interp, _, args ->
            val child = args.getOrNull(0) as? DomElement
            // Guard the two cases real DOM rejects outright: appending a
            // node to itself, or to one of its own descendants (which would
            // make the appended node both an ancestor and a child of `node`
            // - a cycle that would hang any tree walk).
            if (child != null && child.node !== node && !isAncestor(child.node, node)) {
                val oldParent = child.node.parent
                if (oldParent != null && oldParent !== node) {
                    // appendChild MOVES a node that's already attached elsewhere -
                    // it must not be left listed as a child of its old parent too.
                    oldParent.children.remove(child.node)
                    bridge.notifyMutation(interp, oldParent, "childList")
                } else if (oldParent === node) {
                    node.children.remove(child.node)
                }
                child.node.parent = node
                node.children.add(child.node)
                bridge.notifyMutation(interp, node, "childList")
            }
            args.getOrElse(0) { JsUndefined }
        }
        "remove" -> NativeFunction("remove", 0) { interp, _, _ ->
            val parent = node.parent
            parent?.children?.remove(node)
            node.parent = null
            if (parent != null) bridge.notifyMutation(interp, parent, "childList")
            JsUndefined
        }
        "addEventListener" -> NativeFunction("addEventListener", 2) { _, _, args ->
            val type = toJsString(args.getOrElse(0) { JsUndefined })
            (args.getOrNull(1) as? JsFunction)?.let { listeners.getOrPut(type) { ArrayList() }.add(it) }
            JsUndefined
        }
        "getContext" -> NativeFunction("getContext", 1) { _, _, args ->
            if (toJsString(args.getOrElse(0) { JsUndefined }) == "2d") bridge.getOrCreateCanvasContext(node) else JsNull
        }
        "value" -> JsString(if (node.tag == "textarea") collectText(node) else (node.attr("value") ?: ""))
        "checked" -> JsBoolean(node.attributes.containsKey("checked"))
        "type" -> JsString(node.attr("type") ?: if (node.tag == "textarea") "textarea" else "text")
        "name" -> JsString(node.attr("name") ?: "")
        "disabled" -> JsBoolean(node.attributes.containsKey("disabled"))
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
            "value" -> if (node.tag == "textarea") {
                node.children.clear()
                node.children.add(TextNode(toJsString(value), node))
            } else {
                node.attributes["value"] = toJsString(value)
            }
            "checked" -> if (isTruthy(value)) node.attributes["checked"] = "checked" else node.attributes.remove("checked")
            "disabled" -> if (isTruthy(value)) node.attributes["disabled"] = "disabled" else node.attributes.remove("disabled")
            else -> super.set(name, value)
        }
    }

    private fun setInnerHtml(html: String) {
        // Detach the old children properly - they may still be reachable
        // from JS (a variable holding a node fetched before this reset), and
        // must report a null parentNode/parentElement once they're gone
        // rather than keep pointing at an element they're no longer inside.
        for (old in node.children) old.parent = null
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

    private fun adjacentElementSibling(step: Int): JsValue {
        val siblings = node.parent?.children ?: return JsNull
        var i = siblings.indexOf(node) + step
        while (i in siblings.indices) {
            (siblings[i] as? ElementNode)?.let { return bridge.wrap(it) }
            i += step
        }
        return JsNull
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

/** `document`: mostly a flat property bag (see DomBridge.install), except `title`, which reads/writes the live `<title>` element. */
class DomDocument(private val root: ElementNode) : JsObject() {
    override fun get(name: String): JsValue = when (name) {
        "title" -> JsString(findTitle(root)?.let { collectTextStatic(it) } ?: "")
        else -> super.get(name)
    }

    override fun set(name: String, value: JsValue) {
        if (name == "title") {
            val text = toJsString(value)
            val existing = findTitle(root)
            if (existing != null) {
                existing.children.clear()
                existing.children.add(TextNode(text, existing))
            } else {
                val head = root.children.filterIsInstance<ElementNode>().firstOrNull { it.tag == "head" } ?: return
                val newTitle = ElementNode("title", parent = head)
                newTitle.children.add(TextNode(text, newTitle))
                head.children.add(newTitle)
            }
        } else {
            super.set(name, value)
        }
    }

    private fun findTitle(root: ElementNode): ElementNode? {
        var found: ElementNode? = null
        root.walkElements { if (found == null && it.tag == "title") found = it }
        return found
    }

    private fun collectTextStatic(n: Node): String = when (n) {
        is TextNode -> n.text
        is ElementNode -> n.children.joinToString("") { collectTextStatic(it) }
    }
}

/** True if [maybeAncestor] is an ancestor of [node] (walking up `node.parent`). Used to reject cycle-forming appendChild calls. */
private fun isAncestor(maybeAncestor: ElementNode, node: ElementNode): Boolean {
    var current: ElementNode? = node.parent
    while (current != null) {
        if (current === maybeAncestor) return true
        current = current.parent
    }
    return false
}

private val VOID_HTML_TAGS = setOf(
    "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr"
)

/** Serializes a DOM subtree back to markup, backing `innerHTML` reads. */
fun serializeHtml(node: Node): String = when (node) {
    is TextNode -> escapeHtmlText(node.text)
    is ElementNode -> {
        val attrs = node.attributes.entries.joinToString("") { (k, v) -> " $k=\"${escapeHtmlAttr(v)}\"" }
        if (node.tag in VOID_HTML_TAGS) {
            "<${node.tag}$attrs>"
        } else {
            "<${node.tag}$attrs>" + node.children.joinToString("") { serializeHtml(it) } + "</${node.tag}>"
        }
    }
}

private fun escapeHtmlText(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
private fun escapeHtmlAttr(s: String) = escapeHtmlText(s).replace("\"", "&quot;")
