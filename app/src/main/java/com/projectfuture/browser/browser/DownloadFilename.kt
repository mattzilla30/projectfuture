package com.projectfuture.browser.browser

/**
 * Turns a download's suggested filename into one safe to hand to
 * `DownloadManager.Request.setDestinationInExternalPublicDir`.
 *
 * The input is never trustworthy: it comes straight from page content the
 * remote server controls - either the `download="..."` attribute of the
 * clicked `<a>` (see [Tab.dispatchClick]) or (elsewhere) a `Content-
 * Disposition` filename. Handed to `setDestinationInExternalPublicDir`
 * unsanitized, a value like `"../../../../data/data/com.projectfuture.browser/shared_prefs/settings.xml"`
 * is a path-traversal attempt at writing outside the Downloads directory,
 * and an unbounded-length value can blow past the ~255-byte filename limit
 * most filesystems enforce, turning every such download into a silent
 * failure (caught generically by MainActivity.startDownload's catch block).
 *
 * This keeps only the final path segment (both `/`- and `\`-separated,
 * since a crafted value isn't obligated to use the host OS's separator),
 * neutralizes any remaining `..` traversal segments, strips control
 * characters, and clamps the byte length while preserving a short file
 * extension so the download still opens with the right app.
 */
fun sanitizeDownloadFilename(raw: String?): String {
    val fallback = "download"
    var name = raw?.trim().orEmpty()
    if (name.isEmpty()) return fallback

    // Only the last path segment matters - this also neutralizes any leading
    // "../" traversal that used a real separator.
    name = name.replace('\\', '/').substringAfterLast('/')

    // A traversal segment that survives without a separator (e.g. a lone
    // "..") is still meaningless as a filename - collapse it.
    name = name.replace("..", "_")

    // Strip control characters (including NUL) that some filesystems choke on.
    name = name.filter { it.code >= 0x20 && it.code != 0x7f }

    // Trailing dots/spaces are stripped by some filesystems anyway and can be
    // used to hide a real extension (e.g. "evil.exe...."); normalize them away.
    name = name.trim().trim('.', ' ')

    // A name left with nothing but dots/underscores after the traversal
    // collapse above (e.g. the input was just ".." or "...") isn't a usable
    // filename either.
    if (name.isBlank() || name.all { it == '.' || it == '_' }) name = fallback

    return clampToByteLength(name, MAX_FILENAME_BYTES)
}

private const val MAX_FILENAME_BYTES = 200

private fun clampToByteLength(name: String, maxBytes: Int): String {
    if (name.toByteArray(Charsets.UTF_8).size <= maxBytes) return name
    val dot = name.lastIndexOf('.')
    // Only treat it as a "real" extension if it's short, so we don't preserve
    // an oversized suffix that has no dot near the end at all.
    val hasShortExtension = dot > 0 && name.length - dot in 2..11
    val ext = if (hasShortExtension) name.substring(dot) else ""
    val extBytes = ext.toByteArray(Charsets.UTF_8).size
    var base = if (hasShortExtension) name.substring(0, dot) else name
    while (base.isNotEmpty() && base.toByteArray(Charsets.UTF_8).size + extBytes > maxBytes) {
        base = base.substring(0, base.length - 1)
    }
    val result = base + ext
    return result.ifBlank { "download" + ext }
}

/**
 * Appends " (1)", " (2)", ... to [desired] until [exists] reports the
 * candidate name as free, mirroring how desktop browsers avoid silently
 * overwriting an existing file with the same name in the Downloads folder
 * (plain `setDestinationInExternalPublicDir` does not dedupe this itself -
 * see MainActivity.startDownload's doc).
 */
fun uniqueDownloadFilename(desired: String, exists: (String) -> Boolean): String {
    if (!exists(desired)) return desired
    val dot = desired.lastIndexOf('.')
    val hasExtension = dot > 0
    val base = if (hasExtension) desired.substring(0, dot) else desired
    val ext = if (hasExtension) desired.substring(dot) else ""
    var n = 1
    while (n <= 9999) {
        val candidate = "$base ($n)$ext"
        if (!exists(candidate)) return candidate
        n++
    }
    // Pathological case (thousands of same-named downloads) - fall back to a
    // timestamp rather than looping forever or overwriting.
    return "$base-${System.currentTimeMillis()}$ext"
}
