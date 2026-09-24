package com.projectfuture.browser

import android.app.DownloadManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageButton
import android.widget.ImageView
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
import com.projectfuture.browser.browser.CredentialStore
import com.projectfuture.browser.browser.SavedCredential
import com.projectfuture.browser.browser.HistoryStore
import com.projectfuture.browser.browser.LocalStorageStore
import com.projectfuture.browser.browser.sharedLocalStorage
import com.projectfuture.browser.browser.CacheStorageStore
import com.projectfuture.browser.browser.ServiceWorkerRegistry
import com.projectfuture.browser.browser.SharedPrefsStorageBacking
import com.projectfuture.browser.browser.sharedCacheStorageStore
import com.projectfuture.browser.browser.sharedServiceWorkerRegistry
import com.projectfuture.browser.browser.Settings
import com.projectfuture.browser.browser.TabHandle
import com.projectfuture.browser.browser.TabManager
import com.projectfuture.browser.browser.TabSessionStore
import com.projectfuture.browser.browser.TabState
import com.projectfuture.browser.layout.DrawText
import com.projectfuture.browser.html.ElementNode
import com.projectfuture.browser.net.CookieJar
import com.projectfuture.browser.net.HttpCache
import com.projectfuture.browser.net.TrackingProtection
import com.projectfuture.browser.net.Url
import com.projectfuture.browser.net.sharedCookieJar
import com.projectfuture.browser.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var tabManager: TabManager
    private lateinit var bookmarkStore: BookmarkStore
    private lateinit var historyStore: HistoryStore
    private lateinit var settings: Settings
    private lateinit var tabSessionStore: TabSessionStore
    private lateinit var credentialStore: CredentialStore
    private val tabTitles = HashMap<TabHandle, String>()
    /** Origins the user picked "never for this site" on, for this process lifetime - suppresses repeat save-password prompts without persisting an explicit blocklist. */
    private val neverSaveOrigins = HashSet<String>()
    private var lastViewportWidth = 0f
    private var lastViewportHeight = 0f
    private var darkModeEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        tabManager = TabManager(this, { settings.sandboxedTabsEnabled }, ::onTabStateChanged)
        bookmarkStore = BookmarkStore(this)
        historyStore = HistoryStore(this)
        tabSessionStore = TabSessionStore(this)
        credentialStore = CredentialStore(this)
        TrackingProtection.enabled = settings.trackingProtectionEnabled
        if (sharedCookieJar == null) sharedCookieJar = CookieJar(applicationContext)
        if (sharedLocalStorage == null) sharedLocalStorage = LocalStorageStore(applicationContext)
        if (sharedServiceWorkerRegistry == null) sharedServiceWorkerRegistry = ServiceWorkerRegistry(SharedPrefsStorageBacking(applicationContext, "service_workers"))
        if (sharedCacheStorageStore == null) sharedCacheStorageStore = CacheStorageStore(SharedPrefsStorageBacking(applicationContext, "cache_storage"))

        binding.browserView.onSizeAvailable = { width, height ->
            lastViewportWidth = width
            lastViewportHeight = height
            if (tabManager.activeTab?.onViewportSizeChanged(width, height) == true) refreshView()
        }
        binding.browserView.onLinkTapped = { href -> tabManager.activeTab?.followLink(href) }
        binding.browserView.onElementTapped = { element -> handleElementTap(element) }
        binding.browserView.onFormInput = { element, value -> tabManager.activeTab?.dispatchInputEvent(element, value) }
        binding.browserView.onPinchZoomEnded = { factor ->
            tabManager.activeTab?.let { tab ->
                tab.setTextScale(tab.textScale * factor)
                refreshView()
            }
        }

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
                    tabManager.activeTab?.navigate(input, settings.searchTemplate)
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

        val viewIntentUrl = intent?.dataString?.takeIf { intent?.action == Intent.ACTION_VIEW }
        val (savedUrls, savedActiveIndex) = tabSessionStore.load()
        if (savedUrls.isEmpty()) {
            tabManager.newTab()
            tabManager.activeTab?.navigate(viewIntentUrl ?: settings.homePage, settings.searchTemplate)
        } else {
            for (url in savedUrls) {
                tabManager.newTab()
                tabManager.activeTab?.navigate(url, settings.searchTemplate)
            }
            if (viewIntentUrl != null) {
                // A link opened this app fresh alongside a restored session - add it as one more tab.
                tabManager.newTab()
                tabManager.activeTab?.navigate(viewIntentUrl, settings.searchTemplate)
            } else {
                switchToTab(savedActiveIndex)
            }
        }
        binding.editAddress.setText(tabManager.activeTab?.currentUrl?.toString() ?: "")
        updateTabCountButton()

        setUpFindBar()
    }

    /** A link opened from another app (e.g. tapping an http(s) link while this is the default browser) while already running. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) {
            intent.dataString?.let { url ->
                openNewTab()
                binding.editAddress.setText(url)
                tabManager.activeTab?.navigate(url, settings.searchTemplate)
            }
        }
    }

    private fun setUpFindBar() {
        binding.editFindQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performFind(binding.editFindQuery.text.toString())
                true
            } else {
                false
            }
        }
        binding.buttonFindNext.setOnClickListener { stepFind(1) }
        binding.buttonFindPrev.setOnClickListener { stepFind(-1) }
        binding.buttonFindClose.setOnClickListener { hideFindBar() }
    }

    private var findMatches: List<DrawText> = emptyList()
    private var findIndex: Int = -1

    private fun showFindBar() {
        binding.findBar.visibility = View.VISIBLE
        binding.editFindQuery.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.editFindQuery, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideFindBar() {
        binding.findBar.visibility = View.GONE
        findMatches = emptyList()
        findIndex = -1
        binding.browserView.clearFindMatches()
        hideKeyboard()
        binding.browserView.requestFocus()
    }

    /** Per-token substring matching only (see BrowserView.setFindMatches's doc) - a query spanning two rendered words/tokens won't match. */
    private fun performFind(query: String) {
        if (query.isBlank()) {
            findMatches = emptyList()
            findIndex = -1
            binding.browserView.clearFindMatches()
            binding.textFindCount.text = "0/0"
            return
        }
        findMatches = (tabManager.activeTab?.displayList ?: emptyList()).filterIsInstance<DrawText>()
            .filter { it.text.contains(query, ignoreCase = true) }
        findIndex = if (findMatches.isEmpty()) -1 else 0
        updateFindUi()
    }

    private fun stepFind(direction: Int) {
        if (findMatches.isEmpty()) return
        findIndex = (findIndex + direction + findMatches.size) % findMatches.size
        updateFindUi()
    }

    private fun updateFindUi() {
        binding.textFindCount.text = if (findMatches.isEmpty()) "0/0" else "${findIndex + 1}/${findMatches.size}"
        binding.browserView.setFindMatches(findMatches, findIndex)
    }

    private fun onTabStateChanged(tab: TabHandle, state: TabState) {
        // Track title/URL for every tab regardless of which one is on screen,
        // so the switcher list stays accurate for background tabs too.
        when (state) {
            is TabState.Loading -> tabTitles[tab] = getString(R.string.loading)
            is TabState.Loaded -> {
                val label = state.title ?: state.url.toString()
                tabTitles[tab] = label
                if (!tab.isPrivate) historyStore.record(state.url.toString(), label)
            }
            else -> {}
        }
        if (state is TabState.Loaded || state is TabState.Updated) saveSession()

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
                val label = state.title ?: state.url.toString()
                applyWindowTitle(tab, label)
                binding.browserView.clearOverlayViews()
                binding.browserView.resetScroll()
                refreshView()
                updateNavButtons()
                // Announces navigation to a screen reader on every page load, same as before.
                //
                // Beyond this: BrowserView now also exposes a virtual-view AccessibilityNodeProvider
                // (see BrowserAccessibilityNodeProvider.kt) built from the DOM/layout tree - headings,
                // links, form controls, images with alt text, and landmarks each become a real
                // accessibility node with bounds taken from the page's own DisplayCommand layout, in
                // document order, so TalkBack's swipe-to-navigate / double-tap-to-activate gestures
                // should work against the rendered page, not just this one load announcement.
                //
                // IMPORTANT, read before treating this as "done": that provider has NOT been verified
                // against real TalkBack on a device or emulator - none is available in this
                // environment. It's implemented defensively (flat hierarchy, real overlaid EditText
                // children preserved, every framework-facing entry point fails closed instead of
                // crashing - see that class's doc) and the pure tree-building logic it's built on
                // (AccessibilityTreeBuilder.kt) has JVM unit test coverage
                // (AccessibilityTreeBuilderTest.kt), but "implemented and unit-tested" is the accurate
                // claim, not "works" - end-to-end TalkBack behavior remains this feature's top open
                // verification risk, for the same reason noted here previously: a wrong accessibility
                // tree can hang or crash TalkBack for the exact users depending on it.
                binding.browserView.announceForAccessibility(label)
                offerAutofillIfAvailable(tab)
            }
            is TabState.Updated -> {
                state.title?.let { tabTitles[tab] = it; applyWindowTitle(tab, it) }
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
            is TabState.CertificateError -> {
                binding.progressBar.visibility = View.GONE
                updateNavButtons()
                showCertificateErrorDialog(tab, state.url, state.message)
            }
        }
        updateTabCountButton()
    }

    /**
     * Discards non-active tabs' rendered state (DOM, layout, images,
     * interpreter) under memory pressure - this engine's single-process
     * stand-in for how a real multi-process browser kills background tab
     * renderers to reclaim memory. A discarded tab is transparently
     * reloaded (re-fetched) the next time the user switches to it - see
     * Tab.discardForMemoryPressure's doc.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) return
        val active = tabManager.activeTab
        for (tab in tabManager.allTabs()) {
            if (tab !== active) tab.discardForMemoryPressure()
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            HttpCache.clear()
        }
    }

    private fun switchToTab(index: Int) {
        val tab = tabManager.switchTo(index) ?: return
        if (tab.isDiscarded) tab.reload()
        if (lastViewportWidth > 0f && lastViewportHeight > 0f) {
            tab.onViewportSizeChanged(lastViewportWidth, lastViewportHeight)
        }
        binding.editAddress.setText(tab.currentUrl?.toString() ?: "")
        applyWindowTitle(tab, tabTitles[tab] ?: tab.currentUrl?.toString() ?: getString(R.string.untitled_tab))
        binding.browserView.clearOverlayViews()
        binding.browserView.resetScroll()
        refreshView()
        updateNavButtons()
        updateTabCountButton()
        saveSession()
    }

    /** The whole "settings screen": two fields, no PreferenceScreen framework - see Settings.kt's doc. */
    private fun showSettingsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val homePageLabel = TextView(this).apply { text = getString(R.string.settings_home_page) }
        val homePageInput = EditText(this).apply {
            setText(settings.homePage)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            maxLines = 1
        }
        val searchLabel = TextView(this).apply { text = getString(R.string.settings_search_template) }
        val searchInput = EditText(this).apply {
            setText(settings.searchTemplate)
            maxLines = 1
        }
        container.addView(homePageLabel)
        container.addView(homePageInput)
        container.addView(searchLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 32 })
        container.addView(searchInput)

        AlertDialog.Builder(this)
            .setTitle(R.string.menu_settings)
            .setView(container)
            .setPositiveButton(R.string.action_save) { d, _ ->
                settings.homePage = homePageInput.text.toString().trim()
                settings.searchTemplate = searchInput.text.toString().trim()
                d.dismiss()
            }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    private fun applyWindowTitle(tab: TabHandle, text: String) {
        title = if (tab.isPrivate) getString(R.string.private_tab_title_prefix, text) else text
    }

    private fun openNewTab(private: Boolean = false) {
        tabManager.newTab(private)
        switchToTab(tabManager.count() - 1)
        binding.editAddress.requestFocus()
    }

    private fun closeTabAt(index: Int) {
        val closedTab = tabManager.allTabs().getOrNull(index)
        val newActiveTab = tabManager.closeTab(index)
        tabTitles.remove(closedTab)
        switchToTab(tabManager.allTabs().indexOf(newActiveTab))
    }

    /** Never includes private tabs - see TabSessionStore's doc. */
    private fun saveSession() {
        val nonPrivateTabs = tabManager.allTabs().filter { !it.isPrivate }
        val urls = nonPrivateTabs.mapNotNull { it.currentUrl?.toString() }
        val activeIndex = nonPrivateTabs.indexOf(tabManager.activeTab).coerceAtLeast(0)
        tabSessionStore.save(urls, activeIndex)
    }

    /**
     * Renders a tab's *actual* current page content into a small bitmap -
     * not a placeholder icon - by reusing the exact same Canvas paint path
     * as printing (BrowserView.paintFullPageForPrint), scaled to fit.
     * Works for background tabs too, since it only needs a tab's own
     * `displayList`/`contentHeight`, not for it to be the currently
     * visible one. A discarded (memory-pressure-evicted) tab or one that
     * hasn't finished its first layout yet just renders a blank thumbnail
     * (contentHeight is 0) rather than crashing.
     */
    private fun renderTabThumbnail(tab: TabHandle, widthPx: Int, heightPx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val contentWidth = lastViewportWidth.coerceAtLeast(1f)
        val contentHeight = tab.contentHeight.coerceAtLeast(1f)
        val scale = minOf(widthPx / contentWidth, heightPx / contentHeight)
        if (scale.isFinite() && scale > 0f) {
            canvas.save()
            canvas.scale(scale, scale)
            try {
                binding.browserView.paintFullPageForPrint(canvas, tab.displayList)
            } catch (_: Exception) {
                // A thumbnail is a nice-to-have; a rendering hiccup shouldn't break the switcher.
            }
            canvas.restore()
        }
        return bitmap
    }

    private fun showTabSwitcher() {
        lateinit var dialog: AlertDialog
        val tabs = tabManager.allTabs()
        val thumbWidthPx = (resources.displayMetrics.density * 150).toInt()
        val thumbHeightPx = (resources.displayMetrics.density * 110).toInt()
        val gridView = GridView(this)
        gridView.numColumns = 2
        gridView.verticalSpacing = 16
        gridView.horizontalSpacing = 16
        gridView.setPadding(24, 24, 24, 24)
        gridView.adapter = object : BaseAdapter() {
            override fun getCount() = tabs.size
            override fun getItem(position: Int): Any = tabs[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tab = tabs[position]
                val label = tabTitles[tab] ?: tab.currentUrl?.toString() ?: getString(R.string.untitled_tab)

                val cell = FrameLayout(this@MainActivity)
                val isActive = position == tabManager.activeIndex
                cell.setBackgroundColor(themeColor(if (isActive) com.google.android.material.R.attr.colorSecondaryContainer else com.google.android.material.R.attr.colorSurfaceContainerHigh))
                cell.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, thumbHeightPx + 80)

                val thumbnail = ImageView(this@MainActivity).apply {
                    setImageBitmap(renderTabThumbnail(tab, thumbWidthPx, thumbHeightPx))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, thumbHeightPx).apply {
                        setMargins(8, 8, 8, 0)
                    }
                }
                cell.addView(thumbnail)

                val title = TextView(this@MainActivity).apply {
                    text = label
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    textSize = 13f
                    setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
                    setPadding(8, 4, 40, 4)
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                        topMargin = thumbHeightPx + 8
                    }
                }
                cell.addView(title)

                val closeButton = ImageButton(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_close)
                    background = null
                    contentDescription = getString(R.string.action_close)
                    layoutParams = FrameLayout.LayoutParams(64, 64, Gravity.TOP or Gravity.END)
                    setOnClickListener {
                        closeTabAt(position)
                        dialog.dismiss()
                    }
                }
                cell.addView(closeButton)

                cell.setOnClickListener {
                    switchToTab(position)
                    dialog.dismiss()
                }
                return cell
            }
        }
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tabs_dialog_title)
            .setView(gridView)
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
        popup.menu.add(0, 4, 3, R.string.menu_find_in_page).isEnabled = currentUrl != null
        popup.menu.add(0, 5, 4, R.string.menu_share).isEnabled = currentUrl != null
        popup.menu.add(0, 6, 5, R.string.menu_desktop_site).apply {
            isCheckable = true
            isChecked = tabManager.activeTab?.desktopMode == true
            isEnabled = currentUrl != null
        }
        popup.menu.add(0, 7, 6, R.string.menu_dark_mode).apply {
            isCheckable = true
            isChecked = darkModeEnabled
        }
        popup.menu.add(0, 8, 7, R.string.menu_settings)
        popup.menu.add(0, 9, 8, R.string.menu_print).isEnabled = currentUrl != null
        popup.menu.add(0, 10, 9, R.string.menu_reader_mode).apply {
            isCheckable = true
            isChecked = tabManager.activeTab?.readerModeActive == true
            isEnabled = currentUrl != null
        }
        popup.menu.add(0, 11, 10, R.string.menu_tracking_protection).apply {
            isCheckable = true
            isChecked = TrackingProtection.enabled
        }
        popup.menu.add(0, 12, 11, R.string.menu_new_private_tab)
        popup.menu.add(0, 13, 12, R.string.menu_add_to_home_screen).isEnabled = currentUrl != null
        popup.menu.add(0, 14, 13, R.string.menu_passwords)
        // Every new tab runs its engine in a sandboxed pooled process by default (see
        // TabManager.newTab's doc) - this toggle is the fallback escape hatch (Settings.
        // sandboxedTabsEnabled's doc), not a demo entry point. It only affects tabs opened from
        // here on; already-open tabs keep whichever kind they were created as.
        popup.menu.add(0, 15, 14, R.string.menu_sandboxed_tabs).apply {
            isCheckable = true
            isChecked = settings.sandboxedTabsEnabled
        }
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
                4 -> { showFindBar(); true }
                5 -> {
                    val url = currentUrl ?: return@setOnMenuItemClickListener true
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, url)
                    }
                    startActivity(Intent.createChooser(shareIntent, getString(R.string.menu_share)))
                    true
                }
                6 -> { tabManager.activeTab?.toggleDesktopMode(); true }
                7 -> {
                    darkModeEnabled = !darkModeEnabled
                    binding.browserView.setDarkMode(darkModeEnabled)
                    binding.browserView.clearOverlayViews()
                    refreshView()
                    true
                }
                8 -> { showSettingsDialog(); true }
                9 -> { printCurrentPage(); true }
                10 -> { tabManager.activeTab?.toggleReaderMode(); true }
                11 -> {
                    TrackingProtection.enabled = !TrackingProtection.enabled
                    settings.trackingProtectionEnabled = TrackingProtection.enabled
                    true
                }
                12 -> { openNewTab(private = true); true }
                13 -> { addToHomeScreen(); true }
                14 -> { showPasswordsDialog(); true }
                15 -> { settings.sandboxedTabsEnabled = !settings.sandboxedTabsEnabled; true }
                else -> false
            }
        }
        popup.show()
    }

    /** Shared row builder for the tabs/bookmarks/history dialogs: a label, tap to act, X to remove. */
    private fun buildListRow(parent: ViewGroup, label: String, onTap: () -> Unit, onRemove: () -> Unit): View {
        val row = LayoutInflater.from(this).inflate(R.layout.dialog_list_row, parent, false)
        row.findViewById<TextView>(R.id.rowLabel).text = label
        row.findViewById<ImageButton>(R.id.rowClose).setOnClickListener { onRemove() }
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
                        parent,
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
                        parent,
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
        // Re-set on every refresh (cheap, idempotent) rather than only once per Tab, since Tab
        // instances come and go (new tabs, tab-switching) and this is the one place guaranteed to
        // run before the active tab could plausibly have a download triggered against it.
        tab.onDownloadRequested = { url, filename -> startDownload(url, filename) }
        tab.onLoginFormSubmitted = { origin, username, password -> offerSavePassword(origin, username, password) }
        binding.browserView.setContent(tab.displayList, tab.contentHeight, tab.currentDoc)
    }

    /** Offers to fill in a saved login the first time a matching login form appears on a freshly loaded page - never re-prompted on every keystroke, since this only runs from TabState.Loaded. */
    private fun offerAutofillIfAvailable(tab: TabHandle) {
        val origin = tab.currentOrigin() ?: return
        val saved = credentialStore.credentialsForOrigin(origin).firstOrNull() ?: return
        // Detecting the form (and whether it's already filled in - e.g. by the page itself) is
        // async for a sandboxed tab (see TabHandle.detectLoginForm's doc), so the dialog only shows
        // up once the reply actually arrives - by which point the user may have switched tabs, hence
        // the tabManager.activeTab === tab re-check before acting on it.
        tab.detectLoginForm { fields ->
            if (fields == null || fields.usernameAlreadyFilled) return@detectLoginForm
            if (tabManager.activeTab !== tab) return@detectLoginForm
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.autofill_login_title)
                .setMessage(getString(R.string.autofill_login_message, saved.username, origin))
                .setPositiveButton(R.string.autofill_login_fill) { _, _ ->
                    if (tabManager.activeTab === tab) {
                        tab.autofillLoginForm(fields, saved.username, saved.password)
                    }
                }
                .setNegativeButton(R.string.action_close, null)
                .show()
        }
    }

    /** Offers to save a just-submitted login (see Tab.onLoginFormSubmitted / LoginFormDetector) into the encrypted CredentialStore. Skips silently if that exact origin+username is already saved, or the user previously said "never" for this origin this session. */
    private fun offerSavePassword(origin: String, username: String, password: String) {
        if (origin in neverSaveOrigins) return
        val existing = credentialStore.credentialsForOrigin(origin).firstOrNull { it.username == username }
        if (existing?.password == password) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.save_password_title)
            .setMessage(getString(R.string.save_password_message, origin))
            .setPositiveButton(R.string.save_password_save) { _, _ -> credentialStore.save(origin, username, password) }
            .setNegativeButton(R.string.save_password_never) { _, _ -> neverSaveOrigins.add(origin) }
            .show()
    }

    /** Lists every saved login (username + masked origin, never the plaintext password) with per-entry delete - the one settings surface onto CredentialStore. */
    private fun showPasswordsDialog() {
        lateinit var dialog: AlertDialog
        val listView = ListView(this)
        fun bind() {
            val entries = credentialStore.allCredentials()
            if (entries.isEmpty()) {
                listView.adapter = null
                Toast.makeText(this, R.string.passwords_empty, Toast.LENGTH_SHORT).show()
            }
            listView.adapter = object : BaseAdapter() {
                override fun getCount() = entries.size
                override fun getItem(position: Int) = entries[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val entry: SavedCredential = entries[position]
                    return buildListRow(
                        parent,
                        label = "${entry.username} - ${entry.origin}",
                        onTap = {},
                        onRemove = {
                            credentialStore.delete(entry.origin, entry.username)
                            Toast.makeText(this@MainActivity, R.string.password_deleted, Toast.LENGTH_SHORT).show()
                            bind()
                        }
                    )
                }
            }
        }
        bind()
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.passwords_dialog_title)
            .setView(listView)
            .setNegativeButton(R.string.action_close, null)
            .create()
        dialog.show()
    }

    /**
     * Renders the whole page onto a single PDF page via Android's own
     * PrintManager/PrintedPdfDocument (a platform primitive, same tier as
     * BitmapFactory for images) - the whole scrollable document scaled
     * (preserving aspect ratio) to fit one print page, not real multi-page
     * pagination. A long page ends up small; that's a deliberate, bounded
     * trade-off against the real complexity of correctly splitting content
     * across page boundaries. Form field values aren't included - see
     * BrowserView.paintFullPageForPrint's doc.
     */
    private fun printCurrentPage() {
        val tab = tabManager.activeTab ?: return
        val jobName = tab.currentUrl?.toString() ?: getString(R.string.app_name)
        val printManager = getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager
        val adapter = object : android.print.PrintDocumentAdapter() {
            private var pdfDocument: android.print.pdf.PrintedPdfDocument? = null

            override fun onLayout(
                oldAttrs: android.print.PrintAttributes?,
                newAttrs: android.print.PrintAttributes,
                cancellationSignal: android.os.CancellationSignal?,
                callback: LayoutResultCallback,
                extras: Bundle?
            ) {
                if (cancellationSignal?.isCanceled == true) {
                    callback.onLayoutCancelled()
                    return
                }
                pdfDocument = android.print.pdf.PrintedPdfDocument(this@MainActivity, newAttrs)
                val info = android.print.PrintDocumentInfo.Builder(jobName)
                    .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(1)
                    .build()
                callback.onLayoutFinished(info, true)
            }

            override fun onWrite(
                pages: Array<out android.print.PageRange>,
                destination: android.os.ParcelFileDescriptor,
                cancellationSignal: android.os.CancellationSignal?,
                callback: WriteResultCallback
            ) {
                val doc = pdfDocument
                if (doc == null) {
                    callback.onWriteFailed("No document")
                    return
                }
                try {
                    val page = doc.startPage(0)
                    val canvas = page.canvas
                    val contentWidth = lastViewportWidth.coerceAtLeast(1f)
                    val contentHeight = tab.contentHeight.coerceAtLeast(1f)
                    val scale = minOf(canvas.width / contentWidth, canvas.height / contentHeight)
                    canvas.save()
                    canvas.scale(scale, scale)
                    binding.browserView.paintFullPageForPrint(canvas, tab.displayList)
                    canvas.restore()
                    doc.finishPage(page)
                    java.io.FileOutputStream(destination.fileDescriptor).use { doc.writeTo(it) }
                    callback.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback.onWriteFailed(e.message)
                } finally {
                    doc.close()
                    pdfDocument = null
                }
            }
        }
        printManager.print(jobName, adapter, android.print.PrintAttributes.Builder().build())
    }

    /**
     * The certificate-error interstitial: a real "proceed anyway" flow,
     * not a cosmetic warning that does nothing - accepting scopes a
     * non-validating TLS trust to just this host for the rest of the
     * session (see CertificateExceptions/Url.trustAllSocketFactory), never
     * weakening validation anywhere else.
     */
    private fun showCertificateErrorDialog(tab: TabHandle, url: Url, message: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.certificate_error_title)
            .setMessage(getString(R.string.certificate_error_message, url.host, message))
            .setPositiveButton(R.string.certificate_error_proceed) { d, _ ->
                tab.trustCertificateAndReload(url)
                d.dismiss()
            }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    /**
     * "Add to Home Screen": a pinned launcher shortcut whose Intent
     * re-opens this app at the page's URL (via the same VIEW-intent path
     * `onNewIntent`/`onCreate` already handle for external links) - not a
     * true chromeless "standalone" PWA window, since there's no separate
     * app-shell display mode implemented. Requires API 26+
     * (`ShortcutManager.requestPinShortcut`); on older devices this just
     * tells the user it isn't supported rather than silently doing
     * nothing.
     */
    private fun addToHomeScreen() {
        val tab = tabManager.activeTab ?: return
        val url = tab.currentUrl ?: return
        tab.fetchManifestInfo { name, iconBytes ->
            val bitmap = iconBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            createHomeScreenShortcut(url.toString(), name, bitmap)
        }
    }

    private fun createHomeScreenShortcut(url: String, name: String, bitmap: Bitmap?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, R.string.add_to_home_screen_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        val shortcutManager = getSystemService(ShortcutManager::class.java)
        if (shortcutManager?.isRequestPinShortcutSupported != true) {
            Toast.makeText(this, R.string.add_to_home_screen_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(url)
        }
        val icon = if (bitmap != null) Icon.createWithBitmap(bitmap) else Icon.createWithResource(this, R.drawable.ic_launcher)
        val shortcut = ShortcutInfo.Builder(this, "shortcut-${System.currentTimeMillis()}")
            .setShortLabel(name.take(20))
            .setLongLabel(name)
            .setIcon(icon)
            .setIntent(intent)
            .build()
        shortcutManager.requestPinShortcut(shortcut, null)
    }

    /** Hands the URL off to Android's own DownloadManager - a platform primitive (like BitmapFactory for images), not "browser engine" logic. */
    private fun startDownload(url: String, suggestedFilename: String?) {
        try {
            val uri = Uri.parse(url)
            val filename = suggestedFilename ?: uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: "download"
            val request = DownloadManager.Request(uri)
                .setTitle(filename)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(this, getString(R.string.download_started, filename), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.download_failed), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Routes a tap that BrowserView hit-tested to a source element (see
     * LayoutBox's form-control doc comment). A generic element still just
     * bubbles through DOM click dispatch as before; a form control also
     * gets its type-specific behavior - toggling a checkbox/radio in place,
     * prompting for a new value via a dialog, or submitting the form.
     */
    private fun handleElementTap(element: ElementNode) {
        val tab = tabManager.activeTab ?: return
        when (element.tag) {
            "select" -> promptForSelectValue(element, tab)
            // Text fields normally never reach here (they're overlaid EditText views), but keep the dialog as a fallback.
            "textarea" -> promptForFieldValue(element, tab)
            "input" -> when ((element.attr("type") ?: "text").lowercase()) {
                "hidden" -> {}
                "checkbox", "radio", "submit", "button", "reset", "image", "file" -> tab.dispatchClick(element)
                else -> promptForFieldValue(element, tab)
            }
            // dispatchClick handles JS click events, checkbox/radio toggling, submit buttons and links.
            else -> tab.dispatchClick(element)
        }
    }

    private fun promptForFieldValue(element: ElementNode, tab: TabHandle) {
        // Prefilling the dialog needs the field's current value, which for a sandboxed tab is a
        // round trip to the engine process rather than a synchronous read - see
        // TabHandle.currentFieldValue's doc.
        tab.currentFieldValue(element) { currentValue ->
            val input = EditText(this).apply {
                setText(currentValue)
                setSelection(text.length)
                if (element.tag == "input" && (element.attr("type") ?: "").lowercase() == "password") {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }
            AlertDialog.Builder(this)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ -> tab.setFieldValue(element, input.text.toString()) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun promptForSelectValue(element: ElementNode, tab: TabHandle) {
        // The option list itself lives in the engine process's live DOM for a sandboxed tab, so
        // this is also async - see TabHandle.requestSelectOptions's doc.
        tab.requestSelectOptions(element) { options ->
            if (options.isEmpty()) return@requestSelectOptions
            val labels = options.map { it.label }.toTypedArray()
            AlertDialog.Builder(this)
                .setItems(labels) { _, which -> tab.setSelectValue(element, options[which].node) }
                .show()
        }
    }

    /** Resolves a Material theme color attribute against the current (light/dark) theme. */
    private fun themeColor(attr: Int): Int = com.google.android.material.color.MaterialColors.getColor(this, attr, Color.GRAY)

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
