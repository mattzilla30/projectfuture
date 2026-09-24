package com.projectfuture.browser.debug

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The browser's own diagnostic log. Every entry goes to Android's log (tag [TAG]), where the
 * "Copy logs" menu item reads it back together with every other line this app's processes wrote,
 * crash stack traces included. Tab engines run in separate processes, and the system log is the one
 * place all of them meet. A copy of recent entries also stays in memory in each process, as a
 * fallback for devices where reading the system log fails.
 *
 * Never log cookies, form bodies, passwords, or a private tab's URLs.
 */
object BrowserLog {
    const val TAG = "PFBrowser"
    private const val MAX_BUFFERED = 1000

    private val buffer = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    // android.util.Log throws in plain JVM unit tests, where the engine code also runs.
    @Volatile private var systemLogAvailable = true

    fun i(area: String, message: String) = log(Log.INFO, area, message, null)

    fun w(area: String, message: String, error: Throwable? = null) = log(Log.WARN, area, message, error)

    fun e(area: String, message: String, error: Throwable? = null) = log(Log.ERROR, area, message, error)

    /** Recent entries from this process only, oldest first. */
    fun recentEntries(): List<String> = synchronized(buffer) { buffer.toList() }

    private fun log(priority: Int, area: String, message: String, error: Throwable?) {
        val text = "[$area] $message"
        val level = when (priority) { Log.ERROR -> 'E'; Log.WARN -> 'W'; else -> 'I' }
        val stamp = synchronized(timeFormat) { timeFormat.format(Date()) }
        val entry = "$stamp $level $text" + (error?.let { "\n" + it.stackTraceToString().trimEnd() } ?: "")
        synchronized(buffer) {
            buffer.addLast(entry)
            while (buffer.size > MAX_BUFFERED) buffer.removeFirst()
        }
        if (!systemLogAvailable) return
        try {
            if (error == null) Log.println(priority, TAG, text) else Log.println(priority, TAG, text + "\n" + Log.getStackTraceString(error))
        } catch (_: RuntimeException) {
            systemLogAvailable = false
        }
    }
}

/**
 * Builds the text the "Copy logs" menu item puts on the clipboard: device and app details, then
 * the newest log lines. The clipboard crosses Binder too, so the text is capped at [maxChars],
 * keeping the most recent lines.
 */
object LogReport {
    const val DEFAULT_MAX_CHARS = 200_000

    fun build(header: List<String>, logLines: List<String>, maxChars: Int = DEFAULT_MAX_CHARS): String {
        val head = header.joinToString("\n") + "\n\n"
        val budget = maxChars - head.length
        val kept = ArrayDeque<String>()
        var used = 0
        for (line in logLines.asReversed()) {
            if (used + line.length + 1 > budget) break
            kept.addFirst(line)
            used += line.length + 1
        }
        val dropped = logLines.size - kept.size
        val note = if (dropped > 0) "($dropped older lines omitted)\n" else ""
        return head + note + kept.joinToString("\n")
    }
}
