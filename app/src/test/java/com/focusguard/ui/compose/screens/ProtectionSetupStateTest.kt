package com.focusguard.ui.compose.screens

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProtectionSetupStateTest {

    @Test
    fun `empty list does not expose protection modes`() {
        val draft = ProtectionDraft()

        assertThat(draft.hasTargets).isFalse()
        assertThat(draft.availableModes).isEmpty()
    }

    @Test
    fun `app list exposes the three modes in the intended order`() {
        val draft = ProtectionDraft(appPackageNames = setOf("com.example.app"))

        assertThat(draft.availableModes).containsExactly(
            ProtectionMode.LIMIT,
            ProtectionMode.PASSWORD,
            ProtectionMode.DOPAMINE_FAST
        ).inOrder()
    }

    @Test
    fun `website-only list can choose a protection mode`() {
        val draft = ProtectionDraft(websiteRules = setOf("keyword:porn"))

        assertThat(draft.hasTargets).isTrue()
        assertThat(draft.availableModes).hasSize(3)
    }

    @Test
    fun `exact domain already configured by another block is rejected`() {
        assertThat(
            isWebsiteRuleAlreadyBlocked(
                candidate = "https://www.youtube.com/watch?v=1",
                configuredRules = setOf("youtube.com")
            )
        ).isTrue()
    }

    @Test
    fun `existing keyword also rejects matching domains`() {
        assertThat(
            isWebsiteRuleAlreadyBlocked(
                candidate = "example-porn-site.com",
                configuredRules = setOf("keyword:porn")
            )
        ).isTrue()
    }

    @Test
    fun `broader new keyword remains available`() {
        assertThat(
            isWebsiteRuleAlreadyBlocked(
                candidate = "keyword:video",
                configuredRules = setOf("youtube.com")
            )
        ).isFalse()
    }

    @Test
    fun `daily limit accepts mixed hours and minutes`() {
        assertThat(parseDailyLimitMinutes("1", "30")).isEqualTo(90)
        assertThat(parseDailyLimitMinutes("", "45")).isEqualTo(45)
        assertThat(parseDailyLimitMinutes("24", "0")).isEqualTo(1440)
    }

    @Test
    fun `daily limit rejects empty zero or values above one day`() {
        assertThat(parseDailyLimitMinutes("", "")).isNull()
        assertThat(parseDailyLimitMinutes("0", "0")).isNull()
        assertThat(parseDailyLimitMinutes("24", "1")).isNull()
        assertThat(parseDailyLimitMinutes("1", "60")).isNull()
        assertThat(parseDailyLimitMinutes("abc", "30")).isNull()
    }
}
