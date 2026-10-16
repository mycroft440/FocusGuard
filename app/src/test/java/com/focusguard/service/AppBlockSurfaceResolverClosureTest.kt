package com.focusguard.service

import com.focusguard.security.AppBlockSurfacePolicy
import com.focusguard.security.BiometricAppUnlockPolicy
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppBlockSurfaceResolverClosureTest {

    @Test
    fun `plain password visit keeps target task for credential return`() {
        assertThat(
            AppBlockSurfaceResolver.shouldCloseTargetAfterInterception(
                credentialOrigin = BiometricAppUnlockPolicy.BlockOrigin.PASSWORD_SESSION,
                timedOrUsageInterventionBlocksTarget = false,
                dopamineFastBlocksTarget = false
            )
        ).isFalse()
    }

    @Test
    fun `plain password resolution allows one authenticated visit`() {
        val resolution = AppBlockSurfaceResolver.Resolution(
            surface = AppBlockSurfacePolicy.Surface.PASSWORD_UNLOCK,
            closeTargetAfterInterception = false
        )

        assertThat(resolution.allowsPasswordVisit).isTrue()
    }

    @Test
    fun `generic block resolution cannot grant password visit`() {
        val resolution = AppBlockSurfaceResolver.Resolution(
            surface = AppBlockSurfacePolicy.Surface.GENERIC_BLOCK,
            closeTargetAfterInterception = true
        )

        assertThat(resolution.allowsPasswordVisit).isFalse()
    }

    @Test
    fun `password surface marked for closure fails closed`() {
        val resolution = AppBlockSurfaceResolver.Resolution(
            surface = AppBlockSurfacePolicy.Surface.PASSWORD_UNLOCK,
            closeTargetAfterInterception = true
        )

        assertThat(resolution.allowsPasswordVisit).isFalse()
    }

    @Test
    fun `password protected usage limit still closes intercepted target`() {
        assertThat(
            AppBlockSurfaceResolver.shouldCloseTargetAfterInterception(
                credentialOrigin =
                    BiometricAppUnlockPolicy.BlockOrigin.USAGE_LIMIT_PASSWORD_UNLOCK,
                timedOrUsageInterventionBlocksTarget = false,
                dopamineFastBlocksTarget = false
            )
        ).isTrue()
    }

    @Test
    fun `active non password usage intervention closes intercepted target`() {
        assertThat(
            AppBlockSurfaceResolver.shouldCloseTargetAfterInterception(
                credentialOrigin = null,
                timedOrUsageInterventionBlocksTarget = true,
                dopamineFastBlocksTarget = false
            )
        ).isTrue()
    }

    @Test
    fun `time commitment overlapping password closes instead of preserving visit`() {
        assertThat(
            AppBlockSurfaceResolver.shouldCloseTargetAfterInterception(
                credentialOrigin = BiometricAppUnlockPolicy.BlockOrigin.PASSWORD_SESSION,
                timedOrUsageInterventionBlocksTarget = false,
                dopamineFastBlocksTarget = true
            )
        ).isTrue()
    }

    @Test
    fun `unknown generic origin does not invent target shutdown`() {
        assertThat(
            AppBlockSurfaceResolver.shouldCloseTargetAfterInterception(
                credentialOrigin = null,
                timedOrUsageInterventionBlocksTarget = false,
                dopamineFastBlocksTarget = false
            )
        ).isFalse()
    }

    @Test
    fun `attempt without a live owner never grants a password visit`() {
        val resolution = AppBlockSurfaceResolver.Resolution(
            surface = AppBlockSurfacePolicy.Surface.GENERIC_BLOCK,
            closeTargetAfterInterception = false,
            hasLiveOwner = false
        )

        assertThat(resolution.hasLiveOwner).isFalse()
        assertThat(resolution.allowsPasswordVisit).isFalse()
    }

    @Test
    fun `an exhausted password limit offers the master password`() {
        assertThat(offers(BiometricAppUnlockPolicy.BlockOrigin.USAGE_LIMIT_PASSWORD_UNLOCK))
            .isTrue()
    }

    @Test
    fun `a time block or focus mode over the password limit keeps the app closed`() {
        // A senha liberaria só o limite; o bloqueio por tempo (ou o Modo Foco) por cima
        // continuaria segurando o app.
        assertThat(
            offers(
                BiometricAppUnlockPolicy.BlockOrigin.USAGE_LIMIT_PASSWORD_UNLOCK,
                timedOrUsage = true
            )
        ).isFalse()
        assertThat(
            offers(
                BiometricAppUnlockPolicy.BlockOrigin.USAGE_LIMIT_PASSWORD_UNLOCK,
                focusMode = true
            )
        ).isFalse()
    }

    @Test
    fun `other owners never offer the master password on the block screen`() {
        assertThat(offers(BiometricAppUnlockPolicy.BlockOrigin.PASSWORD_SESSION)).isFalse()
        assertThat(offers(null)).isFalse()
        assertThat(offers(null, timedOrUsage = true)).isFalse()
    }

    private fun offers(
        origin: BiometricAppUnlockPolicy.BlockOrigin?,
        timedOrUsage: Boolean = false,
        focusMode: Boolean = false
    ) = AppBlockSurfaceResolver.offersLimitPasswordUnlock(
        credentialOrigin = origin,
        timedOrUsageInterventionBlocksTarget = timedOrUsage,
        focusModeBlocksTarget = focusMode
    )
}
