package com.projectfuture.browser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadsTest {

    @Test fun textHtmlIsNotADownload() {
        assertFalse(isDownloadResponse("text/html; charset=utf-8", null))
        assertFalse(isDownloadResponse("text/html", null))
    }

    @Test fun xhtmlIsNotADownload() {
        assertFalse(isDownloadResponse("application/xhtml+xml", null))
    }

    @Test fun missingContentTypeDefaultsToRenderableHtml() {
        // Real servers (and most test fixtures) often omit Content-Type entirely -
        // that must not be misdetected as a download.
        assertFalse(isDownloadResponse(null, null))
    }

    @Test fun pdfNavigationIsADownload() {
        // The core bug: a JS-driven navigation or redirect landing on a PDF used to
        // get parsed as HTML garbage instead of being routed to the download path.
        assertTrue(isDownloadResponse("application/pdf", null))
    }

    @Test fun zipOctetStreamAndImageNavigationsAreDownloads() {
        assertTrue(isDownloadResponse("application/zip", null))
        assertTrue(isDownloadResponse("application/octet-stream", null))
        assertTrue(isDownloadResponse("image/png", null))
    }

    @Test fun contentDispositionAttachmentForcesDownloadRegardlessOfContentType() {
        assertTrue(isDownloadResponse("text/html", "attachment; filename=\"report.html\""))
    }

    @Test fun contentDispositionInlineDoesNotForceDownload() {
        assertFalse(isDownloadResponse("text/html", "inline"))
    }

    @Test fun parsesQuotedFilename() {
        assertEquals("report.pdf", parseContentDispositionFilename("attachment; filename=\"report.pdf\""))
    }

    @Test fun parsesUnquotedFilename() {
        assertEquals("report.pdf", parseContentDispositionFilename("attachment; filename=report.pdf"))
    }

    @Test fun parsesRfc5987EncodedFilename() {
        assertEquals("report.pdf", parseContentDispositionFilename("attachment; filename*=UTF-8''report.pdf"))
    }

    @Test fun returnsNullWhenNoFilenamePresent() {
        assertNull(parseContentDispositionFilename("attachment"))
        assertNull(parseContentDispositionFilename(null))
        assertNull(parseContentDispositionFilename(""))
    }
}
