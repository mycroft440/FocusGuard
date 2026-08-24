package com.focusguard.security

/**
 * Immutable state published to the Accessibility hot path through one volatile
 * reference. A callback either observes the old complete generation or the new
 * complete generation; it never has to stitch together several mutable flags.
 */
data class ProtectionFastSnapshot(
    val generation: Long = 0L,
    val engaged: Boolean = false,
    val strictPomodoro: Boolean = false,
    val focusModeActive: Boolean = false,
    val deviceOwnerActive: Boolean = false,
    val maintenanceWindowActive: Boolean = false,
    val deviceAdminActive: Boolean = false,
    val adminEnrollmentAuthorized: Boolean = false
) {
    val administrativeRemovalAllowed: Boolean
        get() = maintenanceWindowActive || adminEnrollmentAuthorized
}
