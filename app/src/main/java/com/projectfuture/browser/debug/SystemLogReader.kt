package com.projectfuture.browser.debug

/**
 * Reads the recent Android system log. An app may only read its own lines, which covers every
 * process of this app: the UI, each sandboxed tab engine, and the stack traces Android writes when
 * one of them crashes. Info level and up, to leave out framework debug noise. Returns an empty list
 * when the log can't be read.
 */
object SystemLogReader {
    fun recentLines(maxLines: Int = 3000): List<String> = try {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", maxLines.toString(), "*:I")
            .redirectErrorStream(true)
            .start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor()
        lines
    } catch (_: Exception) {
        emptyList()
    }
}
