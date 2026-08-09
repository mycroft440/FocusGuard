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

    // O split mantém classificadores distintos para a lista e o interruptor.
    // A política de sessão bloqueia ambos enquanto houver proteção ativa.

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
        listOf(
            "com.android.settings.accessibility.AccessibilitySettings",
            "com.android.settings.accessibility.InstalledAccessibilityService",
            "com.samsung.android.settings.accessibility.AccessibilitySettingsActivity",
            "com.samsung.android.settings.accessibility.home.AccessibilityDashboardFragment"
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
    fun `recognizes the One UI installed accessibility apps label`() {
        assertThat(
            AccessibilitySettingsPolicy.textTargetsInstalledAccessibilityApps(
                listOf("Aplicativos instalados", "3 de 9 apps estão em uso")
            )
        ).isTrue()
        assertThat(
            AccessibilitySettingsPolicy.textTargetsInstalledAccessibilityApps(
                listOf("Installed services")
            )
        ).isTrue()
        assertThat(
            AccessibilitySettingsPolicy.textTargetsInstalledAccessibilityApps(
                listOf("Melhorias na visão", "TalkBack")
            )
        ).isFalse()
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
