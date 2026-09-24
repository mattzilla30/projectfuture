package com.projectfuture.browser.browser

import com.projectfuture.browser.html.ElementNode
import com.projectfuture.browser.html.HtmlParser
import com.projectfuture.browser.html.Node
import com.projectfuture.browser.html.TextNode
import com.projectfuture.browser.html.walkElements
import com.projectfuture.browser.js.Interpreter
import com.projectfuture.browser.js.JsArray
import com.projectfuture.browser.js.JsBoolean
import com.projectfuture.browser.js.JsEvent
import com.projectfuture.browser.js.JsFunction
import com.projectfuture.browser.js.JsNull
import com.projectfuture.browser.js.JsNumber
import com.projectfuture.browser.js.JsObject
import com.projectfuture.browser.js.JsString
import com.projectfuture.browser.js.JsUndefined
import com.projectfuture.browser.js.JsValue
import com.projectfuture.browser.js.NativeFunction
import com.projectfuture.browser.js.isTruthy
import com.projectfuture.browser.js.toJsString

/** Tag name used for `document.createDocumentFragment()` containers; inserting one moves its children instead. */
const val FRAGMENT_TAG = "#document-fragment"

/** A DOM text node (`document.createTextNode`, `el.firstChild` on text, `childNodes` entries). */
class DomText(val node: TextNode, private val bridge: DomBridge) : JsObject() {
    override fun get(name: String): JsValue = when (name) {
        "nodeType" -> JsNumber(3.0)
        "nodeName" -> JsString("#text")
        "textContent", "data", "nodeValue", "wholeText" -> JsString(node.text)
        "length" -> JsNumber(node.text.length.toDouble())
        "parentNode", "parentElement" -> node.parent?.let { bridge.wrap(it) } ?: JsNull
        "nextSibling" -> bridge.siblingOf(node, 1, elementsOnly = false)
        "previousSibling" -> bridge.siblingOf(node, -1, elementsOnly = false)
        "ownerDocument" -> bridge.documentObject ?: JsNull
        "isConnected" -> JsBoolean(bridge.isConnected(node))
        "remove" -> NativeFunction("remove", 0) { _, _, _ -> detachNode(node); JsUndefined }
        "cloneNode" -> NativeFunction("cloneNode", 1) { _, _, _ -> bridge.wrapNode(TextNode(node.text)) }
        "childNodes" -> JsArray()
        "hasChildNodes" -> NativeFunction("hasChildNodes", 0) { _, _, _ -> JsBoolean(false) }
        else -> super.get(name)
    }

    override fun set(name: String, value: JsValue) {
        if (name == "textContent" || name == "data" || name == "nodeValue") node.text = toJsString(value) else super.set(name, value)
    }
}

/** `el.dataset`: camelCase names mapped to `data-*` attributes. */
class DomDataset(private val node: ElementNode) : JsObject() {
    private fun attrName(key: String) = "data-" + key.replace(Regex("[A-Z]")) { "-" + it.value.lowercase() }
    private fun keyName(attr: String) = attr.removePrefix("data-").replace(Regex("-([a-z])")) { it.groupValues[1].uppercase() }

    override fun get(name: String): JsValue = node.attr(attrName(name))?.let { JsString(it) } ?: JsUndefined
    override fun set(name: String, value: JsValue) { node.attributes[attrName(name)] = toJsString(value) }
    override fun has(name: String): Boolean = node.attributes.containsKey(attrName(name))
    override fun ownKeys(): List<String> = node.attributes.keys.filter { it.startsWith("data-") }.map { keyName(it) }
}

fun detachNode(n: Node) {
    n.parent?.children?.remove(n)
    n.parent = null
}

fun isAncestorOrSelf(candidate: ElementNode, node: ElementNode): Boolean {
    var cur: ElementNode? = node
    while (cur != null) { if (cur === candidate) return true; cur = cur.parent }
    return false
}

/**
 * Inserts [child] into [parent] before [before] (or at the end). A fragment contributes its children
 * instead of itself. Inserting an ancestor of [parent] (which would make a cycle) is ignored.
 */
fun insertChild(parent: ElementNode, child: Node, before: Node?) {
    if (child is ElementNode && child.tag == FRAGMENT_TAG) {
        for (c in child.children.toList()) insertChild(parent, c, before)
        return
    }
    if (child is ElementNode && isAncestorOrSelf(child, parent)) return
    detachNode(child)
    child.parent = parent
    val index = before?.let { parent.children.indexOf(it) }?.takeIf { it >= 0 } ?: parent.children.size
    parent.children.add(index, child)
}

/** A layout-free bounding box: scripts get a well-formed object even though real geometry isn't known here. */
fun emptyRect(): JsObject = JsObject().also { r ->
    for (k in listOf("x", "y", "top", "left", "right", "bottom", "width", "height")) r.set(k, JsNumber(0.0))
}

/** Parses an HTML fragment into detached nodes, as `innerHTML`/`insertAdjacentHTML` need. */
fun parseFragment(html: String): List<Node> {
    val doc = HtmlParser("<div>$html</div>").parse()
    val body = doc.children.filterIsInstance<ElementNode>().firstOrNull { it.tag == "body" }
    val wrapper = body?.children?.filterIsInstance<ElementNode>()?.firstOrNull { it.tag == "div" } ?: return emptyList()
    return wrapper.children.toList().onEach { it.parent = null }
}

fun cloneNodeTree(n: Node, deep: Boolean): Node = when (n) {
    is TextNode -> TextNode(n.text)
    is ElementNode -> ElementNode(n.tag, LinkedHashMap(n.attributes)).also { copy ->
        if (deep) for (c in n.children) { val cc = cloneNodeTree(c, true); cc.parent = copy; copy.children.add(cc) }
    }
}

fun elementsByTag(scope: ElementNode, tag: String): List<ElementNode> {
    val t = tag.lowercase()
    val out = ArrayList<ElementNode>()
    scope.walkElements { if (it !== scope && (t == "*" || it.tag == t)) out.add(it) }
    return out
}

fun elementsByClass(scope: ElementNode, classNames: String): List<ElementNode> {
    val wanted = classNames.split(Regex("\\s+")).filter { it.isNotEmpty() }
    val out = ArrayList<ElementNode>()
    scope.walkElements { if (it !== scope && wanted.isNotEmpty() && it.classList().containsAll(wanted)) out.add(it) }
    return out
}

/** Registers/removes/dispatches listeners stored in [listeners]; shared by elements, document and window. */
class ListenerSet {
    val listeners = HashMap<String, MutableList<JsFunction>>()

    fun add(args: List<JsValue>) {
        val type = toJsString(args.getOrElse(0) { JsUndefined })
        val fn = args.getOrNull(1) as? JsFunction ?: (args.getOrNull(1) as? JsObject)?.get("handleEvent") as? JsFunction ?: return
        val list = listeners.getOrPut(type) { ArrayList() }
        if (list.none { it === fn }) list.add(fn)
    }

    fun remove(args: List<JsValue>) {
        val type = toJsString(args.getOrElse(0) { JsUndefined })
        val fn = args.getOrNull(1) ?: return
        listeners[type]?.removeAll { it === fn }
    }

    /** Runs listeners for the event's type; a throwing listener doesn't stop the others, as in browsers. */
    fun run(type: String, interp: Interpreter, thisArg: JsValue, event: JsValue): Boolean {
        val fns = listeners[type]?.toList() ?: return false
        for (fn in fns) {
            try { fn.call(interp, thisArg, listOf(event)) } catch (_: Exception) {}
        }
        return fns.isNotEmpty()
    }

    fun methods(target: JsObject, dispatch: (JsValue, Interpreter) -> Boolean): Map<String, NativeFunction> = mapOf(
        "addEventListener" to NativeFunction("addEventListener", 2) { _, _, a -> add(a); JsUndefined },
        "removeEventListener" to NativeFunction("removeEventListener", 2) { _, _, a -> remove(a); JsUndefined },
        "dispatchEvent" to NativeFunction("dispatchEvent", 1) { i, _, a ->
            val ev = a.getOrElse(0) { JsUndefined }
            JsBoolean(dispatch(ev, i).let { !((ev as? JsEvent)?.defaultPrevented ?: false) })
        }
    )
}

fun eventType(event: JsValue): String = toJsString((event as? JsObject)?.get("type") ?: JsUndefined)

fun truthy(v: JsValue?): Boolean = v != null && isTruthy(v)
