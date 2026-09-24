package com.projectfuture.browser.net.http2

import android.os.Build
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket

/**
 * ALPN (RFC 7301) negotiation over the plain [SSLSocket] this project already uses (see
 * `Url.openSocket`) - the "which protocol did we actually get" handshake extension a server uses
 * to tell a client "speak HTTP/2 to me" during the TLS handshake itself, before a single HTTP byte
 * is exchanged.
 *
 * `SSLParameters.setApplicationProtocols`/`SSLSocket.getApplicationProtocol` is the standard JSSE
 * API for this, but it only exists from API 29 (Android 10) onward - this app's minSdk is 24.
 * Android *does* support ALPN on 24-28 too, but only by reflecting into the platform's internal
 * Conscrypt socket implementation (`setAlpnProtocols`/`getAlpnSelectedProtocol` on
 * `com.android.org.conscrypt.OpenSSLSocketImpl` from a private hidden-API surface) - the
 * OkHttp/Google "Platform.java" trick. That reflection is (a) against a non-public,
 * version-fragile internal class it's the object of hidden-API restrictions in the first place,
 * and (b) exactly the kind of fragile-by-construction code this project has otherwise avoided.
 * Given the effort budget for this feature, the deliberate scoping choice is: use the real
 * `SSLParameters` API where it exists (API 29+) and skip ALPN below that - those devices simply
 * always negotiate plain HTTP/1.1, which is the same behavior this app had before HTTP/2 support
 * existed at all, not a regression.
 */
object Alpn {
    private val PROTOCOLS = arrayOf("h2", "http/1.1")

    /** Must be called after `connect()` but before `startHandshake()`. No-op below API 29. */
    fun offer(socket: SSLSocket) {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val params: SSLParameters = socket.sslParameters
            params.applicationProtocols = PROTOCOLS
            socket.sslParameters = params
        } catch (_: Throwable) {
            // Best-effort: an unexpected SSLSocket implementation that doesn't support this
            // shouldn't take down the request - it just means no ALPN, i.e. HTTP/1.1 as before.
        }
    }

    /** Must be called after `startHandshake()` completes. Null means no ALPN protocol was negotiated. */
    fun negotiated(socket: SSLSocket): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        return try {
            socket.applicationProtocol?.takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        }
    }
}
