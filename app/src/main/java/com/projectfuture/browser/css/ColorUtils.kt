package com.projectfuture.browser.css

import android.graphics.Color

private val NAMED_COLORS = mapOf(
    "black" to "#000000", "white" to "#ffffff", "red" to "#ff0000", "green" to "#008000",
    "blue" to "#0000ff", "yellow" to "#ffff00", "gray" to "#808080", "grey" to "#808080",
    "silver" to "#c0c0c0", "orange" to "#ffa500", "purple" to "#800080", "pink" to "#ffc0cb",
    "brown" to "#a52a2a", "navy" to "#000080", "teal" to "#008080", "lime" to "#00ff00",
    "cyan" to "#00ffff", "magenta" to "#ff00ff", "maroon" to "#800000", "olive" to "#808000",
    "transparent" to "#00000000", "darkgray" to "#a9a9a9", "darkgrey" to "#a9a9a9",
    "lightgray" to "#d3d3d3", "lightgrey" to "#d3d3d3", "gold" to "#ffd700",
    "indigo" to "#4b0082", "violet" to "#ee82ee", "crimson" to "#dc143c"
)

/** Parses a CSS color value by hand (#hex, rgb()/rgba(), and a common name table). */
fun parseCssColor(raw: String?): Int? {
    if (raw == null) return null
    val value = raw.trim().lowercase()
    if (value.isEmpty()) return null

    NAMED_COLORS[value]?.let { return parseCssColor(it) }

    if (value.startsWith("#")) {
        val hex = value.substring(1)
        return try {
            when (hex.length) {
                3 -> Color.rgb(
                    (hex[0].toString() + hex[0]).toInt(16),
                    (hex[1].toString() + hex[1]).toInt(16),
                    (hex[2].toString() + hex[2]).toInt(16)
                )
                4 -> Color.argb(
                    (hex[3].toString() + hex[3]).toInt(16),
                    (hex[0].toString() + hex[0]).toInt(16),
                    (hex[1].toString() + hex[1]).toInt(16),
                    (hex[2].toString() + hex[2]).toInt(16)
                )
                6 -> Color.parseColor("#$hex")
                8 -> {
                    // CSS is RRGGBBAA; Android's ARGB wants AARRGGBB.
                    val rgb = hex.substring(0, 6)
                    val a = hex.substring(6, 8).toInt(16)
                    Color.argb(a, rgb.substring(0, 2).toInt(16), rgb.substring(2, 4).toInt(16), rgb.substring(4, 6).toInt(16))
                }
                else -> null
            }
        } catch (_: Exception) { null }
    }

    if (value.startsWith("rgb")) {
        val nums = value.substringAfter('(').substringBefore(')')
            .split(',').map { it.trim().removeSuffix("%").toFloatOrNull() ?: 0f }
        if (nums.size >= 3) {
            val r = nums[0].toInt().coerceIn(0, 255)
            val g = nums[1].toInt().coerceIn(0, 255)
            val b = nums[2].toInt().coerceIn(0, 255)
            val a = if (nums.size >= 4) (nums[3] * 255).toInt().coerceIn(0, 255) else 255
            return Color.argb(a, r, g, b)
        }
    }

    return null
}
