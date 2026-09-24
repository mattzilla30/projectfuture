package com.projectfuture.browser.net.quic

/**
 * The one piece of "HTTP/3 from scratch" this project does **not** have a genuine implementation
 * of: the TLS 1.3 handshake carried inside QUIC CRYPTO frames (RFC 9001), which QUIC requires
 * *instead of* a normal TLS record layer over a byte stream.
 *
 * ## What was investigated
 *
 * QUIC-over-TLS needs something that behaves like `javax.net.ssl.SSLEngine` in "unwrap raw
 * handshake bytes, hand me back what to put on the wire" mode, but:
 *
 * 1. **`SSLEngine` is not usable this way.** `SSLEngine` (and `SSLSocket`) always assume they own
 *    the *record layer* - `wrap()`/`unwrap()` produce and consume TLS records (type + version +
 *    length + payload), not the bare handshake-message bytes QUIC's CRYPTO frames carry. QUIC
 *    needs the underlying handshake message bytes with **no** record framing, since QUIC does its
 *    own framing (STREAM/CRYPTO frames) and its own record-equivalent protection (packet
 *    protection derived from the TLS secrets, applied per-QUIC-packet, not per-TLS-record - see
 *    [QuicInitialSecrets]). There is no public Android/OpenJDK SSLEngine mode, flag, or
 *    `BCFKS`/`Conscrypt`-specific extension that exposes the handshake byte stream without the
 *    record layer wrapped around it, and no supported way to intercept the derived traffic
 *    secrets (RFC 9001 section 5 requires deriving *QUIC* packet-protection keys from the TLS
 *    key schedule at each encryption-level transition - `SSLEngine` never surfaces the raw
 *    secrets it derives internally; that's `BoringSSL`'s `quic_method` callback API in the C++
 *    world, which the JDK/Android `SSLEngine` has no equivalent of). Real QUIC stacks (quiche,
 *    ngtcp2, msquic, Chromium's own) all solve this by patching or specially building their TLS
 *    library (BoringSSL's `SSL_set_quic_method`, etc.) - not something reachable from
 *    `javax.net.ssl` on stock Android.
 * 2. **No vendored pure-Kotlin/Java TLS 1.3 implementation was added.** Unlike the Brotli decoder
 *    elsewhere in this project (a small, self-contained, permissively-licensed decompressor with
 *    no cryptographic/security surface), a TLS 1.3 client handshake implementation is
 *    security-critical: get record/transcript/key-schedule/certificate-validation logic subtly
 *    wrong and you get a connection that *looks* encrypted and authenticated but isn't. Vendoring
 *    one correctly is not a "read the RFC and write it" task that can be done honestly in the
 *    scope of one session - it needs the kind of scrutiny and test-vector coverage real TLS
 *    libraries get from dedicated security review, which this change did not have. Shipping a
 *    hand-rolled TLS 1.3 stack here would risk exactly the "looks like it works but doesn't
 *    actually secure the connection" outcome this project explicitly wants to avoid.
 *
 * ## What this means for [Http3Client]
 *
 * Everything *around* the handshake - QUIC packet/frame encode-decode ([QuicPacket],
 * [QuicFrame], [QuicVarInt]), Initial-packet AEAD + header protection (the one packet-number
 * space whose keys don't require a completed handshake - see [QuicInitialSecrets], which is
 * real, tested crypto per RFC 9001 5.1-5.4), and QPACK ([Qpack]) are implemented for real and
 * unit-tested. [Http3Client] can build and header-protect a genuine ClientHello-bearing Initial
 * packet, but has nothing that can produce a real TLS 1.3 ClientHello (that requires the missing
 * TLS engine) or process the server's response, so [performHandshake] always throws
 * [UnsupportedOperationException] - it is a clearly-marked stub, not a fake success.
 */
class Http3TlsHandshake {
    /**
     * Always throws. A real implementation would drive a QUIC-aware TLS 1.3 engine (see class
     * doc for why none is available here), feeding it the bytes carried in received CRYPTO
     * frames and taking its output bytes to send in outgoing CRYPTO frames, until it reports the
     * handshake complete and hands back the derived Handshake and 1-RTT traffic secrets for
     * [QuicInitialSecrets]-style key derivation at each level.
     */
    fun performHandshake(serverName: String): Nothing {
        throw UnsupportedOperationException(
            "TLS 1.3 over QUIC is not implemented (no QUIC-compatible TLS engine is available " +
                "on this platform, and no vendored TLS 1.3 stack was added - see Http3TlsHandshake's " +
                "class doc for exactly why). HTTP/3 requests cannot complete past the Initial packet."
        )
    }
}
