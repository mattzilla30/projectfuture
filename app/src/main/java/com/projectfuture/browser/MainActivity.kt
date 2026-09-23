package com.projectfuture.browser

import android.content.Context
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.projectfuture.browser.browser.BookmarkStore
import com.projectfuture.browser.browser.HistoryStore
import com.projectfuture.browser.browser.Tab
import com.projectfuture.browser.browser.TabManager
import com.projectfuture.browser.browser.TabState
import com.projectfuture.browser.net.CookieJar
import com.projectfuture.browser.net.sharedCookieJar
import com.projectfuture.browser.databinding.ActivityMainBinding

private const val START_URL = "https://example.com/"

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var tabManager: TabManager
    private lateinit var bookmarkStore: BookmarkStore
    private lateinit var historyStore: HistoryStore
    private val tabTitles = HashMap<Tab, String>()
    private var lastViewportWidth = 0f
    private var lastViewportHeight = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tabManager = TabManager(this, ::onTabStateChanged)
        bookmarkStore = BookmarkStore(this)
        historyStore = HistoryStore(this)
        if (sharedCookieJar == null) sharedCookieJar = CookieJar(applicationContext)

        binding.browserView.onSizeAvailable = { width, height ->
            lastViewportWidth = width
            lastViewportHeight = height
            if (tabManager.activeTab?.onViewportSizeChanged(width, height) == true) refreshView()
        }
        binding.browserView.onLinkTapped = { href -> tabManager.activeTab?.followLink(href) }
        binding.browserView.onElementTapped = { element -> tabManager.activeTab?.dispatchClick(element) }

        binding.buttonBack.setOnClickListener { tabManager.activeTab?.goBack() }
        binding.buttonForward.setOnClickListener { tabManager.activeTab?.goForward() }
        binding.buttonReload.setOnClickListener { tabManager.activeTab?.reload() }
        binding.buttonTabs.setOnClickListener { showTabSwitcher() }
        binding.buttonMenu.setOnClickListener { showOverflowMenu() }

        binding.editAddress.setOnEditorActionListener { _, actionId, event ->
            val committed = actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (committed) {
                val input = binding.editAddress.text.toString()
                if (input.isNotBlank()) {
                    tabManager.activeTab?.navigate(input)
                    hideKeyboard()
                    binding.browserView.requestFocus()
                }
                true
            } else {
                false
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val tab = tabManager.activeTab
                if (tab != null && tab.canGoBack()) {
                    tab.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        tabManager.newTab()
        binding.editAddress.setText(START_URL)
        tabManager.activeTab?.navigate(START_URL)
        updateTabCountButton()
    }

    private fun onTabStateChanged(tab: Tab, state: TabState) {
        // Track title/URL for every tab regardless of which one is on screen,
        // so the switcher list stays accurate for background tabs too.
        when (state) {
            is TabState.Loading -> tabTitles[tab] = getString(R.string.loading)
            is TabState.Loaded -> {
                val label = state.title ?: state.url.toString()
                tabTitles[tab] = label
                historyStore.record(state.url.toString(), label)
            }
            else -> {}
        }

        if (tab !== tabManager.activeTab) {
            updateTabCountButton()
            return
        }

        when (state) {
            is TabState.Loading -> {
                binding.progressBar.visibility = View.VISIBLE
                binding.progressBar.isIndeterminate = true
            }
            is TabState.Loaded -> {
                binding.progressBar.visibility = View.GONE
                binding.editAddress.setText(state.url.toString())
                title = state.title ?: state.url.toString()
                binding.browserView.resetScroll()
                refreshView()
                updateNavButtons()
            }
            is TabState.Updated -> {
                state.title?.let { tabTitles[tab] = it; title = it }
                refreshView()
            }
            is TabState.Error -> {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(
                    this,
                    getString(R.string.error_load_failed, state.url.toString(), state.message),
                    Toast.LENGTH_LONG
                ).show()
                updateNavButtons()
            }
        }
        updateTabCountButton()
    }

    private fun switchToTab(index: Int) {
        val tab = tabManager.switchTo(index) ?: return
        if (lastViewportWidth > 0f && lastViewportHeight > 0f) {
            tab.onViewportSizeChanged(lastViewportWidth, lastViewportHeight)
        }
        binding.editAddress.setText(tab.currentUrl?.toString() ?: "")
        title = tabTitles[tab] ?: tab.currentUrl?.toString() ?: getString(R.string.untitled_tab)
        binding.browserView.resetScroll()
        refreshView()
        updateNavButtons()
        updateTabCountButton()
    }

    private fun openNewTab() {
        tabManager.newTab()
        switchToTab(tabManager.count() - 1)
        binding.editAddress.requestFocus()
    }

    private fun closeTabAt(index: Int) {
        val closedTab = tabManager.allTabs().getOrNull(index)
        val newActiveTab = tabManager.closeTab(index)
        tabTitles.remove(closedTab)
        switchToTab(tabManager.allTabs().indexOf(newActiveTab))
    }

    private fun showTabSwitcher() {
        lateinit var dialog: AlertDialog
        val tabs = tabManager.allTabs()
        val listView = ListView(this)
        listView.adapter = object : BaseAdapter() {
            override fun getCount() = tabs.size
            override fun getItem(position: Int): Any = tabs[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tab = tabs[position]
                val row = listRow(this@MainActivity)
                val activeMarker = if (position == tabManager.activeIndex) "●  " else ""
                val label = tabTitles[tab] ?: tab.currentUrl?.toString() ?: getString(R.string.untitled_tab)
                row.addView(
                    listRowLabel(this@MainActivity, activeMarker + label)
                )
                row.addView(
                    listRowCloseButton(this@MainActivity) {
                        closeTabAt(position)
                        dialog.dismiss()
                    }
                )
                return row
            }
        }
        listView.setOnItemClickListener { _, _, position, _ ->
            switchToTab(position)
            dialog.dismiss()
        }
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tabs_dialog_title)
            .setView(listView)
            .setPositiveButton(R.string.action_new_tab) { d, _ -> openNewTab(); d.dismiss() }
            .setNegativeButton(R.string.action_close, null)
            .create()
        dialog.show()
    }

    private fun showOverflowMenu() {
        val currentUrl = tabManager.activeTab?.currentUrl?.toString()
        val popup = PopupMenu(this, binding.buttonMenu)
        val bookmarkItemTitle = if (currentUrl != null && bookmarkStore.isBookmarked(currentUrl)) {
            R.string.menu_remove_bookmark
        } else {
            R.string.menu_add_bookmark
        }
        popup.menu.add(0, 1, 0, bookmarkItemTitle).isEnabled = currentUrl != null
        popup.menu.add(0, 2, 1, R.string.menu_bookmarks)
        popup.menu.add(0, 3, 2, R.string.menu_history)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    val url = currentUrl ?: return@setOnMenuItemClickListener true
                    val label = tabManager.activeTab?.let { tabTitles[it] } ?: url
                    val nowBookmarked = bookmarkStore.toggle(url, label)
                    val message = if (nowBookmarked) R.string.bookmark_added else R.string.bookmark_removed
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    true
                }
                2 -> { showBookmarksDialog(); true }
                3 -> { showHistoryDialog(); true }
                else -> false
            }
        }
        popup.show()
    }

    /** Shared row builder for the bookmarks/history dialogs: a label, tap to navigate, X to remove. */
    private fun buildListRow(label: String, onTap: () -> Unit, onRemove: () -> Unit): View {
        val row = listRow(this)
        row.addView(listRowLabel(this, label))
        row.addView(listRowCloseButton(this, onRemove))
        row.setOnClickListener { onTap() }
        return row
    }

    private fun showBookmarksDialog() {
        lateinit var dialog: AlertDialog
        val listView = ListView(this)
        fun bind() {
            val bookmarks = bookmarkStore.all()
            listView.adapter = object : BaseAdapter() {
                override fun getCount() = bookmarks.size
                override fun getItem(position: Int) = bookmarks[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val bookmark = bookmarks[position]
                    return buildListRow(
                        label = bookmark.title,
                        onTap = { tabManager.activeTab?.navigate(bookmark.url); dialog.dismiss() },
                        onRemove = { bookmarkStore.remove(bookmark.url); bind() }
                    )
                }
            }
        }
        bind()
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.bookmarks_dialog_title)
            .setView(listView)
            .setNegativeButton(R.string.action_close, null)
            .create()
        dialog.show()
    }

    private fun showHistoryDialog() {
        lateinit var dialog: AlertDialog
        val listView = ListView(this)
        fun bind() {
            val entries = historyStore.load()
            listView.adapter = object : BaseAdapter() {
                override fun getCount() = entries.size
                override fun getItem(position: Int) = entries[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val entry = entries[position]
                    return buildListRow(
                        label = entry.title,
                        onTap = { tabManager.activeTab?.navigate(entry.url); dialog.dismiss() },
                        onRemove = { historyStore.remove(entry.url); bind() }
                    )
                }
            }
        }
        bind()
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.history_dialog_title)
            .setView(listView)
            .setPositiveButton(R.string.action_clear) { _, _ -> historyStore.clear(); dialog.dismiss() }
            .setNegativeButton(R.string.action_close, null)
            .create()
        dialog.show()
    }

    private fun refreshView() {
        val tab = tabManager.activeTab ?: return
        binding.browserView.setContent(tab.displayList, tab.contentHeight)
    }

    private fun updateNavButtons() {
        val tab = tabManager.activeTab
        binding.buttonBack.isEnabled = tab?.canGoBack() ?: false
        binding.buttonForward.isEnabled = tab?.canGoForward() ?: false
    }

    private fun updateTabCountButton() {
        binding.buttonTabs.text = tabManager.count().toString()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.editAddress.windowToken, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        tabManager.destroyAll()
    }
}

/** Builds a themed, rippling row container shared by the tabs/bookmarks/history dialogs. */
private fun listRow(context: Context): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(24, 20, 8, 20)
    val outValue = TypedValue()
    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
    setBackgroundResource(outValue.resourceId)
}

private fun listRowLabel(context: Context, label: String): TextView = TextView(context).apply {
    text = label
    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    maxLines = 1
    ellipsize = TextUtils.TruncateAt.END
    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
    setTextColor(resolveThemeColor(context, com.google.android.material.R.attr.colorOnSurface))
}

private fun listRowCloseButton(context: Context, onRemove: () -> Unit): ImageButton = ImageButton(context).apply {
    setImageResource(R.drawable.ic_close)
    background = null
    imageTintList = android.content.res.ColorStateList.valueOf(
        resolveThemeColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant)
    )
    contentDescription = context.getString(R.string.action_close)
    setOnClickListener { onRemove() }
}

private fun resolveThemeColor(context: Context, attr: Int): Int {
    val value = TypedValue()
    context.theme.resolveAttribute(attr, value, true)
    return value.data
}
