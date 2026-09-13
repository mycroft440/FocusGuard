package com.focusguard.ui

import android.content.Intent
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.security.AppBlockSurfacePolicy
import com.focusguard.service.BlockingAccessibilityService
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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

    @Test
    fun `website password route preserves blocked domain and curtain handshake`() {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val source = Intent().apply {
            putExtra(BlockingAccessibilityService.EXTRA_BLOCKED_DOMAIN, "youtube.com")
            putExtra(BlockingAccessibilityService.EXTRA_CURTAIN_GENERATION, 91L)
        }

        val routed = BlockNoticeActivity.createDestinationIntent(
            context,
            source,
            websiteSurfaceFor(BlockingSessionManager.ActiveWebsiteProtection.PASSWORD)
        )

        assertThat(routed.component?.className)
            .isEqualTo(PasswordUnlockActivity::class.java.name)
        assertThat(routed.getStringExtra(BlockingAccessibilityService.EXTRA_BLOCKED_DOMAIN))
            .isEqualTo("youtube.com")
        assertThat(
            routed.getLongExtra(BlockingAccessibilityService.EXTRA_CURTAIN_GENERATION, 0L)
        ).isEqualTo(91L)
    }
}
