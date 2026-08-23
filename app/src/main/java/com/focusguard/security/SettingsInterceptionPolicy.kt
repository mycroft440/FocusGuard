package com.focusguard.security

/**
 * Pure decision tree that decides whether the accessibility service should
 * intercept a system settings screen, and what action to take.
 *
 * The individual classifiers ([AccessibilitySettingsPolicy] and
 * [ManagedSelfProtectionPolicy]) already have their own tests; what lives here
 * is the composition of those signals. App-specific destructive surfaces still
 * require FocusGuard identity. Two revocation gateways are intentionally
 * protected earlier while a consented protection is active: the Device Admin
 * apps entry and the Installed accessibility apps/services entry.
 *
 * Reading the accessibility node tree is expensive, so the `root*` signals are
 * passed as lambdas and evaluated only on the branches that need them. Keep them
 * lazy when calling.
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

    /**
     * System UI is included only so a click on Android's accessibility privacy
     * disclosure can be classified before it deep-links into Settings. It is
     * handled by a closed branch in [decide] and never inherits the broader
     * Settings interception rules.
     */
    val interceptionPackages = protectedSystemPackages + systemUiPackages

    /** What the service should do with the event. */
    enum class Decision {
        /** Let the event through untouched. */
        IGNORE,

        /** Strict Pomodoro is running: go back and re-show the lock screen. */
        POMODORO_LOCK,

        /** Run the protection action and arm the transition guard. */
        PROTECT_AND_ARM_GUARD,

        /**
         * Run the protection action without re-arming the guard. Used when the
         * guard is already counting down and a follow-up, non-click event
         * arrives during the window.
         */
        PROTECT
    }

    /**
     * Signals extracted from the event before any node-tree read.
     *
     * @param isViewClickedEvent whether the event is `TYPE_VIEW_CLICKED`.
     * @param guardArmed whether the transition guard window is still open.
     */
    data class EventSignals(
        val packageName: String,
        val isViewClickedEvent: Boolean,
        val isWindowTransitionEvent: Boolean,
        val guardArmed: Boolean,

        /** Tela individual que contém o interruptor de um serviço. */
        val classTargetsAccessibilityServiceToggle: Boolean,

        /** Uma tela que lista recursos ou serviços de acessibilidade. */
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
        val textMentionsEssentialSpecialAccess: Boolean
    )

    /**
     * Lazily-evaluated reads of the live accessibility node tree. Each is only
     * invoked on the branch that needs it.
     */
    class RootSignals(
        val mentionsAccessibility: () -> Boolean,
        val mentionsDeviceAdmin: () -> Boolean,
        val mentionsFocusGuard: () -> Boolean,
        val mentionsDestructiveControl: () -> Boolean,
        val mentionsEssentialSpecialAccess: () -> Boolean
    )

    /**
     * @param selfProtectionEngaged true when the app should defend its own
     *   removal right now. Two things turn it on:
     *   - a true Device Owner with hardening armed and maintenance closed
     *     (the strongest form), or
     *   - consumer mode: no Device Owner, but a block, usage limit or the adult
     *     filter is live at this moment. Off the moment nothing is being
     *     protected, so a phone with no active block stays freely removable.
     *
     *   When it is off, every screen is ignored — the whole point is that the
     *   app never traps a user who has nothing running.
     * @param deviceAdminActivationAuthorized true only during a permission
     *   enrollment initiated inside FocusGuard while it is not an administrator.
     */
    fun decide(
        signals: EventSignals,
        selfProtectionEngaged: Boolean,
        strictPomodoroActive: Boolean,
        deviceAdminActivationAuthorized: Boolean,
        rootSignals: RootSignals
    ): Decision {
        if (signals.packageName !in interceptionPackages) return Decision.IGNORE
        if (!selfProtectionEngaged) return Decision.IGNORE

        // Android owns and keeps showing this privacy disclosure. We only stop
        // its direct deep-link to FocusGuard's own service switch while a
        // consented protection is active. Requiring all three signals prevents
        // normal FocusGuard notifications (timers, status, warnings) and every
        // other System UI interaction from being swallowed.
        if (signals.packageName in systemUiPackages) {
            return if (signals.isViewClickedEvent &&
                signals.textMentionsFocusGuard &&
                signals.textMentionsAccessibilityDisclosure
            ) {
                Decision.PROTECT_AND_ARM_GUARD
            } else {
                Decision.IGNORE
            }
        }

        if (strictPomodoroActive) return Decision.POMODORO_LOCK

        // ACTION_ADD_DEVICE_ADMIN is authorized only while FocusGuard is not yet
        // an active administrator. Allow the Device Admin activity during this
        // short enrollment window so the permission can be granted. The window
        // becomes invalid automatically as soon as the permission is active.
        // App details, accessibility and uninstall surfaces remain protected.
        if (deviceAdminActivationAuthorized && signals.classTargetsDeviceAdmin) {
            return Decision.IGNORE
        }
        if (deviceAdminActivationAuthorized && signals.isGenericSubSettings) {
            val onAuthorizedAdminSurface =
                (signals.textMentionsDeviceAdmin || rootSignals.mentionsDeviceAdmin()) &&
                    (signals.textMentionsFocusGuard || rootSignals.mentionsFocusGuard())
            if (onAuthorizedAdminSurface) return Decision.IGNORE
        }

        // The two menus below are revocation gateways. Once a protection is active,
        // letting the user enter them exposes the switches that disable the very
        // permissions enforcing that protection. Block the gateway itself instead
        // of waiting for the final FocusGuard switch to be touched.
        if (signals.isViewClickedEvent && signals.textMentionsDeviceAdmin) {
            return Decision.PROTECT_AND_ARM_GUARD
        }
        if (signals.classTargetsDeviceAdmin) {
            return Decision.PROTECT_AND_ARM_GUARD
        }
        if (signals.isGenericSubSettings &&
            (signals.textMentionsDeviceAdmin || rootSignals.mentionsDeviceAdmin())
        ) {
            return Decision.PROTECT_AND_ARM_GUARD
        }

        val installedAccessibilityEntry =
            signals.textMentionsInstalledAccessibilityApps &&
                (signals.textMentionsAccessibility || rootSignals.mentionsAccessibility())
        if (signals.isViewClickedEvent && installedAccessibilityEntry) {
            return Decision.PROTECT_AND_ARM_GUARD
        }
        if (signals.classTargetsAccessibilityList &&
            signals.textMentionsInstalledAccessibilityApps
        ) {
            return Decision.PROTECT_AND_ARM_GUARD
        }

        // O primeiro evento útil normalmente é o clique no item "FocusGuard".
        // Interceptá-lo antes da transição fecha a principal corrida do modo
        // consumidor sem interditar telas de outros aplicativos.
        if (signals.isViewClickedEvent && signals.textMentionsFocusGuard) {
            return Decision.PROTECT_AND_ARM_GUARD
        }

        if (signals.guardArmed && !signals.isViewClickedEvent) {
            return Decision.PROTECT
        }

        // Only the screen that can switch FocusGuard's own service off, and only
        // once the screen is confirmed to be about FocusGuard. Another service's
        // toggle is none of our business.
        if (signals.classTargetsAccessibilityServiceToggle &&
            (signals.textMentionsFocusGuard || rootSignals.mentionsFocusGuard())
        ) {
            return Decision.PROTECT_AND_ARM_GUARD
        }

        val onFocusGuardControlSurface =
            signals.classTargetsAppDetails ||
                signals.classTargetsUninstall ||
                signals.packageName in packageInstallerPackages
        if (onFocusGuardControlSurface &&
            (signals.textMentionsFocusGuard || rootSignals.mentionsFocusGuard()) &&
            (signals.classTargetsAppDetails ||
                signals.classTargetsUninstall ||
                signals.textMentionsDestructiveControl ||
                rootSignals.mentionsDestructiveControl())
        ) {
            return Decision.PROTECT_AND_ARM_GUARD
        }

        if (signals.classTargetsEssentialSpecialAccess &&
            (signals.textMentionsFocusGuard || rootSignals.mentionsFocusGuard()) &&
            (signals.textMentionsEssentialSpecialAccess ||
                rootSignals.mentionsEssentialSpecialAccess())
        ) {
            return Decision.PROTECT_AND_ARM_GUARD
        }

        // OEM skins often host the per-service toggle inside a generic SubSettings
        // shell, so the class name alone gives nothing away. Requiring both the
        // accessibility context *and* FocusGuard keeps that path covered without
        // swallowing the rest of the section.
        if (signals.isGenericSubSettings &&
            (signals.textMentionsAccessibility || rootSignals.mentionsAccessibility()) &&
            (signals.textMentionsFocusGuard || rootSignals.mentionsFocusGuard())
        ) {
            return Decision.PROTECT_AND_ARM_GUARD
        }

        return Decision.IGNORE
    }
}
