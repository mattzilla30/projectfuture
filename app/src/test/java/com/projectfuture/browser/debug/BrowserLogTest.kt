package com.projectfuture.browser.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserLogTest {
    @Test fun logsWithoutAndroidAndKeepsEntriesInMemory() {
        // android.util.Log isn't available in plain JVM tests; logging must still work.
        BrowserLog.i("test", "hello from a unit test")
        BrowserLog.w("test", "with error", IllegalStateException("boom"))
        val recent = BrowserLog.recentEntries().takeLast(2)
        assertTrue(recent[0].endsWith("I [test] hello from a unit test"))
        assertTrue(recent[1].contains("W [test] with error"))
        assertTrue(recent[1].contains("IllegalStateException: boom"))
    }

    @Test fun memoryBufferIsBounded() {
        repeat(1500) { BrowserLog.i("test", "line $it") }
        val recent = BrowserLog.recentEntries()
        assertEquals(1000, recent.size)
        assertTrue(recent.last().endsWith("line 1499"))
    }

    @Test fun reportKeepsTheHeaderAndTheNewestLinesWithinTheCap() {
        val lines = (1..1000).map { "log line number $it" }
        val report = LogReport.build(listOf("header one", "header two"), lines, maxChars = 2000)
        assertTrue(report.startsWith("header one\nheader two\n\n"))
        assertTrue(report.endsWith("log line number 1000"))
        assertTrue(report.length <= 2100)
        assertTrue(report.contains("older lines omitted"))
    }

    @Test fun reportWithRoomForEverythingOmitsNothing() {
        val report = LogReport.build(listOf("h"), listOf("a", "b"))
        assertEquals("h\n\na\nb", report)
    }
}
