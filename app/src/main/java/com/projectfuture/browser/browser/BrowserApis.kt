package com.projectfuture.browser.browser

import com.projectfuture.browser.css.CssParser
import com.projectfuture.browser.js.Environment
import com.projectfuture.browser.js.Interpreter
import com.projectfuture.browser.js.JsArray
import com.projectfuture.browser.js.JsBoolean
import com.projectfuture.browser.js.JsEvent
import com.projectfuture.browser.js.JsFunction
import com.projectfuture.browser.js.JsNull
import com.projectfuture.browser.js.JsNumber
import com.projectfuture.browser.js.JsObject
import com.projectfuture.browser.js.JsPromise
import com.projectfuture.browser.js.JsString
import com.projectfuture.browser.js.JsTypedArray
import com.projectfuture.browser.js.JsUndefined
import com.projectfuture.browser.js.JsValue
import com.projectfuture.browser.js.NativeFunction
import com.projectfuture.browser.js.TypedArrayKind
import com.projectfuture.browser.js.arrayLikeToList
import com.projectfuture.browser.js.makeTypedArray
import com.projectfuture.browser.js.toJsString
import com.projectfuture.browser.js.toNumber
import com.projectfuture.browser.net.Url
import java.security.SecureRandom
import java.util.UUID

/** The viewport scripts see through `innerWidth`, `screen`, and `matchMedia`, in CSS pixels. */
var scriptViewportWidth = 412.0
var scriptViewportHeight = 915.0

private val startNanos = System.nanoTime()
private val random = SecureRandom()

private fun arg(a: List<JsValue>, i: Int): JsValue = a.getOrElse(i) { JsUndefined }
private fun str(a: List<JsValue>, i: Int): String = toJsString(arg(a, i))
private fun obj(vararg pairs: Pair<String, JsValue>): JsObject = JsObject().also { o -> for ((k, v) in pairs) o.set(k, v) }
private fun fn(name: String, arity: Int = 0, impl: (Interpreter, JsValue, List<JsValue>) -> JsValue) = NativeFunction(name, arity, impl)
private fun noop(name: String) = fn(name) { _, _, _ -> JsUndefined }
private fun resolved(v: JsValue): JsPromise = JsPromise().also { it.resolve(v) }

/**
 * Browser globals scripts expect beyond the DOM itself: `performance`, `navigator`, `matchMedia`,
 * observers, `Event` constructors, `URL`, `crypto`, `Intl` and similar. Where this engine has no
 * real backing (no layout feedback for ResizeObserver, no Intl locale data), the object is present
 * and well-formed so feature checks and setup code keep going.
 */
fun installBrowserApis(env: Environment, bridge: DomBridge, window: JsObject) {
    fun global(name: String, value: JsValue) = env.declare(name, value)

    global("performance", obj(
        "now" to fn("now") { _, _, _ -> JsNumber((System.nanoTime() - startNanos) / 1_000_000.0) },
        "timeOrigin" to JsNumber(System.currentTimeMillis().toDouble()),
        "mark" to noop("mark"), "measure" to noop("measure"), "clearMarks" to noop("clearMarks"), "clearMeasures" to noop("clearMeasures"),
        "getEntries" to fn("getEntries") { _, _, _ -> JsArray() },
        "getEntriesByType" to fn("getEntriesByType", 1) { _, _, _ -> JsArray() },
        "getEntriesByName" to fn("getEntriesByName", 1) { _, _, _ -> JsArray() },
        "timing" to obj("navigationStart" to JsNumber(System.currentTimeMillis().toDouble())),
        "navigation" to obj("type" to JsNumber(0.0))
    ))
    if (!env.has("navigator")) global("navigator", JsObject())
    (env.get("navigator") as? JsObject)?.let { nav ->
        fun put(k: String, v: JsValue) { if (!nav.has(k)) nav.set(k, v) }
        put("userAgent", JsString(MOBILE_USER_AGENT))
        put("appVersion", JsString(MOBILE_USER_AGENT.removePrefix("Mozilla/")))
        put("appName", JsString("Netscape"))
        put("appCodeName", JsString("Mozilla"))
        put("product", JsString("Gecko"))
        put("vendor", JsString("Google Inc."))
        put("platform", JsString("Linux armv81"))
        put("language", JsString("en-US"))
        put("languages", JsArray(mutableListOf(JsString("en-US"), JsString("en"))))
        put("cookieEnabled", JsBoolean(true))
        put("onLine", JsBoolean(true))
        put("doNotTrack", JsNull)
        put("hardwareConcurrency", JsNumber(8.0))
        put("maxTouchPoints", JsNumber(5.0))
        put("deviceMemory", JsNumber(8.0))
        put("webdriver", JsBoolean(false))
        put("pdfViewerEnabled", JsBoolean(false))
        put("sendBeacon", fn("sendBeacon", 2) { _, _, _ -> JsBoolean(true) })
        put("javaEnabled", fn("javaEnabled") { _, _, _ -> JsBoolean(false) })
        put("vibrate", fn("vibrate", 1) { _, _, _ -> JsBoolean(false) })
        put("share", fn("share", 1) { _, _, _ -> JsPromise().also { it.reject(JsString("NotAllowedError")) } })
        put("connection", obj("effectiveType" to JsString("4g"), "downlink" to JsNumber(10.0), "rtt" to JsNumber(50.0), "saveData" to JsBoolean(false)))
        put("permissions", obj("query" to fn("query", 1) { _, _, _ -> resolved(obj("state" to JsString("prompt"))) }))
        put("mediaDevices", obj("enumerateDevices" to fn("enumerateDevices") { _, _, _ -> resolved(JsArray()) }))
        put("clipboard", obj("writeText" to fn("writeText", 1) { _, _, _ -> resolved(JsUndefined) }, "readText" to fn("readText") { _, _, _ -> resolved(JsString("")) }))
        put("storage", obj("estimate" to fn("estimate") { _, _, _ -> resolved(obj("quota" to JsNumber(1e9), "usage" to JsNumber(0.0))) }))
        put("userActivation", obj("isActive" to JsBoolean(false), "hasBeenActive" to JsBoolean(false)))
    }
    fun viewportValues() {
        window.set("innerWidth", JsNumber(scriptViewportWidth))
        window.set("innerHeight", JsNumber(scriptViewportHeight))
        window.set("outerWidth", JsNumber(scriptViewportWidth))
        window.set("outerHeight", JsNumber(scriptViewportHeight))
    }
    viewportValues()
    global("devicePixelRatio", JsNumber(2.625))
    global("screen", obj(
        "width" to JsNumber(scriptViewportWidth), "height" to JsNumber(scriptViewportHeight),
        "availWidth" to JsNumber(scriptViewportWidth), "availHeight" to JsNumber(scriptViewportHeight),
        "colorDepth" to JsNumber(24.0), "pixelDepth" to JsNumber(24.0),
        "orientation" to obj("type" to JsString("portrait-primary"), "angle" to JsNumber(0.0), "addEventListener" to noop("addEventListener"))
    ))
    for (k in listOf("scrollX", "scrollY", "pageXOffset", "pageYOffset", "screenX", "screenY", "screenLeft", "screenTop")) global(k, JsNumber(0.0))
    for (k in listOf("scrollTo", "scrollBy", "scroll", "focus", "blur", "print", "stop", "moveTo", "resizeTo")) global(k, noop(k))
    global("isSecureContext", JsBoolean(true))
    global("origin", JsString(""))
    global("name", JsString(""))
    global("closed", JsBoolean(false))
    global("opener", JsNull)
    global("frameElement", JsNull)
    global("length", JsNumber(0.0))
    global("open", fn("open", 2) { _, _, _ -> JsNull })
    global("close", noop("close"))
    global("confirm", fn("confirm", 1) { _, _, _ -> JsBoolean(false) })
    global("prompt", fn("prompt", 2) { _, _, _ -> JsNull })
    global("getSelection", fn("getSelection") { _, _, _ ->
        obj("toString" to fn("toString") { _, _, _ -> JsString("") }, "rangeCount" to JsNumber(0.0), "removeAllRanges" to noop("removeAllRanges"), "addRange" to noop("addRange"))
    })

    global("matchMedia", fn("matchMedia", 1) { _, _, a ->
        val query = str(a, 0)
        val matches = evaluateMediaQuery(query)
        obj(
            "matches" to JsBoolean(matches), "media" to JsString(query), "onchange" to JsNull,
            "addListener" to noop("addListener"), "removeListener" to noop("removeListener"),
            "addEventListener" to noop("addEventListener"), "removeEventListener" to noop("removeEventListener")
        )
    })
    global("getComputedStyle", fn("getComputedStyle", 2) { _, _, a ->
        val el = arg(a, 0) as? DomElement ?: return@fn JsObject()
        ComputedStyle(el.node.style)
    })

    // IntersectionObserver: nothing here knows what's on screen, so every observed element is
    // reported as visible once page scripts finish. Lazy-loading code then loads its images.
    global("IntersectionObserver", fn("IntersectionObserver", 2) { _, thisArg, a ->
        val callback = arg(a, 0) as? JsFunction
        val observer = thisArg as? JsObject ?: JsObject()
        val observed = ArrayList<JsValue>()
        observer.set("observe", fn("observe", 1) { _, _, oa ->
            val target = arg(oa, 0)
            observed.add(target)
            if (callback != null) bridge.pendingCallbacks.add { interp ->
                if (observed.any { it === target }) {
                    val entry = obj(
                        "isIntersecting" to JsBoolean(true), "intersectionRatio" to JsNumber(1.0), "target" to target,
                        "boundingClientRect" to emptyRect(), "intersectionRect" to emptyRect(), "rootBounds" to JsNull,
                        "time" to JsNumber((System.nanoTime() - startNanos) / 1_000_000.0)
                    )
                    callback.call(interp, observer, listOf(JsArray(mutableListOf(entry)), observer))
                }
            }
            JsUndefined
        })
        observer.set("unobserve", fn("unobserve", 1) { _, _, oa -> observed.removeAll { it === arg(oa, 0) }; JsUndefined })
        observer.set("disconnect", fn("disconnect") { _, _, _ -> observed.clear(); JsUndefined })
        observer.set("takeRecords", fn("takeRecords") { _, _, _ -> JsArray() })
        observer
    })
    for (name in listOf("ResizeObserver", "PerformanceObserver", "ReportingObserver")) {
        global(name, fn(name, 1) { _, thisArg, _ ->
            (thisArg as? JsObject ?: JsObject()).also { o ->
                for (m in listOf("observe", "unobserve", "disconnect")) o.set(m, noop(m))
                o.set("takeRecords", fn("takeRecords") { _, _, _ -> JsArray() })
            }
        })
    }
    (env.get("PerformanceObserver") as JsObject).set("supportedEntryTypes", JsArray())

    // Event constructors.
    fun eventCtor(name: String) = fn(name, 2) { _, _, a ->
        JsEvent(str(a, 0), JsNull).also { ev ->
            (arg(a, 1) as? JsObject)?.let { init -> for (k in init.ownKeys()) ev.set(k, init.get(k)) }
            if (!ev.has("detail")) ev.set("detail", JsNull)
        }
    }
    for (name in listOf("Event", "CustomEvent", "UIEvent", "MouseEvent", "KeyboardEvent", "FocusEvent", "InputEvent",
            "PointerEvent", "TouchEvent", "WheelEvent", "MessageEvent", "ErrorEvent", "PopStateEvent", "HashChangeEvent", "StorageEvent", "ProgressEvent")) {
        global(name, eventCtor(name))
    }
    global("EventTarget", fn("EventTarget") { _, thisArg, _ ->
        (thisArg as? JsObject ?: JsObject()).also { target ->
            val set = ListenerSet()
            for ((k, v) in set.methods(target) { ev, i -> set.run(eventType(ev), i, target, ev) }) target.set(k, v)
        }
    })

    // DOM interface constructors, for `instanceof` checks and code that extends HTMLElement.
    fun iface(name: String, proto: JsObject) = fn(name) { _, _, _ -> JsUndefined }.also { ctor ->
        ctor.properties["prototype"] = proto
        proto.properties["constructor"] = ctor
        global(name, ctor)
    }
    iface("Node", bridge.nodeProto).also { n ->
        for ((k, v) in listOf("ELEMENT_NODE" to 1, "TEXT_NODE" to 3, "COMMENT_NODE" to 8, "DOCUMENT_NODE" to 9, "DOCUMENT_FRAGMENT_NODE" to 11)) n.set(k, JsNumber(v.toDouble()))
    }
    iface("Element", bridge.elementProto)
    iface("HTMLElement", bridge.htmlElementProto)
    iface("Text", bridge.textProto)
    iface("Document", bridge.documentProto)
    iface("HTMLDocument", bridge.documentProto)
    for (name in listOf("HTMLDivElement", "HTMLAnchorElement", "HTMLImageElement", "HTMLInputElement", "HTMLFormElement",
            "HTMLButtonElement", "HTMLScriptElement", "HTMLIFrameElement", "HTMLVideoElement", "HTMLMediaElement", "HTMLCanvasElement",
            "HTMLSelectElement", "HTMLTextAreaElement", "HTMLTemplateElement", "HTMLStyleElement", "HTMLLinkElement", "SVGElement",
            "HTMLSpanElement", "HTMLUnknownElement", "DocumentFragment", "CharacterData", "Comment", "ShadowRoot")) {
        iface(name, JsObject().also { it.proto = bridge.htmlElementProto })
    }
    iface("Window", JsObject())
    val customElements = HashMap<String, JsValue>()
    global("customElements", obj(
        "define" to fn("define", 2) { _, _, a -> customElements[str(a, 0)] = arg(a, 1); JsUndefined },
        "get" to fn("get", 1) { _, _, a -> customElements[str(a, 0)] ?: JsUndefined },
        "whenDefined" to fn("whenDefined", 1) { _, _, a -> resolved(customElements[str(a, 0)] ?: JsUndefined) },
        "upgrade" to noop("upgrade")
    ))

    global("URL", fn("URL", 2) { _, _, a -> makeUrl(str(a, 0), a.getOrNull(1)?.takeIf { it != JsUndefined }?.let { toJsString(it) }) }.also { ctor ->
        ctor.set("createObjectURL", fn("createObjectURL", 1) { _, _, _ -> JsString("blob:null/" + UUID.randomUUID()) })
        ctor.set("revokeObjectURL", noop("revokeObjectURL"))
        ctor.set("canParse", fn("canParse", 2) { _, _, a -> JsBoolean(runCatching { makeUrl(str(a, 0), a.getOrNull(1)?.takeIf { it != JsUndefined }?.let { toJsString(it) }) }.isSuccess) })
    })
    global("URLSearchParams", fn("URLSearchParams", 1) { _, _, a -> makeSearchParams(arg(a, 0)) })

    global("TextEncoder", fn("TextEncoder") { _, _, _ ->
        obj("encoding" to JsString("utf-8"), "encode" to fn("encode", 1) { _, _, a -> bytesToUint8(str(a, 0).toByteArray(Charsets.UTF_8)) })
    })
    global("TextDecoder", fn("TextDecoder", 1) { _, _, _ ->
        obj("encoding" to JsString("utf-8"), "decode" to fn("decode", 1) { _, _, a ->
            val v = arg(a, 0)
            val bytes = arrayLikeToList(if (v is JsTypedArray) JsArray(MutableList(v.length) { i -> v.get(i.toString()) }) else v).map { toNumber(it).toInt().toByte() }
            JsString(String(bytes.toByteArray(), Charsets.UTF_8))
        })
    })
    global("crypto", obj(
        "getRandomValues" to fn("getRandomValues", 1) { _, _, a ->
            val target = arg(a, 0)
            if (target is JsObject) {
                val len = toNumber(target.get("length")).toInt().coerceAtLeast(0)
                for (i in 0 until len) target.set(i.toString(), JsNumber((random.nextInt() and 0xff).toDouble()))
            }
            target
        },
        "randomUUID" to fn("randomUUID") { _, _, _ -> JsString(UUID.randomUUID().toString()) },
        "subtle" to JsObject()
    ))
    global("AbortController", fn("AbortController") { _, _, _ ->
        val signal = obj("aborted" to JsBoolean(false), "reason" to JsUndefined, "onabort" to JsNull)
        val listeners = ListenerSet()
        for ((k, v) in listeners.methods(signal) { ev, i -> listeners.run(eventType(ev), i, signal, ev) }) signal.set(k, v)
        signal.set("throwIfAborted", noop("throwIfAborted"))
        obj("signal" to signal, "abort" to fn("abort", 1) { i, _, _ ->
            signal.set("aborted", JsBoolean(true))
            listeners.run("abort", i, signal, JsEvent("abort", signal))
            JsUndefined
        })
    })
    global("Headers", fn("Headers", 1) { _, _, a -> makeHeaders(arg(a, 0)) })
    global("FormData", fn("FormData", 1) { _, _, _ -> makeFormData() })
    global("Blob", fn("Blob", 2) { _, _, a ->
        val text = arrayLikeToList(arg(a, 0)).joinToString("") { toJsString(it) }
        obj("size" to JsNumber(text.length.toDouble()), "type" to JsString((arg(a, 1) as? JsObject)?.get("type")?.let { if (it == JsUndefined) "" else toJsString(it) } ?: ""),
            "text" to fn("text") { _, _, _ -> resolved(JsString(text)) })
    })
    global("Image", fn("Image", 2) { _, _, a ->
        val img = bridge.wrap(com.projectfuture.browser.html.ElementNode("img"))
        if (arg(a, 0) != JsUndefined) img.set("width", arg(a, 0))
        img
    })
    global("requestIdleCallback", fn("requestIdleCallback", 2) { _, _, a ->
        (arg(a, 0) as? JsFunction)?.let { cb ->
            bridge.pendingCallbacks.add { interp -> cb.call(interp, JsUndefined, listOf(obj("didTimeout" to JsBoolean(false), "timeRemaining" to fn("timeRemaining") { _, _, _ -> JsNumber(0.0) }))) }
        }
        JsNumber(1.0)
    })
    global("cancelIdleCallback", noop("cancelIdleCallback"))
    global("Intl", makeIntl())
    global("CSS", obj(
        "supports" to fn("supports", 2) { _, _, _ -> JsBoolean(true) },
        "escape" to fn("escape", 1) { _, _, a -> JsString(str(a, 0).replace(Regex("([^a-zA-Z0-9_-])"), "\\\\$1")) }
    ))
}

/** `matchMedia`: width features use the engine's @media evaluator; user preferences report light mode and no reduced motion. */
private fun evaluateMediaQuery(query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty() || q == "all" || q == "screen") return true
    if ("prefers-color-scheme" in q) return "light" in q
    if ("prefers-reduced-motion" in q || "prefers-contrast" in q || "forced-colors" in q) return "no-preference" in q || "none" in q
    if ("hover: hover" in q || "hover:hover" in q || "pointer: fine" in q || "pointer:fine" in q) return false
    if ("hover: none" in q || "hover:none" in q || "pointer: coarse" in q || "pointer:coarse" in q) return true
    if ("orientation" in q) return "portrait" in q
    return CssParser.mediaQueryMatches(q, scriptViewportWidth.toFloat())
}

/** `getComputedStyle(el)`: the engine's cascaded values for that element. */
private class ComputedStyle(private val style: Map<String, String>) : JsObject() {
    override fun get(name: String): JsValue = when (name) {
        "getPropertyValue" -> NativeFunction("getPropertyValue", 1) { _, _, a -> JsString(style[toJsString(a.getOrElse(0) { JsUndefined })] ?: "") }
        "length" -> JsNumber(style.size.toDouble())
        else -> JsString(style[name.replace(Regex("[A-Z]")) { "-" + it.value.lowercase() }] ?: "")
    }
}

private fun makeUrl(input: String, base: String?): JsObject {
    val url = try {
        if (base != null) Url.parse(base).resolve(input) else Url.parse(input.takeIf { it.contains("://") } ?: throw IllegalArgumentException())
    } catch (_: Exception) {
        throw com.projectfuture.browser.js.jsError("Failed to construct 'URL': Invalid URL", "TypeError")
    }
    val path = url.path
    val beforeHash = path.substringBefore('#')
    val defaultPort = if (url.scheme == "https") 443 else 80
    val host = if (url.port == defaultPort) url.host else "${url.host}:${url.port}"
    val search = if ('?' in beforeHash) "?" + beforeHash.substringAfter('?') else ""
    return obj(
        "href" to JsString(url.toString()), "protocol" to JsString(url.scheme + ":"), "host" to JsString(host),
        "hostname" to JsString(url.host), "port" to JsString(if (url.port == defaultPort) "" else url.port.toString()),
        "pathname" to JsString(beforeHash.substringBefore('?')), "search" to JsString(search),
        "hash" to JsString(if ('#' in path) "#" + path.substringAfter('#') else ""),
        "origin" to JsString("${url.scheme}://$host"), "username" to JsString(""), "password" to JsString(""),
        "searchParams" to makeSearchParams(JsString(search)),
        "toString" to NativeFunction("toString") { _, _, _ -> JsString(url.toString()) },
        "toJSON" to NativeFunction("toJSON") { _, _, _ -> JsString(url.toString()) }
    )
}

private fun makeSearchParams(init: JsValue): JsObject {
    val pairs = ArrayList<Pair<String, String>>()
    fun decode(s: String) = try { java.net.URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }
    fun encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
    when (init) {
        is JsString -> init.value.removePrefix("?").split('&').filter { it.isNotEmpty() }.forEach {
            pairs.add(decode(it.substringBefore('=')) to decode(it.substringAfter('=', "")))
        }
        is JsArray -> init.elements.forEach { e -> val p = arrayLikeToList(e); pairs.add(toJsString(p.getOrElse(0) { JsUndefined }) to toJsString(p.getOrElse(1) { JsUndefined })) }
        is JsObject -> init.ownKeys().forEach { pairs.add(it to toJsString(init.get(it))) }
        else -> {}
    }
    val o = JsObject()
    o.set("get", NativeFunction("get", 1) { _, _, a -> pairs.firstOrNull { it.first == str(a, 0) }?.let { JsString(it.second) } ?: JsNull })
    o.set("getAll", NativeFunction("getAll", 1) { _, _, a -> JsArray(pairs.filter { it.first == str(a, 0) }.map { JsString(it.second) as JsValue }.toMutableList()) })
    o.set("has", NativeFunction("has", 1) { _, _, a -> JsBoolean(pairs.any { it.first == str(a, 0) }) })
    o.set("set", NativeFunction("set", 2) { _, _, a -> pairs.removeAll { it.first == str(a, 0) }; pairs.add(str(a, 0) to str(a, 1)); JsUndefined })
    o.set("append", NativeFunction("append", 2) { _, _, a -> pairs.add(str(a, 0) to str(a, 1)); JsUndefined })
    o.set("delete", NativeFunction("delete", 1) { _, _, a -> pairs.removeAll { it.first == str(a, 0) }; JsUndefined })
    o.set("forEach", NativeFunction("forEach", 1) { i, _, a -> (arg(a, 0) as? JsFunction)?.let { f -> pairs.toList().forEach { (k, v) -> f.call(i, JsUndefined, listOf(JsString(v), JsString(k), o)) } }; JsUndefined })
    o.set("entries", NativeFunction("entries") { _, _, _ -> JsArray(pairs.map { JsArray(mutableListOf(JsString(it.first), JsString(it.second))) as JsValue }.toMutableList()) })
    o.set("keys", NativeFunction("keys") { _, _, _ -> JsArray(pairs.map { JsString(it.first) as JsValue }.toMutableList()) })
    o.set("values", NativeFunction("values") { _, _, _ -> JsArray(pairs.map { JsString(it.second) as JsValue }.toMutableList()) })
    o.set("toString", NativeFunction("toString") { _, _, _ -> JsString(pairs.joinToString("&") { encode(it.first) + "=" + encode(it.second) }) })
    return o
}

private fun makeHeaders(init: JsValue): JsObject {
    val map = LinkedHashMap<String, String>()
    (init as? JsObject)?.let { o -> for (k in o.ownKeys()) map[k.lowercase()] = toJsString(o.get(k)) }
    return obj(
        "get" to NativeFunction("get", 1) { _, _, a -> map[str(a, 0).lowercase()]?.let { JsString(it) } ?: JsNull },
        "has" to NativeFunction("has", 1) { _, _, a -> JsBoolean(map.containsKey(str(a, 0).lowercase())) },
        "set" to NativeFunction("set", 2) { _, _, a -> map[str(a, 0).lowercase()] = str(a, 1); JsUndefined },
        "append" to NativeFunction("append", 2) { _, _, a -> val k = str(a, 0).lowercase(); map[k] = map[k]?.let { "$it, ${str(a, 1)}" } ?: str(a, 1); JsUndefined },
        "delete" to NativeFunction("delete", 1) { _, _, a -> map.remove(str(a, 0).lowercase()); JsUndefined },
        "forEach" to NativeFunction("forEach", 1) { i, _, a -> (arg(a, 0) as? JsFunction)?.let { f -> map.forEach { (k, v) -> f.call(i, JsUndefined, listOf(JsString(v), JsString(k))) } }; JsUndefined }
    )
}

private fun makeFormData(): JsObject {
    val pairs = ArrayList<Pair<String, JsValue>>()
    return obj(
        "append" to NativeFunction("append", 2) { _, _, a -> pairs.add(str(a, 0) to arg(a, 1)); JsUndefined },
        "set" to NativeFunction("set", 2) { _, _, a -> pairs.removeAll { it.first == str(a, 0) }; pairs.add(str(a, 0) to arg(a, 1)); JsUndefined },
        "get" to NativeFunction("get", 1) { _, _, a -> pairs.firstOrNull { it.first == str(a, 0) }?.second ?: JsNull },
        "has" to NativeFunction("has", 1) { _, _, a -> JsBoolean(pairs.any { it.first == str(a, 0) }) },
        "delete" to NativeFunction("delete", 1) { _, _, a -> pairs.removeAll { it.first == str(a, 0) }; JsUndefined },
        "entries" to NativeFunction("entries") { _, _, _ -> JsArray(pairs.map { JsArray(mutableListOf(JsString(it.first), it.second)) as JsValue }.toMutableList()) }
    )
}

private fun bytesToUint8(bytes: ByteArray): JsValue {
    val arr = makeTypedArray(TypedArrayKind.UINT8, bytes.size)
    bytes.forEachIndexed { i, b -> arr.set(i.toString(), JsNumber((b.toInt() and 0xff).toDouble())) }
    return arr
}

/** A minimal `Intl`: formatting falls back to plain, locale-neutral output. */
private fun makeIntl(): JsObject {
    fun formatter(name: String, format: (JsValue) -> String) = NativeFunction(name, 2) { _, _, _ ->
        obj(
            "format" to NativeFunction("format", 1) { _, _, a -> JsString(format(arg(a, 0))) },
            "formatToParts" to NativeFunction("formatToParts", 1) { _, _, a -> JsArray(mutableListOf(obj("type" to JsString("literal"), "value" to JsString(format(arg(a, 0)))))) },
            "resolvedOptions" to NativeFunction("resolvedOptions") { _, _, _ -> obj("locale" to JsString("en-US"), "timeZone" to JsString(java.util.TimeZone.getDefault().id)) }
        )
    }
    val number = formatter("NumberFormat") { v ->
        val d = toNumber(v)
        if (d.isFinite() && d == Math.floor(d) && Math.abs(d) < 1e15) String.format(java.util.Locale.US, "%,d", d.toLong())
        else String.format(java.util.Locale.US, "%,.2f", d)
    }
    return obj(
        "NumberFormat" to number,
        "DateTimeFormat" to formatter("DateTimeFormat") { v -> toJsString(v) },
        "RelativeTimeFormat" to formatter("RelativeTimeFormat") { v -> toJsString(v) },
        "ListFormat" to formatter("ListFormat") { v -> arrayLikeToList(v).joinToString(", ") { toJsString(it) } },
        "PluralRules" to NativeFunction("PluralRules", 2) { _, _, _ ->
            obj("select" to NativeFunction("select", 1) { _, _, a -> JsString(if (toNumber(arg(a, 0)) == 1.0) "one" else "other") })
        },
        "Collator" to NativeFunction("Collator", 2) { _, _, _ ->
            obj("compare" to NativeFunction("compare", 2) { _, _, a -> JsNumber(str(a, 0).compareTo(str(a, 1)).coerceIn(-1, 1).toDouble()) })
        },
        "getCanonicalLocales" to NativeFunction("getCanonicalLocales", 1) { _, _, _ -> JsArray(mutableListOf(JsString("en-US"))) }
    )
}
