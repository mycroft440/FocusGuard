package com.focusguard.service

import android.graphics.Rect
import com.focusguard.security.SelfProtectionStateStore
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BlockingAccessibilityServiceIntentTest {

    @Before
    fun clearSynchronousSnapshot() {
        SelfProtectionStateStore.setArmed(
            RuntimeEnvironment.getApplication().applicationContext,
            false
        )
    }

    @Test
    fun `development relinquish broadcast is package scoped`() {
        val context = RuntimeEnvironment.getApplication()

        val intent = BlockingAccessibilityService.createDevelopmentRelinquishIntent(context)

        assertThat(intent.action)
            .isEqualTo(BlockingAccessibilityService.ACTION_DEV_RELINQUISH_ACCESSIBILITY)
        assertThat(intent.`package`).isEqualTo(context.packageName)
    }

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
    fun `blocked app notice evicts app while website notice does not`() {
        assertThat(
            BlockingAccessibilityService.shouldEvictBlockedAppBeforeNotice(
                "com.example.blocked"
            )
        ).isTrue()
        assertThat(
            BlockingAccessibilityService.shouldEvictBlockedAppBeforeNotice(null)
        ).isFalse()
        assertThat(
            BlockingAccessibilityService.shouldEvictBlockedAppBeforeNotice("")
        ).isFalse()
    }

    @Test
    fun `blocked app eviction fallback opens launcher in a new task`() {
        val intent = BlockingAccessibilityService.createBlockedAppEvictionIntent()

        assertThat(intent.action).isEqualTo(android.content.Intent.ACTION_MAIN)
        assertThat(intent.categories).contains(android.content.Intent.CATEGORY_HOME)
        assertThat(intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
        assertThat(intent.flags and android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP).isNotEqualTo(0)
    }

    @Test
    fun `refresh intent persists targets before accessibility service receives broadcast`() {
        val context = RuntimeEnvironment.getApplication().applicationContext

        BlockingAccessibilityService.createRefreshBlockingIntent(
            context = context,
            blockedApps = listOf("com.example.blocked"),
            blockedSites = listOf("Example.COM/path"),
            blockingActive = true,
            strictPomodoro = true
        )

        val snapshot = SelfProtectionStateStore.read(context)
        assertThat(snapshot.armed).isTrue()
        assertThat(snapshot.blockedApps).containsExactly("com.example.blocked")
        assertThat(snapshot.blockedSites).containsExactly("example.com")
        assertThat(snapshot.strictPomodoro).isTrue()
    }

    @Test
    fun `installed accessibility apps label is confirmed by Accessibility root`() {
        var rootReads = 0
        val confirmed = BlockingAccessibilityService.confirmAccessibilityContextForInstalledEntry(
            directAccessibility = false,
            installedAccessibilityApps = true,
            rootMentionsAccessibility = {
                rootReads += 1
                true
            }
        )

        assertThat(confirmed).isTrue()
        assertThat(rootReads).isEqualTo(1)
    }

    @Test
    fun `generic installed apps outside Accessibility stays unprotected`() {
        val confirmed = BlockingAccessibilityService.confirmAccessibilityContextForInstalledEntry(
            directAccessibility = false,
            installedAccessibilityApps = true,
            rootMentionsAccessibility = { false }
        )

        assertThat(confirmed).isFalse()
    }

    @Test
    fun `unrelated click never scans Accessibility root`() {
        val confirmed = BlockingAccessibilityService.confirmAccessibilityContextForInstalledEntry(
            directAccessibility = false,
            installedAccessibilityApps = false,
            rootMentionsAccessibility = { error("root must remain lazy") }
        )

        assertThat(confirmed).isFalse()
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
