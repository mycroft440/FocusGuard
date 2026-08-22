package com.focusguard.service

import android.graphics.Rect
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

    @Test
    fun `persisted snapshot protects the first event before async refresh`() {
        assertThat(
            BlockingAccessibilityService.isSelfProtectionEngaged(
                cachedActive = false,
                persistedActive = true,
                focusModeActive = false,
                armoredDeviceOwnerActive = false
            )
        ).isTrue()
        assertThat(
            BlockingAccessibilityService.isSelfProtectionEngaged(
                cachedActive = false,
                persistedActive = false,
                focusModeActive = false,
                armoredDeviceOwnerActive = false
            )
        ).isFalse()
    }

    @Test
    fun `row marker must overlap clicked switch without scanning a whole screen`() {
        val clickedSwitch = Rect(900, 420, 1030, 500)
        val sameRowLabel = Rect(70, 430, 360, 480)
        val otherServiceLabel = Rect(70, 560, 360, 610)
        val wholeScreen = Rect(0, 0, 1080, 2200)

        assertThat(
            BlockingAccessibilityService.shouldSearchSameRowMarkers(
                clickedSwitch,
                wholeScreen
            )
        ).isTrue()
        assertThat(
            BlockingAccessibilityService.boundsShareHorizontalRow(
                clickedSwitch,
                sameRowLabel
            )
        ).isTrue()
        assertThat(
            BlockingAccessibilityService.boundsShareHorizontalRow(
                clickedSwitch,
                otherServiceLabel
            )
        ).isFalse()
        assertThat(
            BlockingAccessibilityService.shouldSearchSameRowMarkers(
                clicked = Rect(0, 0, 1080, 1000),
                root = wholeScreen
            )
        ).isFalse()
    }
}
