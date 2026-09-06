package com.focusguard.security

/**
 * Chooses which blocking surface owns an app interception.
 *
 * PASSWORD is intentionally the weakest interactive route: any protection that
 * must keep the target closed (strict Pomodoro, Focus Mode, dopamine fast, or an
 * already-active usage limit) wins before a target credential can be offered.
 */
object AppBlockSurfacePolicy {
    enum class Surface {
        PASSWORD_UNLOCK,
        GENERIC_BLOCK
    }

    data class Facts(
        val strictPomodoro: Boolean,
        val focusModeBlocksTarget: Boolean,
        val dopamineFastBlocksTarget: Boolean,
        val activeUsageLimitBlocksTarget: Boolean,
        val credentialOrigin: BiometricAppUnlockPolicy.BlockOrigin?
    )

    fun decide(facts: Facts): Surface {
        if (
            facts.strictPomodoro ||
            facts.focusModeBlocksTarget ||
            facts.dopamineFastBlocksTarget ||
            facts.activeUsageLimitBlocksTarget
        ) {
            return Surface.GENERIC_BLOCK
        }

        return if (
            facts.credentialOrigin == BiometricAppUnlockPolicy.BlockOrigin.PASSWORD_SESSION
        ) {
            Surface.PASSWORD_UNLOCK
        } else {
            Surface.GENERIC_BLOCK
        }
    }
}
