package com.projectfuture.browser

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.projectfuture.browser.browser.Tab
import com.projectfuture.browser.browser.TabState
import com.projectfuture.browser.databinding.ActivityMainBinding

private const val START_URL = "https://example.com/"

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var tab: Tab

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tab = Tab(this, ::onTabStateChanged)

        binding.browserView.onSizeAvailable = { width, height ->
            if (tab.onViewportSizeChanged(width, height)) refreshView()
        }
        binding.browserView.onLinkTapped = { href -> tab.followLink(href) }
        binding.browserView.onElementTapped = { element -> tab.dispatchClick(element) }

        binding.buttonBack.setOnClickListener { tab.goBack() }
        binding.buttonForward.setOnClickListener { tab.goForward() }
        binding.buttonReload.setOnClickListener { tab.reload() }

        binding.editAddress.setOnEditorActionListener { _, actionId, event ->
            val committed = actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (committed) {
                val input = binding.editAddress.text.toString()
                if (input.isNotBlank()) {
                    tab.navigate(input)
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
                if (tab.canGoBack()) {
                    tab.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        binding.editAddress.setText(START_URL)
        tab.navigate(START_URL)
    }

    private fun onTabStateChanged(state: TabState) {
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
            is TabState.Updated -> refreshView()
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
    }

    private fun refreshView() {
        binding.browserView.setContent(tab.displayList, tab.contentHeight)
    }

    private fun updateNavButtons() {
        binding.buttonBack.isEnabled = tab.canGoBack()
        binding.buttonForward.isEnabled = tab.canGoForward()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.editAddress.windowToken, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        tab.destroy()
    }
}
