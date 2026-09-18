package com.focusguard.security

/** Final guard before an acknowledged safe Activity may uncover other windows. */
object CurtainSafeWindowPolicy {
    enum class Decision { WAIT_FOR_SETTLE, DISMISS, KEEP_AND_EVACUATE }

    fun decide(
        settleElapsed: Boolean,
        unsafeWindowVisible: Boolean
    ): Decision = when {
        !settleElapsed -> Decision.WAIT_FOR_SETTLE
        unsafeWindowVisible -> Decision.KEEP_AND_EVACUATE
        else -> Decision.DISMISS
    }

    fun isUnsafePackage(
        visiblePackage: String,
        focusGuardPackage: String,
        blockedPackages: Set<String>,
        protectSettings: Boolean,
        protectedSettingsPackages: Set<String>
    ): Boolean {
        if (visiblePackage.isBlank() || visiblePackage == focusGuardPackage) return false
        if (visiblePackage in blockedPackages) return true

        // Settings/installer windows are unsafe only while the current system
        // interaction is still explicitly bound to FocusGuard. Merely being a
        // Settings package while the self-protection curtain is visible is not
        // enough, otherwise navigating to another app extends the interference.
        return protectSettings &&
            visiblePackage in protectedSettingsPackages &&
            SelfProtectionTargetScope.isFocusGuardTargetConfirmed()
    }
}
