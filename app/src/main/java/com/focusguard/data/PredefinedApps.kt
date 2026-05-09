package com.focusguard.data

object PredefinedApps {

    data class AppInfo(
        val packageName: String,
        val appName: String,
        val category: String
    )

    val PREVENTIVE_APPS = listOf(
        // The Big 3 (Always on top)
        AppInfo("com.instagram.android", "Instagram", "Redes Sociais"),
        AppInfo("com.facebook.katana", "Facebook", "Redes Sociais"),
        AppInfo("com.google.android.youtube", "YouTube", "Streaming"),

        // Redes Sociais & Comunicação Curta
        AppInfo("com.zhiliaoapp.musically", "TikTok", "Redes Sociais"),
        AppInfo("com.kwai.video", "Kwai", "Redes Sociais"),
        AppInfo("com.twitter.android", "X (Twitter)", "Redes Sociais"),
        AppInfo("com.snapchat.android", "Snapchat", "Redes Sociais"),
        AppInfo("com.pinterest", "Pinterest", "Redes Sociais"),
        AppInfo("com.reddit.frontpage", "Reddit", "Redes Sociais"),
        AppInfo("org.telegram.messenger", "Telegram", "Comunicação"),
        AppInfo("com.discord", "Discord", "Comunicação"),

        // Streaming & Entretenimento
        AppInfo("com.netflix.mediaclient", "Netflix", "Streaming"),
        AppInfo("com.amazon.avod.thirdpartyclient", "Prime Video", "Streaming"),
        AppInfo("com.disney.disneyplus", "Disney+", "Streaming"),
        AppInfo("com.hbomax.ninja", "Max (HBO)", "Streaming"),
        AppInfo("tv.twitch.android.app", "Twitch", "Streaming"),
        AppInfo("com.spotify.music", "Spotify", "Streaming"),

        // Jogos de Azar / Apostas (Bets)
        AppInfo("com.bet365Wrapper.Bet365_Application", "Bet365", "Apostas"),
        AppInfo("com.betano.android", "Betano", "Apostas"),
        AppInfo("com.sportingbet.sports", "Sportingbet", "Apostas"),
        AppInfo("com.betfair", "Betfair", "Apostas"),
        AppInfo("com.blaze.app", "Blaze", "Apostas"),
        AppInfo("com.pixbet.app", "Pixbet", "Apostas"),

        // Namoro
        AppInfo("com.tinder", "Tinder", "Namoro"),
        AppInfo("com.badoo.mobile", "Badoo", "Namoro"),
        AppInfo("com.bumble.app", "Bumble", "Namoro"),
        AppInfo("com.ftw_and_co.happn", "Happn", "Namoro"),
        AppInfo("com.grindrapp.android", "Grindr", "Namoro"),

        // Jogos Viciantes
        AppInfo("com.dts.freefireth", "Free Fire", "Jogos"),
        AppInfo("com.roblox.client", "Roblox", "Jogos"),
        AppInfo("com.tencent.ig", "PUBG Mobile", "Jogos"),
        AppInfo("com.king.candycrushsaga", "Candy Crush", "Jogos"),
        AppInfo("com.supercell.clashofclans", "Clash of Clans", "Jogos"),
        AppInfo("com.mobile.legends", "Mobile Legends", "Jogos"),
        AppInfo("com.riotgames.league.wildrift", "Wild Rift", "Jogos")
    )
}

