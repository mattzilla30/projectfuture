package com.projectfuture.browser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFilenameTest {

    // --- sanitizeDownloadFilename ---

    @Test
    fun `strips path traversal segments so a malicious download attribute can't escape the Downloads directory`() {
        val malicious = "../../../../data/data/com.projectfuture.browser/shared_prefs/settings.xml"
        val safe = sanitizeDownloadFilename(malicious)
        assertFalse("sanitized name must not contain '..'", safe.contains(".."))
        assertFalse("sanitized name must not contain a path separator", safe.contains("/"))
        assertEquals("settings.xml", safe)
    }

    @Test
    fun `strips backslash path traversal too, not just forward slash`() {
        val malicious = "..\\..\\..\\Windows\\evil.exe"
        val safe = sanitizeDownloadFilename(malicious)
        assertFalse(safe.contains(".."))
        assertFalse(safe.contains("\\"))
        assertEquals("evil.exe", safe)
    }

    @Test
    fun `a bare double-dot with no separator is neutralized`() {
        val safe = sanitizeDownloadFilename("..")
        assertEquals("download", safe)
    }

    @Test
    fun `unicode filenames pass through untouched when short`() {
        val name = "日本語ファイル.pdf" // "日本語ファイル.pdf"
        assertEquals(name, sanitizeDownloadFilename(name))
    }

    @Test
    fun `an extremely long filename is clamped and keeps its extension`() {
        val longName = "a".repeat(2000) + ".png"
        val safe = sanitizeDownloadFilename(longName)
        assertTrue("clamped name must not blow past typical filesystem limits", safe.toByteArray(Charsets.UTF_8).size <= 200)
        assertTrue("extension should be preserved so the file still opens correctly", safe.endsWith(".png"))
    }

    @Test
    fun `a long filename with no real extension is just clamped`() {
        val longName = "b".repeat(2000)
        val safe = sanitizeDownloadFilename(longName)
        assertTrue(safe.toByteArray(Charsets.UTF_8).size <= 200)
        assertTrue(safe.all { it == 'b' })
    }

    @Test
    fun `null or blank input falls back to a default name`() {
        assertEquals("download", sanitizeDownloadFilename(null))
        assertEquals("download", sanitizeDownloadFilename("   "))
        assertEquals("download", sanitizeDownloadFilename("..."))
    }

    @Test
    fun `control characters are stripped`() {
        val safe = sanitizeDownloadFilename("evil\u0000name\u0007.txt")
        assertEquals("evilname.txt", safe)
    }

    @Test
    fun `an ordinary filename is left alone`() {
        assertEquals("report-2024.pdf", sanitizeDownloadFilename("report-2024.pdf"))
    }

    // --- uniqueDownloadFilename ---

    @Test
    fun `returns the desired name unchanged when nothing exists yet`() {
        assertEquals("photo.jpg", uniqueDownloadFilename("photo.jpg") { false })
    }

    @Test
    fun `appends a numbered suffix instead of silently colliding with an existing file`() {
        val taken = setOf("photo.jpg", "photo (1).jpg")
        val result = uniqueDownloadFilename("photo.jpg") { it in taken }
        assertEquals("photo (2).jpg", result)
    }

    @Test
    fun `dedupes files with no extension too`() {
        val taken = setOf("README")
        val result = uniqueDownloadFilename("README") { it in taken }
        assertEquals("README (1)", result)
    }
}
