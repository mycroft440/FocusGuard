package com.focusguard.accessibility.website.redirection

import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CompletableDeferred

/**
 * Mutable state for one browser-bound website redirection transaction.
 *
 * The state lives with the redirection subsystem instead of the AccessibilityService.
 * Android tree/window operations remain in the service adapter; this object only
 * records transaction identity, confirmation evidence and terminal handoff state.
 */
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
    @Volatile internal var curtainGeneration: Long = 0L
)

/**
 * Owns active transaction registration and confirmation ordering.
 *
 * Deliberately contains no ACTION_VIEW fallback or tab-close/rebind state: blocking
 * is same-tab only, and an unproven browser window can never inherit a transition.
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
        transition.sanitizationRequestedAtUptimeMillis = requestedAtUptimeMillis
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

    @Synchronized
    fun transitionForConfirmation(
        browserPackageName: String,
        windowId: Int,
        eventUptimeMillis: Long,
        eventType: Int
    ): WebsiteBlockTransitionHandle? {
        val transition = activeTransitions[browserPackageName] ?: return null
        return transition.takeIf {
            it.sanitizationRequested &&
                it.expectedWindowId == windowId &&
                eventUptimeMillis >= it.sanitizationRequestedAtUptimeMillis &&
                isRedirectNavigationEvidenceEvent(eventType)
        }
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
