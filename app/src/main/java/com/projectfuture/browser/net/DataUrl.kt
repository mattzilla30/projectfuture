package com.projectfuture.browser.net

import java.io.ByteArrayOutputStream
import java.util.Base64

/** Decodes a `data:[<mediatype>][;base64],<data>` URL's payload (RFC 2397), or returns null if it isn't one. */
fun decodeDataUrl(url: String): ByteArray? {
    if (!url.regionMatches(0, "data:", 0, 5, ignoreCase = true)) return null
    val comma = url.indexOf(',')
    if (comma == -1) return null
    val meta = url.substring(5, comma)
    val data = url.substring(comma + 1)
    return if (meta.split(';').any { it.trim().equals("base64", ignoreCase = true) }) {
        try {
            Base64.getDecoder().decode(percentDecode(data).toString(Charsets.ISO_8859_1).replace(Regex("\\s"), ""))
        } catch (_: IllegalArgumentException) {
            null
        }
    } else {
        percentDecode(data)
    }
}

private fun percentDecode(s: String): ByteArray {
    val out = ByteArrayOutputStream(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%' && i + 2 < s.length && s.substring(i + 1, i + 3).toIntOrNull(16) != null) {
            out.write(s.substring(i + 1, i + 3).toInt(16))
            i += 3
        } else {
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
    }
    return out.toByteArray()
}
