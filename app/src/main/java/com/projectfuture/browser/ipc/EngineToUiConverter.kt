package com.projectfuture.browser.ipc

import android.graphics.Bitmap
import com.projectfuture.browser.layout.DisplayCommand
import com.projectfuture.browser.layout.DrawFormControl
import com.projectfuture.browser.layout.DrawImage
import com.projectfuture.browser.layout.DrawRect
import com.projectfuture.browser.layout.DrawText
import com.projectfuture.browser.layout.TextStyle
import java.util.IdentityHashMap

/**
 * Engine-process-only: turns a `Tab`'s real `DisplayCommand` list (which
 * holds live `ElementNode`/`Bitmap` references) into the flat
 * [WireDisplayCommand] form that can actually be shipped across a Binder
 * call - see that class's doc for the two reference-replacement tricks
 * ([ElementIdRegistry] ids, and image-identity caching so a bitmap is only
 * PNG-encoded and sent once no matter how many times the page re-lays-out).
 */
class EngineToUiConverter(private val elementIds: ElementIdRegistry) {
    private val imageIds = IdentityHashMap<Bitmap, Int>()
    private var nextImageId = 1
    /** Which elementIds have already had a [WireElementMeta] sent for them - each is only ever sent once, the first time it's seen (see [TabEngineProtocol.MSG_ELEMENT_META]'s doc). */
    private val metaSent = HashSet<Int>()

    /** The wire commands, plus any bitmaps and element metadata seen here for the first time (send these once - as MSG_IMAGE_DATA/MSG_ELEMENT_META - before or alongside the display list). */
    fun convert(commands: List<DisplayCommand>): Triple<List<WireDisplayCommand>, List<Pair<Int, Bitmap>>, List<WireElementMeta>> {
        val newImages = ArrayList<Pair<Int, Bitmap>>()
        val newMeta = ArrayList<WireElementMeta>()
        fun idFor(element: com.projectfuture.browser.html.ElementNode?): Int {
            val id = elementIds.idFor(element)
            if (id != 0 && metaSent.add(id) && element != null) {
                newMeta.add(WireElementMeta(id, element.tag, element.attr("type")))
            }
            return id
        }
        val wire = commands.map { cmd ->
            when (cmd) {
                is DrawText -> WireDrawText(
                    cmd.x, cmd.baselineY, cmd.text, wireStyle(cmd.style),
                    cmd.left, cmd.right, cmd.boxTop, cmd.boxBottom, cmd.fixed, idFor(cmd.sourceElement)
                )
                is DrawRect -> WireDrawRect(cmd.left, cmd.top, cmd.right, cmd.bottom, cmd.color, cmd.fixed, idFor(cmd.sourceElement))
                is DrawImage -> {
                    var isNew = false
                    val id = imageIds.getOrPut(cmd.bitmap) { isNew = true; nextImageId++ }
                    if (isNew) newImages.add(id to cmd.bitmap)
                    WireDrawImage(cmd.left, cmd.top, cmd.right, cmd.bottom, id, cmd.fixed, idFor(cmd.sourceElement))
                }
                is DrawFormControl -> WireDrawFormControl(
                    cmd.left, cmd.top, cmd.right, cmd.bottom, cmd.controlType.ordinal, cmd.value, cmd.checked,
                    cmd.placeholder, wireStyle(cmd.style), cmd.fixed, idFor(cmd.sourceElement)
                )
            }
        }
        return Triple(wire, newImages, newMeta)
    }

    /** Used outside a display-list conversion - e.g. select options / login-form fields discovered by a direct DOM lookup rather than by appearing in the paint list - to make sure their metadata still gets sent once. */
    fun metaFor(element: com.projectfuture.browser.html.ElementNode): Pair<Int, WireElementMeta?> {
        val id = elementIds.idFor(element)
        val meta = if (metaSent.add(id)) WireElementMeta(id, element.tag, element.attr("type")) else null
        return id to meta
    }

    private fun wireStyle(style: TextStyle) = WireTextStyle(
        style.sizePx, style.bold, style.italic, style.monospace, style.color,
        style.underline, style.strikethrough, style.linkHref, style.fontFamilyName
    )
}
