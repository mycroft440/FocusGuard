package com.focusguard.security

import com.focusguard.security.SettingsInterceptionPolicy.Decision
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Covers the composition of interception signals — the decision tree that was
 * previously inlined in `BlockingAccessibilityService.handleSettingsInterception`
 * and had no direct test.
 */
class SettingsInterceptionPolicyTest {

    // ---------------------------------------------------------------- helpers

    private fun signals(
        packageName: String = SETTINGS,
        isViewClickedEvent: Boolean = false,
        guardArmed: Boolean = false,
        classTargetsAccessibility: Boolean = false,
        classTargetsDeviceAdmin: Boolean = false,
        classTargetsAppDetails: Boolean = false,
        classTargetsUninstall: Boolean = false,
        classTargetsEssentialSpecialAccess: Boolean = false,
        isGenericSubSettings: Boolean = false,
        textMentionsAccessibility: Boolean = false,
        textMentionsDeviceAdmin: Boolean = false,
        textMentionsFocusGuard: Boolean = false,
        textMentionsDestructiveControl: Boolean = false,
        textMentionsEssentialSpecialAccess: Boolean = false
    ) = SettingsInterceptionPolicy.EventSignals(
        packageName = packageName,
        isViewClickedEvent = isViewClickedEvent,
        guardArmed = guardArmed,
        classTargetsAccessibility = classTargetsAccessibility,
        classTargetsDeviceAdmin = classTargetsDeviceAdmin,
        classTargetsAppDetails = classTargetsAppDetails,
        classTargetsUninstall = classTargetsUninstall,
        classTargetsEssentialSpecialAccess = classTargetsEssentialSpecialAccess,
        isGenericSubSettings = isGenericSubSettings,
        textMentionsAccessibility = textMentionsAccessibility,
        textMentionsDeviceAdmin = textMentionsDeviceAdmin,
        textMentionsFocusGuard = textMentionsFocusGuard,
        textMentionsDestructiveControl = textMentionsDestructiveControl,
        textMentionsEssentialSpecialAccess = textMentionsEssentialSpecialAccess
    )

    /** Records which node-tree reads actually happened, to assert laziness. */
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
        armored: Boolean = true,
        strictPomodoro: Boolean = false,
        roots: RecordingRoots = RecordingRoots()
    ) = SettingsInterceptionPolicy.decide(
        signals = signals,
        armoredProtectionEngaged = armored,
        strictPomodoroActive = strictPomodoro,
        rootSignals = roots.asRootSignals()
    )

    // ------------------------------------------------------------- gate: scope

    @Test
    fun `ignores packages outside the protected system set`() {
        val decision = decide(
            signals(
                packageName = "com.example.browser",
                classTargetsAccessibility = true
            )
        )

        assertThat(decision).isEqualTo(Decision.IGNORE)
    }

    @Test
    fun `every known settings and installer package is in scope`() {
        val inScope = SettingsInterceptionPolicy.protectedSystemPackages

        assertThat(inScope).containsAtLeast(
            "com.android.settings",
            "com.miui.securitycenter",
            "com.huawei.systemmanager",
            "com.samsung.android.sm",
            "com.android.packageinstaller",
            "com.miui.packageinstaller"
        )
    }

    // ---------------------------------------------- gate: armored protection

    @Test
    fun `ignores everything when armored protection is not engaged`() {
        // Consumer Device Admin must not intercept: Android allows revoking it and
        // Play forbids using Accessibility to block that escape hatch.
        val decision = decide(
            signals(classTargetsAccessibility = true),
            armored = false
        )

        assertThat(decision).isEqualTo(Decision.IGNORE)
    }

    @Test
    fun `armored gate is checked before reading the node tree`() {
        val roots = RecordingRoots(accessibility = true)

        decide(
            signals(isGenericSubSettings = true),
            armored = false,
            roots = roots
        )

        assertThat(roots.reads).isEmpty()
    }

    // ------------------------------------------------------ strict pomodoro

    @Test
    fun `strict pomodoro takes precedence over any settings screen`() {
        val decision = decide(
            signals(classTargetsAccessibility = true),
            strictPomodoro = true
        )

        assertThat(decision).isEqualTo(Decision.POMODORO_LOCK)
    }

    @Test
    fun `strict pomodoro intercepts even a screen that would otherwise be ignored`() {
        val decision = decide(signals(), strictPomodoro = true)

        assertThat(decision).isEqualTo(Decision.POMODORO_LOCK)
    }

    // ------------------------------------------------------- click intercept

    @Test
    fun `click mentioning accessibility arms the transition guard`() {
        val decision = decide(
            signals(isViewClickedEvent = true, textMentionsAccessibility = true)
        )

        assertThat(decision).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `click mentioning device admin arms the transition guard`() {
        val decision = decide(
            signals(isViewClickedEvent = true, textMentionsDeviceAdmin = true)
        )

        assertThat(decision).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `click naming FocusGuard alone is not enough to intercept`() {
        // Seeing the app name on a settings list is not evidence of intent to
        // uninstall or revoke; requiring a second signal avoids false positives.
        val decision = decide(
            signals(isViewClickedEvent = true, textMentionsFocusGuard = true)
        )

        assertThat(decision).isEqualTo(Decision.IGNORE)
    }

    @Test
    fun `click naming FocusGuard plus a destructive control intercepts`() {
        val decision = decide(
            signals(
                isViewClickedEvent = true,
                textMentionsFocusGuard = true,
                textMentionsDestructiveControl = true
            )
        )

        assertThat(decision).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `click naming FocusGuard plus essential special access intercepts`() {
        val decision = decide(
            signals(
                isViewClickedEvent = true,
                textMentionsFocusGuard = true,
                textMentionsEssentialSpecialAccess = true
            )
        )

        assertThat(decision).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    // -------------------------------------------------------- guard window

    @Test
    fun `non-click event inside the guard window protects without re-arming`() {
        // The destination screen opens right after the click; re-arming here would
        // extend the window indefinitely while the user stays on the page.
        val decision = decide(signals(guardArmed = true))

        assertThat(decision).isEqualTo(Decision.PROTECT)
    }

    @Test
    fun `click inside the guard window does not take the guard shortcut`() {
        val decision = decide(
            signals(guardArmed = true, isViewClickedEvent = true)
        )

        assertThat(decision).isEqualTo(Decision.IGNORE)
    }

    @Test
    fun `guard window does not read the node tree`() {
        val roots = RecordingRoots(accessibility = true)

        decide(signals(guardArmed = true), roots = roots)

        assertThat(roots.reads).isEmpty()
    }

    // ------------------------------------------------------- direct screens

    @Test
    fun `direct accessibility settings screen intercepts on class name alone`() {
        val decision = decide(signals(classTargetsAccessibility = true))

        assertThat(decision).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `direct device admin screen intercepts on class name alone`() {
        val decision = decide(signals(classTargetsDeviceAdmin = true))

        assertThat(decision).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    // -------------------------------------------------- generic SubSettings

    @Test
    fun `generic SubSettings intercepts when event text mentions device admin`() {
        val decision = decide(
            signals(isGenericSubSettings = true, textMentionsDeviceAdmin = true)
        )

        assertThat(decision).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `generic SubSettings falls back to the node tree for device admin`() {
        val roots = RecordingRoots(deviceAdmin = true)

        val decision = decide(signals(isGenericSubSettings = true), roots = roots)

        assertThat(decision).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
        assertThat(roots.reads).contains("deviceAdmin")
    }

    @Test
    fun `generic SubSettings intercepts when the node tree mentions accessibility`() {
        val roots = RecordingRoots(accessibility = true)

        val decision = decide(signals(isGenericSubSettings = true), roots = roots)

        assertThat(decision).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `bare SubSettings with no matching signal is ignored`() {
        val decision = decide(signals(isGenericSubSettings = true))

        assertThat(decision).isEqualTo(Decision.IGNORE)
    }

    // ------------------------------------------- FocusGuard control surface

    @Test
    fun `app details screen naming FocusGuard intercepts`() {
        val decision = decide(
            signals(classTargetsAppDetails = true, textMentionsFocusGuard = true)
        )

        assertThat(decision).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `uninstall screen naming FocusGuard intercepts`() {
        val decision = decide(
            signals(classTargetsUninstall = true, textMentionsFocusGuard = true)
        )

        assertThat(decision).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `app details screen for another app is not intercepted`() {
        val roots = RecordingRoots(focusGuard = false)

        val decision = decide(signals(classTargetsAppDetails = true), roots = roots)

        assertThat(decision).isEqualTo(Decision.IGNORE)
    }

    @Test
    fun `package installer needs both FocusGuard and a destructive signal`() {
        val onlyName = decide(
            signals(packageName = INSTALLER, textMentionsFocusGuard = true)
        )
        assertThat(onlyName).isEqualTo(Decision.IGNORE)

        val both = decide(
            signals(
                packageName = INSTALLER,
                textMentionsFocusGuard = true,
                textMentionsDestructiveControl = true
            )
        )
        assertThat(both).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `package installer falls back to the node tree for both signals`() {
        val roots = RecordingRoots(focusGuard = true, destructive = true)

        val decision = decide(signals(packageName = INSTALLER), roots = roots)

        assertThat(decision).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    // ------------------------------------------- essential special access

    @Test
    fun `essential special access needs FocusGuard plus the access signal`() {
        val missingAccess = decide(
            signals(
                classTargetsEssentialSpecialAccess = true,
                textMentionsFocusGuard = true
            )
        )
        assertThat(missingAccess).isEqualTo(Decision.IGNORE)

        val complete = decide(
            signals(
                classTargetsEssentialSpecialAccess = true,
                textMentionsFocusGuard = true,
                textMentionsEssentialSpecialAccess = true
            )
        )
        assertThat(complete).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `essential special access for another app is not intercepted`() {
        val decision = decide(
            signals(
                classTargetsEssentialSpecialAccess = true,
                textMentionsEssentialSpecialAccess = true
            )
        )

        assertThat(decision).isEqualTo(Decision.IGNORE)
    }

    // -------------------------------------------------------------- default

    @Test
    fun `unremarkable settings screen is left alone`() {
        val decision = decide(signals())

        assertThat(decision).isEqualTo(Decision.IGNORE)
    }

    private companion object {
        const val SETTINGS = "com.android.settings"
        const val INSTALLER = "com.android.packageinstaller"
    }
}
