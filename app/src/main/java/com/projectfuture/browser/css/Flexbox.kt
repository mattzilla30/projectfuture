package com.projectfuture.browser.css

enum class FlexDirection { ROW, ROW_REVERSE, COLUMN, COLUMN_REVERSE }
enum class FlexWrapMode { NOWRAP, WRAP }
enum class JustifyContent { FLEX_START, FLEX_END, CENTER, SPACE_BETWEEN, SPACE_AROUND, SPACE_EVENLY }
enum class AlignItems { STRETCH, FLEX_START, FLEX_END, CENTER }

data class FlexContainerProps(
    val direction: FlexDirection,
    val wrap: FlexWrapMode,
    val justifyContent: JustifyContent,
    val alignItems: AlignItems,
    val rowGap: Float,
    val columnGap: Float
)

data class FlexItemProps(
    val grow: Float,
    val shrink: Float,
    /** "auto" or a length string; resolved against the main axis by the caller. */
    val basis: String,
    val order: Int,
    val alignSelf: AlignItems?
)

fun resolveFlexContainerProps(style: Map<String, String>, containingWidth: Float, fontSizePx: Float): FlexContainerProps {
    val direction = when (style["flex-direction"]) {
        "row-reverse" -> FlexDirection.ROW_REVERSE
        "column" -> FlexDirection.COLUMN
        "column-reverse" -> FlexDirection.COLUMN_REVERSE
        else -> FlexDirection.ROW
    }
    // wrap-reverse isn't distinguished from wrap - lines still stack start-to-end.
    val wrap = if (style["flex-wrap"] == "wrap" || style["flex-wrap"] == "wrap-reverse") {
        FlexWrapMode.WRAP
    } else {
        FlexWrapMode.NOWRAP
    }
    val justifyContent = when (style["justify-content"]) {
        "flex-end", "end" -> JustifyContent.FLEX_END
        "center" -> JustifyContent.CENTER
        "space-between" -> JustifyContent.SPACE_BETWEEN
        "space-around" -> JustifyContent.SPACE_AROUND
        "space-evenly" -> JustifyContent.SPACE_EVENLY
        else -> JustifyContent.FLEX_START
    }
    val alignItems = parseAlign(style["align-items"]) ?: AlignItems.STRETCH

    var rowGap = 0f
    var columnGap = 0f
    style["gap"]?.let { raw ->
        val parts = raw.trim().split(Regex("\\s+"))
        rowGap = lengthValue(parts.getOrElse(0) { "0" }, containingWidth, fontSizePx) ?: 0f
        columnGap = lengthValue(parts.getOrElse(1) { parts[0] }, containingWidth, fontSizePx) ?: rowGap
    }
    style["row-gap"]?.let { lengthValue(it, containingWidth, fontSizePx)?.let { v -> rowGap = v } }
    style["column-gap"]?.let { lengthValue(it, containingWidth, fontSizePx)?.let { v -> columnGap = v } }

    return FlexContainerProps(direction, wrap, justifyContent, alignItems, rowGap, columnGap)
}

fun resolveFlexItemProps(style: Map<String, String>): FlexItemProps {
    var grow = 0f
    var shrink = 1f
    var basis = "auto"

    style["flex"]?.let { raw ->
        when (raw.trim()) {
            "none" -> { grow = 0f; shrink = 0f; basis = "auto" }
            "auto" -> { grow = 1f; shrink = 1f; basis = "auto" }
            "initial" -> { grow = 0f; shrink = 1f; basis = "auto" }
            else -> {
                val parts = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                var idx = 0
                if (parts.getOrNull(idx)?.toFloatOrNull() != null) { grow = parts[idx].toFloat(); idx++ }
                if (parts.getOrNull(idx)?.toFloatOrNull() != null) { shrink = parts[idx].toFloat(); idx++ }
                if (idx < parts.size) basis = parts[idx]
            }
        }
    }
    style["flex-grow"]?.toFloatOrNull()?.let { grow = it }
    style["flex-shrink"]?.toFloatOrNull()?.let { shrink = it }
    style["flex-basis"]?.let { basis = it }

    return FlexItemProps(
        grow = grow,
        shrink = shrink,
        basis = basis,
        order = style["order"]?.toIntOrNull() ?: 0,
        alignSelf = parseAlign(style["align-self"])
    )
}

private fun parseAlign(raw: String?): AlignItems? = when (raw) {
    "flex-start", "start" -> AlignItems.FLEX_START
    "flex-end", "end" -> AlignItems.FLEX_END
    "center" -> AlignItems.CENTER
    "stretch" -> AlignItems.STRETCH
    else -> null
}

/**
 * Resolves grow/shrink for one flex line into final main-axis content
 * sizes, given each item's basis and margin/border/padding along the main
 * axis. Axis-agnostic: the caller maps these back to width or height.
 */
fun resolveFlexMainSizes(
    basis: List<Float>,
    grow: List<Float>,
    shrink: List<Float>,
    mainOuterExtra: List<Float>,
    availableMain: Float,
    gap: Float
): FloatArray {
    val n = basis.size
    val result = FloatArray(n)
    if (n == 0) return result
    val basisSum = basis.sum()
    val extraSum = mainOuterExtra.sum()
    val gapSum = gap * (n - 1).coerceAtLeast(0)
    val freeSpace = availableMain - basisSum - extraSum - gapSum

    when {
        freeSpace > 0.01f -> {
            val growSum = grow.sum()
            for (i in 0 until n) result[i] = basis[i] + if (growSum > 0f) freeSpace * (grow[i] / growSum) else 0f
        }
        freeSpace < -0.01f -> {
            val weights = DoubleArray(n) { (shrink[it] * basis[it]).toDouble() }
            val weightSum = weights.sum().toFloat()
            for (i in 0 until n) {
                val reduction = if (weightSum > 0f) (-freeSpace) * (weights[i].toFloat() / weightSum) else 0f
                result[i] = (basis[i] - reduction).coerceAtLeast(0f)
            }
        }
        else -> for (i in 0 until n) result[i] = basis[i]
    }
    return result
}
