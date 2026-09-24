package com.projectfuture.browser.browser

import com.projectfuture.browser.html.HtmlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoginFormDetectorTest {

    @Test fun findsAUsernameAndPasswordFieldInASimpleLoginForm() {
        val html = """
            <html><body>
                <form action="/login" method="post">
                    <input type="text" name="user" value="alice">
                    <input type="password" name="pass" value="hunter2">
                    <button type="submit">Log in</button>
                </form>
            </body></html>
        """.trimIndent()
        val doc = HtmlParser(html).parse()
        val fields = LoginFormDetector.findLoginForm(doc)
        assertEquals("alice", fields?.usernameField?.attr("value"))
        assertEquals("hunter2", fields?.passwordField?.attr("value"))
    }

    @Test fun treatsEmailFieldsAsUsernameFields() {
        val html = """
            <form>
                <input type="email" name="email">
                <input type="password" name="pass">
            </form>
        """.trimIndent()
        val doc = HtmlParser(html).parse()
        val fields = LoginFormDetector.findLoginForm(doc)
        assertEquals("email", fields?.usernameField?.attr("name"))
    }

    @Test fun returnsNullWhenThereIsNoPasswordField() {
        val html = """
            <form>
                <input type="text" name="q">
                <button type="submit">Search</button>
            </form>
        """.trimIndent()
        val doc = HtmlParser(html).parse()
        assertNull(LoginFormDetector.findLoginForm(doc))
    }

    @Test fun returnsNullWhenPasswordFieldHasNoPrecedingUsernameField() {
        val html = """
            <form>
                <input type="password" name="pass">
            </form>
        """.trimIndent()
        val doc = HtmlParser(html).parse()
        assertNull(LoginFormDetector.findLoginForm(doc))
    }

    @Test fun ignoresUnrelatedFieldsAndPicksTheNearestPrecedingUsernameField() {
        val html = """
            <form>
                <input type="checkbox" name="remember">
                <input type="text" name="promo_code">
                <input type="email" name="email">
                <input type="password" name="pass">
            </form>
        """.trimIndent()
        val doc = HtmlParser(html).parse()
        val fields = LoginFormDetector.findLoginForm(doc)
        assertEquals("email", fields?.usernameField?.attr("name"))
    }

    @Test fun extractsSubmittedCredentialsWhenBothFieldsAreFilledIn() {
        val html = """
            <form>
                <input type="text" name="user" value="bob">
                <input type="password" name="pass" value="s3cret">
            </form>
        """.trimIndent()
        val doc = HtmlParser(html).parse()
        val fields = LoginFormDetector.findLoginForm(doc)!!
        assertEquals("bob" to "s3cret", LoginFormDetector.extractSubmittedCredentials(fields))
    }

    @Test fun extractSubmittedCredentialsReturnsNullWhenPasswordIsBlank() {
        val html = """
            <form>
                <input type="text" name="user" value="bob">
                <input type="password" name="pass" value="">
            </form>
        """.trimIndent()
        val doc = HtmlParser(html).parse()
        val fields = LoginFormDetector.findLoginForm(doc)!!
        assertNull(LoginFormDetector.extractSubmittedCredentials(fields))
    }
}
