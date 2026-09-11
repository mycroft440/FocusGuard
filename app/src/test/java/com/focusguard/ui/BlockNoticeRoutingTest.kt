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

    @Test
    fun `duplicate target coalesces while newest curtain handshake is preserved`() {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val first = Intent().apply {
            putExtra(BlockingAccessibilityService.EXTRA_BLOCKED_PACKAGE, "com.example.secret")
            putExtra(BlockingAccessibilityService.EXTRA_CURTAIN_GENERATION, 10L)
        }
        val latest = Intent().apply {
            putExtra(BlockingAccessibilityService.EXTRA_BLOCKED_PACKAGE, "com.example.secret")
            putExtra(BlockingAccessibilityService.EXTRA_CURTAIN_GENERATION, 11L)
        }
        val firstKey = BlockNoticeActivity.routeKey(first)
        val latestKey = BlockNoticeActivity.routeKey(latest)

        assertThat(
            BlockNoticeActivity.shouldCoalesceRoute(
                routeActive = true,
                activeKey = firstKey,
                incomingKey = latestKey
            )
        ).isTrue()

        val routed = BlockNoticeActivity.createDestinationIntent(
            context,
            latest,
            AppBlockSurfacePolicy.Surface.PASSWORD_UNLOCK
        )
        assertThat(
            routed.getLongExtra(BlockingAccessibilityService.EXTRA_CURTAIN_GENERATION, 0L)
        ).isEqualTo(11L)
    }

    @Test
    fun `different target never coalesces with active route`() {
        val active = Intent().apply {
            putExtra(BlockingAccessibilityService.EXTRA_BLOCKED_PACKAGE, "com.example.first")
        }
        val incoming = Intent().apply {
            putExtra(BlockingAccessibilityService.EXTRA_BLOCKED_PACKAGE, "com.example.second")
        }

        assertThat(
            BlockNoticeActivity.shouldCoalesceRoute(
                routeActive = true,
                activeKey = BlockNoticeActivity.routeKey(active),
                incomingKey = BlockNoticeActivity.routeKey(incoming)
            )
        ).isFalse()
    }

    @Test
    fun `inactive route never coalesces even for same target`() {
        val source = Intent().apply {
            putExtra(BlockingAccessibilityService.EXTRA_BLOCKED_PACKAGE, "com.example.secret")
        }
        val key = BlockNoticeActivity.routeKey(source)

        assertThat(
            BlockNoticeActivity.shouldCoalesceRoute(
                routeActive = false,
                activeKey = key,
                incomingKey = key
            )
        ).isFalse()
    }
}
