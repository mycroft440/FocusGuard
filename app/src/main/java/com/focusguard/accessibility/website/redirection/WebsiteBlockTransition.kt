package com.focusguard.accessibility.website.redirection

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
        // Each retry owns a fresh temporal boundary. Evidence from an older submit
        // must never certify a later attempt.
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
