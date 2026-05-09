package com.focusguard.data

object PredefinedApps {

    data class AppInfo(
        val packageName: String,
        val appName: String,
        val category: String,
        val domain: String? = null
    )

    val PREVENTIVE_APPS = listOf(
        // The Big 3 (Always on top)
        AppInfo("com.instagram.android", "Instagram", "Redes Sociais", "instagram.com"),
        AppInfo("com.facebook.katana", "Facebook", "Redes Sociais", "facebook.com"),
        AppInfo("com.google.android.youtube", "YouTube", "Streaming", "youtube.com"),

        // Redes Sociais & Comunicação Curta
        AppInfo("com.zhiliaoapp.musically", "TikTok", "Redes Sociais", "tiktok.com"),
        AppInfo("com.kwai.video", "Kwai", "Redes Sociais", "kwai.com"),
        AppInfo("com.twitter.android", "X (Twitter)", "Redes Sociais", "x.com"),
        AppInfo("com.snapchat.android", "Snapchat", "Redes Sociais", "snapchat.com"),
        AppInfo("com.pinterest", "Pinterest", "Redes Sociais", "pinterest.com"),
        AppInfo("com.reddit.frontpage", "Reddit", "Redes Sociais", "reddit.com"),
        AppInfo("org.telegram.messenger", "Telegram", "Comunicação", "telegram.org"),
        AppInfo("com.discord", "Discord", "Comunicação", "discord.com"),

        // Streaming & Entretenimento
        AppInfo("com.netflix.mediaclient", "Netflix", "Streaming", "netflix.com"),
        AppInfo("com.amazon.avod.thirdpartyclient", "Prime Video", "Streaming", "amazon.com"),
        AppInfo("com.disney.disneyplus", "Disney+", "Streaming", "disneyplus.com"),
        AppInfo("com.hbomax.ninja", "Max (HBO)", "Streaming", "max.com"),
        AppInfo("tv.twitch.android.app", "Twitch", "Streaming", "twitch.tv"),
        AppInfo("com.spotify.music", "Spotify", "Streaming", "spotify.com"),

        // Jogos de Azar / Apostas (Bets)
        AppInfo("com.bet365Wrapper.Bet365_Application", "Bet365", "Apostas", "bet365.com"),
        AppInfo("com.betano.android", "Betano", "Apostas", "betano.com"),
        AppInfo("com.sportingbet.sports", "Sportingbet", "Apostas", "sportingbet.com"),
        AppInfo("com.betfair", "Betfair", "Apostas", "betfair.com"),
        AppInfo("com.blaze.app", "Blaze", "Apostas", "blaze.com"),
        AppInfo("com.pixbet.app", "Pixbet", "Apostas", "pixbet.com"),

        // Namoro
        AppInfo("com.tinder", "Tinder", "Namoro", "tinder.com"),
        AppInfo("com.badoo.mobile", "Badoo", "Namoro", "badoo.com"),
        AppInfo("com.bumble.app", "Bumble", "Namoro", "bumble.com"),
        AppInfo("com.ftw_and_co.happn", "Happn", "Namoro", "happn.com"),
        AppInfo("com.grindrapp.android", "Grindr", "Namoro", "grindr.com"),

        // Jogos Viciantes
        AppInfo("com.dts.freefireth", "Free Fire", "Jogos", "ff.garena.com"),
        AppInfo("com.roblox.client", "Roblox", "Jogos", "roblox.com"),
        AppInfo("com.tencent.ig", "PUBG Mobile", "Jogos", "pubgmobile.com"),
        AppInfo("com.king.candycrushsaga", "Candy Crush", "Jogos", "king.com"),
        AppInfo("com.supercell.clashofclans", "Clash of Clans", "Jogos", "supercell.com"),
        AppInfo("com.mobile.legends", "Mobile Legends", "Jogos", "mobilelegends.com"),
        AppInfo("com.riotgames.league.wildrift", "Wild Rift", "Jogos", "wildrift.leagueoflegends.com"),

        // Sites Populares (Versão Web)
        AppInfo("site:youtube.com", "YouTube (Site)", "Sites", "youtube.com"),
        AppInfo("site:facebook.com", "Facebook (Site)", "Sites", "facebook.com"),
        AppInfo("site:instagram.com", "Instagram (Site)", "Sites", "instagram.com"),
        AppInfo("site:tiktok.com", "TikTok (Site)", "Sites", "tiktok.com"),
        AppInfo("site:twitter.com", "X/Twitter (Site)", "Sites", "twitter.com"),

        // Pornografia (Jejum de Dopamina Pesado)
        AppInfo("site:pornhub.com", "Pornhub", "Pornografia", "pornhub.com"),
        AppInfo("site:xvideos.com", "XVideos", "Pornografia", "xvideos.com"),
        AppInfo("site:xnxx.com", "XNXX", "Pornografia", "xnxx.com"),
        AppInfo("site:redtube.com", "RedTube", "Pornografia", "redtube.com"),
        AppInfo("site:spankbang.com", "SpankBang", "Pornografia", "spankbang.com"),
        AppInfo("site:eporner.com", "EPorner", "Pornografia", "eporner.com"),
        AppInfo("site:xhamster.com", "xHamster", "Pornografia", "xhamster.com"),
        AppInfo("site:rule34.xxx", "Rule34", "Pornografia", "rule34.xxx")
    )
}

