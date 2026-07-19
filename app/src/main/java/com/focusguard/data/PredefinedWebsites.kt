package com.focusguard.data

/** Sites offered as shortcuts in the website picker, kept separate from app packages. */
object PredefinedWebsites {
    data class WebsiteInfo(
        val name: String,
        val domain: String,
        val iconDomain: String = domain
    )

    val POPULAR = listOf(
        WebsiteInfo("YouTube", "youtube.com"),
        WebsiteInfo("Instagram", "instagram.com"),
        WebsiteInfo("Facebook", "facebook.com"),
        WebsiteInfo("TikTok", "tiktok.com"),
        WebsiteInfo("X (Twitter)", "twitter.com", "x.com"),
        WebsiteInfo("Reddit", "reddit.com")
    )

    val ADULT_DOMAINS = listOf(
        "pornhub.com",
        "xvideos.com",
        "xnxx.com",
        "redtube.com",
        "spankbang.com",
        "eporner.com",
        "xhamster.com",
        "rule34.xxx"
    )
}
