package com.projectfuture.browser.net

/**
 * Hosts the user has explicitly clicked through a TLS certificate warning
 * for - the "proceed anyway" side of the certificate-error interstitial
 * (see Tab.kt's TabState.CertificateError and MainActivity's handling of
 * it). Scoped per-host and session-only (not persisted - the warning
 * comes back next launch), so accepting one bad certificate never weakens
 * validation for any other site. See Url.openSocket for where this is
 * actually consulted to swap in a non-validating SSLContext.
 */
object CertificateExceptions {
    private val allowedHosts = HashSet<String>()

    // Hostnames are case-insensitive (DNS/RFC 4343); compare/store normalized so an exception
    // recorded for one casing of a host (e.g. from a redirect or an address-bar typo) is still
    // honored - and, just as importantly, so it stays scoped to that one host either way rather
    // than silently failing to apply and re-prompting, or (if some other lookup ever normalized
    // differently) drifting into matching a host it was never granted for.
    fun isAllowed(host: String): Boolean = host.lowercase() in allowedHosts

    fun allow(host: String) {
        allowedHosts.add(host.lowercase())
    }
}
