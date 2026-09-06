package com.focusguard.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppBlockSurfacePolicyTest {

    private fun facts(
        strictPomodoro: Boolean = false,
        focusModeBlocksTarget: Boolean = false,
        dopamineFastBlocksTarget: Boolean = false,
        activeUsageLimitBlocksTarget: Boolean = false,
        credentialOrigin: BiometricAppUnlockPolicy.BlockOrigin? =
            BiometricAppUnlockPolicy.BlockOrigin.PASSWORD_SESSION
    ) = AppBlockSurfacePolicy.Facts(
        strictPomodoro = strictPomodoro,
        focusModeBlocksTarget = focusModeBlocksTarget,
        dopamineFastBlocksTarget = dopamineFastBlocksTarget,
        activeUsageLimitBlocksTarget = activeUsageLimitBlocksTarget,
        credentialOrigin = credentialOrigin
    )

    @Test
    fun `plain password session owns dedicated unlock surface`() {
        assertThat(AppBlockSurfacePolicy.decide(facts()))
            .isEqualTo(AppBlockSurfacePolicy.Surface.PASSWORD_UNLOCK)
    }

    @Test
    fun `strict pomodoro always keeps generic hard block`() {
        assertThat(AppBlockSurfacePolicy.decide(facts(strictPomodoro = true)))
            .isEqualTo(AppBlockSurfacePolicy.Surface.GENERIC_BLOCK)
    }

    @Test
    fun `focus mode wins over password session`() {
        assertThat(AppBlockSurfacePolicy.decide(facts(focusModeBlocksTarget = true)))
            .isEqualTo(AppBlockSurfacePolicy.Surface.GENERIC_BLOCK)
    }

    @Test
    fun `dopamine fast wins over password session`() {
        assertThat(AppBlockSurfacePolicy.decide(facts(dopamineFastBlocksTarget = true)))
            .isEqualTo(AppBlockSurfacePolicy.Surface.GENERIC_BLOCK)
    }

    @Test
    fun `active daily limit wins over password session`() {
        assertThat(AppBlockSurfacePolicy.decide(facts(activeUsageLimitBlocksTarget = true)))
            .isEqualTo(AppBlockSurfacePolicy.Surface.GENERIC_BLOCK)
    }

    @Test
    fun `password protected usage limit never enters target password activity`() {
        assertThat(
            AppBlockSurfacePolicy.decide(
                facts(
                    credentialOrigin =
                        BiometricAppUnlockPolicy.BlockOrigin.USAGE_LIMIT_PASSWORD_UNLOCK
                )
            )
        ).isEqualTo(AppBlockSurfacePolicy.Surface.GENERIC_BLOCK)
    }

    @Test
    fun `unknown origin fails closed on generic surface`() {
        assertThat(AppBlockSurfacePolicy.decide(facts(credentialOrigin = null)))
            .isEqualTo(AppBlockSurfacePolicy.Surface.GENERIC_BLOCK)
    }
}
