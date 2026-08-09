package com.focusguard.data

/**
 * Regras do atalho final da jornada AntiPorn.
 *
 * A lista conhecida protege inclusive contra uma instalação futura desses apps.
 * Para redes menos conhecidas, o gerenciador também inclui os apps de terceiros
 * que o Android classifica como sociais. Mensageiros dedicados são sempre
 * retirados do conjunto, mesmo quando o fabricante os classifica como sociais.
 */
object RecoveryProtectionPreset {

    const val SOCIAL_BLOCK_DAYS = 180
    const val CONSENT_PHRASE = "compreendi e concordo com os termos"

    val SOCIAL_WEBSITE_RULES: Set<String> = linkedSetOf(
        "youtube.com",
        "instagram.com",
        "threads.net",
        "facebook.com",
        "tiktok.com",
        "kwai.com",
        "twitter.com",
        "x.com",
        "snapchat.com",
        "pinterest.com",
        "reddit.com",
        "twitch.tv",
        "bsky.app",
        "tumblr.com",
        "linkedin.com",
        "mastodon.social",
        "vk.com",
        "9gag.com",
        "likee.video",
        "quora.com"
    )

    val KNOWN_SOCIAL_APP_PACKAGES: Set<String> = linkedSetOf(
        "com.google.android.youtube",
        "com.google.android.apps.youtube.kids",
        "com.instagram.android",
        "com.instagram.lite",
        "com.instagram.barcelona",
        "com.facebook.katana",
        "com.facebook.lite",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.kwai.video",
        "com.twitter.android",
        "com.snapchat.android",
        "com.pinterest",
        "com.reddit.frontpage",
        "tv.twitch.android.app",
        "xyz.blueskyweb.app",
        "com.tumblr",
        "com.linkedin.android",
        "org.joinmastodon.android",
        "com.vk.android",
        "video.like",
        "com.ninegag.android.app",
        "com.quora.android"
    )

    private val messengerPackagePrefixes: Set<String> = setOf(
        "com.whatsapp",
        "org.telegram",
        "org.thoughtcrime.securesms",
        "org.signal",
        "com.facebook.orca",
        "com.facebook.mlite",
        "com.discord",
        "com.viber",
        "jp.naver.line",
        "com.skype",
        "com.microsoft.teams",
        "com.google.android.apps.messaging",
        "com.google.android.apps.tachyon",
        "com.google.android.apps.meetings",
        "com.samsung.android.messaging",
        "com.android.mms",
        "com.android.messaging",
        "com.tencent.mm",
        "com.kakao.talk",
        "com.imo.android.imoim",
        "com.botim",
        "com.slack"
    )

    fun isMessengerPackage(packageName: String): Boolean =
        messengerPackagePrefixes.any { prefix ->
            packageName == prefix || packageName.startsWith("$prefix.")
        }

    fun shouldBlockApp(
        packageName: String,
        declaredSocialCategory: Boolean,
        isSystemApp: Boolean
    ): Boolean {
        if (packageName.isBlank() || isMessengerPackage(packageName)) return false
        return packageName in KNOWN_SOCIAL_APP_PACKAGES ||
            (declaredSocialCategory && !isSystemApp)
    }

    fun isConsentAccepted(value: String): Boolean = value == CONSENT_PHRASE

    /**
     * Aceita digitação, substituição de um caractere e qualquer remoção.
     * Inserir dois ou mais caracteres de uma vez caracteriza colar, arrastar,
     * preenchimento automático ou texto preditivo e é rejeitado.
     */
    fun acceptsTypedEdit(previous: String, next: String): Boolean {
        if (previous == next) return true

        var commonPrefix = 0
        while (
            commonPrefix < previous.length &&
            commonPrefix < next.length &&
            previous[commonPrefix] == next[commonPrefix]
        ) {
            commonPrefix++
        }

        var commonSuffix = 0
        while (
            commonSuffix < previous.length - commonPrefix &&
            commonSuffix < next.length - commonPrefix &&
            previous[previous.lastIndex - commonSuffix] ==
            next[next.lastIndex - commonSuffix]
        ) {
            commonSuffix++
        }

        val insertedCharacterCount = next.length - commonPrefix - commonSuffix
        return insertedCharacterCount <= 1
    }
}
