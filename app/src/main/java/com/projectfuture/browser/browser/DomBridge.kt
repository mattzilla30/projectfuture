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
import com.projectfuture.browser.js.JsNumber
import com.projectfuture.browser.js.JsPromise
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
    private val textWrappers = HashMap<TextNode, DomText>()
    private val canvasContexts = HashMap<ElementNode, CanvasContext2D>()
    private val mutationObservers = ArrayList<ObserverRegistration>()
    val windowListeners = ListenerSet()
    val documentListeners = ListenerSet()

    // Prototype objects behind `el instanceof HTMLElement` and anything a page adds to `HTMLElement.prototype`.
    val nodeProto = JsObject()
    val elementProto = JsObject().also { it.proto = nodeProto }
    val htmlElementProto = JsObject().also { it.proto = elementProto }
    val textProto = JsObject().also { it.proto = nodeProto }
    val documentProto = JsObject().also { it.proto = nodeProto }

    var documentObject: DomDocument? = null
        private set
    var windowObject: JsObject? = null
        private set

    /** `document.readyState`: "loading" while page scripts run, then "interactive", then "complete". */
    var readyState = "loading"

    /** The `<script>` element currently running, for `document.currentScript`. Set by Tab. */
    var currentScript: ElementNode? = null

    /** Backs `document.cookie`; Tab wires these to its cookie jar. */
    var cookieReader: () -> String = { "" }
    var cookieWriter: (String) -> Unit = {}

    /** Callbacks deferred until page scripts finish (IntersectionObserver's first report, `load` handlers). */
    val pendingCallbacks = ArrayList<(Interpreter) -> Unit>()

    private class ObserverRegistration(val callback: JsFunction, val target: ElementNode, val subtree: Boolean)

    fun wrap(node: ElementNode): DomElement = wrappers.getOrPut(node) { DomElement(node, this).also { it.proto = htmlElementProto } }

    fun wrapNode(node: Node): JsValue = when (node) {
        is ElementNode -> wrap(node)
        is TextNode -> textWrappers.getOrPut(node) { DomText(node, this).also { it.proto = textProto } }
    }

    fun siblingOf(node: Node, step: Int, elementsOnly: Boolean): JsValue {
        val siblings = node.parent?.children ?: return JsNull
        var i = siblings.indexOf(node) + step
        while (i in siblings.indices) {
            val s = siblings[i]
            if (!elementsOnly || s is ElementNode) return wrapNode(s)
            i += step
        }
        return JsNull
    }

    fun isConnected(node: Node): Boolean {
        var cur: Node? = node
        while (cur != null) { if (cur === root) return true; cur = cur.parent }
        return false
    }

    /** `<canvas>` uses width/height HTML attributes for its bitmap resolution (default 300x150 per spec), not CSS. */
    fun getOrCreateCanvasContext(node: ElementNode): CanvasContext2D = canvasContexts.getOrPut(node) {
        val w = (node.attr("width")?.toIntOrNull() ?: 300).coerceAtLeast(1)
        val h = (node.attr("height")?.toIntOrNull() ?: 150).coerceAtLeast(1)
        CanvasContext2D(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888))
    }

    /** For Tab to merge into its image map, since canvases render via the same inline-image path as `<img>`/`<svg>`. */
    fun canvasBitmaps(): Map<ElementNode, Bitmap> = canvasContexts.mapValues { it.value.bitmap }

    fun dispatchClick(startNode: ElementNode, interpreter: Interpreter): JsEvent =
        dispatchEvent(startNode, "click", interpreter)

    /**
     * Bubbles an event of [type] from [startNode] up through every ancestor, running each one's
     * inline `on<type>="..."` attribute (with an implicit `event` variable in scope) and its
     * `addEventListener` listeners, until a listener calls `stopPropagation()`. Returns the event so
     * the caller can check `defaultPrevented` and whether anything ran.
     */
    fun dispatchEvent(startNode: ElementNode, type: String, interpreter: Interpreter): JsEvent {
        val event = JsEvent(type, wrap(startNode))
        dispatchEventObject(startNode, event, interpreter)
        return event
    }

    fun dispatchEventObject(startNode: ElementNode, event: JsValue, interpreter: Interpreter): Boolean {
        val type = eventType(event)
        (event as? JsObject)?.let { if (it.get("target") == JsUndefined || it.get("target") == JsNull) it.set("target", wrap(startNode)) }
        interpreter.globalEnv.declare("event", event)
        var ran = false
        var current: ElementNode? = startNode
        while (current != null) {
            (event as? JsObject)?.set("currentTarget", wrap(current))
            val onAttr = current.attr("on$type")
            if (!onAttr.isNullOrBlank()) {
                try {
                    interpreter.run(Parser(Lexer(onAttr).tokenize()).parseProgram())
                    ran = true
                } catch (_: Exception) {
                    // A broken inline handler shouldn't block bubbling to ancestors.
                }
            }
            if (wrappers[current]?.runListeners(type, interpreter, event) == true) ran = true
            if ((event as? JsEvent)?.propagationStopped == true) break
            current = current.parent
        }
        if ((event as? JsEvent)?.propagationStopped != true) {
            if (documentListeners.run(type, interpreter, documentObject ?: JsUndefined, event)) ran = true
            if (windowListeners.run(type, interpreter, windowObject ?: JsUndefined, event)) ran = true
        }
        if (ran) (event as? JsEvent)?.listenersRan = true
        return ran
    }

    fun install(env: Environment) {
        installMutationObserver(env)
        val document = DomDocument(root, this)
        document.proto = documentProto
        documentObject = document
        document.set("documentElement", wrap(root))
        document.set("getElementById", NativeFunction("getElementById", 1) { _, _, args ->
            val id = toJsString(args.getOrElse(0) { JsUndefined })
            var found: ElementNode? = null
            root.walkElements { if (found == null && it.attr("id") == id) found = it }
            found?.let { wrap(it) } ?: JsNull
        })
        document.set("querySelector", NativeFunction("querySelector", 1) { _, _, args ->
            querySelectorAll(root, toJsString(args.getOrElse(0) { JsUndefined })).firstOrNull()?.let { wrap(it) } ?: JsNull
        })
        document.set("querySelectorAll", NativeFunction("querySelectorAll", 1) { _, _, args ->
            wrapList(querySelectorAll(root, toJsString(args.getOrElse(0) { JsUndefined })))
        })
        document.set("getElementsByTagName", NativeFunction("getElementsByTagName", 1) { _, _, args ->
            wrapList(listOf(root).filter { t -> toJsString(args.getOrElse(0) { JsUndefined }).lowercase().let { it == "*" || it == t.tag } } + elementsByTag(root, toJsString(args.getOrElse(0) { JsUndefined })))
        })
        document.set("getElementsByClassName", NativeFunction("getElementsByClassName", 1) { _, _, args ->
            wrapList(elementsByClass(root, toJsString(args.getOrElse(0) { JsUndefined })))
        })
        document.set("getElementsByName", NativeFunction("getElementsByName", 1) { _, _, args ->
            val n = toJsString(args.getOrElse(0) { JsUndefined })
            val out = ArrayList<ElementNode>(); root.walkElements { if (it.attr("name") == n) out.add(it) }
            wrapList(out)
        })
        document.set("createElement", NativeFunction("createElement", 1) { _, _, args ->
            wrap(ElementNode(toJsString(args.getOrElse(0) { JsString("div") }).lowercase()))
        })
        document.set("createElementNS", NativeFunction("createElementNS", 2) { _, _, args ->
            wrap(ElementNode(toJsString(args.getOrElse(1) { JsString("div") }).lowercase()))
        })
        document.set("createTextNode", NativeFunction("createTextNode", 1) { _, _, args ->
            wrapNode(TextNode(toJsString(args.getOrElse(0) { JsUndefined })))
        })
        // Comments aren't kept in this DOM; an empty text node stands in so insertion calls still work.
        document.set("createComment", NativeFunction("createComment", 1) { _, _, _ -> wrapNode(TextNode("")) })
        document.set("createDocumentFragment", NativeFunction("createDocumentFragment", 0) { _, _, _ -> wrap(ElementNode(FRAGMENT_TAG)) })
        document.set("createEvent", NativeFunction("createEvent", 1) { _, _, _ ->
            JsEvent("", JsNull).also { ev ->
                ev.set("initEvent", NativeFunction("initEvent", 3) { _, _, a -> ev.set("type", JsString(toJsString(a.getOrElse(0) { JsUndefined }))); JsUndefined })
                ev.set("initCustomEvent", NativeFunction("initCustomEvent", 4) { _, _, a ->
                    ev.set("type", JsString(toJsString(a.getOrElse(0) { JsUndefined }))); ev.set("detail", a.getOrElse(3) { JsNull }); JsUndefined
                })
            }
        })
        for ((name, fn) in documentListeners.methods(document) { ev, i -> dispatchToDocument(ev, i) }) document.set(name, fn)
        document.set("hasFocus", NativeFunction("hasFocus", 0) { _, _, _ -> JsBoolean(true) })
        document.set("elementFromPoint", NativeFunction("elementFromPoint", 2) { _, _, _ -> JsNull })
        document.set("execCommand", NativeFunction("execCommand", 1) { _, _, _ -> JsBoolean(false) })
        document.set("fonts", JsObject().also { fonts ->
            fonts.set("ready", JsPromise().also { it.resolve(fonts) })
            fonts.set("load", NativeFunction("load", 1) { _, _, _ -> JsPromise().also { it.resolve(JsArray()) } })
            fonts.set("check", NativeFunction("check", 1) { _, _, _ -> JsBoolean(true) })
            fonts.set("add", NativeFunction("add", 1) { _, _, _ -> JsUndefined })
            fonts.set("status", JsString("loaded"))
        })
        document.set("implementation", JsObject().also { it.set("hasFeature", NativeFunction("hasFeature", 0) { _, _, _ -> JsBoolean(true) }) })
        env.declare("document", document)

        val window = env.get("globalThis") as? JsObject ?: JsObject()
        windowObject = window
        env.declare("window", window)
        for (alias in listOf("self", "top", "parent", "frames")) env.declare(alias, window)
        window.set("document", document)
        document.set("defaultView", window)
        window.set("alert", NativeFunction("alert", 1) { _, _, args ->
            println("[alert] " + toJsString(args.getOrElse(0) { JsUndefined }))
            JsUndefined
        })
        for ((name, fn) in windowListeners.methods(window) { ev, i -> windowListeners.run(eventType(ev), i, window, ev) }) window.set(name, fn)
        installBrowserApis(env, this, window)
    }

    private fun dispatchToDocument(event: JsValue, interp: Interpreter): Boolean {
        val type = eventType(event)
        val ran = documentListeners.run(type, interp, documentObject ?: JsUndefined, event)
        return windowListeners.run(type, interp, windowObject ?: JsUndefined, event) || ran
    }

    fun wrapList(nodes: List<ElementNode>): JsArray = JsArray(nodes.map { wrap(it) as JsValue }.toMutableList())

    /** Fires a `window`-level event (`popstate` from Tab's history handling, `load`, `resize`...). */
    fun dispatchWindowEvent(type: String, interpreter: Interpreter, event: JsEvent) {
        windowListeners.run(type, interpreter, windowObject ?: JsUndefined, event)
    }

    /**
     * Runs after every page script: `DOMContentLoaded` on document and window, then `readyState`
     * "complete", `readystatechange`, window `load`, and anything scripts deferred (an
     * IntersectionObserver's first report) until the page had finished loading.
     */
    fun fireDomContentLoaded(interpreter: Interpreter) {
        readyState = "interactive"
        dispatchToDocument(JsEvent("DOMContentLoaded", documentObject ?: JsNull), interpreter)
        readyState = "complete"
        documentListeners.run("readystatechange", interpreter, documentObject ?: JsUndefined, JsEvent("readystatechange", documentObject ?: JsNull))
        (documentObject?.get("onreadystatechange") as? JsFunction)?.let { try { it.call(interpreter, documentObject!!, emptyList()) } catch (_: Exception) {} }
        val loadEvent = JsEvent("load", windowObject ?: JsNull)
        windowListeners.run("load", interpreter, windowObject ?: JsUndefined, loadEvent)
        (windowObject?.get("onload") as? JsFunction)?.let { try { it.call(interpreter, windowObject!!, listOf(loadEvent)) } catch (_: Exception) {} }
        flushPendingCallbacks(interpreter)
    }

    fun flushPendingCallbacks(interpreter: Interpreter) {
        while (pendingCallbacks.isNotEmpty()) {
            val batch = pendingCallbacks.toList()
            pendingCallbacks.clear()
            for (cb in batch) try { cb(interpreter) } catch (_: Exception) {}
        }
    }

    /**
     * `new MutationObserver(callback)` / `.observe(target, options)` / `.disconnect()`. Every
     * mutation is reported the instant it happens, as a single-record callback call. `options` is
     * accepted but only `subtree` is honored. Only method-style mutations notify (they run with an
     * Interpreter to call the observer with); property-style ones (`el.textContent = x`) don't.
     */
    fun installMutationObserver(env: Environment) {
        val ctor = NativeFunction("MutationObserver", 1) { _, thisArg, args ->
            val callback = args.getOrNull(0) as? JsFunction
            val obj = thisArg as? JsObject ?: JsObject()
            obj.set("observe", NativeFunction("observe", 2) { _, _, observeArgs ->
                val target = (observeArgs.getOrNull(0) as? DomElement)?.node ?: (observeArgs.getOrNull(0) as? DomDocument)?.let { root }
                val options = observeArgs.getOrNull(1) as? JsObject
                if (target != null && callback != null) {
                    val subtree = options?.get("subtree")?.let { isTruthy(it) } ?: false
                    mutationObservers.removeAll { it.callback === callback && it.target === target }
                    mutationObservers.add(ObserverRegistration(callback, target, subtree))
                }
                JsUndefined
            })
            obj.set("disconnect", NativeFunction("disconnect", 0) { _, _, _ ->
                mutationObservers.removeAll { it.callback === callback }
                JsUndefined
            })
            obj.set("takeRecords", NativeFunction("takeRecords", 0) { _, _, _ -> JsArray() })
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
            record.set("addedNodes", JsArray())
            record.set("removedNodes", JsArray())
            try {
                reg.callback.call(interpreter, JsUndefined, listOf(JsArray(mutableListOf(record))))
            } catch (_: Exception) {
                // A broken observer callback shouldn't block the mutation that triggered it.
            }
        }
    }

    fun findFirst(node: ElementNode, tag: String): ElementNode? {
        var found: ElementNode? = null
        node.walkElements { if (found == null && it.tag == tag) found = it }
        return found
    }

    /** Descendants of [scope] matching a selector list (`a, .b > c`). An unsupported selector matches nothing. */
    fun querySelectorAll(scope: ElementNode, selectorText: String): List<ElementNode> {
        val selectors = splitSelectorList(selectorText).mapNotNull { CssParser("").parseSingleSelector(it) }
        if (selectors.isEmpty()) return emptyList()
        val results = ArrayList<ElementNode>()
        scope.walkElements { el -> if (el !== scope && selectors.any { it.matches(el) }) results.add(el) }
        return results
    }

    private fun splitSelectorList(text: String): List<String> {
        val parts = ArrayList<String>()
        var depth = 0
        val cur = StringBuilder()
        for (c in text) {
            when (c) {
                '(', '[' -> depth++
                ')', ']' -> depth--
            }
            if (c == ',' && depth == 0) { parts.add(cur.toString().trim()); cur.setLength(0) } else cur.append(c)
        }
        if (cur.isNotBlank()) parts.add(cur.toString().trim())
        return parts.filter { it.isNotEmpty() }
    }
}

class DomElement(val node: ElementNode, private val bridge: DomBridge) : JsObject() {
    private val listenerSet = ListenerSet()

    /** Invokes every listener registered for [type] (see DomBridge.dispatchEvent), `this`-bound to this element. */
    fun runListeners(type: String, interpreter: Interpreter, event: JsValue): Boolean {
        val ran = listenerSet.run(type, interpreter, this, event)
        val handler = super.get("on$type") as? JsFunction
        if (handler != null) {
            try { handler.call(interpreter, this, listOf(event)) } catch (_: Exception) {}
            return true
        }
        return ran
    }

    /** True if this element has any listener/attribute wired for [type]. */
    fun hasListenerFor(type: String): Boolean =
        listenerSet.listeners[type]?.isNotEmpty() == true || !node.attr("on$type").isNullOrBlank() || super.get("on$type") is JsFunction

    private fun fn(name: String, arity: Int, impl: (Interpreter, List<JsValue>) -> JsValue) = NativeFunction(name, arity) { i, _, a -> impl(i, a) }

    private fun nodeArg(v: JsValue): Node? = when (v) {
        is DomElement -> v.node
        is DomText -> v.node
        is JsString -> TextNode(v.value)
        else -> null
    }

    private fun changed(i: Interpreter) = bridge.notifyMutation(i, node, "childList")

    override fun get(name: String): JsValue = when (name) {
        "tagName", "nodeName" -> JsString(if (node.tag == FRAGMENT_TAG) "#document-fragment" else node.tag.uppercase())
        "localName" -> JsString(node.tag)
        "nodeType" -> JsNumber(if (node.tag == FRAGMENT_TAG) 11.0 else 1.0)
        "id" -> JsString(node.attr("id") ?: "")
        "className" -> JsString(node.attr("class") ?: "")
        "textContent", "innerText" -> JsString(collectText(node))
        "innerHTML" -> JsString(node.children.joinToString("") { serializeHtml(it) })
        "outerHTML" -> JsString(serializeHtml(node))
        "style" -> DomStyle(node)
        "dataset" -> DomDataset(node)
        "children" -> JsArray(node.children.filterIsInstance<ElementNode>().map { bridge.wrap(it) as JsValue }.toMutableList())
        "childNodes" -> JsArray(node.children.map { bridge.wrapNode(it) }.toMutableList())
        "childElementCount" -> JsNumber(node.children.count { it is ElementNode }.toDouble())
        "firstChild" -> node.children.firstOrNull()?.let { bridge.wrapNode(it) } ?: JsNull
        "lastChild" -> node.children.lastOrNull()?.let { bridge.wrapNode(it) } ?: JsNull
        "firstElementChild" -> node.children.firstOrNull { it is ElementNode }?.let { bridge.wrapNode(it) } ?: JsNull
        "lastElementChild" -> node.children.lastOrNull { it is ElementNode }?.let { bridge.wrapNode(it) } ?: JsNull
        "parentNode", "parentElement" -> node.parent?.let { bridge.wrap(it) } ?: JsNull
        "nextElementSibling" -> bridge.siblingOf(node, 1, elementsOnly = true)
        "previousElementSibling" -> bridge.siblingOf(node, -1, elementsOnly = true)
        "nextSibling" -> bridge.siblingOf(node, 1, elementsOnly = false)
        "previousSibling" -> bridge.siblingOf(node, -1, elementsOnly = false)
        "ownerDocument" -> bridge.documentObject ?: JsNull
        "isConnected" -> JsBoolean(bridge.isConnected(node))
        "classList" -> makeClassList(node)
        "attributes" -> JsArray(node.attributes.entries.map { (k, v) -> JsObject().also { a -> a.set("name", JsString(k)); a.set("value", JsString(v)) } as JsValue }.toMutableList())
        "hidden" -> JsBoolean(node.attributes.containsKey("hidden"))
        "href", "src", "action", "rel", "target", "alt", "title", "lang", "dir", "placeholder", "content", "role" ->
            node.attr(name)?.let { JsString(it) } ?: JsString("")
        "hasAttribute" -> fn("hasAttribute", 1) { _, a -> JsBoolean(node.attr(toJsString(a.getOrElse(0) { JsUndefined })) != null) }
        "getAttributeNames" -> fn("getAttributeNames", 0) { _, _ -> JsArray(node.attributes.keys.map { JsString(it) as JsValue }.toMutableList()) }
        "hasAttributes" -> fn("hasAttributes", 0) { _, _ -> JsBoolean(node.attributes.isNotEmpty()) }
        "hasChildNodes" -> fn("hasChildNodes", 0) { _, _ -> JsBoolean(node.children.isNotEmpty()) }
        "matches", "webkitMatchesSelector" -> fn("matches", 1) { _, a ->
            JsBoolean(bridge.querySelectorAll(node.parent ?: node, toJsString(a.getOrElse(0) { JsUndefined })).any { it === node })
        }
        "closest" -> fn("closest", 1) { _, a ->
            val sel = toJsString(a.getOrElse(0) { JsUndefined })
            var current: ElementNode? = node
            var result: JsValue = JsNull
            while (current != null) {
                val scope = current.parent ?: current
                if (bridge.querySelectorAll(scope, sel).any { it === current }) { result = bridge.wrap(current); break }
                current = current.parent
            }
            result
        }
        "querySelector" -> fn("querySelector", 1) { _, a -> bridge.querySelectorAll(node, toJsString(a.getOrElse(0) { JsUndefined })).firstOrNull()?.let { bridge.wrap(it) } ?: JsNull }
        "querySelectorAll" -> fn("querySelectorAll", 1) { _, a -> bridge.wrapList(bridge.querySelectorAll(node, toJsString(a.getOrElse(0) { JsUndefined }))) }
        "getElementsByTagName" -> fn("getElementsByTagName", 1) { _, a -> bridge.wrapList(elementsByTag(node, toJsString(a.getOrElse(0) { JsUndefined }))) }
        "getElementsByClassName" -> fn("getElementsByClassName", 1) { _, a -> bridge.wrapList(elementsByClass(node, toJsString(a.getOrElse(0) { JsUndefined }))) }
        "setAttribute" -> fn("setAttribute", 2) { i, a ->
            val attrName = toJsString(a.getOrElse(0) { JsUndefined }).lowercase()
            node.attributes[attrName] = toJsString(a.getOrElse(1) { JsUndefined })
            bridge.notifyMutation(i, node, "attributes", attrName)
            JsUndefined
        }
        "setAttributeNS" -> fn("setAttributeNS", 3) { _, a -> node.attributes[toJsString(a.getOrElse(1) { JsUndefined })] = toJsString(a.getOrElse(2) { JsUndefined }); JsUndefined }
        "getAttribute" -> fn("getAttribute", 1) { _, a -> node.attr(toJsString(a.getOrElse(0) { JsUndefined }).lowercase())?.let { JsString(it) } ?: JsNull }
        "removeAttribute" -> fn("removeAttribute", 1) { i, a ->
            val attrName = toJsString(a.getOrElse(0) { JsUndefined }).lowercase()
            node.attributes.remove(attrName)
            bridge.notifyMutation(i, node, "attributes", attrName)
            JsUndefined
        }
        "toggleAttribute" -> fn("toggleAttribute", 2) { _, a ->
            val attrName = toJsString(a.getOrElse(0) { JsUndefined }).lowercase()
            val force = a.getOrNull(1)?.takeIf { it != JsUndefined }?.let { isTruthy(it) }
            val on = force ?: !node.attributes.containsKey(attrName)
            if (on) node.attributes.putIfAbsent(attrName, "") else node.attributes.remove(attrName)
            JsBoolean(on)
        }
        "appendChild" -> fn("appendChild", 1) { i, a ->
            nodeArg(a.getOrElse(0) { JsUndefined })?.let { insertChild(node, it, null); changed(i) }
            a.getOrElse(0) { JsUndefined }
        }
        "append" -> fn("append", 1) { i, a -> a.mapNotNull { nodeArg(it) }.forEach { insertChild(node, it, null) }; changed(i); JsUndefined }
        "prepend" -> fn("prepend", 1) { i, a ->
            val first = node.children.firstOrNull()
            a.mapNotNull { nodeArg(it) }.forEach { insertChild(node, it, first) }
            changed(i); JsUndefined
        }
        "insertBefore" -> fn("insertBefore", 2) { i, a ->
            val child = nodeArg(a.getOrElse(0) { JsUndefined })
            val ref = a.getOrNull(1)?.let { nodeArg(it) }?.takeIf { it.parent === node }
            if (child != null) { insertChild(node, child, ref); changed(i) }
            a.getOrElse(0) { JsUndefined }
        }
        "removeChild" -> fn("removeChild", 1) { i, a ->
            nodeArg(a.getOrElse(0) { JsUndefined })?.takeIf { it.parent === node }?.let { detachNode(it); changed(i) }
            a.getOrElse(0) { JsUndefined }
        }
        "replaceChild" -> fn("replaceChild", 2) { i, a ->
            val newChild = nodeArg(a.getOrElse(0) { JsUndefined })
            val old = a.getOrNull(1)?.let { nodeArg(it) }?.takeIf { it.parent === node }
            if (newChild != null && old != null) { insertChild(node, newChild, old); detachNode(old); changed(i) }
            a.getOrElse(1) { JsUndefined }
        }
        "replaceChildren" -> fn("replaceChildren", 0) { i, a ->
            for (c in node.children.toList()) detachNode(c)
            a.mapNotNull { nodeArg(it) }.forEach { insertChild(node, it, null) }
            changed(i); JsUndefined
        }
        "remove" -> fn("remove", 0) { i, _ ->
            val parent = node.parent
            detachNode(node)
            if (parent != null) bridge.notifyMutation(i, parent, "childList")
            JsUndefined
        }
        "before", "after", "replaceWith" -> fn(name, 1) { i, a ->
            val parent = node.parent
            if (parent != null) {
                val ref = if (name == "after") parent.children.getOrNull(parent.children.indexOf(node) + 1) else node
                a.mapNotNull { nodeArg(it) }.forEach { insertChild(parent, it, ref) }
                if (name == "replaceWith") detachNode(node)
                bridge.notifyMutation(i, parent, "childList")
            }
            JsUndefined
        }
        "insertAdjacentHTML", "insertAdjacentElement", "insertAdjacentText" -> fn(name, 2) { i, a ->
            val where = toJsString(a.getOrElse(0) { JsUndefined }).lowercase()
            val payload = a.getOrElse(1) { JsUndefined }
            val nodes: List<Node> = when (name) {
                "insertAdjacentHTML" -> parseFragment(toJsString(payload))
                "insertAdjacentText" -> listOf(TextNode(toJsString(payload)))
                else -> listOfNotNull(nodeArg(payload))
            }
            val parent = node.parent
            when (where) {
                "beforebegin" -> if (parent != null) nodes.forEach { insertChild(parent, it, node) }
                "afterbegin" -> { val first = node.children.firstOrNull(); nodes.forEach { insertChild(node, it, first) } }
                "beforeend" -> nodes.forEach { insertChild(node, it, null) }
                "afterend" -> if (parent != null) { val next = parent.children.getOrNull(parent.children.indexOf(node) + 1); nodes.forEach { insertChild(parent, it, next) } }
            }
            changed(i)
            if (name == "insertAdjacentElement") payload else JsUndefined
        }
        "cloneNode" -> fn("cloneNode", 1) { _, a -> bridge.wrapNode(cloneNodeTree(node, truthy(a.getOrNull(0)))) }
        "contains" -> fn("contains", 1) { _, a ->
            val other = nodeArg(a.getOrElse(0) { JsUndefined })
            var cur: Node? = other
            var found = false
            while (cur != null) { if (cur === node) { found = true; break }; cur = cur.parent }
            JsBoolean(found)
        }
        "getRootNode" -> fn("getRootNode", 0) { _, _ -> if (bridge.isConnected(node)) bridge.documentObject ?: JsNull else this }
        "addEventListener", "removeEventListener", "dispatchEvent" ->
            listenerSet.methods(this) { ev, i -> node.let { bridge.dispatchEventObject(it, ev, i) } }.getValue(name)
        "click" -> fn("click", 0) { i, _ -> bridge.dispatchEvent(node, "click", i); JsUndefined }
        "focus", "blur", "scrollIntoView", "scrollTo", "scrollBy", "scroll", "select", "setPointerCapture", "releasePointerCapture" ->
            fn(name, 0) { _, _ -> JsUndefined }
        "getBoundingClientRect" -> fn("getBoundingClientRect", 0) { _, _ -> emptyRect() }
        "getClientRects" -> fn("getClientRects", 0) { _, _ -> JsArray() }
        "animate" -> fn("animate", 2) { _, _ -> JsObject().also { anim ->
            anim.set("finished", JsPromise().also { it.resolve(anim) })
            anim.set("cancel", NativeFunction("cancel", 0) { _, _, _ -> JsUndefined })
        } }
        "offsetWidth", "offsetHeight", "offsetTop", "offsetLeft", "clientWidth", "clientHeight", "clientTop", "clientLeft",
        "scrollWidth", "scrollHeight", "scrollTop", "scrollLeft" -> super.get(name).takeIf { it != JsUndefined } ?: JsNumber(0.0)
        "getContext" -> fn("getContext", 1) { _, a ->
            if (toJsString(a.getOrElse(0) { JsUndefined }) == "2d") bridge.getOrCreateCanvasContext(node) else JsNull
        }
        "value" -> JsString(if (node.tag == "textarea") collectText(node) else (node.attr("value") ?: ""))
        "checked" -> JsBoolean(node.attributes.containsKey("checked"))
        "type" -> JsString(node.attr("type") ?: if (node.tag == "textarea") "textarea" else if (node.tag == "input") "text" else "")
        "name" -> JsString(node.attr("name") ?: "")
        "disabled" -> JsBoolean(node.attributes.containsKey("disabled"))
        else -> super.get(name)
    }

    override fun set(name: String, value: JsValue) {
        when (name) {
            "textContent", "innerText" -> {
                for (c in node.children) c.parent = null
                node.children.clear()
                toJsString(value).takeIf { it.isNotEmpty() }?.let { node.children.add(TextNode(it, node)) }
            }
            "id" -> node.attributes["id"] = toJsString(value)
            "className" -> node.attributes["class"] = toJsString(value)
            "innerHTML" -> setInnerHtml(toJsString(value))
            "outerHTML" -> node.parent?.let { parent -> parseFragment(toJsString(value)).forEach { insertChild(parent, it, node) }; detachNode(node) }
            "value" -> if (node.tag == "textarea") {
                node.children.clear()
                node.children.add(TextNode(toJsString(value), node))
            } else {
                node.attributes["value"] = toJsString(value)
            }
            "checked" -> if (isTruthy(value)) node.attributes["checked"] = "checked" else node.attributes.remove("checked")
            "disabled" -> if (isTruthy(value)) node.attributes["disabled"] = "disabled" else node.attributes.remove("disabled")
            "hidden" -> if (isTruthy(value)) node.attributes["hidden"] = "" else node.attributes.remove("hidden")
            "href", "src", "alt", "title", "lang", "dir", "placeholder", "rel", "target", "type", "name", "action", "role" ->
                node.attributes[name] = toJsString(value)
            "style" -> node.attributes["style"] = toJsString(value)
            else -> super.set(name, value)
        }
    }

    private fun setInnerHtml(html: String) {
        // Old children may still be reachable from JS and must report a null parentNode once gone.
        for (old in node.children) old.parent = null
        node.children.clear()
        for (child in parseFragment(html)) {
            child.parent = node
            node.children.add(child)
        }
    }

    private fun collectText(n: Node): String = when (n) {
        is TextNode -> n.text
        is ElementNode -> if (n.tag == "script" || n.tag == "style") "" else n.children.joinToString("") { collectText(it) }
    }

    private fun makeClassList(node: ElementNode): JsObject {
        val obj = JsObject()
        fun classes(): MutableList<String> = (node.attr("class") ?: "").split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        fun save(list: List<String>) { node.attributes["class"] = list.joinToString(" ") }
        obj.set("add", NativeFunction("add", 1) { _, _, args ->
            val c = classes()
            for (a in args) { val n = toJsString(a); if (n !in c) c.add(n) }
            save(c)
            JsUndefined
        })
        obj.set("remove", NativeFunction("remove", 1) { _, _, args ->
            val c = classes()
            for (a in args) c.remove(toJsString(a))
            save(c)
            JsUndefined
        })
        obj.set("toggle", NativeFunction("toggle", 2) { _, _, args ->
            val c = classes()
            val name = toJsString(args.getOrElse(0) { JsUndefined })
            val force = args.getOrNull(1)?.takeIf { it != JsUndefined }?.let { isTruthy(it) }
            val on = force ?: (name !in c)
            c.remove(name)
            if (on) c.add(name)
            save(c)
            JsBoolean(on)
        })
        obj.set("replace", NativeFunction("replace", 2) { _, _, args ->
            val c = classes()
            val i = c.indexOf(toJsString(args.getOrElse(0) { JsUndefined }))
            if (i >= 0) c[i] = toJsString(args.getOrElse(1) { JsUndefined })
            save(c)
            JsBoolean(i >= 0)
        })
        obj.set("contains", NativeFunction("contains", 1) { _, _, args ->
            JsBoolean(toJsString(args.getOrElse(0) { JsUndefined }) in classes())
        })
        obj.set("item", NativeFunction("item", 1) { _, _, args ->
            classes().getOrNull(com.projectfuture.browser.js.toNumber(args.getOrElse(0) { JsUndefined }).toInt())?.let { JsString(it) } ?: JsNull
        })
        obj.set("length", JsNumber(classes().size.toDouble()))
        obj.set("value", JsString(node.attr("class") ?: ""))
        return obj
    }
}

/** `element.style.property = value` writes into the inline `style="..."` attribute, so it survives the next cascade re-run. */
class DomStyle(private val node: ElementNode) : JsObject() {
    override fun get(name: String): JsValue = when (name) {
        "cssText" -> JsString(node.attr("style") ?: "")
        "length" -> JsNumber(currentDeclarations().size.toDouble())
        "setProperty" -> NativeFunction("setProperty", 3) { _, _, a ->
            write(toJsString(a.getOrElse(0) { JsUndefined }), a.getOrNull(1)?.takeIf { it != JsNull && it != JsUndefined }?.let { toJsString(it) })
            JsUndefined
        }
        "getPropertyValue" -> NativeFunction("getPropertyValue", 1) { _, _, a -> JsString(currentDeclarations()[toJsString(a.getOrElse(0) { JsUndefined })] ?: "") }
        "removeProperty" -> NativeFunction("removeProperty", 1) { _, _, a ->
            val key = toJsString(a.getOrElse(0) { JsUndefined })
            val old = currentDeclarations()[key] ?: ""
            write(key, null)
            JsString(old)
        }
        "getPropertyPriority" -> NativeFunction("getPropertyPriority", 1) { _, _, _ -> JsString("") }
        else -> currentDeclarations()[cssPropertyName(name)]?.let { JsString(it) } ?: JsString("")
    }

    override fun set(name: String, value: JsValue) {
        if (name == "cssText") { node.attributes["style"] = toJsString(value); return }
        val v = if (value == JsNull || value == JsUndefined) null else toJsString(value)
        write(cssPropertyName(name), v?.takeIf { it.isNotEmpty() })
    }

    private fun write(property: String, value: String?) {
        val decls = currentDeclarations().toMutableMap()
        if (value == null) decls.remove(property) else decls[property] = value
        node.attributes["style"] = decls.entries.joinToString("; ") { (k, v) -> "$k: $v" }
    }

    private fun currentDeclarations(): Map<String, String> = CssParser.parseInlineDeclarations(node.attr("style") ?: "")

    /** JS uses camelCase (backgroundColor); CSS uses kebab-case (background-color). Custom properties pass through. */
    private fun cssPropertyName(js: String): String = if (js.startsWith("--")) js else js.replace(Regex("[A-Z]")) { "-" + it.value.lowercase() }
}

/** `document`: a property bag plus the live parts: `title`, `head`, `body`, `cookie`, `readyState`, `currentScript`. */
class DomDocument(private val root: ElementNode, private val bridge: DomBridge) : JsObject() {
    override fun get(name: String): JsValue = when (name) {
        "title" -> JsString(findTitle(root)?.let { collectTextStatic(it) }?.trim() ?: "")
        "head" -> bridge.findFirst(root, "head")?.let { bridge.wrap(it) } ?: JsNull
        "body" -> bridge.findFirst(root, "body")?.let { bridge.wrap(it) } ?: JsNull
        "cookie" -> JsString(bridge.cookieReader())
        "readyState" -> JsString(bridge.readyState)
        "currentScript" -> bridge.currentScript?.let { bridge.wrap(it) } ?: JsNull
        "nodeType" -> JsNumber(9.0)
        "nodeName" -> JsString("#document")
        "visibilityState" -> JsString("visible")
        "hidden" -> JsBoolean(false)
        "compatMode" -> JsString("CSS1Compat")
        "characterSet", "charset" -> JsString("UTF-8")
        "contentType" -> JsString("text/html")
        "referrer" -> JsString("")
        "activeElement", "scrollingElement" -> bridge.findFirst(root, "body")?.let { bridge.wrap(it) } ?: JsNull
        "forms" -> bridge.wrapList(elementsByTag(root, "form"))
        "images" -> bridge.wrapList(elementsByTag(root, "img"))
        "links" -> bridge.wrapList(elementsByTag(root, "a").filter { it.attr("href") != null })
        "scripts" -> bridge.wrapList(elementsByTag(root, "script"))
        "styleSheets" -> JsArray()
        "childNodes", "children" -> JsArray(mutableListOf(bridge.wrap(root)))
        "firstElementChild", "firstChild" -> bridge.wrap(root)
        else -> super.get(name)
    }

    override fun set(name: String, value: JsValue) {
        when (name) {
            "title" -> {
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
            }
            "cookie" -> bridge.cookieWriter(toJsString(value))
            else -> super.set(name, value)
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
