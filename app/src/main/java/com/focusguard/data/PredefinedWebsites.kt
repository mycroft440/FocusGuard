package com.focusguard.data

/** Sites offered as shortcuts in the website picker, kept separate from app packages. */
object PredefinedWebsites {
    /**
     * Regra persistida para o atalho único de pornografia. Ela é expandida
     * somente durante a fiscalização, mantendo a categoria como um único item
     * nas sessões, limites e telas de configuração.
     */
    const val PORNOGRAPHY_RULE = "category:pornography"
    const val PORNOGRAPHY_NAME = "Pornografia"

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

    /**
     * Every site the app can offer as a shortcut: [POPULAR] first, then the
     * website of each preventive app that is not already on it.
     *
     * The app catalogue already knows the domain behind each distraction it
     * lists, so betting, dating and streaming sites are reachable without asking
     * the user to remember how each one is spelled. [POPULAR] stays a short list
     * for places too small to scroll — a dialog, a chip row.
     */
    val ALL_PRESETS: List<WebsiteInfo> by lazy {
        val seen = POPULAR.mapTo(linkedSetOf()) { it.domain }
        POPULAR + PredefinedApps.PREVENTIVE_APPS.mapNotNull { app ->
            app.domain
                ?.takeIf { it.isNotBlank() && seen.add(it) }
                ?.let { WebsiteInfo(app.appName, it) }
        }
    }

    val PORNOGRAPHY_KEYWORDS = listOf(
        "porn",
        "xxx",
        "sex",
        "xvideos"
    )

    /**
     * Fallback local para domínios adultos conhecidos. O filtro DNS familiar
     * do Device Owner continua sendo a fonte ampla e atualizada por categoria;
     * esta lista mantém cobertura quando DNS gerenciado não está disponível.
     */
    val ADULT_DOMAINS = listOf(
        "pornhub.com",
        "xvideos.com",
        "xnxx.com",
        "redtube.com",
        "spankbang.com",
        "eporner.com",
        "xhamster.com",
        "rule34.xxx",
        "youporn.com",
        "tube8.com",
        "beeg.com",
        "tnaflix.com",
        "motherless.com",
        "hclips.com",
        "drtuber.com",
        "pornmd.com",
        "hqporner.com",
        "fuq.com",
        "4tube.com",
        "porntrex.com",
        "pornone.com",
        "thumbzilla.com",
        "keezmovies.com",
        "nuvid.com",
        "empflix.com",
        "sunporno.com",
        "youjizz.com",
        "manyvids.com",
        "onlyfans.com",
        "fansly.com",
        "chaturbate.com",
        "stripchat.com",
        "bongacams.com",
        "livejasmin.com",
        "cam4.com",
        "myfreecams.com",
        "fapello.com"
    )
}
