package com.projectfuture.browser.browser

import android.content.Context

data class Bookmark(val url: String, val title: String)

/** Persistent bookmarks, one per unique URL. See HistoryStore's doc for why this isn't JSON. */
class BookmarkStore(context: Context) {
    private val prefs = context.getSharedPreferences("bookmarks", Context.MODE_PRIVATE)

    fun all(): List<Bookmark> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return raw.split("\n").filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split("\t", limit = 2)
            if (parts.size != 2) null else Bookmark(url = parts[1], title = parts[0])
        }
    }

    fun isBookmarked(url: String): Boolean = all().any { it.url == url }

    /** Adds a bookmark for [url] if none exists, else removes it. Returns the new bookmarked state. */
    fun toggle(url: String, title: String): Boolean {
        val current = all()
        val alreadyBookmarked = current.any { it.url == url }
        val updated = if (alreadyBookmarked) {
            current.filterNot { it.url == url }
        } else {
            current + Bookmark(url, title.replace('\n', ' ').replace('\t', ' '))
        }
        save(updated)
        return !alreadyBookmarked
    }

    fun remove(url: String) = save(all().filterNot { it.url == url })

    private fun save(entries: List<Bookmark>) {
        prefs.edit().putString(KEY, entries.joinToString("\n") { "${it.title}\t${it.url}" }).apply()
    }

    companion object {
        private const val KEY = "entries"
    }
}
