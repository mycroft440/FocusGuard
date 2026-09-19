from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, content: str) -> None:
    (ROOT / path).write_text(content, encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected exactly one match, found {count}: {old[:120]!r}")
    write(path, text.replace(old, new, 1))


def regex_replace_once(path: str, pattern: str, replacement: str) -> None:
    text = read(path)
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise RuntimeError(f"{path}: expected exactly one regex match, found {count}: {pattern[:120]!r}")
    write(path, updated)


# 1) Coordinator owns executable prepare -> submit -> confirmation -> retry sequence.
write(
    "app/src/main/java/com/focusguard/accessibility/website/redirection/WebsiteRedirectionCoordinator.kt",
    '''package com.focusguard.accessibility.website.redirection

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

        var attemptNumber = 1
        while (attemptNumber <= WebsiteRedirectionPlan.MAX_SAME_TAB_ATTEMPTS) {
            if (!adapter.ownsProtection()) return Outcome.ABORTED

            val prepared = adapter.prepareSameTabRedirect(attemptNumber)
            if (!adapter.ownsProtection()) return Outcome.ABORTED

            val submitted = prepared && adapter.submitSameTabRedirect(attemptNumber)
            if (!adapter.ownsProtection()) return Outcome.ABORTED

            if (submitted && adapter.awaitRedirectConfirmation()) {
                if (!adapter.ownsProtection()) return Outcome.ABORTED
                return completeConfirmedRedirect(session, adapter)
            }
            if (!adapter.ownsProtection()) return Outcome.ABORTED

            if (!WebsiteRedirectionPlan.canRetry(attemptNumber)) {
                return failClosed(session, adapter)
            }
            if (!adapter.restoreBlockedSurfaceForRetry()) {
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
    ): Boolean = state == State.SAFE_ADDRESS_SET &&
        safeAddressSetAtUptimeMillis > 0L &&
        latestWindowTransitionEventUptimeMillis <= safeAddressSetAtUptimeMillis &&
        activePackageName == browserPackageName &&
        activeWindowId == expectedWindowId

    fun markRedirectRequested() {
        check(state == State.SAFE_ADDRESS_SET)
        state = State.REDIRECT_REQUESTED
    }
}
'''
)

# 2) Retry plan contains only limits actually consumed by the coordinator.
write(
    "app/src/main/java/com/focusguard/accessibility/website/redirection/WebsiteRedirectionPlan.kt",
    '''package com.focusguard.accessibility.website.redirection

/** Executable retry policy consumed directly by [WebsiteRedirectionCoordinator]. */
internal object WebsiteRedirectionPlan {
    const val MAX_SAME_TAB_ATTEMPTS = 2

    /**
     * Coordinate taps, guessed keyboard positions and ACTION_VIEW are deliberately
     * excluded. Exhausting this bounded same-tab budget is terminal fail-closed.
     */
    fun canRetry(attemptNumber: Int): Boolean = attemptNumber < MAX_SAME_TAB_ATTEMPTS
}
'''
)

# 3) Transition guard permits one strictly verified destination-window rebind.
write(
    "app/src/main/java/com/focusguard/accessibility/website/redirection/WebsiteBlockTransition.kt",
    '''package com.focusguard.accessibility.website.redirection

import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CompletableDeferred

/** Mutable state for one browser-bound website redirection transaction. */
internal data class WebsiteBlockTransitionHandle(
    val id: Long,
    val browserPackageName: String,
    val destination: WebsiteRedirectionCoordinator.TerminalDestination,
    @Volatile internal var expectedWindowId: Int,
    @Volatile internal var inspectionGeneration: Long,
    val blockedCandidate: String?,
    val blockedRules: Set<String>,
    val detectionEventUptimeMillis: Long,
    internal val safeRedirectConfirmed: CompletableDeferred<Unit> = CompletableDeferred(),
    internal val destinationConfirmed: CompletableDeferred<Unit> = CompletableDeferred(),
    internal var sanitizationRequested: Boolean = false,
    internal var sanitizationRequestedAtUptimeMillis: Long = Long.MAX_VALUE,
    internal var destinationRequested: Boolean = false,
    internal var handedOff: Boolean = false,
    internal var destinationRequestedAtUptimeMillis: Long = Long.MAX_VALUE,
    @Volatile internal var latestObservedEventUptimeMillis: Long = detectionEventUptimeMillis,
    @Volatile internal var latestWindowTransitionEventUptimeMillis: Long = detectionEventUptimeMillis,
    internal var latestSurfaceMutationEventUptimeMillis: Long = 0L,
    internal var latestNavigationEvidenceEventUptimeMillis: Long = 0L,
    @Volatile internal var activatedAddressViewId: String? = null,
    @Volatile internal var editorAddressViewId: String? = null,
    @Volatile internal var curtainGeneration: Long = 0L,
    @Volatile internal var verifiedDestinationWindowRebound: Boolean = false
)

/**
 * Owns active transaction registration and confirmation ordering.
 *
 * The original browser window remains authoritative while editing. A different
 * Accessibility window may inherit the transition exactly once, and only after the
 * service has independently proved that it is the stable configured destination.
 */
internal class WebsiteBlockTransitionGuard {
    private val activeTransitions = mutableMapOf<String, WebsiteBlockTransitionHandle>()

    @Synchronized
    fun tryStart(
        browserPackageName: String,
        transitionId: Long,
        destination: WebsiteRedirectionCoordinator.TerminalDestination,
        expectedWindowId: Int = INVALID_BROWSER_WINDOW_ID,
        inspectionGeneration: Long = 0L,
        blockedCandidate: String? = null,
        blockedRules: Set<String> = emptySet(),
        detectionEventUptimeMillis: Long = 0L
    ): WebsiteBlockTransitionHandle? {
        require(browserPackageName.isNotBlank())
        require(transitionId > 0L)
        if (browserPackageName in activeTransitions) return null
        return WebsiteBlockTransitionHandle(
            id = transitionId,
            browserPackageName = browserPackageName,
            destination = destination,
            expectedWindowId = expectedWindowId,
            inspectionGeneration = inspectionGeneration,
            blockedCandidate = blockedCandidate,
            blockedRules = blockedRules,
            detectionEventUptimeMillis = detectionEventUptimeMillis
        ).also { activeTransitions[browserPackageName] = it }
    }

    @Synchronized
    fun isActive(browserPackageName: String): Boolean = browserPackageName in activeTransitions

    @Synchronized
    fun activeTransition(browserPackageName: String): WebsiteBlockTransitionHandle? =
        activeTransitions[browserPackageName]

    @Synchronized
    fun activeBrowserPackages(): Set<String> = activeTransitions.keys.toSet()

    @Synchronized
    fun markSanitizationRequested(
        browserPackageName: String,
        transitionId: Long,
        requestedAtUptimeMillis: Long
    ): Boolean {
        val transition = activeTransitions[browserPackageName] ?: return false
        if (transition.id != transitionId || transition.destinationRequested ||
            requestedAtUptimeMillis < transition.detectionEventUptimeMillis
        ) return false
        transition.sanitizationRequested = true
        transition.sanitizationRequestedAtUptimeMillis = minOf(
            transition.sanitizationRequestedAtUptimeMillis,
            requestedAtUptimeMillis
        )
        return true
    }

    @Synchronized
    fun markDestinationRequested(
        browserPackageName: String,
        transitionId: Long,
        requestedAtUptimeMillis: Long
    ): Boolean {
        val transition = activeTransitions[browserPackageName] ?: return false
        if (transition.id != transitionId ||
            !transition.safeRedirectConfirmed.isCompleted ||
            requestedAtUptimeMillis < transition.sanitizationRequestedAtUptimeMillis
        ) return false
        transition.destinationRequested = true
        transition.destinationRequestedAtUptimeMillis = requestedAtUptimeMillis
        return true
    }

    @Synchronized
    fun markCurtainGeneration(
        browserPackageName: String,
        transitionId: Long,
        curtainGeneration: Long
    ): Boolean {
        val transition = activeTransitions[browserPackageName] ?: return false
        if (transition.id != transitionId || curtainGeneration <= 0L) return false
        transition.curtainGeneration = curtainGeneration
        return true
    }

    @Synchronized
    fun confirmRedirect(
        browserPackageName: String,
        windowId: Int,
        eventUptimeMillis: Long
    ): Boolean {
        val transition = activeTransitions[browserPackageName] ?: return false
        if (!transition.sanitizationRequested ||
            transition.expectedWindowId != windowId ||
            eventUptimeMillis < transition.sanitizationRequestedAtUptimeMillis ||
            transition.latestNavigationEvidenceEventUptimeMillis < eventUptimeMillis
        ) return false
        transition.latestObservedEventUptimeMillis = maxOf(
            transition.latestObservedEventUptimeMillis,
            eventUptimeMillis
        )
        return transition.safeRedirectConfirmed.complete(Unit)
    }

    @Synchronized
    fun confirmRedirectFromStableCurrentSurface(
        browserPackageName: String,
        windowId: Int,
        observedAtUptimeMillis: Long
    ): Boolean {
        val transition = activeTransitions[browserPackageName] ?: return false
        if (!transition.sanitizationRequested ||
            transition.expectedWindowId != windowId ||
            observedAtUptimeMillis < transition.sanitizationRequestedAtUptimeMillis
        ) return false
        transition.latestObservedEventUptimeMillis = maxOf(
            transition.latestObservedEventUptimeMillis,
            observedAtUptimeMillis
        )
        return transition.safeRedirectConfirmed.complete(Unit) ||
            transition.safeRedirectConfirmed.isCompleted
    }

    /** Same-window confirmation path used after ordinary navigation evidence. */
    @Synchronized
    fun transitionForConfirmation(
        browserPackageName: String,
        windowId: Int,
        eventUptimeMillis: Long,
        eventType: Int
    ): WebsiteBlockTransitionHandle? {
        val transition = transitionForDestinationCandidate(
            browserPackageName,
            eventUptimeMillis,
            eventType
        ) ?: return null
        return transition.takeIf { it.expectedWindowId == windowId }
    }

    /**
     * Returns a transaction that is temporally eligible for destination validation.
     * Window ownership is deliberately not changed here; the caller must first prove
     * the exact stable destination surface, then call [rebindVerifiedDestinationWindow].
     */
    @Synchronized
    fun transitionForDestinationCandidate(
        browserPackageName: String,
        eventUptimeMillis: Long,
        eventType: Int
    ): WebsiteBlockTransitionHandle? {
        val transition = activeTransitions[browserPackageName] ?: return null
        return transition.takeIf {
            it.sanitizationRequested &&
                eventUptimeMillis >= it.sanitizationRequestedAtUptimeMillis &&
                isRedirectNavigationEvidenceEvent(eventType)
        }
    }

    /**
     * One-time rebind for browsers that recreate their Accessibility window during
     * a certified same-tab navigation. Call only after the new window has been
     * independently inspected twice as the exact configured destination.
     */
    @Synchronized
    fun rebindVerifiedDestinationWindow(
        browserPackageName: String,
        transitionId: Long,
        windowId: Int,
        inspectionGeneration: Long,
        eventUptimeMillis: Long,
        eventType: Int
    ): Boolean {
        val transition = activeTransitions[browserPackageName] ?: return false
        if (transition.id != transitionId ||
            !transition.sanitizationRequested ||
            transition.destinationRequested ||
            windowId < 0 || inspectionGeneration <= 0L ||
            eventUptimeMillis < transition.sanitizationRequestedAtUptimeMillis ||
            !isRedirectNavigationEvidenceEvent(eventType)
        ) return false
        if (transition.expectedWindowId == windowId) return true
        if (transition.verifiedDestinationWindowRebound) return false

        transition.expectedWindowId = windowId
        transition.inspectionGeneration = inspectionGeneration
        transition.verifiedDestinationWindowRebound = true
        transition.latestObservedEventUptimeMillis = maxOf(
            transition.latestObservedEventUptimeMillis,
            eventUptimeMillis
        )
        transition.latestSurfaceMutationEventUptimeMillis = maxOf(
            transition.latestSurfaceMutationEventUptimeMillis,
            eventUptimeMillis
        )
        transition.latestNavigationEvidenceEventUptimeMillis = maxOf(
            transition.latestNavigationEvidenceEventUptimeMillis,
            eventUptimeMillis
        )
        return true
    }

    @Synchronized
    fun observeBrowserEvent(
        browserPackageName: String,
        windowId: Int,
        eventUptimeMillis: Long,
        eventType: Int
    ) {
        val transition = activeTransitions[browserPackageName] ?: return
        if (transition.expectedWindowId != windowId) return
        transition.latestObservedEventUptimeMillis = maxOf(
            transition.latestObservedEventUptimeMillis,
            eventUptimeMillis
        )
        if (isWindowOrTabTransitionEvent(eventType)) {
            transition.latestWindowTransitionEventUptimeMillis = maxOf(
                transition.latestWindowTransitionEventUptimeMillis,
                eventUptimeMillis
            )
        }
        if (isRedirectNavigationEvidenceEvent(eventType)) {
            transition.latestSurfaceMutationEventUptimeMillis = maxOf(
                transition.latestSurfaceMutationEventUptimeMillis,
                eventUptimeMillis
            )
        }
        if (transition.sanitizationRequested &&
            isRedirectNavigationEvidenceEvent(eventType) &&
            eventUptimeMillis >= transition.sanitizationRequestedAtUptimeMillis
        ) {
            transition.latestNavigationEvidenceEventUptimeMillis = maxOf(
                transition.latestNavigationEvidenceEventUptimeMillis,
                eventUptimeMillis
            )
        }
    }

    @Synchronized
    fun confirmPomodoro(curtainGeneration: Long, readyAtUptimeMillis: Long): Boolean {
        val transition = activeTransitions.values.singleOrNull {
            it.destinationRequested &&
                it.destination == WebsiteRedirectionCoordinator.TerminalDestination.POMODORO &&
                it.curtainGeneration == curtainGeneration &&
                readyAtUptimeMillis >= it.destinationRequestedAtUptimeMillis
        } ?: return false
        return transition.destinationConfirmed.complete(Unit)
    }

    @Synchronized
    fun finish(browserPackageName: String, transitionId: Long): Boolean {
        if (activeTransitions[browserPackageName]?.id != transitionId) return false
        activeTransitions.remove(browserPackageName)
        return true
    }

    @Synchronized
    fun clear() {
        activeTransitions.clear()
    }

    private companion object {
        const val INVALID_BROWSER_WINDOW_ID = -1

        fun isRedirectNavigationEvidenceEvent(eventType: Int): Boolean =
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED

        fun isWindowOrTabTransitionEvent(eventType: Int): Boolean =
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
    }
}
'''
)

# 4) Destination host aliases are explicit; www is no longer globally collapsed.
dest_path = "app/src/main/java/com/focusguard/accessibility/website/redirection/WebsiteRedirectDestination.kt"
replace_once(
    dest_path,
    '        private fun normalizeHost(host: String): String =\n            host.trim().lowercase(Locale.US).removePrefix("www.")\n',
    '        private fun normalizeHost(host: String): String =\n            host.trim().lowercase(Locale.US)\n'
)
replace_once(
    dest_path,
    '        /** Initial FocusGuard destination. Future selection belongs in this layer. */\n        val GOOGLE = WebsiteRedirectDestination(\n            url = "https://www.google.com",\n            acceptedRootHosts = GOOGLE_ROOT_HOSTS,\n',
    '        private val GOOGLE_ACCEPTED_ROOT_HOSTS = GOOGLE_ROOT_HOSTS\n            .flatMapTo(linkedSetOf()) { host -> listOf(host, "www.$host") }\n\n        /** Initial FocusGuard destination. Future selection belongs in this layer. */\n        val GOOGLE = WebsiteRedirectDestination(\n            url = "https://www.google.com",\n            acceptedRootHosts = GOOGLE_ACCEPTED_ROOT_HOSTS,\n'
)

# 5) Submit preference is promoted only after confirmed navigation and is demoted on repeated failure.
store_path = "app/src/main/java/com/focusguard/accessibility/website/compatibility/BrowserCompatibilityStore.kt"
replace_once(
    store_path,
    '''    private data class PendingRedirect(\n        val normalizedTarget: String,\n        val submitted: Boolean\n    )\n''',
    '''    private data class PendingRedirect(\n        val normalizedTarget: String,\n        val submitted: Boolean,\n        val candidateSubmitMethod: BrowserSubmitMethod? = null,\n        val candidateSubmitEntryName: String? = null\n    )\n'''
)
replace_once(
    store_path,
    '''            val previous = recordForLocked(packageName)\n            saveLocked(\n                previous.copy(\n                    status = BrowserCompatibilityStatus.SUPPORTED,\n                    packageVersionCode = installedVersionCodeLocked(packageName)\n                        ?: previous.packageVersionCode,\n                    consecutiveRedirectionFailures = 0,\n                    updatedAtMillis = System.currentTimeMillis()\n                )\n            )\n''',
    '''            val previous = recordForLocked(packageName)\n            saveLocked(\n                previous.copy(\n                    status = BrowserCompatibilityStatus.SUPPORTED,\n                    packageVersionCode = installedVersionCodeLocked(packageName)\n                        ?: previous.packageVersionCode,\n                    preferredAddressBarEntryName = pending.candidateSubmitEntryName\n                        ?: previous.preferredAddressBarEntryName,\n                    submitMethod = pending.candidateSubmitMethod ?: previous.submitMethod,\n                    consecutiveRedirectionFailures = 0,\n                    updatedAtMillis = System.currentTimeMillis()\n                )\n            )\n'''
)
replace_once(
    store_path,
    '''    fun recordSubmitAccepted(\n        packageName: String,\n        viewIdResourceName: String?,\n        method: BrowserSubmitMethod\n    ) = updateMethod(packageName, viewIdResourceName) { previous, entryName ->\n        pendingRedirects[packageName]?.let { pending ->\n            pendingRedirects[packageName] = pending.copy(submitted = true)\n        }\n        previous.copy(\n            preferredAddressBarEntryName = entryName,\n            submitMethod = method,\n            updatedAtMillis = System.currentTimeMillis()\n        )\n    }\n''',
    '''    fun recordSubmitAccepted(\n        packageName: String,\n        viewIdResourceName: String?,\n        method: BrowserSubmitMethod\n    ) {\n        if (packageName.isBlank()) return\n        synchronized(lock) {\n            val pending = pendingRedirects[packageName] ?: return\n            pendingRedirects[packageName] = pending.copy(\n                submitted = true,\n                candidateSubmitMethod = method,\n                candidateSubmitEntryName = browserOwnedEntryName(packageName, viewIdResourceName)\n            )\n        }\n    }\n'''
)
replace_once(
    store_path,
    '''                    status = if (unsupported) {\n                        BrowserCompatibilityStatus.UNSUPPORTED\n                    } else {\n                        previous.status\n                    },\n                    consecutiveRedirectionFailures = failures,\n''',
    '''                    status = if (unsupported) {\n                        BrowserCompatibilityStatus.UNSUPPORTED\n                    } else {\n                        previous.status\n                    },\n                    submitMethod = if (unsupported) null else previous.submitMethod,\n                    consecutiveRedirectionFailures = failures,\n'''
)

# 6) Service: candidate-window verification, executable coordinator adapter, single confirmation timeout,
# explicit outcome telemetry, and immediate post-focus target revalidation.
service_path = "app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt"
regex_replace_once(
    service_path,
    r'    private fun observeSafeDestinationFromInspection\(outcome: BrowserInspectionOutcome\) \{.*?\n    \}\n\n    private fun scheduleAsyncWebsiteRecovery',
    '''    private fun observeSafeDestinationFromInspection(outcome: BrowserInspectionOutcome) {\n        val token = outcome.token\n        val transition = websiteBlockTransitionGuard.transitionForDestinationCandidate(\n            browserPackageName = token.packageName,\n            eventUptimeMillis = outcome.eventUptimeMillis,\n            eventType = outcome.eventType\n        ) ?: return\n        val stableRedirectCandidate =\n            isSafeRedirectSurface(outcome.bestCandidate) &&\n                outcome.surface == BrowserSurfaceInspector.Surface.WEB_CONTENT &&\n                !outcome.focusedAddressEditor\n        if (!stableRedirectCandidate || !transitionOwnsCurtain(transition)) return\n\n        scope.launch {\n            delay(WEBSITE_REDIRECT_SURFACE_SETTLE_MILLIS)\n            if (!browserInspectionCoordinator.isCurrent(token, requireLatestSequence = true)) return@launch\n            val stable = inspectBrowserSnapshot(BrowserInspectionCoordinator.Snapshot(\n                token, outcome.eventType, outcome.eventUptimeMillis, SystemClock.uptimeMillis(),\n                outcome.eventClassName, outcome.directEventText, outcome.eventContentDescription\n            )) ?: return@launch\n            if (!isSafeRedirectSurface(stable.bestCandidate) ||\n                stable.surface != BrowserSurfaceInspector.Surface.WEB_CONTENT ||\n                stable.focusedAddressEditor\n            ) return@launch\n            withContext(Dispatchers.Main.immediate) {\n                if (!browserInspectionCoordinator.isCurrent(token, requireLatestSequence = true)) return@withContext\n                val current = websiteBlockTransitionGuard.transitionForDestinationCandidate(\n                    browserPackageName = token.packageName,\n                    eventUptimeMillis = outcome.eventUptimeMillis,\n                    eventType = outcome.eventType\n                ) ?: return@withContext\n                if (current.id != transition.id || !transitionOwnsCurtain(current)) return@withContext\n\n                if (token.windowId != current.expectedWindowId &&\n                    !websiteBlockTransitionGuard.rebindVerifiedDestinationWindow(\n                        browserPackageName = token.packageName,\n                        transitionId = current.id,\n                        windowId = token.windowId,\n                        inspectionGeneration = token.generation,\n                        eventUptimeMillis = outcome.eventUptimeMillis,\n                        eventType = outcome.eventType\n                    )\n                ) return@withContext\n\n                if (!transitionWindowIsCurrent(current)) return@withContext\n                websiteBlockTransitionGuard.confirmRedirect(\n                    browserPackageName = token.packageName,\n                    windowId = token.windowId,\n                    eventUptimeMillis = outcome.eventUptimeMillis\n                )\n            }\n        }\n    }\n\n    private fun scheduleAsyncWebsiteRecovery'''
)
regex_replace_once(
    service_path,
    r'    private fun startWebsiteBlockTransition\(.*?\n    \}\n\n\n    /\*\*\n     \* Normal website block presentation\.',
    '''    private fun startWebsiteBlockTransition(\n        browserPackageName: String,\n        browserWindowId: Int = INVALID_BROWSER_WINDOW_ID,\n        blockedCandidate: String? = null,\n        detectionEventUptimeMillis: Long = 0L\n    ) {\n        val expectedWindowId = browserWindowId.takeIf { it >= 0 } ?: return\n        val inspectionGeneration = browserInspectionCoordinator.currentGeneration(\n            browserPackageName,\n            expectedWindowId\n        ) ?: return\n        val strict = isPomodoroStrictActive\n        val transitionId = websiteBlockTransitionCounter.incrementAndGet()\n        val transition = websiteBlockTransitionGuard.tryStart(\n            browserPackageName = browserPackageName,\n            transitionId = transitionId,\n            destination = if (strict) {\n                WebsiteRedirectionCoordinator.TerminalDestination.POMODORO\n            } else {\n                WebsiteRedirectionCoordinator.TerminalDestination.REDIRECT\n            },\n            expectedWindowId = expectedWindowId,\n            inspectionGeneration = inspectionGeneration,\n            blockedCandidate = blockedCandidate,\n            blockedRules = blockedWebsitesDomainSet,\n            detectionEventUptimeMillis = detectionEventUptimeMillis\n        ) ?: return\n        val stateMachine = WebsiteRedirectionCoordinator.Session(strict = strict)\n        stateMachine.begin()\n        val curtainGeneration = showWebsiteBlockPresentation(blockedCandidate)\n        if (curtainGeneration <= 0L ||\n            !websiteBlockTransitionGuard.markCurtainGeneration(\n                browserPackageName = browserPackageName,\n                transitionId = transitionId,\n                curtainGeneration = curtainGeneration\n            )\n        ) {\n            websiteBlockTransitionGuard.finish(browserPackageName, transitionId)\n            return\n        }\n        awaitingSafeSurfaceGeneration = curtainGeneration\n        val curtainShownAtUptimeMillis = SystemClock.uptimeMillis()\n\n        scope.launch(Dispatchers.Main.immediate) {\n            var outcome: WebsiteRedirectionCoordinator.Outcome? = null\n            var activePolicy: WebsiteTabNeutralizationPolicy? = null\n            try {\n                outcome = WebsiteRedirectionCoordinator.execute(\n                    session = stateMachine,\n                    adapter = object : WebsiteRedirectionCoordinator.Adapter {\n                        override suspend fun awaitPresentationFrame(): Boolean {\n                            awaitNextWebsiteRedirectFrame()\n                            return transitionOwnsCurtain(transition)\n                        }\n\n                        override fun ownsProtection(): Boolean = transitionOwnsCurtain(transition)\n\n                        override suspend fun prepareSameTabRedirect(attemptNumber: Int): Boolean {\n                            if (!curtainReadyForTransition(transition)) return false\n                            val policy = WebsiteTabNeutralizationPolicy(\n                                browserPackageName = browserPackageName,\n                                expectedWindowId = transition.expectedWindowId\n                            )\n                            val setRequestedAt = websiteTreeWorker.run {\n                                prepareSafeAddressBar(\n                                    browserPackageName = browserPackageName,\n                                    expectedWindowId = transition.expectedWindowId,\n                                    policy = policy,\n                                    transition = transition,\n                                    phaseStartedAtUptimeMillis = transition.detectionEventUptimeMillis\n                                )\n                            }\n                            if (setRequestedAt <= 0L || !curtainReadyForTransition(transition)) {\n                                activePolicy = null\n                                return false\n                            }\n                            policy.markSafeAddressSet(setRequestedAt)\n                            activePolicy = policy\n                            return true\n                        }\n\n                        override suspend fun submitSameTabRedirect(attemptNumber: Int): Boolean {\n                            val policy = activePolicy ?: return false\n                            if (!transitionOwnsCurtain(transition)) return false\n                            val submitRequestedAt = websiteTreeWorker.run {\n                                submitSafeAddressBar(\n                                    browserPackageName = browserPackageName,\n                                    expectedWindowId = transition.expectedWindowId,\n                                    policy = policy,\n                                    transition = transition\n                                )\n                            }\n                            if (submitRequestedAt <= 0L || !transitionOwnsCurtain(transition)) {\n                                return false\n                            }\n                            policy.markRedirectRequested()\n                            return true\n                        }\n\n                        override suspend fun restoreBlockedSurfaceForRetry(): Boolean {\n                            activePolicy = null\n                            return restoreBlockedSurfaceAfterAddressEdit(transition)\n                        }\n\n                        override suspend fun beforeRetry(nextAttemptNumber: Int) {\n                            FocusGuardLogger.log(\n                                "A11y",\n                                "Repetindo redirecionamento seguro na mesma aba de " +\n                                    "$browserPackageName (tentativa $nextAttemptNumber)"\n                            )\n                            delay(WEBSITE_ADDRESS_BAR_ACTION_RETRY_MILLIS)\n                        }\n\n                        override suspend fun awaitRedirectConfirmation(): Boolean =\n                            withTimeoutOrNull(WEBSITE_DESTINATION_CONFIRM_TIMEOUT_MILLIS) {\n                                transition.safeRedirectConfirmed.await()\n                                true\n                            } == true\n\n                        override suspend fun completeStrictDestination(): Boolean =\n                            completeStrictWebsiteDestination(\n                                transition = transition,\n                                curtainGeneration = curtainGeneration\n                            )\n\n                        override suspend fun releasePresentation() {\n                            releaseWebsiteCurtainAfterMinimumNotice(\n                                curtainGeneration = curtainGeneration,\n                                curtainShownAtUptimeMillis = curtainShownAtUptimeMillis\n                            )\n                        }\n\n                        override fun failClosed() {\n                            FocusGuardLogger.log(\n                                "A11y",\n                                "Redirecionamento seguro não pôde ser certificado para " +\n                                    "$browserPackageName (API ${Build.VERSION.SDK_INT}); " +\n                                    "bloqueando fail-closed"\n                            )\n                            failClosedWebsiteTransition(transition)\n                        }\n                    }\n                )\n            } catch (cancellation: CancellationException) {\n                outcome = WebsiteRedirectionCoordinator.Outcome.ABORTED\n                throw cancellation\n            } finally {\n                finishWebsiteTransition(transition, outcome)\n            }\n        }\n    }\n\n\n    /**\n     * Normal website block presentation.'''
)
# requestSafeRedirectInCurrentTab is now represented by coordinator prepare/submit adapter methods.
regex_replace_once(
    service_path,
    r'    private suspend fun requestSafeRedirectInCurrentTab\(.*?\n    \}\n\n    private fun performTransitionBack',
    '    private fun performTransitionBack'
)
# Revalidate the editor immediately before writing so a same-window surface change cannot inherit the write.
replace_once(
    service_path,
    '''                    if (!curtainReadyForTransition(transition)) return 0L\n                    if (!policy.mayTouchBlockedTab(browserPackageName, editRoot.windowId)) return 0L\n                    when (method) {\n''',
    '''                    if (!curtainReadyForTransition(transition)) return 0L\n                    if (!policy.mayTouchBlockedTab(browserPackageName, editRoot.windowId) ||\n                        !editorSurfaceBelongsToTransitionOrSafeDestination(editRoot, transition)\n                    ) return 0L\n                    when (method) {\n'''
)
# Insert the editor fingerprint helper before submit.
replace_once(
    service_path,
    '''    private suspend fun submitSafeAddressBar(\n''',
    '''    private fun editorSurfaceBelongsToTransitionOrSafeDestination(\n        root: AccessibilityNodeInfo,\n        transition: WebsiteBlockTransitionHandle\n    ): Boolean {\n        if (!transitionWindowIsCurrent(transition)) return false\n        val https = isVerifiedHttpsHandler(transition.browserPackageName)\n        val currentAddress = WebsiteBlocker.extractAddressBarTextFromRoot(\n            root,\n            transition.browserPackageName,\n            https\n        ) ?: WebsiteBlocker.extractUrlFromRoot(\n            root,\n            transition.browserPackageName,\n            https\n        )\n        if (!transitionWindowIsCurrent(transition) || currentAddress.isNullOrBlank()) return false\n        if (isSafeRedirectSurface(currentAddress)) return true\n        val detected = transition.blockedCandidate?.takeIf(String::isNotBlank) ?: return false\n        val detectedRule = WebsiteBlocker.findMatchingRule(detected, transition.blockedRules) ?: return false\n        return WebsiteBlocker.findMatchingRule(currentAddress, transition.blockedRules) == detectedRule\n    }\n\n    private suspend fun submitSafeAddressBar(\n'''
)
# Submit accepts one certified action and returns immediately; coordinator owns the only confirmation timeout.
regex_replace_once(
    service_path,
    r'    private suspend fun submitSafeAddressBar\(.*?\n    \}\n\n    private suspend fun confirmSafeRedirectFromFreshBrowserSurface',
    '''    private suspend fun submitSafeAddressBar(\n        browserPackageName: String,\n        expectedWindowId: Int,\n        policy: WebsiteTabNeutralizationPolicy,\n        transition: WebsiteBlockTransitionHandle\n    ): Long {\n        if (!curtainReadyForTransition(transition)) return 0L\n        val https = isVerifiedHttpsHandler(browserPackageName)\n        val methods = (listOfNotNull(BrowserCompatibilityStore.preferredSubmitMethod(browserPackageName)) +\n            listOf(BrowserSubmitMethod.IME_ENTER, BrowserSubmitMethod.ANNOUNCED_EDITOR_ACTION,\n                BrowserSubmitMethod.CERTIFIED_GO_BUTTON)).distinct()\n        for (method in methods) {\n            if (method == BrowserSubmitMethod.IME_ENTER &&\n                !canUseCertifiableImeSubmit(Build.VERSION.SDK_INT)\n            ) continue\n            if (!curtainReadyForTransition(transition)) return 0L\n            val root = activeBrowserRoot(browserPackageName, expectedWindowId) ?: return 0L\n            val submittedAt = SystemClock.uptimeMillis()\n            val submitted = try {\n                if (!policy.maySubmitSafeAddress(\n                        browserPackageName,\n                        root.windowId,\n                        transition.latestWindowTransitionEventUptimeMillis\n                    ) ||\n                    !AddressBarRedirectionActions.hasFocusedAddressEditor(\n                        root, browserPackageName, expectedWindowId, https, ::isSafeRedirectSurface\n                    )\n                ) return 0L\n                when (method) {\n                    BrowserSubmitMethod.IME_ENTER -> AddressBarRedirectionActions.submitImeEnter(\n                        root, browserPackageName, expectedWindowId, ::isSafeRedirectSurface, https,\n                        isCurrent = { transitionOwnsCurtain(transition) }\n                    )\n                    BrowserSubmitMethod.ANNOUNCED_EDITOR_ACTION ->\n                        AddressBarRedirectionActions.submitAnnouncedEditorAction(\n                            root, browserPackageName, expectedWindowId, ::isSafeRedirectSurface, https,\n                            isCurrent = { transitionOwnsCurtain(transition) }\n                        )\n                    BrowserSubmitMethod.CERTIFIED_GO_BUTTON ->\n                        AddressBarRedirectionActions.clickCertifiedGoButton(\n                            root, browserPackageName, expectedWindowId,\n                            isCurrent = { transitionOwnsCurtain(transition) }\n                        )\n                }\n            } finally {\n                recycleSafely(root)\n            }\n\n            if (!transitionOwnsCurtain(transition)) return 0L\n            if (submitted.status == AddressBarRedirectionActions.Status.AMBIGUOUS) return 0L\n            if (!submitted.accepted) {\n                if (!curtainReadyForTransition(transition)) return 0L\n                delay(WEBSITE_ADDRESS_BAR_FOCUS_SETTLE_MILLIS)\n                continue\n            }\n\n            BrowserCompatibilityStore.recordSubmitAccepted(\n                browserPackageName,\n                submitted.selectedViewId,\n                method\n            )\n            if (!websiteBlockTransitionGuard.markSanitizationRequested(\n                    browserPackageName = browserPackageName,\n                    transitionId = transition.id,\n                    requestedAtUptimeMillis = submittedAt\n                )\n            ) return 0L\n            return submittedAt\n        }\n        return 0L\n    }\n\n    private suspend fun confirmSafeRedirectFromFreshBrowserSurface'''
)
# Navigation learning is finalized from the transaction outcome, not from an event callback.
replace_once(
    service_path,
    '''        if (confirmed) {\n            BrowserCompatibilityStore.recordNavigationConfirmed(transition.browserPackageName)\n        }\n        return confirmed\n''',
    '''        return confirmed\n'''
)
regex_replace_once(
    service_path,
    r'    private fun finishWebsiteTransition\(transition: WebsiteBlockTransitionHandle\) \{.*?\n    \}\n\n    private fun rootStillShowsDetectedBlockedTarget',
    '''    private fun finishWebsiteTransition(\n        transition: WebsiteBlockTransitionHandle,\n        outcome: WebsiteRedirectionCoordinator.Outcome? = null\n    ) {\n        val current = transitionWindowIsCurrent(transition)\n        if (!websiteBlockTransitionGuard.finish(transition.browserPackageName, transition.id)) return\n\n        val redirectConfirmed = transition.safeRedirectConfirmed.isCompleted\n        if (redirectConfirmed) {\n            BrowserCompatibilityStore.recordNavigationConfirmed(transition.browserPackageName)\n        }\n        when (outcome) {\n            WebsiteRedirectionCoordinator.Outcome.FAIL_CLOSED -> {\n                if (!redirectConfirmed) {\n                    BrowserCompatibilityStore.recordRedirectionFailure(transition.browserPackageName)\n                }\n            }\n            WebsiteRedirectionCoordinator.Outcome.ABORTED,\n            WebsiteRedirectionCoordinator.Outcome.REDIRECT_CONFIRMED,\n            WebsiteRedirectionCoordinator.Outcome.STRICT_DESTINATION_CONFIRMED -> Unit\n            null -> {\n                if (current && !redirectConfirmed) {\n                    BrowserCompatibilityStore.recordRedirectionFailure(transition.browserPackageName)\n                }\n            }\n        }\n        BrowserCompatibilityStore.finishRedirection(transition.browserPackageName)\n        if (!current && !transition.handedOff && !transition.destinationRequested) {\n            dismissInstantBlockCurtain(transition.curtainGeneration)\n        }\n    }\n\n    private fun rootStillShowsDetectedBlockedTarget'''
)
# Remove stale comment reference to the deleted intent fallback.
replace_once(
    service_path,
    '            // the guarded restore/retry/intent fallback revalidate what is current.\n',
    '            // the guarded retry path revalidates what is current.\n'
) if '            // the guarded restore/retry/intent fallback revalidate what is current.\n' in read(service_path) else None

# 7) Usage-access monitor teardown: close the pre-dispatch cancellation race and stop it on Robolectric Application teardown.
usage_path = "app/src/main/java/com/focusguard/utils/UsageAccessStateMonitor.kt"
replace_once(
    usage_path,
    '''        scope.launch {\n            try {\n                val granted = PermissionUtils.isUsageAccessEnabled(context)\n''',
    '''        scope.launch {\n            try {\n                currentCoroutineContext().ensureActive()\n                if (!isGenerationActive(generation)) return@launch\n                val granted = PermissionUtils.isUsageAccessEnabled(context)\n'''
)
app_path = "app/src/main/java/com/focusguard/FocusGuardApplication.kt"
replace_once(
    app_path,
    'import kotlinx.coroutines.SupervisorJob\nimport kotlinx.coroutines.launch\n',
    'import kotlinx.coroutines.SupervisorJob\nimport kotlinx.coroutines.cancel\nimport kotlinx.coroutines.launch\n'
)
replace_once(
    app_path,
    '''        }\n    }\n}\n''',
    '''        }\n    }\n\n    /** Robolectric calls this during sandbox teardown; Android production normally does not. */\n    override fun onTerminate() {\n        UsageAccessStateMonitor.stop()\n        AccessibilityStateMonitor.stop(this)\n        applicationScope.cancel()\n        super.onTerminate()\n    }\n}\n'''
)

# 8) Tests: destination alias strictness.
dest_test = "app/src/test/java/com/focusguard/accessibility/website/redirection/WebsiteRedirectDestinationTest.kt"
replace_once(
    dest_test,
    '''    @Test\n    fun `destination rejects non https and user info surfaces`() {\n''',
    '''    @Test\n    fun `www alias must be explicitly accepted for custom destinations`() {\n        val destination = WebsiteRedirectDestination(\n            url = "https://www.example.org",\n            acceptedRootHosts = setOf("www.example.org")\n        )\n        assertThat(destination.matchesSurface("https://www.example.org/")).isTrue()\n        assertThat(destination.matchesSurface("https://example.org/")).isFalse()\n    }\n\n    @Test\n    fun `destination rejects non https and user info surfaces`() {\n'''
)

# Coordinator tests now exercise the executable phase sequence and edge cases.
write(
    "app/src/test/java/com/focusguard/accessibility/website/redirection/WebsiteRedirectionCoordinatorTest.kt",
    '''package com.focusguard.accessibility.website.redirection

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test

class WebsiteRedirectionCoordinatorTest {
    @Test
    fun `execution prepares submits confirms and releases`() = runBlocking {
        val adapter = FakeAdapter()
        val outcome = WebsiteRedirectionCoordinator.execute(normalSession(), adapter)

        assertThat(outcome).isEqualTo(WebsiteRedirectionCoordinator.Outcome.REDIRECT_CONFIRMED)
        assertThat(adapter.prepareAttempts).containsExactly(1)
        assertThat(adapter.submitAttempts).containsExactly(1)
        assertThat(adapter.confirmCalls).isEqualTo(1)
        assertThat(adapter.releaseCalls).isEqualTo(1)
    }

    @Test
    fun `confirmation timeout retries whole same tab transaction once`() = runBlocking {
        val adapter = FakeAdapter(confirmResults = ArrayDeque(listOf(false, true)))
        val outcome = WebsiteRedirectionCoordinator.execute(normalSession(), adapter)

        assertThat(outcome).isEqualTo(WebsiteRedirectionCoordinator.Outcome.REDIRECT_CONFIRMED)
        assertThat(adapter.prepareAttempts).containsExactly(1, 2).inOrder()
        assertThat(adapter.submitAttempts).containsExactly(1, 2).inOrder()
        assertThat(adapter.confirmCalls).isEqualTo(2)
        assertThat(adapter.restoreCalls).isEqualTo(1)
        assertThat(adapter.failClosedCalls).isEqualTo(0)
    }

    @Test
    fun `prepare failure retries without submit`() = runBlocking {
        val adapter = FakeAdapter(prepareResults = ArrayDeque(listOf(false, true)))
        val outcome = WebsiteRedirectionCoordinator.execute(normalSession(), adapter)

        assertThat(outcome).isEqualTo(WebsiteRedirectionCoordinator.Outcome.REDIRECT_CONFIRMED)
        assertThat(adapter.prepareAttempts).containsExactly(1, 2).inOrder()
        assertThat(adapter.submitAttempts).containsExactly(2)
        assertThat(adapter.restoreCalls).isEqualTo(1)
    }

    @Test
    fun `submit failure retries from restored blocked surface`() = runBlocking {
        val adapter = FakeAdapter(submitResults = ArrayDeque(listOf(false, true)))
        val outcome = WebsiteRedirectionCoordinator.execute(normalSession(), adapter)

        assertThat(outcome).isEqualTo(WebsiteRedirectionCoordinator.Outcome.REDIRECT_CONFIRMED)
        assertThat(adapter.submitAttempts).containsExactly(1, 2).inOrder()
        assertThat(adapter.restoreCalls).isEqualTo(1)
    }

    @Test
    fun `restore failure is terminal fail closed`() = runBlocking {
        val adapter = FakeAdapter(
            prepareResults = ArrayDeque(listOf(false)),
            restoreResult = false
        )
        val outcome = WebsiteRedirectionCoordinator.execute(normalSession(), adapter)

        assertThat(outcome).isEqualTo(WebsiteRedirectionCoordinator.Outcome.FAIL_CLOSED)
        assertThat(adapter.releaseCalls).isEqualTo(0)
        assertThat(adapter.failClosedCalls).isEqualTo(1)
    }

    @Test
    fun `loss of curtain ownership aborts without penalizing with fail closed`() = runBlocking {
        val adapter = FakeAdapter(loseOwnershipAfterPrepare = true)
        val outcome = WebsiteRedirectionCoordinator.execute(normalSession(), adapter)

        assertThat(outcome).isEqualTo(WebsiteRedirectionCoordinator.Outcome.ABORTED)
        assertThat(adapter.failClosedCalls).isEqualTo(0)
        assertThat(adapter.releaseCalls).isEqualTo(0)
    }

    @Test
    fun `strict destination failure remains fail closed`() = runBlocking {
        val adapter = FakeAdapter(strictDestinationResult = false)
        val outcome = WebsiteRedirectionCoordinator.execute(strictSession(), adapter)

        assertThat(outcome).isEqualTo(WebsiteRedirectionCoordinator.Outcome.FAIL_CLOSED)
        assertThat(adapter.failClosedCalls).isEqualTo(1)
        assertThat(adapter.releaseCalls).isEqualTo(0)
    }

    @Test
    fun `runtime exception while protected becomes fail closed`() = runBlocking {
        val adapter = FakeAdapter(throwOnSubmit = true)
        val outcome = WebsiteRedirectionCoordinator.execute(normalSession(), adapter)

        assertThat(outcome).isEqualTo(WebsiteRedirectionCoordinator.Outcome.FAIL_CLOSED)
        assertThat(adapter.failClosedCalls).isEqualTo(1)
    }

    @Test(expected = CancellationException::class)
    fun `cancellation is never converted into fail closed`() = runBlocking {
        WebsiteRedirectionCoordinator.execute(
            normalSession(),
            FakeAdapter(throwCancellationOnSubmit = true)
        )
    }

    private fun normalSession() = WebsiteRedirectionCoordinator.Session(strict = false).also { it.begin() }
    private fun strictSession() = WebsiteRedirectionCoordinator.Session(strict = true).also { it.begin() }

    private class FakeAdapter(
        private val prepareResults: ArrayDeque<Boolean> = ArrayDeque(listOf(true, true)),
        private val submitResults: ArrayDeque<Boolean> = ArrayDeque(listOf(true, true)),
        private val confirmResults: ArrayDeque<Boolean> = ArrayDeque(listOf(true, true)),
        private val restoreResult: Boolean = true,
        private val strictDestinationResult: Boolean = true,
        private val loseOwnershipAfterPrepare: Boolean = false,
        private val throwOnSubmit: Boolean = false,
        private val throwCancellationOnSubmit: Boolean = false
    ) : WebsiteRedirectionCoordinator.Adapter {
        val prepareAttempts = mutableListOf<Int>()
        val submitAttempts = mutableListOf<Int>()
        var confirmCalls = 0
        var restoreCalls = 0
        var releaseCalls = 0
        var failClosedCalls = 0
        private var owned = true

        override suspend fun awaitPresentationFrame(): Boolean = true
        override fun ownsProtection(): Boolean = owned

        override suspend fun prepareSameTabRedirect(attemptNumber: Int): Boolean {
            prepareAttempts += attemptNumber
            val result = prepareResults.removeFirstOrNull() ?: false
            if (loseOwnershipAfterPrepare) owned = false
            return result
        }

        override suspend fun submitSameTabRedirect(attemptNumber: Int): Boolean {
            if (throwCancellationOnSubmit) throw CancellationException("cancel")
            if (throwOnSubmit) throw IllegalStateException("boom")
            submitAttempts += attemptNumber
            return submitResults.removeFirstOrNull() ?: false
        }

        override suspend fun restoreBlockedSurfaceForRetry(): Boolean {
            restoreCalls += 1
            return restoreResult
        }

        override suspend fun beforeRetry(nextAttemptNumber: Int) = Unit
        override suspend fun awaitRedirectConfirmation(): Boolean {
            confirmCalls += 1
            return confirmResults.removeFirstOrNull() ?: false
        }

        override suspend fun completeStrictDestination(): Boolean = strictDestinationResult
        override suspend fun releasePresentation() { releaseCalls += 1 }
        override fun failClosed() { failClosedCalls += 1 }
    }
}
'''
)

write(
    "app/src/test/java/com/focusguard/accessibility/website/redirection/WebsiteRedirectionPlanTest.kt",
    '''package com.focusguard.accessibility.website.redirection

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebsiteRedirectionPlanTest {
    @Test
    fun `only one same tab retry is allowed before fail closed`() {
        assertThat(WebsiteRedirectionPlan.MAX_SAME_TAB_ATTEMPTS).isEqualTo(2)
        assertThat(WebsiteRedirectionPlan.canRetry(1)).isTrue()
        assertThat(WebsiteRedirectionPlan.canRetry(2)).isFalse()
    }
}
'''
)

# Navigation tests: policy timing and verified destination-window rebind.
nav_test = "app/src/test/java/com/focusguard/service/WebsiteBlockNavigationTest.kt"
replace_once(
    nav_test,
    '''        assertThat(\n            policy.mayActivateBlockedAddressBar(\n                browserPackage,\n                activeWindowId = 7,\n                phaseStartedAtUptimeMillis = 100L,\n                latestWindowTransitionEventUptimeMillis = 101L\n            )\n        ).isTrue()\n''',
    '''        assertThat(\n            policy.mayActivateBlockedAddressBar(\n                browserPackage,\n                activeWindowId = 7,\n                phaseStartedAtUptimeMillis = 100L,\n                latestWindowTransitionEventUptimeMillis = 100L\n            )\n        ).isTrue()\n        assertThat(\n            policy.mayActivateBlockedAddressBar(\n                browserPackage,\n                activeWindowId = 7,\n                phaseStartedAtUptimeMillis = 100L,\n                latestWindowTransitionEventUptimeMillis = 101L\n            )\n        ).isFalse()\n'''
)
replace_once(
    nav_test,
    '''    @Test\n    fun `text and focus events cannot release the curtain after submit`() {\n''',
    '''    @Test\n    fun `verified destination can rebind one recreated accessibility window`() {\n        val guard = WebsiteBlockTransitionGuard()\n        val transition = guard.tryStart(\n            BRAVE_PACKAGE,\n            transitionId = 23L,\n            destination = WebsiteRedirectionCoordinator.TerminalDestination.REDIRECT,\n            expectedWindowId = 7,\n            inspectionGeneration = 3L,\n            detectionEventUptimeMillis = 100L\n        )!!\n        guard.markSanitizationRequested(BRAVE_PACKAGE, 23L, requestedAtUptimeMillis = 150L)\n\n        assertThat(\n            guard.transitionForDestinationCandidate(\n                BRAVE_PACKAGE,\n                eventUptimeMillis = 151L,\n                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED\n            )\n        ).isSameInstanceAs(transition)\n        assertThat(\n            guard.rebindVerifiedDestinationWindow(\n                BRAVE_PACKAGE,\n                transitionId = 23L,\n                windowId = 8,\n                inspectionGeneration = 4L,\n                eventUptimeMillis = 151L,\n                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED\n            )\n        ).isTrue()\n        assertThat(transition.expectedWindowId).isEqualTo(8)\n        assertThat(transition.inspectionGeneration).isEqualTo(4L)\n        assertThat(guard.confirmRedirect(BRAVE_PACKAGE, 8, 151L)).isTrue()\n        assertThat(\n            guard.rebindVerifiedDestinationWindow(\n                BRAVE_PACKAGE,\n                transitionId = 23L,\n                windowId = 9,\n                inspectionGeneration = 5L,\n                eventUptimeMillis = 152L,\n                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED\n            )\n        ).isFalse()\n    }\n\n    @Test\n    fun `text and focus events cannot release the curtain after submit`() {\n'''
)

# Compatibility-store test: accepted submit is provisional until navigation is confirmed.
compat_test = "app/src/test/java/com/focusguard/accessibility/website/compatibility/BrowserCompatibilityStorePolicyTest.kt"
replace_once(
    compat_test,
    '''    @Test\n    fun `weak activation evidence alone never promotes browser`() {\n''',
    '''    @Test\n    fun `submit preference is learned only after confirmed navigation`() {\n        val packageName = "example.deferred.submit.browser"\n        BrowserCompatibilityStore.recordWriteSuccess(\n            packageName,\n            "$packageName:id/url_bar",\n            BrowserWriteMethod.SET_TEXT,\n            "https://www.google.com"\n        )\n        BrowserCompatibilityStore.recordSubmitAccepted(\n            packageName,\n            "$packageName:id/url_bar",\n            BrowserSubmitMethod.IME_ENTER\n        )\n\n        assertThat(BrowserCompatibilityStore.preferredSubmitMethod(packageName)).isNull()\n        BrowserCompatibilityStore.recordNavigationConfirmed(packageName)\n        assertThat(BrowserCompatibilityStore.preferredSubmitMethod(packageName))\n            .isEqualTo(BrowserSubmitMethod.IME_ENTER)\n        BrowserCompatibilityStore.finishRedirection(packageName)\n    }\n\n    @Test\n    fun `weak activation evidence alone never promotes browser`() {\n'''
)

# Firefox test naming is destination-generic now.
firefox_test = "app/src/test/java/com/focusguard/service/FirefoxWebsiteRedirectReliabilityTest.kt"
text = read(firefox_test).replace(
    'fun `stable current Google surface can confirm a missed Firefox navigation event`()',
    'fun `stable current redirect surface can confirm a missed Firefox navigation event`()'
)
write(firefox_test, text)

# 9) Documentation and agent plan reflect the hardened transaction semantics.
plan_path = ".agents/website_blocking_separation_plan.md"
plan = read(plan_path)
if "## Hardening pós-revisão" not in plan:
    plan += '''\n\n## Hardening pós-revisão\n\n- O coordinator executa explicitamente `prepare -> submit -> confirmação -> restore/retry`; não existe mais uma lista de fases apenas documental.\n- A confirmação tem um único timeout por tentativa e uma falha de confirmação pode consumir o único retry same-tab.\n- `Outcome` é a fonte explícita da telemetria de sucesso/falha/abort; supersessão não penaliza compatibilidade.\n- Métodos de submit são candidatos transitórios e só viram preferência após navegação confirmada.\n- Navegadores que recriam a janela de Accessibility podem fazer um único rebind, exclusivamente após dupla prova da superfície segura configurada.\n- O editor é revalidado imediatamente antes da escrita e a política rejeita transições de janela posteriores ao início da fase.\n- Exceções de runtime sob cortina viram fail-closed; cancelamento de coroutine continua sendo propagado.\n- Aliases `www` de destinos customizados são explícitos; não existe colapso global de host.\n- `UsageAccessStateMonitor` fecha a corrida pré-dispatch e o `Application` encerra monitores no teardown Robolectric.\n'''
    write(plan_path, plan)

map_path = ".agents/project_map.md"
project_map = read(map_path)
project_map = project_map.replace(
    '- `accessibility/website/redirection/WebsiteRedirectionCoordinator`: dono da sequência de redirecionamento após a apresentação; controla tentativas na mesma aba, retry limitado, confirmação, terminal estrito e fail-closed. `WebsiteBlockTransitionHandle`/`WebsiteBlockTransitionGuard` mantêm o estado da transação no mesmo módulo, e `WebsiteTabNeutralizationPolicy` fixa as ações à janela original.\n',
    '- `accessibility/website/redirection/WebsiteRedirectionCoordinator`: dono executável de `prepare -> submit -> confirmar -> restore/retry`, terminal estrito e fail-closed. `Outcome` alimenta telemetria; `WebsiteBlockTransitionGuard` permite no máximo um rebind de janela somente após prova estável do destino, e `WebsiteTabNeutralizationPolicy` fixa edição/envio à transação original.\n'
)
write(map_path, project_map)

doc_path = "docs/WEBSITE_BLOCKING_ARCHITECTURE.md"
doc = read(doc_path)
if "### Hardening de confirmação e aprendizagem" not in doc:
    doc += '''\n\n### Hardening de confirmação e aprendizagem\n\n- Ação de submit aceita não é navegação confirmada: a preferência de submit só é persistida depois da confirmação positiva do destino.\n- Cada tentativa tem exatamente um timeout de confirmação, controlado pelo `WebsiteRedirectionCoordinator`; timeout pode restaurar a superfície e consumir o retry same-tab.\n- `Outcome` diferencia sucesso, fail-closed e abort por perda de ownership para evitar penalizar o navegador por supersessão.\n- Se a navegação same-tab recriar a janela de Accessibility, o guard aceita no máximo um rebind e apenas depois de duas leituras estáveis da superfície exata de `WebsiteRedirectDestination`, no mesmo pacote e após o submit.\n- Antes da escrita, o editor precisa continuar pertencendo à regra bloqueada original ou já conter o próprio destino seguro.\n- Runtime exception enquanto a cortina ainda pertence à transação termina em fail-closed; cancelamento estruturado não é convertido em falha funcional.\n'''
    write(doc_path, doc)

# Sanity checks: old pseudo-plan and old monolithic request hook must be gone.
all_kt = "\n".join(p.read_text(encoding="utf-8") for p in (ROOT / "app/src").rglob("*.kt"))
for forbidden in ("WebsiteRedirectionPhase", "requestSameTabRedirect(attemptNumber"):
    if forbidden in all_kt:
        raise RuntimeError(f"stale symbol remains after hardening: {forbidden}")

print("website pipeline hardening applied")
