package com.projectfuture.browser.ipc

/**
 * Cross-process twin of [com.projectfuture.browser.layout.DisplayCommand],
 * used to ship a tab's paint list out of the sandboxed engine process (see
 * [TabEngineServiceBase]) into the UI process. It deliberately holds no
 * Android or engine-internal object references - only primitives - so it
 * can be serialized with plain `java.io` streams ([DisplayListCodec]) and
 * exercised in ordinary JVM unit tests with no Android runtime involved.
 *
 * Two fields replace object references that can't cross a process
 * boundary:
 *  - [elementId] stands in for `DisplayCommand.sourceElement` (an
 *    `ElementNode` living in the engine process's DOM). 0 means "no source
 *    element". The UI process never needs the real DOM node - it only
 *    needs a stable handle to hand back on a tap/input event so the engine
 *    process can look the real element back up (see
 *    [ElementIdRegistry]).
 *  - [WireDrawImage.imageId] stands in for `DrawImage.bitmap`. Bitmaps are
 *    comparatively large and don't change every relayout, so they're sent
 *    once via a separate `MSG_IMAGE_DATA` message (see
 *    `TabEngineProtocol`) and referenced by id afterward, rather than
 *    re-encoding pixel data into every display-list message - the same
 *    "send the picture once, reference it after" idea a real compositor's
 *    shared-texture/surface handoff uses, done here with a plain id + a
 *    UI-side bitmap cache since there's no shared-memory/`SurfaceTexture`
 *    handoff implemented.
 */
sealed class WireDisplayCommand {
    abstract val left: Float
    abstract val top: Float
    abstract val right: Float
    abstract val bottom: Float
    abstract val fixed: Boolean
    abstract val elementId: Int
}

/** Wire twin of [com.projectfuture.browser.layout.TextStyle]; see its class doc for why [fontFamilyName] doesn't actually resolve to a custom glyph in the UI process. */
data class WireTextStyle(
    val sizePx: Float,
    val bold: Boolean,
    val italic: Boolean,
    val monospace: Boolean,
    val colorArgb: Int,
    val underline: Boolean,
    val strikethrough: Boolean,
    val linkHref: String?,
    val fontFamilyName: String?
)

data class WireDrawText(
    val x: Float,
    val baselineY: Float,
    val text: String,
    val style: WireTextStyle,
    override val left: Float,
    override val right: Float,
    val boxTop: Float,
    val boxBottom: Float,
    override val fixed: Boolean,
    override val elementId: Int
) : WireDisplayCommand() {
    override val top get() = boxTop
    override val bottom get() = boxBottom
}

data class WireDrawRect(
    override val left: Float,
    override val top: Float,
    override val right: Float,
    override val bottom: Float,
    val colorArgb: Int,
    override val fixed: Boolean,
    override val elementId: Int
) : WireDisplayCommand()

data class WireDrawImage(
    override val left: Float,
    override val top: Float,
    override val right: Float,
    override val bottom: Float,
    val imageId: Int,
    override val fixed: Boolean,
    override val elementId: Int
) : WireDisplayCommand()

/** Mirrors [com.projectfuture.browser.layout.FormControlType]'s ordinal - see [DisplayListCodec] for the encode/decode pairing. */
data class WireDrawFormControl(
    override val left: Float,
    override val top: Float,
    override val right: Float,
    override val bottom: Float,
    val controlType: Int,
    val value: String,
    val checked: Boolean,
    val placeholder: String,
    val style: WireTextStyle,
    override val fixed: Boolean,
    override val elementId: Int
) : WireDisplayCommand()
