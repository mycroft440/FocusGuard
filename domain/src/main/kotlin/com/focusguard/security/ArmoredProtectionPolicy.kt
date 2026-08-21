package com.focusguard.security

/**
 * State machine for the self-protection boundary enforced by Device Owner.
 *
 * The phase is derived from Android-confirmed ownership, persisted protection flags and the
 * short maintenance gate. UI state is deliberately not an input, so recomposition, deep links
 * or another screen cannot manufacture a maintenance transition.
 */
object ArmoredProtectionPolicy {

    enum class Phase {
        NOT_DEVICE_OWNER,
        READY,
        ARMED,
        MAINTENANCE
    }

    fun phase(
        deviceOwnerActive: Boolean,
        protectionArmed: Boolean,
        maintenanceActive: Boolean
    ): Phase = when {
        !deviceOwnerActive -> Phase.NOT_DEVICE_OWNER
        maintenanceActive -> Phase.MAINTENANCE
        protectionArmed -> Phase.ARMED
        else -> Phase.READY
    }

    /** A local credential is never an immediate escape from an active commitment. */
    fun canOpenMaintenanceWithCredential(phase: Phase): Boolean = phase == Phase.READY

    /** Device Owner can only be relinquished after a legitimate maintenance transition. */
    fun canRenounceDeviceOwner(phase: Phase): Boolean = phase == Phase.MAINTENANCE
}

