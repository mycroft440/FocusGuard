package com.focusguard.accessibility.website.redirection

import com.focusguard.utils.BrowserUiCapabilityPolicy

/**
 * Pure coordinator for one website redirection transaction.
 *
 * Android window/tree operations stay in the accessibility adapter, but ordering,
 * terminal ownership and same-tab state no longer belong to the service itself.
 */
internal object WebsiteRedirectionCoordinator {
    enum class TerminalDestination { REDIRECT, POMODORO }

    enum class Action {
        SHOW_BLOCK_PRESENTATION,
        NEUTRALIZE_BLOCKED_TAB,
        OPEN_POMODORO,
        HIDE_BLOCK_PRESENTATION
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
