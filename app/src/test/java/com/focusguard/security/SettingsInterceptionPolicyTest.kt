package com.focusguard.security

import com.focusguard.security.SettingsInterceptionPolicy.Decision
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettingsInterceptionPolicyTest {

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

    private class RecordingRoots(
        private val accessibility: Boolean = false,
        private val deviceAdmin: Boolean = false,
        private val focusGuard: Boolean = false,
        private val destructive: Boolean = false,
        private val essential: Boolean = false
    ) {
        val reads = mutableListOf<String>()

        fun asRootSignals() = SettingsInterceptionPolicy.RootSignals(
            mentionsAccessibility = { reads += "accessibility"; accessibility },
            mentionsDeviceAdmin = { reads += "deviceAdmin"; deviceAdmin },
            mentionsFocusGuard = { reads += "focusGuard"; focusGuard },
            mentionsDestructiveControl = { reads += "destructive"; destructive },
            mentionsEssentialSpecialAccess = { reads += "essential"; essential }
        )
    }

    private fun decide(
        signals: SettingsInterceptionPolicy.EventSignals,
        engaged: Boolean = true,
        strictPomodoro: Boolean = false,
        deviceAdminActivationAuthorized: Boolean = false,
        roots: RecordingRoots = RecordingRoots()
    ) = SettingsInterceptionPolicy.decide(
        signals = signals,
        selfProtectionEngaged = engaged,
        strictPomodoroActive = strictPomodoro,
        deviceAdminActivationAuthorized = deviceAdminActivationAuthorized,
        rootSignals = roots.asRootSignals()
    )

    @Test
    fun `ignores packages outside system settings and installers`() {
        assertThat(
            decide(
                signals(
                    packageName = "com.example.browser",
                    isViewClickedEvent = true,
                    textMentionsFocusGuard = true
                )
            )
        ).isEqualTo(Decision.IGNORE)
    }

    @Test
    fun `ignores every surface when self protection is not engaged`() {
        val roots = RecordingRoots(focusGuard = true, destructive = true)
        assertThat(
            decide(signals(classTargetsUninstall = true), engaged = false, roots = roots)
        ).isEqualTo(Decision.IGNORE)
        assertThat(roots.reads).isEmpty()
    }

    @Test
    fun `app info gateway is blocked before navigation`() {
        assertThat(
            decide(signals(isViewClickedEvent = true, textMentionsAppInfoGateway = true))
        ).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `explicit app info gateway wins over strict pomodoro for master exit`() {
        assertThat(
            decide(
                signals(isViewClickedEvent = true, textMentionsAppInfoGateway = true),
                strictPomodoro = true
            )
        ).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `strict pomodoro still owns settings while its device lock is active`() {
        assertThat(decide(signals(), strictPomodoro = true))
            .isEqualTo(Decision.POMODORO_LOCK)
    }

    @Test
    fun `generic accessibility list and other service toggles remain available`() {
        assertThat(decide(signals(classTargetsAccessibilityList = true)))
            .isEqualTo(Decision.IGNORE)
        assertThat(
            decide(
                signals(
                    classTargetsAccessibilityServiceToggle = true,
                    textMentionsAccessibility = true
                )
            )
        ).isEqualTo(Decision.IGNORE)
    }

    @Test
    fun `installed accessibility apps entry is blocked before navigation`() {
        val roots = RecordingRoots(accessibility = true)
        assertThat(
            decide(
                signals(
                    isViewClickedEvent = true,
                    textMentionsInstalledAccessibilityApps = true
                ),
                roots = roots
            )
        ).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `installed accessibility list transition is blocked`() {
        assertThat(
            decide(
                signals(
                    isWindowTransitionEvent = true,
                    classTargetsAccessibilityList = true,
                    textMentionsAccessibility = true,
                    textMentionsInstalledAccessibilityApps = true
                )
            )
        ).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `generic installed apps label outside accessibility is not blocked`() {
        val roots = RecordingRoots(accessibility = false)
        assertThat(
            decide(
                signals(
                    isViewClickedEvent = true,
                    textMentionsInstalledAccessibilityApps = true
                ),
                roots = roots
            )
        ).isEqualTo(Decision.IGNORE)
    }

    @Test
    fun `first click naming FocusGuard is intercepted before navigation`() {
        assertThat(
            decide(
                signals(
                    isViewClickedEvent = true,
                    textMentionsFocusGuard = true
                )
            )
        ).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `System UI accessibility disclosure click for FocusGuard is intercepted`() {
        assertThat(
            decide(
                signals(
                    packageName = SYSTEM_UI,
                    isViewClickedEvent = true,
                    textMentionsFocusGuard = true,
                    textMentionsAccessibilityDisclosure = true
                )
            )
        ).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `System UI device admin deep link is blocked during active protection`() {
        assertThat(
            decide(
                signals(
                    packageName = SYSTEM_UI,
                    isViewClickedEvent = true,
                    textMentionsDeviceAdmin = true
                )
            )
        ).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `System UI device admin text without a click is ignored`() {
        assertThat(
            decide(
                signals(
                    packageName = SYSTEM_UI,
                    textMentionsDeviceAdmin = true
                )
            )
        ).isEqualTo(Decision.IGNORE)
    }

    @Test
    fun `System UI requires a click plus FocusGuard plus disclosure`() {
        assertThat(
            decide(
                signals(
                    packageName = SYSTEM_UI,
                    isViewClickedEvent = true,
                    textMentionsFocusGuard = true
                )
            )
        ).isEqualTo(Decision.IGNORE)
        assertThat(
            decide(
                signals(
                    packageName = SYSTEM_UI,
                    isViewClickedEvent = true,
                    textMentionsAccessibilityDisclosure = true
                )
            )
        ).isEqualTo(Decision.IGNORE)
        assertThat(
            decide(
                signals(
                    packageName = SYSTEM_UI,
                    textMentionsFocusGuard = true,
                    textMentionsAccessibilityDisclosure = true
                )
            )
        ).isEqualTo(Decision.IGNORE)
    }

    @Test
    fun `normal FocusGuard notifications are not mistaken for disclosure`() {
        assertThat(
            decide(
                signals(
                    packageName = SYSTEM_UI,
                    isViewClickedEvent = true,
                    textMentionsFocusGuard = true
                )
            )
        ).isEqualTo(Decision.IGNORE)
    }

    @Test
    fun `generic accessibility click is ignored but device admin entry is blocked`() {
        assertThat(
            decide(signals(isViewClickedEvent = true, textMentionsAccessibility = true))
        ).isEqualTo(Decision.IGNORE)
        assertThat(
            decide(signals(isViewClickedEvent = true, textMentionsDeviceAdmin = true))
        ).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `follow up transition inside an armed guard is consumed without rearming`() {
        val roots = RecordingRoots(focusGuard = true)
        assertThat(decide(signals(guardArmed = true), roots = roots))
            .isEqualTo(Decision.PROTECT)
        assertThat(roots.reads).isEmpty()
    }

    @Test
    fun `guard shortcut never consumes a new unrelated click`() {
        assertThat(decide(signals(guardArmed = true, isViewClickedEvent = true)))
            .isEqualTo(Decision.IGNORE)
    }

    @Test
    fun `FocusGuard accessibility toggle is protected but another toggle is not`() {
        assertThat(
            decide(
                signals(
                    classTargetsAccessibilityServiceToggle = true,
                    textMentionsFocusGuard = true
                )
            )
        ).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
        assertThat(decide(signals(classTargetsAccessibilityServiceToggle = true)))
            .isEqualTo(Decision.IGNORE)
    }

    @Test
    fun `device admin screen is blocked at the gateway`() {
        assertThat(decide(signals(classTargetsDeviceAdmin = true)))
            .isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
        assertThat(
            decide(
                signals(
                    classTargetsDeviceAdmin = true,
                    textMentionsFocusGuard = true
                )
            )
        ).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `generic device admin shell is blocked without requiring FocusGuard row`() {
        assertThat(
            decide(signals(isGenericSubSettings = true, textMentionsDeviceAdmin = true))
        ).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
        val roots = RecordingRoots(deviceAdmin = true)
        assertThat(decide(signals(isGenericSubSettings = true), roots = roots))
            .isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `other app details and uninstall are never intercepted`() {
        assertThat(decide(signals(classTargetsAppDetails = true)))
            .isEqualTo(Decision.IGNORE)
        assertThat(decide(signals(classTargetsUninstall = true)))
            .isEqualTo(Decision.IGNORE)
        assertThat(
            decide(
                signals(
                    packageName = INSTALLER,
                    classTargetsUninstall = true,
                    textMentionsDestructiveControl = true
                )
            )
        ).isEqualTo(Decision.IGNORE)
    }

    @Test
    fun `FocusGuard app details and uninstall are intercepted`() {
        assertThat(
            decide(
                signals(
                    classTargetsAppDetails = true,
                    textMentionsFocusGuard = true
                )
            )
        ).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
        assertThat(
            decide(
                signals(
                    packageName = INSTALLER,
                    classTargetsUninstall = true,
                    textMentionsFocusGuard = true
                )
            )
        ).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `installer can identify FocusGuard through the rendered node tree`() {
        val roots = RecordingRoots(focusGuard = true, destructive = true)
        assertThat(decide(signals(packageName = INSTALLER), roots = roots))
            .isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `special access requires both FocusGuard and the relevant access`() {
        assertThat(
            decide(
                signals(
                    classTargetsEssentialSpecialAccess = true,
                    textMentionsEssentialSpecialAccess = true
                )
            )
        ).isEqualTo(Decision.IGNORE)
        assertThat(
            decide(
                signals(
                    classTargetsEssentialSpecialAccess = true,
                    textMentionsFocusGuard = true,
                    textMentionsEssentialSpecialAccess = true
                )
            )
        ).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `generic accessibility shell needs FocusGuard too`() {
        val onlyAccessibility = RecordingRoots(accessibility = true)
        assertThat(
            decide(signals(isGenericSubSettings = true), roots = onlyAccessibility)
        ).isEqualTo(Decision.IGNORE)
        val both = RecordingRoots(accessibility = true, focusGuard = true)
        assertThat(decide(signals(isGenericSubSettings = true), roots = both))
            .isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `unremarkable settings screen is left alone`() {
        assertThat(decide(signals())).isEqualTo(Decision.IGNORE)
    }

    @Test
    fun authorizedEnrollmentAllowsDeviceAdminActivityButNotOtherProtectedSurfaces() {
        assertThat(
            decide(
                signals(classTargetsDeviceAdmin = true),
                deviceAdminActivationAuthorized = true
            )
        ).isEqualTo(Decision.IGNORE)

        val genericAdmin = RecordingRoots(deviceAdmin = true, focusGuard = true)
        assertThat(
            decide(
                signals(isGenericSubSettings = true),
                deviceAdminActivationAuthorized = true,
                roots = genericAdmin
            )
        ).isEqualTo(Decision.IGNORE)

        assertThat(
            decide(
                signals(
                    classTargetsAppDetails = true,
                    isGenericSubSettings = true,
                    textMentionsDeviceAdmin = true,
                    textMentionsFocusGuard = true
                ),
                deviceAdminActivationAuthorized = true
            )
        ).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun strictPomodoroOverridesAuthorizedDeviceAdminEnrollment() {
        assertThat(
            decide(
                signals(
                    classTargetsDeviceAdmin = true,
                    textMentionsFocusGuard = true
                ),
                strictPomodoro = true,
                deviceAdminActivationAuthorized = true
            )
        ).isEqualTo(Decision.POMODORO_LOCK)
    }

    private companion object {
        const val SETTINGS = "com.android.settings"
        const val INSTALLER = "com.android.packageinstaller"
        const val SYSTEM_UI = "com.android.systemui"
    }
}
