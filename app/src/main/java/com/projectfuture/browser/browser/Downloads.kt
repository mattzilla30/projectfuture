package com.projectfuture.browser.browser

/**
 * Decides whether a top-level navigation response should be treated as a
 * file download rather than rendered as a page.
 *
 * Real bug this fixes: previously [Tab]'s `load()` unconditionally ran
 * `HtmlParser` over every top-level navigation response's body, regardless
 * of `Content-Type` - so a download triggered any way other than an
 * explicit `<a download>` tap (a JS-driven `location.href = ...` to a PDF,
 * a server redirect landing on a `.zip`, a `Content-Disposition: attachment`
 * response, etc.) got parsed as HTML and rendered as garbage "page content"
 * instead of being handed off to [Tab.onDownloadRequested] like a real
 * browser's download path.
 *
 * A response with no `Content-Type` at all is treated as renderable HTML
 * (the safe default many real servers and most of this project's own test
 * fixtures rely on implicitly) rather than as a download.
 */
fun isDownloadResponse(contentType: String?, contentDisposition: String?): Boolean {
    if (contentDisposition?.trim()?.startsWith("attachment", ignoreCase = true) == true) return true
    val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
    return type != "text/html" && type != "application/xhtml+xml"
}

/**
 * Extracts a suggested filename from a `Content-Disposition` header, e.g.
 * `attachment; filename="report.pdf"` or the RFC 5987 `filename*=UTF-8''...`
 * form. Returns null if the header is absent or has no filename.
 */
fun parseContentDispositionFilename(contentDisposition: String?): String? {
    if (contentDisposition.isNullOrBlank()) return null
    val match = Regex("""filename\*?=(?:UTF-8''|"UTF-8''")?"?([^";]+)"?""", RegexOption.IGNORE_CASE)
        .find(contentDisposition) ?: return null
    return match.groupValues[1].trim().trim('"').takeIf { it.isNotBlank() }
}
