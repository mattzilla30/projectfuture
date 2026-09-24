package com.projectfuture.browser.browser

import com.projectfuture.browser.html.ElementNode
import com.projectfuture.browser.html.TextNode
import com.projectfuture.browser.html.walkElements
import java.net.URLEncoder

/**
 * Pure `<form>` -> name/value pairs extraction, split out of [Tab.submitForm]
 * so it can be exercised directly on the host JVM (like [extractReaderContent]
 * in ReaderMode.kt) instead of only ever being checked by "it compiles" -
 * `Tab` itself can't be constructed in a plain JUnit test since its
 * constructor touches real `Handler`/`Looper`.
 *
 * Per-control-type submission rules (mirrors what real browsers do):
 *  - `disabled` controls, and any without a `name`, are excluded entirely.
 *  - `submit`/`button`/`reset`/`image`/`file` inputs never contribute a value
 *    here (a clicked submit button's own name=value pair, if any, is added
 *    by the caller instead - not every submit control was necessarily the
 *    one clicked).
 *  - `checkbox`/`radio` only contribute when `checked` - since a radio
 *    group's un-checked siblings are already made mutually exclusive by
 *    [Tab.toggleFormControlIfNeeded]/[Tab.selectRadio], at most one member of
 *    a `name` group is ever `checked` at submit time.
 *  - `<select multiple>` contributes one pair per selected `<option>` (every
 *    one, not just the first); a plain `<select>` contributes its first
 *    selected option, or its first option if none is explicitly selected -
 *    matching the browser default of "nothing explicitly chosen selects the
 *    first option".
 */
fun collectFormParams(form: ElementNode): List<Pair<String, String>> {
    val params = ArrayList<Pair<String, String>>()
    form.walkElements { el ->
        if (el === form) return@walkElements
        val name = el.attr("name") ?: return@walkElements
        if (el.attributes.containsKey("disabled")) return@walkElements
        when (el.tag) {
            "input" -> when ((el.attr("type") ?: "text").lowercase()) {
                "submit", "button", "reset", "image", "file" -> {}
                "checkbox", "radio" -> if (el.attr("checked") != null) params.add(name to (el.attr("value") ?: "on"))
                else -> params.add(name to (el.attr("value") ?: ""))
            }
            "textarea" -> params.add(name to textareaText(el))
            "select" -> {
                val options = el.children.filterIsInstance<ElementNode>().filter { it.tag == "option" }
                val selectedOptions = options.filter { it.attr("selected") != null }
                val chosen = if (el.attributes.containsKey("multiple")) {
                    selectedOptions
                } else {
                    listOfNotNull(selectedOptions.firstOrNull() ?: options.firstOrNull())
                }
                for (opt in chosen) {
                    val value = opt.attr("value") ?: opt.children.filterIsInstance<TextNode>().joinToString("") { it.text }
                    params.add(name to value)
                }
            }
        }
    }
    return params
}

/** A `<textarea>`'s current text content, same as [Tab.currentFieldValue]'s textarea branch. */
fun textareaText(el: ElementNode): String = el.children.filterIsInstance<TextNode>().joinToString("") { it.text }

/** `application/x-www-form-urlencoded` key=value&... encoding of [params]. */
fun encodeFormParams(params: List<Pair<String, String>>): String =
    params.joinToString("&") { (k, v) -> "${formUrlEncode(k)}=${formUrlEncode(v)}" }

fun formUrlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")
