package com.focusguard.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecoveryProtectionPresetTest {

    @Test
    fun `social shortcut lasts six thirty-day months`() {
        assertThat(RecoveryProtectionPreset.SOCIAL_BLOCK_DAYS).isEqualTo(180)
    }

    @Test
    fun `preset covers major social sites but not messenger sites`() {
        assertThat(RecoveryProtectionPreset.SOCIAL_WEBSITE_RULES)
            .containsAtLeast("youtube.com", "instagram.com", "facebook.com", "tiktok.com")
        assertThat(RecoveryProtectionPreset.SOCIAL_WEBSITE_RULES)
            .containsNoneOf("whatsapp.com", "telegram.org", "signal.org", "discord.com")
    }

    @Test
    fun `preset covers youtube and instagram apps even before installation`() {
        assertThat(RecoveryProtectionPreset.KNOWN_SOCIAL_APP_PACKAGES)
            .containsAtLeast("com.google.android.youtube", "com.instagram.android")
    }

    @Test
    fun `dedicated messengers stay allowed even if Android labels them social`() {
        assertThat(
            RecoveryProtectionPreset.shouldBlockApp(
                packageName = "com.whatsapp",
                declaredSocialCategory = true,
                isSystemApp = false
            )
        ).isFalse()
        assertThat(
            RecoveryProtectionPreset.shouldBlockApp(
                packageName = "org.telegram.messenger",
                declaredSocialCategory = true,
                isSystemApp = false
            )
        ).isFalse()
        assertThat(
            RecoveryProtectionPreset.shouldBlockApp(
                packageName = "com.discord",
                declaredSocialCategory = true,
                isSystemApp = false
            )
        ).isFalse()
    }

    @Test
    fun `unknown third party social app is included but unknown system app is not`() {
        assertThat(
            RecoveryProtectionPreset.shouldBlockApp(
                packageName = "social.example.new",
                declaredSocialCategory = true,
                isSystemApp = false
            )
        ).isTrue()
        assertThat(
            RecoveryProtectionPreset.shouldBlockApp(
                packageName = "android.system.social",
                declaredSocialCategory = true,
                isSystemApp = true
            )
        ).isFalse()
    }

    @Test
    fun `known social app remains included when preinstalled by manufacturer`() {
        assertThat(
            RecoveryProtectionPreset.shouldBlockApp(
                packageName = "com.google.android.youtube",
                declaredSocialCategory = true,
                isSystemApp = true
            )
        ).isTrue()
    }

    @Test
    fun `consent must match the requested phrase exactly`() {
        assertThat(
            RecoveryProtectionPreset.isConsentAccepted(
                "compreendi e concordo com os termos"
            )
        ).isTrue()
        assertThat(
            RecoveryProtectionPreset.isConsentAccepted(
                "Compreendi e concordo com os termos"
            )
        ).isFalse()
        assertThat(
            RecoveryProtectionPreset.isConsentAccepted(
                "compreendi e concordo com os termos "
            )
        ).isFalse()
    }

    @Test
    fun `typed edits allow one character and deletion but reject paste`() {
        assertThat(RecoveryProtectionPreset.acceptsTypedEdit("", "c")).isTrue()
        assertThat(RecoveryProtectionPreset.acceptsTypedEdit("c", "co")).isTrue()
        assertThat(RecoveryProtectionPreset.acceptsTypedEdit("abc", "adc")).isTrue()
        assertThat(RecoveryProtectionPreset.acceptsTypedEdit("compreendi", "")).isTrue()

        assertThat(
            RecoveryProtectionPreset.acceptsTypedEdit(
                "",
                RecoveryProtectionPreset.CONSENT_PHRASE
            )
        ).isFalse()
        assertThat(
            RecoveryProtectionPreset.acceptsTypedEdit("com", "compreendi")
        ).isFalse()
    }
}
