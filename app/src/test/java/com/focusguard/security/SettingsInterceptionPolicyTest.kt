package com.focusguard.security

import com.focusguard.security.SettingsInterceptionPolicy.Decision
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

class SettingsInterceptionPolicyTest {

    @Before
    fun resetTargetScope() {
        SelfProtectionTargetScope.clear()
    }

    private fun signals(
        packageName: String = SETTINGS,
        isViewClickedEvent: Boolean = false,
        isWindowTransitionEvent: Boolean = false,
        guardArmed: Boolean = false,
        classTargetsAccessibilityServiceToggle: Boolean = false,
        classTargetsAccessibilityList: Boolean = false,
        classTargetsDeviceAdmin: Boolean = false,
        classTargetsAppDetails: Boolean = false,
        classTargetsUninstall: Boolean = false,
        classTargetsEssentialSpecialAccess: Boolean = false,
        isGenericSubSettings: Boolean = false,
        textMentionsAccessibility: Boolean = false,
        textMentionsInstalledAccessibilityApps: Boolean = false,
        textMentionsAccessibilityDisclosure: Boolean = false,
        textMentionsDeviceAdmin: Boolean = false,
        textMentionsFocusGuard: Boolean = false,
        textMentionsDestructiveControl: Boolean = false,
        textMentionsEssentialSpecialAccess: Boolean = false,
        textMentionsAppInfoGateway: Boolean = false
    ) = SettingsInterceptionPolicy.EventSignals(
        packageName = packageName,
        isViewClickedEvent = isViewClickedEvent,
        isWindowTransitionEvent = isWindowTransitionEvent,
        guardArmed = guardArmed,
        classTargetsAccessibilityServiceToggle = classTargetsAccessibilityServiceToggle,
        classTargetsAccessibilityList = classTargetsAccessibilityList,
        classTargetsDeviceAdmin = classTargetsDeviceAdmin,
        classTargetsAppDetails = classTargetsAppDetails,
        classTargetsUninstall = classTargetsUninstall,
        classTargetsEssentialSpecialAccess = classTargetsEssentialSpecialAccess,
        isGenericSubSettings = isGenericSubSettings,
        textMentionsAccessibility = textMentionsAccessibility,
        textMentionsInstalledAccessibilityApps = textMentionsInstalledAccessibilityApps,
        textMentionsAccessibilityDisclosure = textMentionsAccessibilityDisclosure,
        textMentionsDeviceAdmin = textMentionsDeviceAdmin,
        textMentionsFocusGuard = textMentionsFocusGuard,
        textMentionsDestructiveControl = textMentionsDestructiveControl,
        textMentionsEssentialSpecialAccess = textMentionsEssentialSpecialAccess,
        textMentionsAppInfoGateway = textMentionsAppInfoGateway
    )

    private fun roots(
        accessibility: Boolean = false,
        deviceAdmin: Boolean = false,
        focusGuard: Boolean = false,
        destructive: Boolean = false,
        essential: Boolean = false
    ) = SettingsInterceptionPolicy.RootSignals(
        mentionsAccessibility = { accessibility },
        mentionsDeviceAdmin = { deviceAdmin },
        mentionsFocusGuard = { focusGuard },
        mentionsDestructiveControl = { destructive },
        mentionsEssentialSpecialAccess = { essential }
    )

    private fun decide(
        signals: SettingsInterceptionPolicy.EventSignals,
        engaged: Boolean = true,
        deviceAdminActivationAuthorized: Boolean = false,
        maintenanceActive: Boolean = false,
        rootSignals: SettingsInterceptionPolicy.RootSignals = roots()
    ) = SettingsInterceptionPolicy.decide(
        signals = signals,
        selfProtectionEngaged = engaged,
        deviceAdminActivationAuthorized = deviceAdminActivationAuthorized,
        maintenanceActive = maintenanceActive,
        rootSignals = rootSignals
    )

    @Test
    fun `generic device admin list remains accessible`() {
        assertThat(
            decide(
                signals(
                    isViewClickedEvent = true,
                    textMentionsDeviceAdmin = true
                )
            )
        ).isEqualTo(Decision.IGNORE)
        assertThat(
            decide(
                signals(
                    isWindowTransitionEvent = true,
                    classTargetsDeviceAdmin = true,
                    textMentionsDeviceAdmin = true
                )
            )
        ).isEqualTo(Decision.IGNORE)
    }

    @Test
    fun `another administrator remains manageable while protection is active`() {
        assertThat(
            decide(
                signals(
                    isViewClickedEvent = true,
                    textMentionsDeviceAdmin = true,
                    textMentionsFocusGuard = false
                ),
                engaged = true
            )
        ).isEqualTo(Decision.IGNORE)
        assertThat(SelfProtectionTargetScope.isFocusGuardTargetConfirmed()).isFalse()
    }

    @Test
    fun `FocusGuard device admin control is protected without arming global guard`() {
        assertThat(
            decide(
                signals(
                    isViewClickedEvent = true,
                    textMentionsDeviceAdmin = true,
                    textMentionsFocusGuard = true
                )
            )
        ).isEqualTo(Decision.PROTECT)
        assertThat(SelfProtectionTargetScope.isFocusGuardTargetConfirmed()).isTrue()
    }

    @Test
    fun `generic accessibility list remains accessible`() {
        assertThat(
            decide(
                signals(
                    isViewClickedEvent = true,
                    textMentionsAccessibility = true,
                    textMentionsInstalledAccessibilityApps = true
                ),
                rootSignals = roots(accessibility = true)
            )
        ).isEqualTo(Decision.IGNORE)
        assertThat(
            decide(signals(classTargetsAccessibilityList = true))
        ).isEqualTo(Decision.IGNORE)
    }

    @Test
    fun `FocusGuard accessibility toggle is protected`() {
        assertThat(
            decide(
                signals(
                    classTargetsAccessibilityServiceToggle = true,
                    textMentionsAccessibility = true,
                    textMentionsFocusGuard = true
                )
            )
        ).isEqualTo(Decision.PROTECT)
    }

    @Test
    fun `root tree mentioning FocusGuard is not target proof`() {
        assertThat(
            decide(
                signals(
                    classTargetsAppDetails = true,
                    textMentionsFocusGuard = false
                ),
                rootSignals = roots(focusGuard = true, destructive = true)
            )
        ).isEqualTo(Decision.IGNORE)
        assertThat(
            decide(
                signals(packageName = INSTALLER, classTargetsUninstall = true),
                rootSignals = roots(focusGuard = true, destructive = true)
            )
        ).isEqualTo(Decision.IGNORE)
    }

    @Test
    fun `FocusGuard app details and uninstall are protected`() {
        assertThat(
            decide(
                signals(
                    classTargetsAppDetails = true,
                    textMentionsFocusGuard = true
                )
            )
        ).isEqualTo(Decision.PROTECT)
        assertThat(
            decide(
                signals(
                    packageName = INSTALLER,
                    classTargetsUninstall = true,
                    textMentionsFocusGuard = true,
                    textMentionsDestructiveControl = true
                )
            )
        ).isEqualTo(Decision.PROTECT)
    }

    @Test
    fun `other app uninstall remains available even with armed guard`() {
        assertThat(
            decide(
                signals(
                    packageName = INSTALLER,
                    isWindowTransitionEvent = true,
                    guardArmed = true,
                    classTargetsUninstall = true,
                    textMentionsDestructiveControl = true,
                    textMentionsFocusGuard = false
                )
            )
        ).isEqualTo(Decision.IGNORE)
    }

    @Test
    fun `fast transition to another app clears confirmed target`() {
        assertThat(
            decide(
                signals(
                    isViewClickedEvent = true,
                    textMentionsFocusGuard = true,
                    textMentionsAppInfoGateway = true
                )
            )
        ).isEqualTo(Decision.PROTECT)
        assertThat(SelfProtectionTargetScope.isFocusGuardTargetConfirmed()).isTrue()

        assertThat(
            decide(
                signals(
                    isWindowTransitionEvent = true,
                    classTargetsAppDetails = true,
                    textMentionsFocusGuard = false
                )
            )
        ).isEqualTo(Decision.IGNORE)
        assertThat(SelfProtectionTargetScope.isFocusGuardTargetConfirmed()).isFalse()
    }

    @Test
    fun `system ui device admin shortcut needs FocusGuard identity`() {
        assertThat(
            decide(
                signals(
                    packageName = SYSTEM_UI,
                    isViewClickedEvent = true,
                    textMentionsDeviceAdmin = true
                )
            )
        ).isEqualTo(Decision.IGNORE)
        assertThat(
            decide(
                signals(
                    packageName = SYSTEM_UI,
                    isViewClickedEvent = true,
                    textMentionsDeviceAdmin = true,
                    textMentionsFocusGuard = true
                )
            )
        ).isEqualTo(Decision.PROTECT)
    }

    @Test
    fun `authorized FocusGuard device admin enrollment stays available`() {
        assertThat(
            decide(
                signals(
                    isViewClickedEvent = true,
                    isGenericSubSettings = true,
                    textMentionsDeviceAdmin = true,
                    textMentionsFocusGuard = true
                ),
                deviceAdminActivationAuthorized = true
            )
        ).isEqualTo(Decision.IGNORE)
    }

    @Test
    fun `self protection off leaves all system surfaces alone`() {
        assertThat(
            decide(
                signals(
                    isViewClickedEvent = true,
                    textMentionsFocusGuard = true,
                    textMentionsDeviceAdmin = true
                ),
                engaged = false
            )
        ).isEqualTo(Decision.IGNORE)
        assertThat(SelfProtectionTargetScope.isFocusGuardTargetConfirmed()).isFalse()
    }

    private companion object {
        const val SETTINGS = "com.android.settings"
        const val INSTALLER = "com.android.packageinstaller"
        const val SYSTEM_UI = "com.android.systemui"
    }
}
