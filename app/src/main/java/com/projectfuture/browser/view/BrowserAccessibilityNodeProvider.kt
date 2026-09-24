package com.projectfuture.browser.view

import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import com.projectfuture.browser.accessibility.AccessibilityRole
import com.projectfuture.browser.accessibility.AccessibilityTree
import com.projectfuture.browser.accessibility.AccessibleNode
import com.projectfuture.browser.accessibility.NodeBounds

private const val TAG = "A11yNodeProvider"

/**
 * NOT VERIFIED: this class has not been exercised against real TalkBack on
 * a device or emulator - there is none available in this environment. It is
 * implemented carefully and defensively (see the safety notes below and in
 * AccessibilityTreeBuilder.kt, which supplies the [AccessibleNode] list this
 * class turns into real `AccessibilityNodeInfo` objects and which DOES have
 * automated JVM unit test coverage), but "implemented and unit-tested" is
 * the honest claim here, not "works" - the true test, real TalkBack
 * end-to-end, remains this feature's top open verification risk. See
 * README.md's accessibility section and MainActivity's onTabStateChanged
 * for the same note at the call sites that matter to a reader deciding
 * whether to rely on this.
 *
 * Bridges the pure, unit-tested [AccessibilityTree] onto Android's virtual-
 * view [AccessibilityNodeProvider] API, so a screen reader can:
 *  - move focus between headings/links/form controls/images/landmarks with
 *    swipe gestures (TalkBack's own linear next/previous traversal, driven
 *    by this provider's ordered child list - no custom gesture handling
 *    needed here, the platform does it once the tree is correctly shaped);
 *  - activate the focused one with a double-tap, which the platform turns
 *    into `ACTION_CLICK` on that virtual node (see [performAction]).
 *
 * Safety-motivated design choices - this is exactly the kind of code where
 * a subtle bug can hang or crash TalkBack for the people depending on it,
 * so it stays conservative on purpose:
 *
 * - FLAT hierarchy: every [AccessibleNode] is reported as a direct child of
 *   [AccessibilityNodeProvider.HOST_VIEW_ID], matching the flat shape
 *   [AccessibilityTree] already has (see its class doc) - never nested, so
 *   there's no way to produce a wrong-parent, orphaned, or cyclic subtree.
 * - Real child Views are preserved: BrowserView also hosts genuine overlaid
 *   `EditText` views for text/password/textarea fields (see its class doc),
 *   which TalkBack already reads/operates correctly for free because
 *   they're real platform widgets. Replacing `getAccessibilityNodeProvider`
 *   wholesale would hide them - the framework stops walking a view's real
 *   children once it returns a non-null provider for it - so [rootNode]
 *   explicitly re-adds every real child view (`hostView.getChildAt(i)`)
 *   alongside the virtual nodes. Losing that would be a regression, not
 *   just a missed opportunity.
 * - Every entry point that talks to the Android accessibility framework
 *   ([createAccessibilityNodeInfo], [performAction]) is wrapped so a bug in
 *   this new, unverified code can only fail to add an accessibility node -
 *   it must never crash the view or the app. A screen-reader gap is a worse
 *   outcome than the pre-existing one only if it takes the app down with
 *   it; failing closed (return null / false) keeps the failure bounded to
 *   "TalkBack doesn't see this", which is the same gap this code is meant
 *   to close, not a new, worse one.
 * - The tree is rebuilt and swapped in wholesale on every [updateTree] call
 *   (from BrowserView.setContent) rather than patched incrementally, so
 *   there's no long-lived, easy-to-desync provider-side state.
 */
class BrowserAccessibilityNodeProvider(
    private val hostView: BrowserView
) : AccessibilityNodeProvider() {

    private var tree: AccessibilityTree = AccessibilityTree(emptyList())

    /** Page-space -> screen-space: subtract the current scroll offset (see BrowserView.onDraw). */
    var scrollYProvider: () -> Float = { 0f }

    private var focusedVirtualId: Int = HOST_VIEW_ID

    companion object {
        // Aliased explicitly rather than relying on Kotlin resolving the inherited Java static
        // constant unqualified - safer to be unambiguous in code the accessibility framework calls into.
        private const val HOST_VIEW_ID = AccessibilityNodeProvider.HOST_VIEW_ID
    }

    fun updateTree(newTree: AccessibilityTree) {
        if (focusedVirtualId != HOST_VIEW_ID && newTree.nodeAt(focusedVirtualId) == null) {
            focusedVirtualId = HOST_VIEW_ID
        }
        tree = newTree
    }

    override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? = try {
        if (virtualViewId == HOST_VIEW_ID) rootNode() else childNode(virtualViewId)
    } catch (t: Throwable) {
        // See class doc: a bug here must never surface as a crash - only as a missing node.
        Log.w(TAG, "createAccessibilityNodeInfo($virtualViewId) failed", t)
        null
    }

    private fun rootNode(): AccessibilityNodeInfo {
        val info = AccessibilityNodeInfo.obtain(hostView)
        hostView.onInitializeAccessibilityNodeInfo(info)
        // Real overlaid form-control views first, unaffected by the virtual layer below them.
        for (i in 0 until hostView.childCount) {
            info.addChild(hostView.getChildAt(i))
        }
        for (node in tree.nodes) {
            info.addChild(hostView, node.id)
        }
        return info
    }

    private fun childNode(virtualViewId: Int): AccessibilityNodeInfo? {
        val node = tree.nodeAt(virtualViewId) ?: return null
        val info = AccessibilityNodeInfo.obtain()
        info.setSource(hostView, virtualViewId)
        info.setParent(hostView)
        info.packageName = hostView.context.packageName
        info.className = classNameFor(node.role)
        info.contentDescription = describe(node)
        info.isEnabled = true
        info.isVisibleToUser = true
        info.isFocusable = true
        info.isClickable = node.clickable
        info.isCheckable = node.role == AccessibilityRole.CHECKBOX || node.role == AccessibilityRole.RADIO
        if (info.isCheckable) info.isChecked = node.checked == true
        info.isAccessibilityFocused = virtualViewId == focusedVirtualId

        val screenRect = toScreenRect(node.bounds)
        info.setBoundsInParent(screenRect)
        val hostLocation = IntArray(2)
        hostView.getLocationOnScreen(hostLocation)
        info.setBoundsInScreen(Rect(screenRect).apply { offset(hostLocation[0], hostLocation[1]) })

        if (node.clickable) info.addAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (virtualViewId == focusedVirtualId) {
            info.addAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
        } else {
            info.addAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        }
        return info
    }

    /** Heading level and link/landmark hints are spoken as part of the description, not just the raw label. */
    private fun describe(node: AccessibleNode): String {
        val prefix = when (node.role) {
            AccessibilityRole.HEADING -> "Heading level ${node.headingLevel ?: 2}: "
            AccessibilityRole.LINK -> "Link: "
            AccessibilityRole.LANDMARK -> ""
            else -> ""
        }
        return prefix + node.label
    }

    private fun toScreenRect(bounds: NodeBounds): Rect {
        val scrollY = scrollYProvider()
        return Rect(
            bounds.left.toInt(),
            (bounds.top - scrollY).toInt(),
            bounds.right.toInt(),
            (bounds.bottom - scrollY).toInt()
        )
    }

    override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean = try {
        when (action) {
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                if (tree.nodeAt(virtualViewId) != null && focusedVirtualId != virtualViewId) {
                    focusedVirtualId = virtualViewId
                    sendVirtualEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                }
                true
            }
            AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                if (focusedVirtualId == virtualViewId) {
                    focusedVirtualId = HOST_VIEW_ID
                    sendVirtualEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                }
                true
            }
            // Double-tap on the focused node is delivered by TalkBack as ACTION_CLICK.
            AccessibilityNodeInfo.ACTION_CLICK -> {
                val node = tree.nodeAt(virtualViewId)
                node != null && hostView.activateAccessibilityNode(node.sourceElement)
            }
            else -> false
        }
    } catch (t: Throwable) {
        Log.w(TAG, "performAction($virtualViewId, $action) failed", t)
        false
    }

    override fun findFocus(focus: Int): AccessibilityNodeInfo? = try {
        if (focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY && focusedVirtualId != HOST_VIEW_ID) {
            createAccessibilityNodeInfo(focusedVirtualId)
        } else {
            null
        }
    } catch (t: Throwable) {
        Log.w(TAG, "findFocus($focus) failed", t)
        null
    }

    private fun sendVirtualEvent(virtualViewId: Int, eventType: Int) {
        val parent = hostView.parent ?: return
        val event = AccessibilityEvent.obtain(eventType)
        event.packageName = hostView.context.packageName
        event.className = hostView.javaClass.name
        event.setSource(hostView, virtualViewId)
        parent.requestSendAccessibilityEvent(hostView, event)
    }

    private fun classNameFor(role: AccessibilityRole): String = when (role) {
        AccessibilityRole.LINK -> "android.widget.TextView"
        AccessibilityRole.BUTTON -> "android.widget.Button"
        AccessibilityRole.CHECKBOX -> "android.widget.CheckBox"
        AccessibilityRole.RADIO -> "android.widget.RadioButton"
        AccessibilityRole.TEXT_FIELD -> "android.widget.EditText"
        AccessibilityRole.COMBO_BOX -> "android.widget.Spinner"
        AccessibilityRole.IMAGE -> "android.widget.ImageView"
        AccessibilityRole.HEADING, AccessibilityRole.LANDMARK, AccessibilityRole.LIST,
        AccessibilityRole.LIST_ITEM, AccessibilityRole.GENERIC -> "android.view.View"
    }
}
