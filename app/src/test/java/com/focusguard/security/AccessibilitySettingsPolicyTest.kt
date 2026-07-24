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
}
