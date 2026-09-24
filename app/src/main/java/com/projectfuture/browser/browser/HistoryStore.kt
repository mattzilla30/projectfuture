package com.projectfuture.browser.browser

import android.content.Context

data class HistoryEntry(val url: String, val title: String, val timestamp: Long)

/**
 * Persistent browsing history, one row per successful page load across all
 * tabs. Uses SharedPreferences with a small hand-written tab/newline-
 * delimited serialization rather than pulling in a JSON library dependency
 * for one small feature - titles/URLs never legitimately contain a literal
 * tab or newline, so replacing those characters on write is a lossless-
 * enough escape for this purpose. Capped at [MAX_ENTRIES], oldest dropped
 * first.
 */
class HistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("history", Context.MODE_PRIVATE)

    fun record(url: String, title: String) {
        val entries = load().toMutableList()
        entries.add(0, HistoryEntry(url, sanitize(title), System.currentTimeMillis()))
        save(entries.take(MAX_ENTRIES))
    }

    fun load(): List<HistoryEntry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return raw.split("\n").filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split("\t", limit = 3)
            if (parts.size != 3) return@mapNotNull null
            val timestamp = parts[0].toLongOrNull() ?: return@mapNotNull null
            HistoryEntry(url = parts[2], title = parts[1], timestamp = timestamp)
        }
    }

    /**
     * Removes exactly [entry] (matched by full identity, including its
     * timestamp) rather than every entry that shares its URL - history keeps
     * one row per page load, so repeat visits to the same URL show up as
     * separate rows and deleting one must not delete the others.
     */
    fun remove(entry: HistoryEntry) = save(load().filterNot { it == entry })

    fun clear() = prefs.edit().remove(KEY).apply()

    private fun sanitize(text: String) = text.replace('\n', ' ').replace('\t', ' ')

    private fun save(entries: List<HistoryEntry>) {
        prefs.edit().putString(KEY, entries.joinToString("\n") { "${it.timestamp}\t${it.title}\t${it.url}" }).apply()
    }

    companion object {
        private const val KEY = "entries"
        private const val MAX_ENTRIES = 500
    }
}
