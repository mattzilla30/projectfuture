package com.projectfuture.browser.net

import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession

/**
 * On Android a raw SSLSocket checks that the certificate chain is trusted but not that it was
 * issued for the host being contacted; that check belongs to the HTTP layer. Without it, any
 * trusted certificate for any domain would be accepted for every site.
 */
object TlsHostname {
    fun requireMatch(host: String, session: SSLSession, verifier: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()) {
        if (!verifier.verify(host, session)) {
            throw SSLPeerUnverifiedException("The certificate isn't valid for $host")
        }
    }
}
