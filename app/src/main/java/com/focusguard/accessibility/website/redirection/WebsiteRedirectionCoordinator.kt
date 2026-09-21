package com.focusguard.accessibility.website.redirection

import kotlinx.coroutines.CancellationException

/**
 * Coordinator for one website redirection transaction.
 *
 * Android window/tree operations stay behind [Adapter], while ordering, retries,
 * redirect confirmation, strict terminal routing and fail-closed ownership live here.
 * The presentation is shown by the service adapter before [execute], because it owns
 * the Android overlay generation token.
 */
internal object WebsiteRedirectionCoordinator {
    enum class TerminalDestination { REDIRECT, POMODORO }

    enum class Action { OPEN_POMODORO, HIDE_BLOCK_PRESENTATION }

    enum class Outcome {
        REDIRECT_CONFIRMED,
        STRICT_DESTINATION_CONFIRMED,
        FAIL_CLOSED,
        ABORTED
    }

    interface Adapter {
        suspend fun awaitPresentationFrame(): Boolean
        fun ownsProtection(): Boolean

        /**
         * Revalidated before every operation that could navigate. Returning false
         * means the configured destination is no longer eligible (for example a
         * newly active rule now blocks it), so protection remains fail-closed.
         */
        fun mayAttemptRedirect(): Boolean = true

        suspend fun prepareSameTabRedirect(attemptNumber: Int): Boolean
        suspend fun submitSameTabRedirect(attemptNumber: Int): Boolean
        suspend fun restoreBlockedSurfaceForRetry(): Boolean
        suspend fun beforeRetry(nextAttemptNumber: Int)
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

        fun begin() {
            check(state == State.NEW)
            state = State.SANITIZATION_PENDING
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
            if (state != State.FINISHED) state = State.FINISHED
        }
    }

    suspend fun execute(session: Session, adapter: Adapter): Outcome = try {
        executeInternal(session, adapter)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: RuntimeException) {
        if (adapter.ownsProtection()) failClosed(session, adapter) else Outcome.ABORTED
    }

    private suspend fun executeInternal(session: Session, adapter: Adapter): Outcome {
        if (!adapter.awaitPresentationFrame() || !adapter.ownsProtection()) {
            return Outcome.ABORTED
        }
        if (!adapter.mayAttemptRedirect()) {
            return failClosed(session, adapter)
        }

        var attemptNumber = 1
        while (attemptNumber <= WebsiteRedirectionPlan.MAX_SAME_TAB_ATTEMPTS) {
            if (!adapter.ownsProtection()) return Outcome.ABORTED
            if (!adapter.mayAttemptRedirect()) return failClosed(session, adapter)

            val prepared = adapter.prepareSameTabRedirect(attemptNumber)
            if (!adapter.ownsProtection()) return Outcome.ABORTED
            if (!adapter.mayAttemptRedirect()) return failClosed(session, adapter)

            var submittedAtLeastOnce = false
            if (prepared) {
                var submitAlternativeNumber = 1
                while (submitAlternativeNumber <= WebsiteRedirectionPlan.MAX_SUBMIT_ALTERNATIVES) {
                    if (!adapter.mayAttemptRedirect()) return failClosed(session, adapter)
                    val submitted = adapter.submitSameTabRedirect(attemptNumber)
                    submittedAtLeastOnce = submittedAtLeastOnce || submitted
                    if (!adapter.ownsProtection()) return Outcome.ABORTED

                    if (submitted && adapter.awaitRedirectConfirmation()) {
                        if (!adapter.ownsProtection()) return Outcome.ABORTED
                        return completeConfirmedRedirect(session, adapter)
                    }
                    if (!adapter.ownsProtection()) return Outcome.ABORTED
                    submitAlternativeNumber += 1
                }
            }

            if (!adapter.mayAttemptRedirect()) return failClosed(session, adapter)
            if (!WebsiteRedirectionPlan.canRetry(attemptNumber)) {
                return failClosed(session, adapter)
            }
            if (!adapter.restoreBlockedSurfaceForRetry()) {
                if (!adapter.ownsProtection()) return Outcome.ABORTED
                if (submittedAtLeastOnce && adapter.awaitRedirectConfirmation()) {
                    if (!adapter.ownsProtection()) return Outcome.ABORTED
                    return completeConfirmedRedirect(session, adapter)
                }
                if (!adapter.ownsProtection()) return Outcome.ABORTED
                return failClosed(session, adapter)
            }
            if (!adapter.ownsProtection()) return Outcome.ABORTED

            attemptNumber += 1
            adapter.beforeRetry(attemptNumber)
        }

        return failClosed(session, adapter)
    }

    private suspend fun completeConfirmedRedirect(
        session: Session,
        adapter: Adapter
    ): Outcome = when (session.afterRedirectConfirmed()) {
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
    private var safeAddressSetAtUptimeMillis = Long.MIN_VALUE

    fun mayTouchBlockedTab(activePackageName: String, activeWindowId: Int): Boolean =
        state == State.BLOCKED_TAB &&
            activePackageName == browserPackageName &&
            activeWindowId == expectedWindowId

    fun mayActivateBlockedAddressBar(
        activePackageName: String,
        activeWindowId: Int,
        phaseStartedAtUptimeMillis: Long,
        latestWindowTransitionEventUptimeMillis: Long
    ): Boolean = state == State.BLOCKED_TAB &&
        phaseStartedAtUptimeMillis > 0L &&
        latestWindowTransitionEventUptimeMillis <= phaseStartedAtUptimeMillis &&
        activePackageName == browserPackageName &&
        activeWindowId == expectedWindowId

    fun markSafeAddressSet(setAtUptimeMillis: Long) {
        check(state == State.BLOCKED_TAB)
        check(setAtUptimeMillis > 0L)
        safeAddressSetAtUptimeMillis = setAtUptimeMillis
        state = State.SAFE_ADDRESS_SET
    }

    fun maySubmitSafeAddress(
        activePackageName: String,
        activeWindowId: Int,
        latestWindowTransitionEventUptimeMillis: Long
    ): Boolean = safeAddressSetAtUptimeMillis > 0L &&
        activePackageName == browserPackageName &&
        activeWindowId == expectedWindowId &&
        when (state) {
            State.SAFE_ADDRESS_SET ->
                latestWindowTransitionEventUptimeMillis <= safeAddressSetAtUptimeMillis
            State.REDIRECT_REQUESTED -> true
            State.BLOCKED_TAB -> false
        }

    fun markRedirectRequested() {
        check(state == State.SAFE_ADDRESS_SET || state == State.REDIRECT_REQUESTED)
        state = State.REDIRECT_REQUESTED
    }
}
