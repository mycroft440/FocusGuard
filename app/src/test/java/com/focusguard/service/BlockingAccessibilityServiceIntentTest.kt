package com.focusguard.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BlockingAccessibilityServiceIntentTest {

    @Test
    fun `duplicate block notice is coalesced during cooldown`() {
        val shouldLaunch = BlockingAccessibilityService.shouldLaunchBlockNotice(
            previousKey = "app|example",
            previousLaunchElapsed = 1_000L,
            requestedKey = "app|example",
            nowElapsed = 1_100L
        )

        assertThat(shouldLaunch).isFalse()
    }

    @Test
    fun `same notice can be shown again after cooldown`() {
        val shouldLaunch = BlockingAccessibilityService.shouldLaunchBlockNotice(
            previousKey = "app|example",
            previousLaunchElapsed = 1_000L,
            requestedKey = "app|example",
            nowElapsed = 1_000L +
                BlockingAccessibilityService.BLOCK_NOTICE_RELAUNCH_COOLDOWN_MILLIS
        )

        assertThat(shouldLaunch).isTrue()
    }

    @Test
    fun `a different blocked target is never swallowed by cooldown`() {
        val shouldLaunch = BlockingAccessibilityService.shouldLaunchBlockNotice(
            previousKey = "app|one",
            previousLaunchElapsed = 1_000L,
            requestedKey = "app|two",
            nowElapsed = 1_001L
        )

        assertThat(shouldLaunch).isTrue()
    }

    @Test
    fun `settings event storm cannot execute the protection animation twice`() {
        assertThat(
            BlockingAccessibilityService.shouldExecuteProtectionAction(
                blockedUntilElapsed = 3_500L,
                nowElapsed = 2_000L
            )
        ).isFalse()
        assertThat(
            BlockingAccessibilityService.selfProtectionActionDebounceMillisForTest()
        ).isAtLeast(BlockingAccessibilityService.settingsTransitionGuardMillisForTest())
    }
}
