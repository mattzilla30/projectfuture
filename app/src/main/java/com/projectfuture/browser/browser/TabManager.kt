package com.projectfuture.browser.browser

import android.content.Context
import com.projectfuture.browser.ipc.RemoteTabHandle

/**
 * Owns the open tabs and which one is active. This is MainActivity's only
 * handle onto multiple pages - it doesn't touch the View layer itself
 * (MainActivity re-points its single BrowserView at whichever tab is
 * active on every switch).
 *
 * Each tab is a [TabHandle] - by default a [RemoteTabHandle] whose engine
 * runs in one of the 4 pooled `:tabengineN` processes (see
 * [com.projectfuture.browser.ipc.TabEngineServiceBase]'s class doc), or a
 * [LocalTabHandle] running in this process if [sandboxedTabsEnabled]
 * returns false (see [Settings.sandboxedTabsEnabled]'s doc for why that
 * fallback exists). Tab N is assigned pooled process `N mod 4`, so at
 * most 4 open tabs are ever isolated from each other at once - a 5th tab
 * shares a process with the 1st (same bound this app's whole sandboxing
 * story has - see [com.projectfuture.browser.ipc.TabEngineServiceBase]'s
 * pool doc).
 *
 * Closing the last remaining tab opens a fresh blank one instead of
 * leaving zero tabs, matching how mobile browsers usually behave rather
 * than leaving the app with nothing to show.
 */
class TabManager(
    private val context: Context,
    private val sandboxedTabsEnabled: () -> Boolean,
    private val onStateChanged: (TabHandle, TabState) -> Unit
) {
    private val tabs = ArrayList<TabHandle>()

    var activeIndex = -1
        private set

    val activeTab: TabHandle? get() = tabs.getOrNull(activeIndex)
    fun allTabs(): List<TabHandle> = tabs
    fun count() = tabs.size

    fun newTab(private: Boolean = false): TabHandle {
        lateinit var handle: TabHandle
        val listener: (TabState) -> Unit = { state -> onStateChanged(handle, state) }
        handle = if (sandboxedTabsEnabled()) {
            RemoteTabHandle(context, tabIndex = tabs.size, isPrivate = private, onStateChanged = listener)
        } else {
            LocalTabHandle(context, private, listener)
        }
        tabs.add(handle)
        activeIndex = tabs.size - 1
        return handle
    }

    fun switchTo(index: Int): TabHandle? {
        if (index !in tabs.indices) return null
        activeIndex = index
        return tabs[index]
    }

    /** Returns the tab that ends up active afterward (a fresh one if this closed the last tab). */
    fun closeTab(index: Int): TabHandle {
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
