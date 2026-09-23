package com.projectfuture.browser.browser

import android.content.Context

/**
 * Owns the open Tab instances and which one is active. This is
 * MainActivity's only handle onto multiple pages - it doesn't touch the
 * View layer itself (MainActivity re-points its single BrowserView at
 * whichever Tab is active on every switch).
 *
 * Closing the last remaining tab opens a fresh blank one instead of
 * leaving zero tabs, matching how mobile browsers usually behave rather
 * than leaving the app with nothing to show.
 */
class TabManager(private val context: Context, private val onStateChanged: (Tab, TabState) -> Unit) {
    private val tabs = ArrayList<Tab>()

    var activeIndex = -1
        private set

    val activeTab: Tab? get() = tabs.getOrNull(activeIndex)
    fun allTabs(): List<Tab> = tabs
    fun count() = tabs.size

    fun newTab(): Tab {
        lateinit var tab: Tab
        tab = Tab(context) { state -> onStateChanged(tab, state) }
        tabs.add(tab)
        activeIndex = tabs.size - 1
        return tab
    }

    fun switchTo(index: Int): Tab? {
        if (index !in tabs.indices) return null
        activeIndex = index
        return tabs[index]
    }

    /** Returns the tab that ends up active afterward (a fresh one if this closed the last tab). */
    fun closeTab(index: Int): Tab {
        if (index in tabs.indices) {
            tabs[index].destroy()
            tabs.removeAt(index)
        }
        if (tabs.isEmpty()) return newTab()
        activeIndex = activeIndex.coerceIn(0, tabs.size - 1)
        return tabs[activeIndex]
    }

    fun destroyAll() {
        for (tab in tabs) tab.destroy()
        tabs.clear()
        activeIndex = -1
    }
}
