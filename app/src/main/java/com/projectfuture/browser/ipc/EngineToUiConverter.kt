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

    /** The wire commands, plus any bitmaps seen here for the first time (send these once, as MSG_IMAGE_DATA, before or alongside the display list). */
    fun convert(commands: List<DisplayCommand>): Pair<List<WireDisplayCommand>, List<Pair<Int, Bitmap>>> {
        val newImages = ArrayList<Pair<Int, Bitmap>>()
        val wire = commands.map { cmd ->
            when (cmd) {
                is DrawText -> WireDrawText(
                    cmd.x, cmd.baselineY, cmd.text, wireStyle(cmd.style),
                    cmd.left, cmd.right, cmd.boxTop, cmd.boxBottom, cmd.fixed, elementIds.idFor(cmd.sourceElement)
                )
                is DrawRect -> WireDrawRect(cmd.left, cmd.top, cmd.right, cmd.bottom, cmd.color, cmd.fixed, elementIds.idFor(cmd.sourceElement))
                is DrawImage -> {
                    var isNew = false
                    val id = imageIds.getOrPut(cmd.bitmap) { isNew = true; nextImageId++ }
                    if (isNew) newImages.add(id to cmd.bitmap)
                    WireDrawImage(cmd.left, cmd.top, cmd.right, cmd.bottom, id, cmd.fixed, elementIds.idFor(cmd.sourceElement))
                }
                is DrawFormControl -> WireDrawFormControl(
                    cmd.left, cmd.top, cmd.right, cmd.bottom, cmd.controlType.ordinal, cmd.value, cmd.checked,
                    cmd.placeholder, wireStyle(cmd.style), cmd.fixed, elementIds.idFor(cmd.sourceElement)
                )
            }
        }
        return wire to newImages
    }

    private fun wireStyle(style: TextStyle) = WireTextStyle(
        style.sizePx, style.bold, style.italic, style.monospace, style.color,
        style.underline, style.strikethrough, style.linkHref, style.fontFamilyName
    )
}
