package com.focusguard.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AccessibilitySettingsPolicyTest {

    @Test
    fun `recognizes accessibility service screens`() {
        assertThat(
            AccessibilitySettingsPolicy.classTargetsAccessibility(
                "com.android.settings.accessibility.AccessibilityServiceSettings"
            )
        ).isTrue()
    }

    @Test
    fun `does not classify generic app details as accessibility`() {
        assertThat(
            AccessibilitySettingsPolicy.classTargetsAccessibility(
                "com.android.settings.applications.InstalledAppDetails"
            )
        ).isFalse()
    }

    @Test
    fun `recognizes localized accessibility labels`() {
        assertThat(
            AccessibilitySettingsPolicy.textTargetsAccessibility(
                listOf("Acessibilidade", "Other text")
            )
        ).isTrue()
        assertThat(
            AccessibilitySettingsPolicy.textTargetsAccessibility(
                listOf("Accesibilidad")
            )
        ).isTrue()
    }

    @Test
    fun `does not classify normal settings labels`() {
        assertThat(
            AccessibilitySettingsPolicy.textTargetsAccessibility(
                listOf("Wi-Fi", "Bateria", "Aplicativos")
            )
        ).isFalse()
    }

    // The split below is what keeps the Accessibility section usable during a
    // block: only the per-service switch is a candidate for interception.

    @Test
    fun `per-service toggle screens are separated from list screens`() {
        listOf(
            "com.android.settings.accessibility.ToggleAccessibilityServicePreferenceFragment",
            "com.android.settings.accessibility.AccessibilityDetailsSettings",
            "com.android.settings.accessibility.AccessibilityServiceSettings",
            "com.android.settings.accessibility.AccessibilityShortcutPreferenceFragment"
        ).forEach { className ->
            assertThat(
                AccessibilitySettingsPolicy.classTargetsAccessibilityServiceToggle(className)
            ).isTrue()
            assertThat(
                AccessibilitySettingsPolicy.classTargetsAccessibilityList(className)
            ).isFalse()
        }
    }

    @Test
    fun `the accessibility list screen is not a toggle screen`() {
        // Reaching this screen must never be intercepted — it is where the user
        // changes font size or turns TalkBack on.
        listOf(
            "com.android.settings.accessibility.AccessibilitySettings",
            "com.android.settings.accessibility.InstalledAccessibilityService"
        ).forEach { className ->
            assertThat(
                AccessibilitySettingsPolicy.classTargetsAccessibilityList(className)
            ).isTrue()
            assertThat(
                AccessibilitySettingsPolicy.classTargetsAccessibilityServiceToggle(className)
            ).isFalse()
        }
    }

    @Test
    fun `app details is neither a toggle nor a list screen`() {
        val className = "com.android.settings.applications.InstalledAppDetails"

        assertThat(
            AccessibilitySettingsPolicy.classTargetsAccessibilityServiceToggle(className)
        ).isFalse()
        assertThat(
            AccessibilitySettingsPolicy.classTargetsAccessibilityList(className)
        ).isFalse()
    }
}
