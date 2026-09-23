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

    fun isAllowed(host: String): Boolean = host in allowedHosts

    fun allow(host: String) {
        allowedHosts.add(host)
    }
}
