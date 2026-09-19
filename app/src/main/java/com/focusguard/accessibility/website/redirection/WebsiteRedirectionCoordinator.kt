package com.focusguard.accessibility.website.redirection

import com.focusguard.utils.BrowserUiCapabilityPolicy

/**
 * Coordinator for one website redirection transaction.
 *
 * Android window/tree operations stay behind [Adapter], while ordering, retries,
 * redirect confirmation, strict terminal routing and fail-closed ownership live in
 * this package instead of in `BlockingAccessibilityService`.
 */
internal object WebsiteRedirectionCoordinator {
    enum class TerminalDestination { REDIRECT, POMODORO }

    enum class Action {
        SHOW_BLOCK_PRESENTATION,
        NEUTRALIZE_BLOCKED_TAB,
        OPEN_POMODORO,
        HIDE_BLOCK_PRESENTATION
    }

    enum class Outcome {
        REDIRECT_CONFIRMED,
        STRICT_DESTINATION_CONFIRMED,
        FAIL_CLOSED,
        ABORTED
    }

    /** Android-specific work supplied by the AccessibilityService adapter. */
    interface Adapter {
        suspend fun awaitPresentationFrame(): Boolean
        fun ownsProtection(): Boolean
        suspend fun requestSameTabRedirect(attemptNumber: Int): Boolean
        suspend fun restoreBlockedSurfaceForRetry(): Boolean
        suspend fun beforeRetry(nextAttemptNumber: Int)
        suspend fun requestExternalFallback(): Boolean
        suspend fun awaitRedirectConfirmation(): Boolean
        suspend fun completeStrictDestination(): Boolean
        suspend fun releasePresentation()
        fun failClosed()
    }

    class Session(private val strict: Boolean) {
        private enum class State { NEW, SANITIZATION_PENDING, POMODORO_REQUESTED, FINISHED }

        private var state = State.NEW

        val terminalDestination: TerminalDestination = if (strict) {
            TerminalDestination.POMODORO
        } else {
            TerminalDestination.REDIRECT
        }

        fun begin(): List<Action> {
            check(state == State.NEW)
            state = State.SANITIZATION_PENDING
            return listOf(Action.SHOW_BLOCK_PRESENTATION, Action.NEUTRALIZE_BLOCKED_TAB)
        }

        fun afterRedirectConfirmed(): Action {
            check(state == State.SANITIZATION_PENDING)
            state = if (strict) State.POMODORO_REQUESTED else State.FINISHED
            return if (strict) Action.OPEN_POMODORO else Action.HIDE_BLOCK_PRESENTATION
        }

        fun onPomodoroConfirmed(): Action {
            check(state == State.POMODORO_REQUESTED)
            state = State.FINISHED
            return Action.HIDE_BLOCK_PRESENTATION
        }

        fun failClosed() {
            check(state != State.FINISHED)
            state = State.FINISHED
        }
    }

    /**
     * Runs the complete post-presentation redirect transaction.
     *
     * The adapter may lose ownership because the browser/window was replaced by a
     * newer attempt. That is an abort, not a redirect failure, so this method never
     * opens a fail-closed surface after ownership has already moved elsewhere.
     */
    suspend fun execute(session: Session, adapter: Adapter): Outcome {
        if (!adapter.awaitPresentationFrame() || !adapter.ownsProtection()) {
            return Outcome.ABORTED
        }

        var redirectRequested = false
        var attemptNumber = 1
        while (adapter.ownsProtection() && attemptNumber <= WebsiteRedirectionPlan.MAX_SAME_TAB_ATTEMPTS) {
            redirectRequested = adapter.requestSameTabRedirect(attemptNumber)
            if (redirectRequested) break
            if (!adapter.ownsProtection()) return Outcome.ABORTED
            if (!WebsiteRedirectionPlan.canRetry(attemptNumber)) break
            if (!adapter.restoreBlockedSurfaceForRetry()) break
            if (!adapter.ownsProtection()) return Outcome.ABORTED
            attemptNumber += 1
            adapter.beforeRetry(attemptNumber)
        }

        if (!redirectRequested &&
            WebsiteRedirectionPlan.ALLOW_EXTERNAL_BROWSER_INTENT_FALLBACK &&
            adapter.ownsProtection()
        ) {
            redirectRequested = adapter.requestExternalFallback()
        }

        if (!adapter.ownsProtection()) return Outcome.ABORTED
        if (!redirectRequested) return failClosed(session, adapter)

        if (!adapter.awaitRedirectConfirmation()) {
            if (!adapter.ownsProtection()) return Outcome.ABORTED
            return failClosed(session, adapter)
        }
        if (!adapter.ownsProtection()) return Outcome.ABORTED

        return when (session.afterRedirectConfirmed()) {
            Action.HIDE_BLOCK_PRESENTATION -> {
                adapter.releasePresentation()
                Outcome.REDIRECT_CONFIRMED
            }

            Action.OPEN_POMODORO -> {
                if (adapter.completeStrictDestination()) {
                    session.onPomodoroConfirmed()
                    adapter.releasePresentation()
                    Outcome.STRICT_DESTINATION_CONFIRMED
                } else if (!adapter.ownsProtection()) {
                    Outcome.ABORTED
                } else {
                    failClosed(session, adapter)
                }
            }

            else -> failClosed(session, adapter)
        }
    }

    private fun failClosed(session: Session, adapter: Adapter): Outcome {
        session.failClosed()
        adapter.failClosed()
        return Outcome.FAIL_CLOSED
    }
}

/** Keeps same-tab address-bar actions bound to the browser window that was blocked. */
internal class WebsiteTabNeutralizationPolicy(
    private val browserPackageName: String,
    private val expectedWindowId: Int
) {
    private enum class State { BLOCKED_TAB, SAFE_ADDRESS_SET, REDIRECT_REQUESTED }

    private var state = State.BLOCKED_TAB

    fun mayTouchBlockedTab(activePackageName: String, activeWindowId: Int): Boolean =
        state == State.BLOCKED_TAB &&
            activePackageName == browserPackageName &&
            activeWindowId == expectedWindowId

    fun mayAttemptChromiumClose(
        activePackageName: String,
        activeWindowId: Int,
        phaseStartedAtUptimeMillis: Long,
        latestWindowTransitionEventUptimeMillis: Long
    ): Boolean = state == State.BLOCKED_TAB &&
        BrowserUiCapabilityPolicy.isFreshExpectedSurface(
            expectedBrowserPackage = browserPackageName,
            expectedWindowId = expectedWindowId,
            activePackageName = activePackageName,
            activeWindowId = activeWindowId,
            phaseStartedAtUptimeMillis = phaseStartedAtUptimeMillis,
            latestWindowTransitionEventUptimeMillis = latestWindowTransitionEventUptimeMillis
        )

    fun mayActivateBlockedAddressBar(
        activePackageName: String,
        activeWindowId: Int,
        @Suppress("UNUSED_PARAMETER") phaseStartedAtUptimeMillis: Long,
        @Suppress("UNUSED_PARAMETER") latestWindowTransitionEventUptimeMillis: Long
    ): Boolean = state == State.BLOCKED_TAB &&
        activePackageName == browserPackageName &&
        activeWindowId == expectedWindowId

    fun markSafeAddressSet(setAtUptimeMillis: Long) {
        check(state == State.BLOCKED_TAB)
        check(setAtUptimeMillis > 0L)
        state = State.SAFE_ADDRESS_SET
    }

    fun maySubmitSafeAddress(
        activePackageName: String,
        activeWindowId: Int,
        @Suppress("UNUSED_PARAMETER") latestWindowTransitionEventUptimeMillis: Long
    ): Boolean = state == State.SAFE_ADDRESS_SET &&
        activePackageName == browserPackageName &&
        activeWindowId == expectedWindowId

    fun markRedirectRequested() {
        check(state == State.SAFE_ADDRESS_SET)
        state = State.REDIRECT_REQUESTED
    }
}
