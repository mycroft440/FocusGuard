package com.focusguard.security

/**
 * Authorization policy for Settings > Remove all blocks.
 *
 * Time blocks and usage limits can be configured without locking entry to the
 * management UI, so clearing them is sensitive. They require the master
 * credential unless this same foreground visit already verified an active app
 * entry credential (password, pattern, or biometric).
 *
 * With no time block and no usage limit, the action does not introduce an
 * additional credential prompt. PASSWORD-only protection is already guarded by
 * the app-entry flow when applicable.
 */
object RemoveAllBlocksAuthorizationPolicy {
    enum class Gate {
        ALLOW,
        REQUIRE_MASTER_CREDENTIAL
    }

    fun evaluate(
        hasActiveTimeProtection: Boolean,
        hasActiveUsageLimit: Boolean,
        appEntryCredentialAuthenticated: Boolean
    ): Gate {
        val needsSensitiveAuthorization =
            hasActiveTimeProtection || hasActiveUsageLimit
        if (!needsSensitiveAuthorization) return Gate.ALLOW

        return if (appEntryCredentialAuthenticated) {
            Gate.ALLOW
        } else {
            Gate.REQUIRE_MASTER_CREDENTIAL
        }
    }
}
