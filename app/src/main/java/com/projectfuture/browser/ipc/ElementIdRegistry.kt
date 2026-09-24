package com.projectfuture.browser.ipc

import com.projectfuture.browser.html.ElementNode
import java.util.IdentityHashMap

/**
 * Engine-process-only: assigns stable small integer ids to `ElementNode`s
 * for the current document's lifetime, so the UI process can name a DOM
 * node in a tap/input IPC message ([TabEngineProtocol.MSG_DISPATCH_TAP]
 * etc.) without ever holding a reference to it. [reset] on every fresh
 * document load - a stale id from a discarded document must never be
 * confused with an id in the new one.
 */
class ElementIdRegistry {
    private val idsByElement = IdentityHashMap<ElementNode, Int>()
    private val elementsById = HashMap<Int, ElementNode>()
    private var nextId = 1 // 0 is reserved for "no source element"

    fun reset() {
        idsByElement.clear()
        elementsById.clear()
        nextId = 1
    }

    fun idFor(element: ElementNode?): Int {
        if (element == null) return 0
        idsByElement[element]?.let { return it }
        val id = nextId++
        idsByElement[element] = id
        elementsById[id] = element
        return id
    }

    fun elementFor(id: Int): ElementNode? = if (id == 0) null else elementsById[id]
}
