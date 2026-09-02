package com.focusguard.service

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import com.focusguard.data.PredefinedWebsites
import com.focusguard.ui.BlockNoticeActivity
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebsiteBlockNavigationTest {

    private val context: Context = RuntimeEnvironment.getApplication().applicationContext

    @Test
    fun `accessibility window events are requested without delivery debounce`() {
        val eventTypes = BlockingAccessibilityService.requestedAccessibilityEventTypes()

        assertThat(
            eventTypes and AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ).isNotEqualTo(0)
        assertThat(
            eventTypes and AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ).isNotEqualTo(0)
        assertThat(BlockingAccessibilityService.EVENT_NOTIFICATION_TIMEOUT_MILLIS)
            .isEqualTo(0L)
    }

    @Test
    fun `website blocking listens to address bar events with no delivery debounce`() {
        val immediateTypes = BlockingAccessibilityService.immediateBrowserBlockEventTypesForTest()
        val requested = BlockingAccessibilityService.requestedAccessibilityEventTypes()

        assertThat(immediateTypes).containsAtLeast(
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED
        )
        immediateTypes.forEach { type ->
            assertThat(requested and type).isNotEqualTo(0)
        }
        assertThat(BlockingAccessibilityService.EVENT_NOTIFICATION_TIMEOUT_MILLIS).isEqualTo(0L)
    }

    @Test
    fun `blocked domain is classified immediately from address bar`() {
        assertThat(
            BlockingAccessibilityService.immediateWebsiteBlockTarget(
                addressText = "https://m.facebook.com/profile",
                url = "https://m.facebook.com/profile",
                blockedRules = listOf("facebook.com")
            )
        ).isEqualTo("m.facebook.com")
    }

    @Test
    fun `pornography search is classified before navigation finishes`() {
        assertThat(
            BlockingAccessibilityService.immediateWebsiteBlockTarget(
                addressText = "free porn videos",
                url = null,
                blockedRules = listOf(PredefinedWebsites.PORNOGRAPHY_RULE)
            )
        ).isEqualTo(PredefinedWebsites.PORNOGRAPHY_RULE)
    }

    @Test
    fun `safe address is not blocked by the immediate classifier`() {
        assertThat(
            BlockingAccessibilityService.immediateWebsiteBlockTarget(
                addressText = "https://example.com/news",
                url = "https://example.com/news",
                blockedRules = listOf("facebook.com", PredefinedWebsites.PORNOGRAPHY_RULE)
            )
        ).isNull()
    }

    @Test
    fun `settings interception listens to the earliest window signals`() {
        // The race the user can win is measured in frames: every event type the
        // guard ignores is time in which the switch that disables this service is
        // already on screen. TYPE_WINDOWS_CHANGED and TYPE_VIEW_FOCUSED arrive
        // before TYPE_WINDOW_STATE_CHANGED and cost nothing extra to observe.
        val interceptionTypes = BlockingAccessibilityService.settingsInterceptionEventTypesForTest()

        assertThat(interceptionTypes).containsAtLeast(
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED
        )
    }

    @Test
    fun `every interception event type is actually subscribed`() {
        // Listening for an event the service never receives would be a silent hole.
        val requested = BlockingAccessibilityService.requestedAccessibilityEventTypes()

        BlockingAccessibilityService.settingsInterceptionEventTypesForTest().forEach { type ->
            assertThat(requested and type).isNotEqualTo(0)
        }
    }

    @Test
    fun `transition guard covers the protection handoff without restarting settings`() {
        // The new flow never cold-starts Settings. It only needs to cover BACK,
        // the delayed HOME action and the stable notice while follow-up window
        // events from the intercepted attempt are still being delivered.
        assertThat(BlockingAccessibilityService.settingsTransitionGuardMillisForTest())
            .isAtLeast(2_000L)
    }

    @Test
    fun `blocking refresh carries an immediate in-memory snapshot`() {
        val intent = BlockingAccessibilityService.createRefreshBlockingIntent(
            context = context,
            blockedApps = listOf("com.example.blocked"),
            blockedSites = listOf("https://www.YouTube.com/watch?v=1"),
            blockingActive = true,
            strictPomodoro = false
        )

        assertThat(intent.`package`).isEqualTo(context.packageName)
        assertThat(
            intent.getStringArrayListExtra(
                BlockingAccessibilityService.EXTRA_BLOCKED_APPS_SNAPSHOT
            )
        ).containsExactly("com.example.blocked")
        assertThat(
            intent.getStringArrayListExtra(
                BlockingAccessibilityService.EXTRA_BLOCKED_SITES_SNAPSHOT
            )
        ).containsExactly("youtube.com")
        assertThat(
            intent.getBooleanExtra(
                BlockingAccessibilityService.EXTRA_BLOCKING_ACTIVE_SNAPSHOT,
                false
            )
        ).isTrue()
    }

    @Test
    fun `website handoff waits for a positively confirmed destination`() {
        assertThat(BlockingAccessibilityService.WEBSITE_DESTINATION_CONFIRM_TIMEOUT_MILLIS)
            .isGreaterThan(BlockingAccessibilityService.EVENT_NOTIFICATION_TIMEOUT_MILLIS)
    }

    @Test
    fun `website transition guard rejects overlap only for the same browser`() {
        val guard = BlockingAccessibilityService.WebsiteBlockTransitionGuard()

        assertThat(
            guard.tryStart(
                CHROME_PACKAGE,
                transitionId = 1L,
                destination = BlockingAccessibilityService.WebsiteTransitionDestination.GOOGLE
            )
        ).isNotNull()
        assertThat(
            guard.tryStart(
                CHROME_PACKAGE,
                transitionId = 2L,
                destination = BlockingAccessibilityService.WebsiteTransitionDestination.GOOGLE
            )
        ).isNull()
        assertThat(
            guard.tryStart(
                FIREFOX_PACKAGE,
                transitionId = 3L,
                destination = BlockingAccessibilityService.WebsiteTransitionDestination.POMODORO
            )
        ).isNotNull()
        assertThat(guard.finish(CHROME_PACKAGE, transitionId = 2L)).isFalse()
        assertThat(guard.isActive(CHROME_PACKAGE)).isTrue()
        assertThat(guard.finish(CHROME_PACKAGE, transitionId = 1L)).isTrue()
        assertThat(
            guard.tryStart(
                CHROME_PACKAGE,
                transitionId = 4L,
                destination = BlockingAccessibilityService.WebsiteTransitionDestination.GOOGLE
            )
        ).isNotNull()
    }

    @Test
    fun `safe browser event confirms only after same window sanitization request`() {
        val guard = BlockingAccessibilityService.WebsiteBlockTransitionGuard()
        val transition = guard.tryStart(
            CHROME_PACKAGE,
            transitionId = 11L,
            destination = BlockingAccessibilityService.WebsiteTransitionDestination.GOOGLE,
            expectedWindowId = 7,
            detectionEventUptimeMillis = 50L
        )!!

        assertThat(guard.confirmGoogle(CHROME_PACKAGE, windowId = 7, eventUptimeMillis = 99L))
            .isFalse()
        assertThat(transition.safeGoogleConfirmed.isCompleted).isFalse()
        assertThat(
            guard.markSanitizationRequested(
                CHROME_PACKAGE,
                transitionId = 11L,
                requestedAtUptimeMillis = 100L
            )
        ).isTrue()
        assertThat(guard.confirmGoogle(CHROME_PACKAGE, windowId = 7, eventUptimeMillis = 99L))
            .isFalse()
        assertThat(transition.safeGoogleConfirmed.isCompleted).isFalse()
        assertThat(guard.confirmGoogle(CHROME_PACKAGE, windowId = 7, eventUptimeMillis = 100L))
            .isTrue()
        assertThat(transition.safeGoogleConfirmed.isCompleted).isTrue()
    }

    @Test
    fun `strict guard rejects old Pomodoro ack and requires Google first`() {
        val guard = BlockingAccessibilityService.WebsiteBlockTransitionGuard()
        val transition = guard.tryStart(
            CHROME_PACKAGE,
            transitionId = 12L,
            destination = BlockingAccessibilityService.WebsiteTransitionDestination.POMODORO,
            expectedWindowId = 7,
            detectionEventUptimeMillis = 50L
        )!!
        guard.markCurtainGeneration(CHROME_PACKAGE, transitionId = 12L, curtainGeneration = 44L)
        assertThat(
            guard.markDestinationRequested(CHROME_PACKAGE, 12L, requestedAtUptimeMillis = 100L)
        ).isFalse()
        guard.markSanitizationRequested(CHROME_PACKAGE, 12L, requestedAtUptimeMillis = 80L)
        assertThat(guard.confirmGoogle(CHROME_PACKAGE, windowId = 7, eventUptimeMillis = 80L))
            .isTrue()
        guard.markDestinationRequested(
            CHROME_PACKAGE,
            transitionId = 12L,
            requestedAtUptimeMillis = 100L
        )

        assertThat(transition.destinationConfirmed.isCompleted).isFalse()
        assertThat(guard.confirmPomodoro(curtainGeneration = 43L, readyAtUptimeMillis = 101L))
            .isFalse()
        assertThat(guard.confirmPomodoro(curtainGeneration = 44L, readyAtUptimeMillis = 99L))
            .isFalse()
        assertThat(guard.confirmPomodoro(curtainGeneration = 44L, readyAtUptimeMillis = 101L))
            .isTrue()
        assertThat(transition.destinationConfirmed.isCompleted).isTrue()
    }

    @Test
    fun `google confirmation accepts homepage but rejects search and lookalike`() {
        assertThat(
            BlockingAccessibilityService.isSafeGoogleRedirectSurface(
                "https://www.google.com/"
            )
        ).isTrue()
        assertThat(
            BlockingAccessibilityService.isSafeGoogleRedirectSurface(
                "https://www.google.com.br/?hl=pt-BR&gl=br"
            )
        ).isTrue()
        assertThat(
            BlockingAccessibilityService.isSafeGoogleRedirectSurface(
                "https://www.google.com/search?q=blocked"
            )
        ).isFalse()
        assertThat(
            BlockingAccessibilityService.isSafeGoogleRedirectSurface(
                "https://google.com.evil.example/"
            )
        ).isFalse()
        assertThat(
            BlockingAccessibilityService.isSafeGoogleRedirectSurface(
                "https://google.evil/"
            )
        ).isFalse()
    }

    @Test
    fun `stale Google history and another browser window cannot confirm redirect`() {
        val guard = BlockingAccessibilityService.WebsiteBlockTransitionGuard()
        val transition = guard.tryStart(
            BRAVE_PACKAGE,
            transitionId = 21L,
            destination = BlockingAccessibilityService.WebsiteTransitionDestination.GOOGLE,
            expectedWindowId = 7
        )!!
        guard.markSanitizationRequested(
            BRAVE_PACKAGE,
            transitionId = 21L,
            requestedAtUptimeMillis = 500L
        )

        assertThat(
            guard.transitionForConfirmation(BRAVE_PACKAGE, windowId = 7, eventUptimeMillis = 499L)
        ).isNull()
        assertThat(
            guard.transitionForConfirmation(BRAVE_PACKAGE, windowId = 8, eventUptimeMillis = 500L)
        ).isNull()
        assertThat(transition.safeGoogleConfirmed.isCompleted).isFalse()
        assertThat(
            guard.transitionForConfirmation(BRAVE_PACKAGE, windowId = 7, eventUptimeMillis = 501L)
        ).isSameInstanceAs(transition)
    }

    @Test
    fun `Brave tab policy rejects delayed window and stops touching tab after redirect`() {
        val policy = BlockingAccessibilityService.WebsiteTabNeutralizationPolicy(
            browserPackageName = BRAVE_PACKAGE,
            expectedWindowId = 7
        )

        assertThat(policy.mayTouchBlockedTab(BRAVE_PACKAGE, 7)).isTrue()
        assertThat(policy.mayAttemptBraveClose(BRAVE_PACKAGE, 7)).isTrue()
        assertThat(policy.mayTouchBlockedTab(BRAVE_PACKAGE, 8)).isFalse()
        assertThat(policy.mayTouchBlockedTab(CHROME_PACKAGE, 7)).isFalse()
        policy.markRedirectRequested()
        assertThat(policy.mayTouchBlockedTab(BRAVE_PACKAGE, 7)).isFalse()

        listOf("com.brave.browser_beta", "com.brave.browser_nightly").forEach { packageName ->
            val variant = BlockingAccessibilityService.WebsiteTabNeutralizationPolicy(
                browserPackageName = packageName,
                expectedWindowId = 9
            )
            assertThat(variant.mayAttemptBraveClose(packageName, 9)).isTrue()
        }
    }

    @Test
    fun `obsolete detection in same window cannot touch a changed tab`() {
        val rules = setOf("facebook.com", "instagram.com")

        assertThat(
            BlockingAccessibilityService.detectedBrowserTargetStillCurrent(
                blockedCandidate = "https://m.facebook.com/profile",
                currentAddress = "m.facebook.com/profile",
                blockedRules = rules,
                detectionEventUptimeMillis = 100L,
                latestObservedEventUptimeMillis = 101L
            )
        ).isTrue()
        assertThat(
            BlockingAccessibilityService.detectedBrowserTargetStillCurrent(
                blockedCandidate = "https://m.facebook.com/profile",
                currentAddress = "https://m.facebook.com/other-tab",
                blockedRules = rules,
                detectionEventUptimeMillis = 100L,
                latestObservedEventUptimeMillis = 101L
            )
        ).isFalse()
        assertThat(
            BlockingAccessibilityService.detectedBrowserTargetStillCurrent(
                blockedCandidate = "https://m.facebook.com/profile",
                currentAddress = "https://www.instagram.com/reels",
                blockedRules = rules,
                detectionEventUptimeMillis = 100L,
                latestObservedEventUptimeMillis = 101L
            )
        ).isFalse()
        assertThat(
            BlockingAccessibilityService.detectedBrowserTargetStillCurrent(
                blockedCandidate = "https://m.facebook.com/profile",
                currentAddress = "https://m.facebook.com/profile",
                blockedRules = rules,
                detectionEventUptimeMillis = 100L,
                latestObservedEventUptimeMillis = 99L
            )
        ).isFalse()
        assertThat(
            BlockingAccessibilityService.detectedBrowserTargetStillCurrent(
                blockedCandidate = "https://m.facebook.com/profile",
                currentAddress = "https://example.com/",
                blockedRules = rules,
                detectionEventUptimeMillis = 100L,
                latestObservedEventUptimeMillis = 101L
            )
        ).isFalse()
    }

    @Test
    fun `missing or superseded curtain aborts destructive tab actions`() {
        assertThat(
            BlockingAccessibilityService.curtainReadyForTabAction(
                attached = false,
                visible = true,
                currentGeneration = 7L,
                expectedGeneration = 7L
            )
        ).isFalse()
        assertThat(
            BlockingAccessibilityService.curtainReadyForTabAction(
                attached = true,
                visible = false,
                currentGeneration = 7L,
                expectedGeneration = 7L
            )
        ).isFalse()
        assertThat(
            BlockingAccessibilityService.curtainReadyForTabAction(
                attached = true,
                visible = true,
                currentGeneration = 8L,
                expectedGeneration = 7L
            )
        ).isFalse()
    }

    @Test
    fun `close click alone never authorizes destination`() {
        assertThat(
            BlockingAccessibilityService.mayOpenDestinationAfterSanitization(
                safeGoogleConfirmed = false
            )
        ).isFalse()
        assertThat(
            BlockingAccessibilityService.mayOpenDestinationAfterSanitization(
                safeGoogleConfirmed = true
            )
        ).isTrue()
    }

    @Test
    fun `set or submit failure evacuates Home instead of ACTION_VIEW`() {
        assertThat(BlockingAccessibilityService.afterSafeAddressSet(false)).isEqualTo(
            BlockingAccessibilityService.WebsiteSanitizationDecision.EVACUATE_HOME
        )
        assertThat(BlockingAccessibilityService.afterSafeAddressSubmit(false)).isEqualTo(
            BlockingAccessibilityService.WebsiteSanitizationDecision.EVACUATE_HOME
        )
    }

    @Test
    fun `normal transition hides only after Google was sanitized`() {
        val machine = BlockingAccessibilityService.WebsiteBlockTransitionStateMachine(
            strict = false
        )

        assertThat(machine.begin()).containsExactly(
            BlockingAccessibilityService.WebsiteTransitionAction.SHOW_CURTAIN,
            BlockingAccessibilityService.WebsiteTransitionAction.NEUTRALIZE_BLOCKED_TAB
        ).inOrder()
        assertThat(machine.afterGoogleSanitized()).isEqualTo(
            BlockingAccessibilityService.WebsiteTransitionAction.HIDE_CURTAIN
        )
    }

    @Test
    fun `confirmation timeout evacuates Home without hiding directly`() {
        val machine = BlockingAccessibilityService.WebsiteBlockTransitionStateMachine(
            strict = false
        )
        machine.begin()

        assertThat(machine.onFailureOrTimeout()).isEqualTo(
            BlockingAccessibilityService.WebsiteTransitionAction.EVACUATE_HOME
        )
    }

    @Test
    fun `sanitization or destination failure evacuates Home`() {
        val sanitizationFailure = BlockingAccessibilityService.WebsiteBlockTransitionStateMachine(
            strict = false
        )
        sanitizationFailure.begin()
        assertThat(sanitizationFailure.onFailureOrTimeout()).isEqualTo(
            BlockingAccessibilityService.WebsiteTransitionAction.EVACUATE_HOME
        )

        val launchFailure = BlockingAccessibilityService.WebsiteBlockTransitionStateMachine(
            strict = false
        )
        launchFailure.begin()
        assertThat(launchFailure.onFailureOrTimeout()).isEqualTo(
            BlockingAccessibilityService.WebsiteTransitionAction.EVACUATE_HOME
        )
    }

    @Test
    fun `strict transition opens Pomodoro only after Google sanitization`() {
        val machine = BlockingAccessibilityService.WebsiteBlockTransitionStateMachine(
            strict = true
        )

        assertThat(machine.begin().last()).isEqualTo(
            BlockingAccessibilityService.WebsiteTransitionAction.NEUTRALIZE_BLOCKED_TAB
        )
        assertThat(machine.afterGoogleSanitized()).isEqualTo(
            BlockingAccessibilityService.WebsiteTransitionAction.OPEN_POMODORO
        )
        assertThat(machine.onPomodoroConfirmed()).isEqualTo(
            BlockingAccessibilityService.WebsiteTransitionAction.HIDE_CURTAIN
        )
    }

    @Test
    fun `blocked app notice does not request a browser redirect`() {
        val intent = BlockingAccessibilityService.createBlockNoticeIntent(
            context = context,
            strictBlock = false,
            blockedPackage = "com.example.blocked",
            blockedDomain = null,
            redirectBrowserPackage = null
        )

        assertThat(intent.component?.className).isEqualTo(BlockNoticeActivity::class.java.name)
        assertThat(
            intent.hasExtra(BlockingAccessibilityService.EXTRA_REDIRECT_BROWSER_PACKAGE)
        ).isFalse()
    }

    private companion object {
        const val CHROME_PACKAGE = "com.android.chrome"
        const val BRAVE_PACKAGE = "com.brave.browser"
        const val FIREFOX_PACKAGE = "org.mozilla.firefox"
    }
}
