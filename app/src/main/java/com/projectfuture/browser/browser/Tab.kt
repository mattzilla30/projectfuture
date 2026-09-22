package com.projectfuture.browser.browser

import android.os.Handler
import android.os.Looper
import com.projectfuture.browser.css.CssParser
import com.projectfuture.browser.css.CssRule
import com.projectfuture.browser.css.computeStyles
import com.projectfuture.browser.html.ElementNode
import com.projectfuture.browser.html.HtmlParser
import com.projectfuture.browser.html.TextNode
import com.projectfuture.browser.html.walkElements
import com.projectfuture.browser.layout.DisplayCommand
import com.projectfuture.browser.layout.DocumentLayout
import com.projectfuture.browser.net.Url
import java.util.concurrent.Executors

sealed class TabState {
    data class Loading(val url: Url) : TabState()
    data class Loaded(val url: Url, val title: String?) : TabState()
    data class Error(val url: Url, val message: String) : TabState()
}

private enum class HistoryAction { PUSH, NONE }

/**
 * Owns one page's lifecycle: fetch -> parse -> style -> layout, all off the
 * main thread except the final layout/paint handoff, plus back/forward
 * history. This is the only place that talks to the network and DOM/CSS/
 * layout modules together.
 */
class Tab(private val onStateChanged: (TabState) -> Unit) {

    private val history = ArrayList<Url>()
    private var historyIndex = -1
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    var currentUrl: Url? = null
        private set
    var currentDoc: ElementNode? = null
        private set
    var displayList: List<DisplayCommand> = emptyList()
        private set
    var contentHeight: Float = 0f
        private set
    private var viewportWidth: Float = 0f

    fun canGoBack() = historyIndex > 0
    fun canGoForward() = historyIndex in 0 until (history.size - 1)

    fun navigate(addressBarInput: String) {
        load(Url.fromAddressBar(addressBarInput), HistoryAction.PUSH)
    }

    fun goBack() {
        if (!canGoBack()) return
        historyIndex--
        load(history[historyIndex], HistoryAction.NONE)
    }

    fun goForward() {
        if (!canGoForward()) return
        historyIndex++
        load(history[historyIndex], HistoryAction.NONE)
    }

    fun reload() {
        currentUrl?.let { load(it, HistoryAction.NONE) }
    }

    fun followLink(href: String) {
        val base = currentUrl ?: return
        load(base.resolve(href), HistoryAction.PUSH)
    }

    /** Called by the view when its width changes; re-runs layout without re-fetching. */
    fun onViewportWidthChanged(widthPx: Float): Boolean {
        if (widthPx <= 0f || widthPx == viewportWidth) return false
        viewportWidth = widthPx
        relayout()
        return true
    }

    private fun load(url: Url, action: HistoryAction) {
        onStateChanged(TabState.Loading(url))
        executor.execute {
            try {
                val response = url.fetch()
                val root = HtmlParser(response.body).parse()
                val authorRules = collectAuthorCss(root, response.url)
                computeStyles(root, authorRules)
                val title = extractTitle(root)
                mainHandler.post {
                    currentUrl = response.url
                    currentDoc = root
                    when (action) {
                        HistoryAction.PUSH -> {
                            while (history.size > historyIndex + 1) history.removeAt(history.size - 1)
                            history.add(response.url)
                            historyIndex = history.size - 1
                        }
                        HistoryAction.NONE -> if (history.isEmpty()) {
                            history.add(response.url)
                            historyIndex = 0
                        }
                    }
                    relayout()
                    onStateChanged(TabState.Loaded(response.url, title))
                }
            } catch (e: Exception) {
                mainHandler.post { onStateChanged(TabState.Error(url, e.message ?: e.toString())) }
            }
        }
    }

    private fun relayout() {
        val doc = currentDoc ?: return
        if (viewportWidth <= 0f) return
        val docLayout = DocumentLayout(doc)
        displayList = docLayout.layout(viewportWidth)
        contentHeight = docLayout.height
    }

    private fun collectAuthorCss(root: ElementNode, baseUrl: Url): List<CssRule> {
        val rules = ArrayList<CssRule>()
        root.walkElements { el ->
            when {
                el.tag == "style" -> {
                    val text = el.children.filterIsInstance<TextNode>().joinToString("") { it.text }
                    rules.addAll(CssParser(text).parseRules())
                }
                el.tag == "link" && el.attr("rel")?.lowercase()?.contains("stylesheet") == true -> {
                    val href = el.attr("href")
                    if (href != null) {
                        try {
                            val response = baseUrl.resolve(href).fetch()
                            rules.addAll(CssParser(response.body).parseRules())
                        } catch (_: Exception) {
                            // A failed stylesheet fetch shouldn't block the page from rendering.
                        }
                    }
                }
            }
        }
        return rules
    }

    private fun extractTitle(root: ElementNode): String? {
        var title: String? = null
        root.walkElements { el ->
            if (el.tag == "title" && title == null) {
                val text = el.children.filterIsInstance<TextNode>().joinToString("") { it.text }.trim()
                if (text.isNotEmpty()) title = text
            }
        }
        return title
    }

    fun destroy() {
        executor.shutdownNow()
    }
}
