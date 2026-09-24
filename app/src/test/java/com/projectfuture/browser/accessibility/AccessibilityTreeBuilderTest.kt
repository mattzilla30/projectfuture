package com.projectfuture.browser.accessibility

import android.graphics.Color
import com.projectfuture.browser.html.ElementNode
import com.projectfuture.browser.html.TextNode
import com.projectfuture.browser.layout.DisplayCommand
import com.projectfuture.browser.layout.DrawRect
import com.projectfuture.browser.layout.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [buildAccessibilityTree] - the one piece of the
 * TalkBack-navigation work that can actually be automated here (see its
 * class doc): there is no device/emulator in this environment to verify
 * real screen-reader behavior, so these tests instead pin down the pure
 * DOM-plus-layout -> accessibility-node-tree logic that
 * BrowserAccessibilityNodeProvider is built on. They intentionally use
 * [DrawRect] (rather than [com.projectfuture.browser.layout.DrawImage] or
 * [com.projectfuture.browser.layout.DrawText]) as a stand-in "painted
 * region" for every element, including images - buildAccessibilityTree
 * only ever reads a DisplayCommand's abstract left/top/right/bottom/
 * sourceElement, never its concrete subtype, so this is a faithful,
 * dependency-free way to simulate arbitrary layout output.
 */
class AccessibilityTreeBuilderTest {

    private fun rect(l: Float, t: Float, r: Float, b: Float, source: ElementNode) =
        DrawRect(l, t, r, b, Color.BLACK, sourceElement = source)

    private fun textStyle() = TextStyle(16f, false, false, false, Color.BLACK, false, false, null)

    private fun child(parent: ElementNode, tag: String, attrs: Map<String, String> = emptyMap()): ElementNode {
        val el = ElementNode(tag, attrs.toMutableMap(), parent)
        parent.children.add(el)
        return el
    }

    private fun text(parent: ElementNode, value: String) {
        parent.children.add(TextNode(value, parent))
    }

    @Test fun classifiesHeadingsLinksImagesAndFormControlsInDocumentOrder() {
        val root = ElementNode("body")
        val h1 = child(root, "h1")
        text(h1, "Welcome")
        // A plain <div> wrapper (not <nav>/<main>/etc.) so this test isolates heading/link/image/
        // form-control classification without a landmark node also appearing - see the dedicated
        // landmarksGetReadableLabelsAndAreNotClickable test for that.
        val wrapper = child(root, "div")
        val link = child(wrapper, "a", mapOf("href" to "/about"))
        text(link, "About")
        val img = child(root, "img", mapOf("alt" to "Site logo"))
        val decorativeImg = child(root, "img", mapOf("alt" to ""))
        val missingAltImg = child(root, "img")
        val checkbox = child(root, "input", mapOf("type" to "checkbox", "checked" to "checked"))

        val commands: List<DisplayCommand> = listOf(
            rect(0f, 0f, 200f, 30f, h1),
            rect(0f, 30f, 100f, 60f, link),
            rect(0f, 60f, 50f, 110f, img),
            rect(0f, 110f, 50f, 160f, decorativeImg),
            rect(0f, 160f, 50f, 210f, missingAltImg),
            rect(0f, 210f, 30f, 240f, checkbox)
        )

        val tree = buildAccessibilityTree(root, commands)

        // Decorative (alt="") and un-alt'd images are both excluded - see class doc.
        assertEquals(listOf(h1, link, img, checkbox), tree.nodes.map { it.sourceElement })
        assertEquals(
            listOf(AccessibilityRole.HEADING, AccessibilityRole.LINK, AccessibilityRole.IMAGE, AccessibilityRole.CHECKBOX),
            tree.nodes.map { it.role }
        )

        val headingNode = tree.nodes[0]
        assertEquals("Welcome", headingNode.label)
        assertEquals(1, headingNode.headingLevel)
        assertEquals(NodeBounds(0f, 0f, 200f, 30f), headingNode.bounds)
        assertFalse(headingNode.clickable)

        val linkNode = tree.nodes[1]
        assertEquals("About", linkNode.label)
        assertTrue(linkNode.clickable)

        val imgNode = tree.nodes[2]
        assertEquals("Site logo", imgNode.label)

        val checkboxNode = tree.nodes[3]
        assertEquals(true, checkboxNode.checked)
        assertTrue(checkboxNode.clickable)

        // ids double as the flat traversal order (see AccessibleNode.id's doc).
        assertEquals(listOf(0, 1, 2, 3), tree.nodes.map { it.id })
    }

    @Test fun unionsBoundsFromDescendantElementsNotPaintedOnTheLinkItself() {
        // <a href="/x"><b>Foo</b>Bar</a> - "Foo"'s DisplayCommand.sourceElement is <b> (the nearest
        // owning element per LayoutBox's addWord), not the <a> itself; the exposed LINK node still
        // needs bounds covering both runs.
        val root = ElementNode("body")
        val a = child(root, "a", mapOf("href" to "/x"))
        val b = child(a, "b")
        text(b, "Foo")
        text(a, "Bar")

        val commands: List<DisplayCommand> = listOf(
            rect(0f, 0f, 40f, 20f, b),
            rect(40f, 0f, 80f, 20f, a)
        )

        val tree = buildAccessibilityTree(root, commands)

        assertEquals(1, tree.nodes.size)
        assertEquals(NodeBounds(0f, 0f, 80f, 20f), tree.nodes.single().bounds)
    }

    @Test fun excludesHiddenInputAndDisplayNoneElements() {
        val root = ElementNode("body")
        val hidden = child(root, "input", mapOf("type" to "hidden", "value" to "csrf"))
        val displayNone = child(root, "button")
        displayNone.style["display"] = "none"
        text(displayNone, "Invisible")
        val visibleButton = child(root, "button")
        text(visibleButton, "Submit")

        val commands: List<DisplayCommand> = listOf(
            rect(0f, 0f, 10f, 10f, hidden),
            rect(0f, 10f, 60f, 40f, displayNone),
            rect(0f, 40f, 60f, 70f, visibleButton)
        )

        val tree = buildAccessibilityTree(root, commands)

        assertEquals(listOf(visibleButton), tree.nodes.map { it.sourceElement })
        assertEquals("Submit", tree.nodes.single().label)
    }

    @Test fun excludesElementsWithNoPaintedContentAtAll() {
        val root = ElementNode("body")
        val link = child(root, "a", mapOf("href" to "/never-laid-out"))
        text(link, "Ghost link")
        // No DisplayCommand at all references `link` (e.g. never actually painted) - must not
        // appear with fabricated/zero bounds (see class doc).
        val tree = buildAccessibilityTree(root, emptyList())
        assertTrue(tree.isEmpty)
    }

    @Test fun ariaRoleAndAriaLabelOverrideNativeSemantics() {
        val root = ElementNode("body")
        val div = child(root, "div", mapOf("role" to "button", "aria-label" to "Close dialog"))
        text(div, "X")

        val tree = buildAccessibilityTree(root, listOf(rect(0f, 0f, 24f, 24f, div)))

        val node = tree.nodes.single()
        assertEquals(AccessibilityRole.BUTTON, node.role)
        assertEquals("Close dialog", node.label)
        assertTrue(node.clickable)
    }

    @Test fun landmarksGetReadableLabelsAndAreNotClickable() {
        val root = ElementNode("body")
        val nav = child(root, "nav")
        val link = child(nav, "a", mapOf("href" to "/x"))
        text(link, "Home")

        val tree = buildAccessibilityTree(root, listOf(rect(0f, 0f, 50f, 20f, link)))

        val landmarkNode = tree.nodes.firstOrNull { it.role == AccessibilityRole.LANDMARK }
        assertEquals("navigation", landmarkNode?.label)
        assertEquals(false, landmarkNode?.clickable)
        // The landmark's bounds are the union over its descendants (the link), same rule as any container.
        assertEquals(NodeBounds(0f, 0f, 50f, 20f), landmarkNode?.bounds)
    }

    @Test fun formControlLabelFallsBackToPlaceholderThenNameWhenNoText() {
        val root = ElementNode("body")
        val withPlaceholder = child(root, "input", mapOf("type" to "text", "placeholder" to "Search"))
        val withNameOnly = child(root, "input", mapOf("type" to "text", "name" to "email"))

        val tree = buildAccessibilityTree(
            root,
            listOf(rect(0f, 0f, 50f, 20f, withPlaceholder), rect(0f, 20f, 50f, 40f, withNameOnly))
        )

        assertEquals("Search", tree.nodes[0].label)
        assertEquals("email", tree.nodes[1].label)
        assertEquals(AccessibilityRole.TEXT_FIELD, tree.nodes[0].role)
    }

    @Test fun textStyleHelperCompilesAgainstAbstractDisplayCommandFields() {
        // Sanity check that the shared TextStyle used elsewhere in this test file constructs
        // cleanly with no Android framework calls beyond a plain Color int constant.
        val style = textStyle()
        assertEquals(16f, style.sizePx, 0.01f)
        assertNull(style.linkHref)
    }
}
