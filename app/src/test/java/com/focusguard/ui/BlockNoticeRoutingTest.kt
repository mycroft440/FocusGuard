package com.focusguard.ui

import android.content.Intent
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
class BlockNoticeRoutingTest {

    @Test
    fun `password surface targets dedicated password activity and preserves handshake`() {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val source = Intent().apply {
            putExtra(BlockingAccessibilityService.EXTRA_BLOCKED_PACKAGE, "com.example.secret")
            putExtra(BlockingAccessibilityService.EXTRA_CURTAIN_GENERATION, 77L)
            putExtra(BlockingAccessibilityService.EXTRA_BLOCK_EVENT_UPTIME_MILLIS, 123L)
        }

        val routed = BlockNoticeActivity.createDestinationIntent(
            context,
            source,
            AppBlockSurfacePolicy.Surface.PASSWORD_UNLOCK
        )

        assertThat(routed.component?.className)
            .isEqualTo(PasswordUnlockActivity::class.java.name)
        assertThat(
            routed.getLongExtra(BlockingAccessibilityService.EXTRA_CURTAIN_GENERATION, 0L)
        ).isEqualTo(77L)
        assertThat(
            routed.getStringExtra(BlockingAccessibilityService.EXTRA_BLOCKED_PACKAGE)
        ).isEqualTo("com.example.secret")
    }

    @Test
    fun `generic surface never targets password activity`() {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val source = Intent().apply {
            putExtra(BlockingAccessibilityService.EXTRA_BLOCKED_PACKAGE, "com.example.blocked")
        }

        val routed = BlockNoticeActivity.createDestinationIntent(
            context,
            source,
            AppBlockSurfacePolicy.Surface.GENERIC_BLOCK
        )

        assertThat(routed.component?.className)
            .isEqualTo(GenericBlockNoticeActivity::class.java.name)
        assertThat(routed.component?.className)
            .isNotEqualTo(PasswordUnlockActivity::class.java.name)
    }
}
