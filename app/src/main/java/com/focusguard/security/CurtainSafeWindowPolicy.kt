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
    ): Boolean = visiblePackage.isNotBlank() &&
        visiblePackage != focusGuardPackage &&
        (visiblePackage in blockedPackages ||
            (protectSettings && visiblePackage in protectedSettingsPackages))
}
