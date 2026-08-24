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
    fun `delayed readiness callback cannot hide a newer curtain`() {
        assertThat(
            BlockingAccessibilityService.shouldDismissCurtain(
                currentGeneration = 9L,
                readyGeneration = 8L
            )
        ).isFalse()
    }

    @Test
    fun `only the currently drawn safe surface can hide its curtain`() {
        assertThat(
            BlockingAccessibilityService.shouldDismissCurtain(
                currentGeneration = 9L,
                readyGeneration = 9L
            )
        ).isTrue()
    }

    @Test
    fun `missing generation never dismisses a curtain`() {
        assertThat(
            BlockingAccessibilityService.shouldDismissCurtain(
                currentGeneration = 9L,
                readyGeneration = 0L
            )
        ).isFalse()
    }

    @Test
    fun `awaited safe surface failsafe evacuates before it can hide`() {
        assertThat(
            BlockingAccessibilityService.instantCurtainFailsafeDecision(
                curtainVisible = true,
                awaitingSafeSurfaceGeneration = 12L,
                unsafeWindowVisible = false
            )
        ).isEqualTo(
            BlockingAccessibilityService.InstantCurtainFailsafeDecision.EVACUATE_THEN_HIDE
        )
        assertThat(
            BlockingAccessibilityService.instantCurtainFailsafeDecision(
                curtainVisible = true,
                awaitingSafeSurfaceGeneration = 0L,
                unsafeWindowVisible = false
            )
        ).isEqualTo(BlockingAccessibilityService.InstantCurtainFailsafeDecision.HIDE)
        assertThat(
            BlockingAccessibilityService.instantCurtainFailsafeDecision(
                curtainVisible = true,
                awaitingSafeSurfaceGeneration = 0L,
                unsafeWindowVisible = true
            )
        ).isEqualTo(
            BlockingAccessibilityService.InstantCurtainFailsafeDecision.EVACUATE_THEN_HIDE
        )
        assertThat(BlockingAccessibilityService.FAILSAFE_EVACUATION_HOLD_MILLIS)
            .isAtLeast(BlockingAccessibilityService.EVENT_NOTIFICATION_TIMEOUT_MILLIS + 1L)
    }

    @Test
    fun `screen off evacuates an awaited or unsafe surface before release`() {
        assertThat(
            BlockingAccessibilityService.screenOffCurtainDecision(
                curtainVisible = true,
                awaitingSafeSurfaceGeneration = 21L,
                unsafeWindowVisible = false
            )
        ).isEqualTo(
            BlockingAccessibilityService.InstantCurtainFailsafeDecision.EVACUATE_THEN_HIDE
        )
        assertThat(
            BlockingAccessibilityService.screenOffCurtainDecision(
                curtainVisible = true,
                awaitingSafeSurfaceGeneration = 0L,
                unsafeWindowVisible = true
            )
        ).isEqualTo(
            BlockingAccessibilityService.InstantCurtainFailsafeDecision.EVACUATE_THEN_HIDE
        )
        assertThat(
            BlockingAccessibilityService.screenOffCurtainDecision(
                curtainVisible = true,
                awaitingSafeSurfaceGeneration = 0L,
                unsafeWindowVisible = false
            )
        ).isEqualTo(BlockingAccessibilityService.InstantCurtainFailsafeDecision.HIDE)
    }

    @Test
    fun `activity launch failure evacuates only its current curtain generation`() {
        assertThat(
            BlockingAccessibilityService.curtainLaunchFailureDecision(
                currentGeneration = 31L,
                failedGeneration = 31L
            )
        ).isEqualTo(
            BlockingAccessibilityService.CurtainLaunchFailureDecision.EVACUATE_THEN_HIDE
        )
        assertThat(
            BlockingAccessibilityService.curtainLaunchFailureDecision(
                currentGeneration = 32L,
                failedGeneration = 31L
            )
        ).isEqualTo(BlockingAccessibilityService.CurtainLaunchFailureDecision.NO_ACTION)
        assertThat(
            BlockingAccessibilityService.curtainLaunchFailureDecision(
                currentGeneration = 0L,
                failedGeneration = 0L
            )
        ).isEqualTo(BlockingAccessibilityService.CurtainLaunchFailureDecision.NO_ACTION)
    }

    @Test
    fun `follow-up event keeps the curtain awaiting the safe surface`() {
        assertThat(
            BlockingAccessibilityService.shouldReuseAwaitedCurtain(
                holdUntilSafeSurface = false,
                awaitingGeneration = 17L,
                curtainVisible = true
            )
        ).isTrue()
        assertThat(
            BlockingAccessibilityService.shouldReuseAwaitedCurtain(
                holdUntilSafeSurface = true,
                awaitingGeneration = 17L,
                curtainVisible = true
            )
        ).isFalse()
        assertThat(
            BlockingAccessibilityService.shouldEvictForProtectionAttempt(
                alreadyAwaitingSafeSurface = true
            )
        ).isFalse()
        assertThat(
            BlockingAccessibilityService.shouldEvictForProtectionAttempt(
                alreadyAwaitingSafeSurface = false
            )
        ).isTrue()
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
        assertThat(
            BlockingAccessibilityService.shouldLaunchBlockedAppEvictionFallback(
                globalHomeAccepted = true,
                forceLauncherFallback = true
            )
        ).isTrue()
        assertThat(
            BlockingAccessibilityService.shouldLaunchBlockedAppEvictionFallback(
                globalHomeAccepted = true,
                forceLauncherFallback = false
            )
        ).isFalse()
    }

    @Test
    fun `block notice intent carries the curtain generation handshake`() {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val intent = BlockingAccessibilityService.createBlockNoticeIntent(
            context = context,
            strictBlock = false,
            blockedPackage = "com.example.blocked",
            blockedDomain = null,
            redirectBrowserPackage = null,
            curtainGeneration = 42L
        )

        assertThat(
            intent.getLongExtra(BlockingAccessibilityService.EXTRA_CURTAIN_GENERATION, 0L)
        ).isEqualTo(42L)
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
