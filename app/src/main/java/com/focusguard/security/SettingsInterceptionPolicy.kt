package com.focusguard.security

/**
 * Pure decision tree that decides whether the accessibility service should
 * intercept a system settings screen, and what action to take.
 *
 * Self-protection is deliberately target-scoped: a Settings class name, a Device
 * Admin/Accessibility gateway label, or the mere presence of the app name somewhere
 * in the root tree is never sufficient to block navigation. The current event must
 * directly identify FocusGuard before destructive controls are intercepted.
 */
object SettingsInterceptionPolicy {

    val settingsPackages = setOf(
        "com.android.settings",
        "com.miui.securitycenter",
        "com.huawei.systemmanager",
        "com.samsung.android.sm",
        "com.samsung.android.sm_cn"
    )

    val packageInstallerPackages = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.miui.packageinstaller"
    )

    val systemUiPackages = setOf("com.android.systemui")

    val protectedSystemPackages = settingsPackages + packageInstallerPackages

    /** System UI is included only for narrowly classified FocusGuard actions. */
    val interceptionPackages = protectedSystemPackages + systemUiPackages

    enum class Decision {
        IGNORE,
        POMODORO_LOCK,
        PROTECT_AND_ARM_GUARD,
        PROTECT
    }

    data class EventSignals(
        val packageName: String,
        val isViewClickedEvent: Boolean,
        val isWindowTransitionEvent: Boolean,
        val guardArmed: Boolean,
        val classTargetsAccessibilityServiceToggle: Boolean,
        val classTargetsAccessibilityList: Boolean,
        val classTargetsDeviceAdmin: Boolean,
        val classTargetsAppDetails: Boolean,
        val classTargetsUninstall: Boolean,
        val classTargetsEssentialSpecialAccess: Boolean,
        val isGenericSubSettings: Boolean,
        val textMentionsAccessibility: Boolean,
        val textMentionsInstalledAccessibilityApps: Boolean,
        val textMentionsAccessibilityDisclosure: Boolean,
        val textMentionsDeviceAdmin: Boolean,
        val textMentionsFocusGuard: Boolean,
        val textMentionsDestructiveControl: Boolean,
        val textMentionsEssentialSpecialAccess: Boolean,
        val textMentionsAppInfoGateway: Boolean = false
    )

    /**
     * Root reads remain available for contextual classification only. In particular,
     * [mentionsFocusGuard] is intentionally not used as target identity by [decide].
     */
    class RootSignals(
        mentionsAccessibility: () -> Boolean,
        mentionsDeviceAdmin: () -> Boolean,
        mentionsFocusGuard: () -> Boolean,
        mentionsDestructiveControl: () -> Boolean,
        mentionsEssentialSpecialAccess: () -> Boolean
    ) {
        val mentionsAccessibility = memoized(mentionsAccessibility)
        val mentionsDeviceAdmin = memoized(mentionsDeviceAdmin)
        val mentionsFocusGuard = memoized(mentionsFocusGuard)
        val mentionsDestructiveControl = memoized(mentionsDestructiveControl)
        val mentionsEssentialSpecialAccess = memoized(mentionsEssentialSpecialAccess)

        private fun memoized(source: () -> Boolean): () -> Boolean {
            val cached by lazy(LazyThreadSafetyMode.NONE) { source() }
            return { cached }
        }
    }

    fun decide(
        signals: EventSignals,
        selfProtectionEngaged: Boolean,
        strictPomodoroActive: Boolean,
        deviceAdminActivationAuthorized: Boolean,
        maintenanceActive: Boolean = false,
        rootSignals: RootSignals
    ): Decision {
        if (signals.packageName !in interceptionPackages) return Decision.IGNORE

        updateTargetScope(signals)

        if (!selfProtectionEngaged) {
            SelfProtectionTargetScope.clear()
            return Decision.IGNORE
        }

        // Maintenance keeps only FocusGuard's own essential enforcement accesses
        // protected. The app name must come from the current event, never a broad
        // root-tree hit that could merely be a row in a list.
        if (maintenanceActive) {
            if (!signals.textMentionsFocusGuard) return Decision.IGNORE
            val essentialAccess = signals.classTargetsEssentialSpecialAccess ||
                signals.textMentionsEssentialSpecialAccess ||
                rootSignals.mentionsEssentialSpecialAccess()
            return if (essentialAccess) Decision.PROTECT else Decision.IGNORE
        }

        if (signals.packageName in systemUiPackages) {
            if (!signals.isViewClickedEvent || !signals.textMentionsFocusGuard) {
                return Decision.IGNORE
            }

            return if (signals.textMentionsAccessibilityDisclosure ||
                signals.textMentionsDeviceAdmin
            ) {
                Decision.PROTECT
            } else {
                Decision.IGNORE
            }
        }

        if (strictPomodoroActive &&
            (!signals.isViewClickedEvent || deviceAdminActivationAuthorized)
        ) {
            return Decision.POMODORO_LOCK
        }

        // The short ACTION_ADD_DEVICE_ADMIN enrollment opened by FocusGuard must
        // stay usable. It is allowed only when the current event directly names
        // FocusGuard and it is not another destructive/special-access surface.
        if (deviceAdminActivationAuthorized &&
            signals.textMentionsFocusGuard &&
            !signals.classTargetsAppDetails &&
            !signals.classTargetsUninstall &&
            !signals.classTargetsAccessibilityServiceToggle &&
            !signals.classTargetsEssentialSpecialAccess &&
            (signals.textMentionsDeviceAdmin ||
                signals.classTargetsDeviceAdmin ||
                signals.isGenericSubSettings ||
                rootSignals.mentionsDeviceAdmin())
        ) {
            return Decision.IGNORE
        }

        if (signals.isViewClickedEvent &&
            signals.textMentionsAppInfoGateway &&
            signals.textMentionsFocusGuard
        ) {
            return Decision.PROTECT
        }

        // Device Admin list/gateway stays fully available. Only an interaction
        // that is both about Device Admin and directly tied to FocusGuard is ours.
        val deviceAdminContext = signals.textMentionsDeviceAdmin ||
            signals.classTargetsDeviceAdmin ||
            (signals.textMentionsFocusGuard && rootSignals.mentionsDeviceAdmin())
        if (signals.textMentionsFocusGuard && deviceAdminContext) {
            return Decision.PROTECT
        }

        // The Accessibility services list is also a normal system-management area.
        // Do not block its gateway/list. The FocusGuard row or its own toggle is
        // protected only when the current event identifies FocusGuard.
        if (signals.classTargetsAccessibilityServiceToggle &&
            signals.textMentionsFocusGuard
        ) {
            return Decision.PROTECT
        }

        if (signals.isViewClickedEvent && signals.textMentionsFocusGuard) {
            return Decision.PROTECT
        }

        // Strict Pomodoro owns ordinary Settings navigation, but only after the
        // explicit FocusGuard removal/permission cases above had a chance to route
        // through the master-removal flow.
        if (strictPomodoroActive) return Decision.POMODORO_LOCK

        // A stale guard may never consume a different app's event. The guard is
        // accepted only when this event independently proves FocusGuard identity.
        if (signals.guardArmed &&
            !signals.isViewClickedEvent &&
            signals.textMentionsFocusGuard
        ) {
            return Decision.PROTECT
        }

        val onFocusGuardControlSurface =
            signals.classTargetsAppDetails ||
                signals.classTargetsUninstall ||
                signals.packageName in packageInstallerPackages
        if (onFocusGuardControlSurface &&
            signals.textMentionsFocusGuard &&
            (signals.classTargetsAppDetails ||
                signals.classTargetsUninstall ||
                signals.textMentionsDestructiveControl ||
                rootSignals.mentionsDestructiveControl())
        ) {
            return Decision.PROTECT
        }

        if (signals.classTargetsEssentialSpecialAccess &&
            signals.textMentionsFocusGuard &&
            (signals.textMentionsEssentialSpecialAccess ||
                rootSignals.mentionsEssentialSpecialAccess())
        ) {
            return Decision.PROTECT
        }

        if (signals.isGenericSubSettings &&
            signals.textMentionsFocusGuard &&
            (signals.textMentionsAccessibility || rootSignals.mentionsAccessibility())
        ) {
            return Decision.PROTECT
        }

        return Decision.IGNORE
    }

    /**
     * Bind temporary self-protection state to the current interaction. A direct
     * FocusGuard identity arms it; the next click/window transition in a protected
     * system package without that identity clears it immediately.
     */
    private fun updateTargetScope(signals: EventSignals) {
        if (signals.textMentionsFocusGuard) {
            SelfProtectionTargetScope.confirmFocusGuardTarget()
            return
        }
        if (signals.isViewClickedEvent || signals.isWindowTransitionEvent) {
            SelfProtectionTargetScope.clear()
        }
    }
}
