package com.kguard.app.vpn

import com.kguard.app.data.model.AgeProfile

/**
 * Forces the search engines' and YouTube's own "safe" modes by DNS: a lookup for the normal host is answered
 * with a CNAME to the vendor's enforcement host (the mechanism Google and Microsoft document for this).
 * Nothing is rewritten for adult profiles, and exact host names only, so no other Google sites are touched.
 *
 * DuckDuckGo is deliberately NOT rewritten: it has no DNS-level safe-search enforcement host, so aliasing
 * one would look like protection without doing anything.
 *
 * ponytail: YouTube always uses strict Restricted Mode. The app's teen profile spans 13-17 and plan.md's
 * `restrictmoderate.youtube.com` is for 16-17, so a separate 16-17 profile is needed before using it.
 */
object SafeSearch {
    const val GOOGLE = "forcesafesearch.google.com"
    const val YOUTUBE = "restrict.youtube.com"
    const val BING = "strict.bing.com"

    // google.com, google.co.ug, google.com.au, google.fr ... with or without www.
    private val GOOGLE_HOSTS = Regex("^(www\\.)?google\\.(com|co\\.[a-z]{2}|com\\.[a-z]{2}|[a-z]{2})$")

    private val YOUTUBE_HOSTS = setOf(
        "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com",
        "youtubei.googleapis.com", "youtube.googleapis.com",
        "youtube-nocookie.com", "www.youtube-nocookie.com"
    )

    /** The host [domain] should be aliased to for [age], or null if it should be answered normally. */
    fun targetFor(domain: String, age: AgeProfile): String? {
        if (age == AgeProfile.ADULTS_18_PLUS) return null
        val d = DomainFilter.normalise(domain)
        return when {
            GOOGLE_HOSTS.matches(d) -> GOOGLE
            d in YOUTUBE_HOSTS -> YOUTUBE
            d == "bing.com" || d == "www.bing.com" -> BING
            else -> null
        }
    }
}
