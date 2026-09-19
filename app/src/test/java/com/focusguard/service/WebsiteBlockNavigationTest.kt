package com.focusguard.service

import com.focusguard.accessibility.website.redirection.WebsiteBlockTransitionGuard
import com.focusguard.accessibility.website.redirection.WebsiteRedirectionCoordinator
import com.focusguard.accessibility.website.redirection.WebsiteTabNeutralizationPolicy

import android.content.Context
import android.content.Intent
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
    fun `website curtain remains briefly visible while redirect starts immediately`() {
        assertThat(BlockingAccessibilityService.WEBSITE_MIN_BLOCK_NOTICE_MILLIS)
            .isEqualTo(250L)
    }

    @Test
    fun `website transition guard rejects overlap only for the same browser`() {
        val guard = WebsiteBlockTransitionGuard()

        assertThat(
            guard.tryStart(
                CHROME_PACKAGE,
                transitionId = 1L,
                destination = WebsiteRedirectionCoordinator.TerminalDestination.REDIRECT
            )
        ).isNotNull()
        assertThat(
            guard.tryStart(
                CHROME_PACKAGE,
                transitionId = 2L,
                destination = WebsiteRedirectionCoordinator.TerminalDestination.REDIRECT
            )
        ).isNull()
        assertThat(
            guard.tryStart(
                FIREFOX_PACKAGE,
                transitionId = 3L,
                destination = WebsiteRedirectionCoordinator.TerminalDestination.POMODORO
            )
        ).isNotNull()
        assertThat(guard.finish(CHROME_PACKAGE, transitionId = 2L)).isFalse()
        assertThat(guard.isActive(CHROME_PACKAGE)).isTrue()
        assertThat(guard.finish(CHROME_PACKAGE, transitionId = 1L)).isTrue()
        assertThat(
            guard.tryStart(
                CHROME_PACKAGE,
                transitionId = 4L,
                destination = WebsiteRedirectionCoordinator.TerminalDestination.REDIRECT
            )
        ).isNotNull()
    }

    @Test
    fun `safe browser event confirms only after same window sanitization request`() {
        val guard = WebsiteBlockTransitionGuard()
        val transition = guard.tryStart(
            CHROME_PACKAGE,
            transitionId = 11L,
            destination = WebsiteRedirectionCoordinator.TerminalDestination.REDIRECT,
            expectedWindowId = 7,
            detectionEventUptimeMillis = 50L
        )!!

        assertThat(guard.confirmRedirect(CHROME_PACKAGE, windowId = 7, eventUptimeMillis = 99L))
            .isFalse()
        assertThat(transition.safeRedirectConfirmed.isCompleted).isFalse()
        assertThat(
            guard.markSanitizationRequested(
                CHROME_PACKAGE,
                transitionId = 11L,
                requestedAtUptimeMillis = 100L
            )
        ).isTrue()
        assertThat(guard.confirmRedirect(CHROME_PACKAGE, windowId = 7, eventUptimeMillis = 99L))
            .isFalse()
        assertThat(transition.safeRedirectConfirmed.isCompleted).isFalse()
        guard.observeBrowserEvent(
            browserPackageName = CHROME_PACKAGE,
            windowId = 7,
            eventUptimeMillis = 100L,
            eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        )
        assertThat(guard.confirmRedirect(CHROME_PACKAGE, windowId = 7, eventUptimeMillis = 100L))
            .isTrue()
        assertThat(transition.safeRedirectConfirmed.isCompleted).isTrue()
    }

    @Test
    fun `strict guard rejects old Pomodoro ack and requires Google first`() {
        val guard = WebsiteBlockTransitionGuard()
        val transition = guard.tryStart(
            CHROME_PACKAGE,
            transitionId = 12L,
            destination = WebsiteRedirectionCoordinator.TerminalDestination.POMODORO,
            expectedWindowId = 7,
            detectionEventUptimeMillis = 50L
        )!!
        guard.markCurtainGeneration(CHROME_PACKAGE, transitionId = 12L, curtainGeneration = 44L)
        assertThat(
            guard.markDestinationRequested(CHROME_PACKAGE, 12L, requestedAtUptimeMillis = 100L)
        ).isFalse()
        guard.markSanitizationRequested(CHROME_PACKAGE, 12L, requestedAtUptimeMillis = 80L)
        guard.observeBrowserEvent(
            browserPackageName = CHROME_PACKAGE,
            windowId = 7,
            eventUptimeMillis = 80L,
            eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        )
        assertThat(guard.confirmRedirect(CHROME_PACKAGE, windowId = 7, eventUptimeMillis = 80L))
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
            BlockingAccessibilityService.isSafeRedirectSurface(
                "https://www.google.com/"
            )
        ).isTrue()
        assertThat(
            BlockingAccessibilityService.isSafeRedirectSurface(
                "https://google.com/"
            )
        ).isTrue()
        assertThat(
            BlockingAccessibilityService.isSafeRedirectSurface(
                "https://www.google.com.br/?hl=pt-BR&gl=br"
            )
        ).isTrue()
        assertThat(
            BlockingAccessibilityService.isSafeRedirectSurface(
                "https://www.google.co.za/"
            )
        ).isTrue()
        assertThat(
            BlockingAccessibilityService.isSafeRedirectSurface(
                "https://www.google.com/search?q=blocked"
            )
        ).isFalse()
        assertThat(
            BlockingAccessibilityService.isSafeRedirectSurface(
                "https://google.com.evil.example/"
            )
        ).isFalse()
        assertThat(
            BlockingAccessibilityService.isSafeRedirectSurface(
                "https://google.evil/"
            )
        ).isFalse()
    }

    @Test
    fun `stale Google history and another browser window cannot confirm redirect`() {
        val guard = WebsiteBlockTransitionGuard()
        val transition = guard.tryStart(
            BRAVE_PACKAGE,
            transitionId = 21L,
            destination = WebsiteRedirectionCoordinator.TerminalDestination.REDIRECT,
            expectedWindowId = 7
        )!!
        guard.markSanitizationRequested(
            BRAVE_PACKAGE,
            transitionId = 21L,
            requestedAtUptimeMillis = 500L
        )

        assertThat(
            guard.transitionForConfirmation(
                BRAVE_PACKAGE,
                windowId = 7,
                eventUptimeMillis = 499L,
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            )
        ).isNull()
        assertThat(
            guard.transitionForConfirmation(
                BRAVE_PACKAGE,
                windowId = 8,
                eventUptimeMillis = 500L,
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            )
        ).isNull()
        assertThat(
            guard.transitionForConfirmation(
                BRAVE_PACKAGE,
                windowId = 7,
                eventUptimeMillis = 501L,
                eventType = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            )
        ).isNull()
        assertThat(transition.safeRedirectConfirmed.isCompleted).isFalse()
        guard.observeBrowserEvent(
            browserPackageName = BRAVE_PACKAGE,
            windowId = 7,
            eventUptimeMillis = 502L,
            eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        )
        assertThat(
            guard.transitionForConfirmation(
                BRAVE_PACKAGE,
                windowId = 7,
                eventUptimeMillis = 502L,
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            )
        ).isSameInstanceAs(transition)
    }

    @Test
    fun `text and focus events cannot release the curtain after submit`() {
        val guard = WebsiteBlockTransitionGuard()
        val transition = guard.tryStart(
            FIREFOX_PACKAGE,
            transitionId = 22L,
            destination = WebsiteRedirectionCoordinator.TerminalDestination.REDIRECT,
            expectedWindowId = 9,
            detectionEventUptimeMillis = 50L
        )!!
        guard.markSanitizationRequested(
            FIREFOX_PACKAGE,
            transitionId = 22L,
            requestedAtUptimeMillis = 100L
        )

        listOf(
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED
        ).forEachIndexed { index, eventType ->
            val eventTime = 101L + index
            guard.observeBrowserEvent(FIREFOX_PACKAGE, 9, eventTime, eventType)
            assertThat(
                guard.transitionForConfirmation(
                    FIREFOX_PACKAGE,
                    windowId = 9,
                    eventUptimeMillis = eventTime,
                    eventType = eventType
                )
            ).isNull()
            assertThat(guard.confirmRedirect(FIREFOX_PACKAGE, 9, eventTime)).isFalse()
        }
        assertThat(transition.safeRedirectConfirmed.isCompleted).isFalse()
    }

    @Test
    fun `finished website transition re-arms the same browser for Back navigation`() {
        val guard = WebsiteBlockTransitionGuard()
        assertThat(
  guard.tryStart(
      CHROME_PACKAGE,
      transitionId = 26L,
      destination = WebsiteRedirectionCoordinator.TerminalDestination.REDIRECT,
      expectedWindowId = 7,
      detectionEventUptimeMillis = 100L
  )
        ).isNotNull()
        assertThat(guard.finish(CHROME_PACKAGE, transitionId = 26L)).isTrue()
        assertThat(
  guard.tryStart(
      CHROME_PACKAGE,
      transitionId = 27L,
      destination = WebsiteRedirectionCoordinator.TerminalDestination.REDIRECT,
      expectedWindowId = 7,
      detectionEventUptimeMillis = 200L
  )
        ).isNotNull()
    }

    @Test
    fun `visible browser chrome without URL still enters bounded identity recovery`() {
        assertThat(
            BlockingAccessibilityService.shouldStartWebsiteIdentityRecovery(
                websiteIdentified = false,
                addressBarObservable = true,
                focusedAddressEditor = false
            )
        ).isTrue()
        assertThat(
            BlockingAccessibilityService.shouldStartWebsiteIdentityRecovery(
                websiteIdentified = false,
                addressBarObservable = false,
                focusedAddressEditor = false
            )
        ).isTrue()
        assertThat(
            BlockingAccessibilityService.shouldStartWebsiteIdentityRecovery(
                websiteIdentified = false,
                addressBarObservable = true,
                focusedAddressEditor = true
            )
        ).isFalse()
        assertThat(
            BlockingAccessibilityService.shouldStartWebsiteIdentityRecovery(
                websiteIdentified = true,
                addressBarObservable = true,
                focusedAddressEditor = false
            )
        ).isFalse()
    }

    @Test
    fun `same tab policy is package and window bound`() {
        val browserPackage = "org.example.chromium.fork"
        val policy = WebsiteTabNeutralizationPolicy(
            browserPackageName = browserPackage,
            expectedWindowId = 7
        )

        assertThat(policy.mayTouchBlockedTab(browserPackage, 7)).isTrue()
        assertThat(
            policy.mayActivateBlockedAddressBar(
                browserPackage,
                activeWindowId = 7,
                phaseStartedAtUptimeMillis = 100L,
                latestWindowTransitionEventUptimeMillis = 101L
            )
        ).isTrue()
        assertThat(policy.mayTouchBlockedTab(browserPackage, 8)).isFalse()
        assertThat(policy.mayTouchBlockedTab(CHROME_PACKAGE, 7)).isFalse()

        policy.markSafeAddressSet(200L)
        assertThat(policy.mayTouchBlockedTab(browserPackage, 7)).isFalse()
        assertThat(
            policy.maySubmitSafeAddress(
                browserPackage,
                activeWindowId = 7,
                latestWindowTransitionEventUptimeMillis = 200L
            )
        ).isTrue()
        policy.markRedirectRequested()
        assertThat(
            policy.maySubmitSafeAddress(
                browserPackage,
                activeWindowId = 7,
                latestWindowTransitionEventUptimeMillis = 201L
            )
        ).isFalse()
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
    fun `redirect remains fail closed until destination confirmation`() {
        assertThat(
            BlockingAccessibilityService.mayOpenDestinationAfterSanitization(
                safeRedirectConfirmed = false
            )
        ).isFalse()
        assertThat(
            BlockingAccessibilityService.mayOpenDestinationAfterSanitization(
                safeRedirectConfirmed = true
            )
        ).isTrue()
    }

    @Test
    fun `set or submit failure aborts redirect without closing or leaving browser`() {
        assertThat(BlockingAccessibilityService.afterSafeAddressSet(false)).isEqualTo(
            BlockingAccessibilityService.WebsiteSanitizationDecision.ABORT_REDIRECT
        )
        assertThat(BlockingAccessibilityService.afterSafeAddressSubmit(false)).isEqualTo(
            BlockingAccessibilityService.WebsiteSanitizationDecision.ABORT_REDIRECT
        )
        assertThat(BlockingAccessibilityService.canUseCertifiableImeSubmit(29)).isFalse()
        assertThat(BlockingAccessibilityService.canUseCertifiableImeSubmit(30)).isTrue()
    }

    @Test
    fun `website transition actions contain no browser eviction action`() {
        assertThat(
            WebsiteRedirectionCoordinator.Action.values().map { it.name }
        ).doesNotContain("EVACUATE_HOME")
    }

    @Test
    fun `strict Pomodoro does not block browser window transitions`() {
        assertThat(
            BlockingAccessibilityService.shouldApplyStrictPomodoroToWindow(
                strictActive = true,
                isWindowTransition = true,
                isBrowserWindow = true
            )
        ).isFalse()
        assertThat(
            BlockingAccessibilityService.shouldApplyStrictPomodoroToWindow(
                strictActive = true,
                isWindowTransition = true,
                isBrowserWindow = false
            )
        ).isTrue()
    }



    @Test
    fun `normal transition hides only after redirect was confirmed`() {
        val machine = WebsiteRedirectionCoordinator.Session(strict = false)

        machine.begin()
        assertThat(machine.afterRedirectConfirmed()).isEqualTo(
            WebsiteRedirectionCoordinator.Action.HIDE_BLOCK_PRESENTATION
        )
    }

    @Test
    fun `strict transition opens Pomodoro only after redirect confirmation`() {
        val machine = WebsiteRedirectionCoordinator.Session(strict = true)

        machine.begin()
        assertThat(machine.afterRedirectConfirmed()).isEqualTo(
            WebsiteRedirectionCoordinator.Action.OPEN_POMODORO
        )
        assertThat(machine.onPomodoroConfirmed()).isEqualTo(
            WebsiteRedirectionCoordinator.Action.HIDE_BLOCK_PRESENTATION
        )
    }

    private companion object {
        const val CHROME_PACKAGE = "com.android.chrome"
        const val BRAVE_PACKAGE = "com.brave.browser"
        const val FIREFOX_PACKAGE = "org.mozilla.firefox"
    }
}
