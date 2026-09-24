package com.projectfuture.browser.net

import java.lang.reflect.Proxy
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import org.junit.Assert.assertEquals
import org.junit.Test

class TlsHostnameTest {
    private val session = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SSLSession::class.java)) { _, _, _ -> null } as SSLSession

    @Test fun acceptsWhenTheVerifierMatches() {
        var checked: String? = null
        TlsHostname.requireMatch("example.com", session) { host, _ -> checked = host; true }
        assertEquals("example.com", checked)
    }

    @Test(expected = SSLPeerUnverifiedException::class)
    fun rejectsACertificateForAnotherHost() {
        TlsHostname.requireMatch("bank.example", session) { _, _ -> false }
    }
}
