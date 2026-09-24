package com.projectfuture.browser

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.projectfuture.browser.html.ElementNode
import com.projectfuture.browser.ipc.RemoteTabState
import com.projectfuture.browser.ipc.TabEngineClient
import com.projectfuture.browser.view.BrowserView

/**
 * A real, working demonstration that a tab's JS/DOM/layout engine can run
 * entirely in a separate OS process from this Activity's UI process (see
 * `ipc/TabEngineServiceBase.kt`'s class doc): typing a URL here sends it
 * over Binder/Messenger to a pooled `TabEngineServiceN`, which fetches,
 * parses, scripts, and lays the page out on its own process's threads, and
 * only the resulting display list crosses back - the same [BrowserView]
 * used everywhere else in this app renders it, unmodified.
 *
 * This is intentionally a separate, minimal screen rather than a swap-in
 * replacement for the main multi-tab browsing UI - see [TabEngineClient]'s
 * class doc for exactly what that would still take and why it wasn't
 * attempted this pass (most of `Tab`'s ~40-method surface - reader mode,
 * printing, history, cookies/session persistence, find-in-page, etc. -
 * isn't proxied here).
 */
class SandboxedTabActivity : AppCompatActivity() {

    private lateinit var client: TabEngineClient
    private lateinit var browserView: BrowserView
    private lateinit var addressBar: EditText
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val hintLabel = TextView(this).apply {
            text = getString(R.string.sandboxed_tab_hint)
            setPadding(24, 12, 24, 4)
            setTextColor(Color.GRAY)
        }
        val addressRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(16, 8, 16, 8) }
        addressBar = EditText(this).apply {
            hint = getString(R.string.address_hint)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) { loadCurrentAddress(); true } else false
            }
        }
        addressRow.addView(addressBar)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 12)
            visibility = View.GONE
            isIndeterminate = true
        }
        browserView = BrowserView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        root.addView(hintLabel)
        root.addView(addressRow)
        root.addView(progressBar)
        root.addView(browserView)
        setContentView(root)

        // Assign this tab a slot in the pooled processes - see TabEngineServiceBase's pool doc.
        client = TabEngineClient(applicationContext, tabIndex = 0)
        client.onDisplayListChanged = { commands, contentHeight ->
            runOnUiThread { browserView.setContent(commands, contentHeight) }
        }
        client.onStateChanged = { state ->
            runOnUiThread {
                when (state) {
                    is RemoteTabState.Loading -> {
                        progressBar.visibility = View.VISIBLE
                        addressBar.setText(state.url)
                    }
                    is RemoteTabState.Loaded -> {
                        progressBar.visibility = View.GONE
                        addressBar.setText(state.url)
                        setTitle(state.title ?: state.url)
                    }
                    is RemoteTabState.Error -> {
                        progressBar.visibility = View.GONE
                        Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        browserView.onLinkTapped = { href -> client.loadUrl(href) }
        browserView.onElementTapped = { element: ElementNode -> client.dispatchTap(element) }
        browserView.onFormInput = { element: ElementNode, value: String -> client.dispatchInput(element, value) }
        browserView.onSizeAvailable = { width, height -> client.setViewportSize(width, height) }

        client.bind()
    }

    private fun loadCurrentAddress() {
        val input = addressBar.text?.toString()?.trim().orEmpty()
        if (input.isNotEmpty()) client.loadUrl(input)
    }

    override fun onDestroy() {
        client.unbind()
        super.onDestroy()
    }
}
