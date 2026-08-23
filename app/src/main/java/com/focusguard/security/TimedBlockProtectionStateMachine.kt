package com.focusguard.security

/**
 * Pure state machine for the protected TIME lifecycle.
 *
 * Keeping transitions outside Android code makes crash/reboot behaviour testable without a
 * Device Owner emulator. The persisted phase is diagnostic: the authoritative state is still
 * the exact protected-session record plus the Android policy read-back.
 */
object TimedBlockProtectionStateMachine {

    enum class Phase(val storageValue: String) {
        IDLE("idle"),
        PREPARING("preparing"),
        ACTIVE("active"),
        REVOKING("revoking"),
        ERROR("error");

        companion object {
            fun fromStorage(value: String?): Phase =
                entries.firstOrNull { it.storageValue == value } ?: IDLE
        }
    }

    fun afterReconcile(
        hasProtectedSessions: Boolean,
        pendingCreation: Boolean,
        policyApplied: Boolean
    ): Phase = when {
        !policyApplied && (hasProtectedSessions || pendingCreation) -> Phase.ERROR
        pendingCreation -> Phase.PREPARING
        hasProtectedSessions -> Phase.ACTIVE
        else -> Phase.IDLE
    }
}
