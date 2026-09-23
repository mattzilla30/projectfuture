package com.projectfuture.browser.css

/**
 * A deliberately narrow slice of CSS `transform`: only `translate()`/
 * `translateX()`/`translateY()`, resolved to a plain pixel offset that
 * LayoutBox folds into an element's position exactly like `position:
 * relative`'s offset - no new rendering machinery needed.
 *
 * NOT implemented: `scale()`/`rotate()`/`skew()`/matrix() - these need
 * non-axis-aligned rendering (a rotated/scaled rect isn't representable by
 * this engine's DrawRect/DrawText's plain left/top/right/bottom fields)
 * or a real transform-matrix paint path, neither of which exist here.
 * Also NOT implemented: `transition`/`@keyframes` animation - interpolating
 * a property over time needs a continuous frame-driven render loop (e.g.
 * a Choreographer callback re-running style/layout/paint every frame),
 * which is a fundamentally different architecture from this engine's
 * current one-shot navigate -> fetch -> parse -> style -> layout -> paint
 * pipeline. Adding real animation support means building that loop first,
 * not just parsing more CSS.
 */
data class TransformOffset(val dx: Float, val dy: Float)

private val TRANSFORM_FUNCTION = Regex("([a-zA-Z]+)\\(([^)]*)\\)")

fun resolveTranslate(transformValue: String?, refWidth: Float, refHeight: Float?, fontSizePx: Float): TransformOffset {
    if (transformValue.isNullOrBlank() || transformValue.trim() == "none") return TransformOffset(0f, 0f)
    var dx = 0f
    var dy = 0f
    for (match in TRANSFORM_FUNCTION.findAll(transformValue)) {
        val name = match.groupValues[1].lowercase()
        val args = match.groupValues[2].split(',').map { it.trim() }
        when (name) {
            "translate" -> {
                dx += component(args.getOrNull(0), refWidth, fontSizePx)
                dy += component(args.getOrNull(1), refHeight ?: 0f, fontSizePx)
            }
            "translatex" -> dx += component(args.getOrNull(0), refWidth, fontSizePx)
            "translatey" -> dy += component(args.getOrNull(0), refHeight ?: 0f, fontSizePx)
        }
    }
    return TransformOffset(dx, dy)
}

private fun component(raw: String?, refSize: Float, fontSizePx: Float): Float {
    if (raw.isNullOrBlank()) return 0f
    return lengthValue(raw, refSize, fontSizePx) ?: 0f
}
