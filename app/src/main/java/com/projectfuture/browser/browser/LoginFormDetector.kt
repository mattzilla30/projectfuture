package com.projectfuture.browser.browser

import com.projectfuture.browser.html.ElementNode
import com.projectfuture.browser.html.walkElements

/** A detected login form: its `<form>` element plus the username-like and password `<input>`s within it. */
data class LoginFormFields(
    val form: ElementNode,
    val usernameField: ElementNode,
    val passwordField: ElementNode
)

private val USERNAME_LIKE_TYPES = setOf("text", "email", "tel")

/**
 * Pure DOM scanning used by the credential manager: no Android dependency,
 * so it's exercised directly in JVM unit tests against a hand-built
 * [ElementNode] tree, the same way HtmlParser/CssParser's own tests do.
 *
 * Detection heuristic (matches what real browsers approximate too - there's
 * no fully reliable way to identify "this is a login form" from markup
 * alone): a `<form>` containing an `<input type=password>`, plus the
 * nearest preceding text-like input (`text`/`email`/`tel`, or a bare
 * `<input>` with no `type` at all) to serve as the username field.
 */
object LoginFormDetector {

    /** Finds the first login-shaped form at or under [root] (which may itself be a `<form>`). Null if none qualifies. */
    fun findLoginForm(root: ElementNode): LoginFormFields? {
        var result: LoginFormFields? = null
        root.walkElements { el ->
            if (result != null || el.tag != "form") return@walkElements
            val inputs = ArrayList<ElementNode>()
            el.walkElements { inner -> if (inner.tag == "input") inputs.add(inner) }
            val passwordIndex = inputs.indexOfFirst { (it.attr("type") ?: "text").lowercase() == "password" }
            if (passwordIndex == -1) return@walkElements
            val usernameField = inputs.subList(0, passwordIndex)
                .lastOrNull { (it.attr("type") ?: "text").lowercase() in USERNAME_LIKE_TYPES }
            if (usernameField != null) {
                result = LoginFormFields(el, usernameField, inputs[passwordIndex])
            }
        }
        return result
    }

    /** Reads the current (submitted) username/password out of a detected form's fields; null if either is blank. */
    fun extractSubmittedCredentials(fields: LoginFormFields): Pair<String, String>? {
        val username = fields.usernameField.attr("value")?.trim().orEmpty()
        val password = fields.passwordField.attr("value").orEmpty()
        if (username.isEmpty() || password.isEmpty()) return null
        return username to password
    }
}
