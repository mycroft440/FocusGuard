package com.focusguard.security

import com.focusguard.security.SettingsInterceptionPolicy.Decision
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MaintenanceSpecialAccessPolicyTest {

    private fun roots(
        focusGuard: Boolean = false,
        essential: Boolean = false
    ) = SettingsInterceptionPolicy.RootSignals(
        mentionsAccessibility = { false },
        mentionsDeviceAdmin = { false },
        mentionsFocusGuard = { focusGuard },
        mentionsDestructiveControl = { false },
        mentionsEssentialSpecialAccess = { essential }
    )

    private fun signals(
        classTargetsEssential: Boolean = false,
        textMentionsEssential: Boolean = false,
        focusGuard: Boolean = false,
        uninstall: Boolean = false
    ) = SettingsInterceptionPolicy.EventSignals(
        packageName = "com.android.settings",
        isViewClickedEvent = true,
        isWindowTransitionEvent = false,
        guardArmed = false,
        classTargetsAccessibilityServiceToggle = false,
        classTargetsAccessibilityList = false,
        classTargetsDeviceAdmin = false,
        classTargetsAppDetails = false,
        classTargetsUninstall = uninstall,
        classTargetsEssentialSpecialAccess = classTargetsEssential,
        isGenericSubSettings = false,
        textMentionsAccessibility = false,
        textMentionsInstalledAccessibilityApps = false,
        textMentionsAccessibilityDisclosure = false,
        textMentionsDeviceAdmin = false,
        textMentionsFocusGuard = focusGuard,
        textMentionsDestructiveControl = uninstall,
        textMentionsEssentialSpecialAccess = textMentionsEssential,
        textMentionsAppInfoGateway = false
    )

    @Test
    fun `maintenance still protects FocusGuard essential special access`() {
        assertThat(
            SettingsInterceptionPolicy.decide(
                signals = signals(
                    classTargetsEssential = true,
                    textMentionsEssential = true,
                    focusGuard = true
                ),
                selfProtectionEngaged = true,
                strictPomodoroActive = false,
                deviceAdminActivationAuthorized = false,
                maintenanceActive = true,
                rootSignals = roots()
            )
        ).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)

        assertThat(
            SettingsInterceptionPolicy.decide(
                signals = signals(),
                selfProtectionEngaged = true,
                strictPomodoroActive = false,
                deviceAdminActivationAuthorized = false,
                maintenanceActive = true,
                rootSignals = roots(focusGuard = true, essential = true)
            )
        ).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `maintenance leaves non essential Settings surfaces available`() {
        assertThat(
            SettingsInterceptionPolicy.decide(
                signals = signals(focusGuard = true, uninstall = true),
                selfProtectionEngaged = true,
                strictPomodoroActive = false,
                deviceAdminActivationAuthorized = false,
                maintenanceActive = true,
                rootSignals = roots()
            )
        ).isEqualTo(Decision.IGNORE)
    }

    @Test
    fun `exact alarm settings are classified as essential special access`() {
        assertThat(
            ManagedSelfProtectionPolicy.classTargetsEssentialSpecialAccess(
                "com.android.settings.applications.appinfo.AlarmsAndRemindersDetails"
            )
        ).isTrue()

        assertThat(
            ManagedSelfProtectionPolicy.textTargetsEssentialSpecialAccess(
                listOf("Alarmes e lembretes")
            )
        ).isTrue()
        assertThat(
            ManagedSelfProtectionPolicy.textTargetsEssentialSpecialAccess(
                listOf("Alarms & reminders")
            )
        ).isTrue()
    }
}
