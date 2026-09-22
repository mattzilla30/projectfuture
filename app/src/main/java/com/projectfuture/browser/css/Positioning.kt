package com.projectfuture.browser.css

/**
 * Resolved `position`/`top`/`right`/`bottom`/`left`/`z-index` for one
 * element. Percentages for top/bottom resolve against the containing
 * block's height, left/right against its width, per spec.
 */
data class PositionOffsets(
    val position: String,
    val top: Float?,
    val right: Float?,
    val bottom: Float?,
    val left: Float?,
    val zIndex: Int
)

fun resolvePositionOffsets(
    style: Map<String, String>,
    containingWidth: Float,
    containingHeight: Float,
    fontSizePx: Float
): PositionOffsets {
    fun offset(key: String, base: Float): Float? = style[key]?.let { lengthValue(it, base, fontSizePx) }
    return PositionOffsets(
        position = style["position"] ?: "static",
        top = offset("top", containingHeight),
        right = offset("right", containingWidth),
        bottom = offset("bottom", containingHeight),
        left = offset("left", containingWidth),
        zIndex = style["z-index"]?.toIntOrNull() ?: 0
    )
}
