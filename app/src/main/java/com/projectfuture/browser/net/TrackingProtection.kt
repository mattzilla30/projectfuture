package com.projectfuture.browser.net

/**
 * A minimal domain-blocklist tracker/ad blocker: refuses to load
 * subresources (scripts, images, stylesheets, fonts, fetch()/XHR targets)
 * from a short, hardcoded list of well-known third-party tracking/ad
 * domains when [enabled]. This is nowhere near a real tracking-protection
 * list - EasyList/EasyPrivacy have tens of thousands of entries, updated
 * continuously by a community; this is a bounded, illustrative subset of
 * some of the most common trackers, not a serious privacy guarantee, and
 * it never blocks the page's own first-party domain regardless of what's
 * in the list.
 */
object TrackingProtection {
    var enabled: Boolean = false

    private val BLOCKED_DOMAINS = setOf(
        "doubleclick.net", "googlesyndication.com", "googletagmanager.com", "google-analytics.com",
        "googleadservices.com", "adservice.google.com", "facebook.net", "connect.facebook.net",
        "scorecardresearch.com", "adnxs.com", "outbrain.com", "taboola.com", "criteo.com",
        "moatads.com", "amazon-adsystem.com", "quantserve.com", "hotjar.com"
    )

    fun isBlocked(pageUrl: Url, target: Url): Boolean {
        if (!enabled) return false
        if (isSameOrigin(pageUrl, target)) return false
        return BLOCKED_DOMAINS.any { target.host.equals(it, ignoreCase = true) || target.host.endsWith(".$it", ignoreCase = true) }
    }
}
