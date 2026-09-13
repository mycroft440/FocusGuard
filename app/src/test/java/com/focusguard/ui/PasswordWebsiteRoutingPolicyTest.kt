package com.focusguard.ui

import com.focusguard.manager.BlockingSessionManager
import com.focusguard.security.AppBlockSurfacePolicy
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PasswordWebsiteRoutingPolicyTest {

    @Test
    fun `password is the only website owner routed to credential surface`() {
        assertThat(
            websiteSurfaceFor(BlockingSessionManager.ActiveWebsiteProtection.PASSWORD)
        ).isEqualTo(AppBlockSurfacePolicy.Surface.PASSWORD_UNLOCK)
    }

    @Test
    fun `stronger website owners stay on hard block surface`() {
        listOf(
            BlockingSessionManager.ActiveWebsiteProtection.TIME,
            BlockingSessionManager.ActiveWebsiteProtection.DAILY_LIMIT,
            BlockingSessionManager.ActiveWebsiteProtection.OTHER_HARD_BLOCK,
            BlockingSessionManager.ActiveWebsiteProtection.NONE
        ).forEach { protection ->
            assertThat(websiteSurfaceFor(protection))
                .isEqualTo(AppBlockSurfacePolicy.Surface.GENERIC_BLOCK)
        }
    }
}
