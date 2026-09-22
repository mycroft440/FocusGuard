package com.focusguard.accessibility.website.redirection

import android.view.accessibility.AccessibilityEvent
import com.focusguard.accessibility.website.BrowserSurfaceIdentityRegistry
import com.focusguard.accessibility.website.diagnostics.WebsiteBlockingDiagnostics
import kotlinx.coroutines.CompletableDeferred

/** Mutable state for one browser-bound website redirection transaction. */
internal data class WebsiteBlockTransitionHandle(
    val id: Long,
    val browserPackageName: String,
    val destination: WebsiteRedirectionCoordinator.TerminalDestination,
    @Volatile internal var expectedWindowId: Int,
    @Volatile internal var inspectionGeneration: Long,
    @Volatile internal var inspectionSurfaceEpoch: Long = 0L,
    @Volatile internal var pendingSurfaceWindowId: Int = -1,
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
 * The original browser surface remains authoritative while editing. If Android
 * reuses that Accessibility window for another document/tab, the binding is made
 * temporarily invalid while the curtain stays owned by this transition. A window
 * (including the same numeric window id) may be rebound exactly once, and only by
 * the caller after independently proving the stable configured destination.
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
        val surfaceIdentity = BrowserSurfaceIdentityRegistry.current(
            browserPackageName,
            expectedWindowId
        )?.takeIf { it.generation == inspectionGeneration }
        return WebsiteBlockTransitionHandle(
            id = transitionId,
            browserPackageName = browserPackageName,
            destination = destination,
            expectedWindowId = expectedWindowId,
            inspectionGeneration = inspectionGeneration,
            inspectionSurfaceEpoch = surfaceIdentity?.surfaceEpoch ?: 0L,
            blockedCandidate = blockedCandidate,
            blockedRules = blockedRules,
            detectionEventUptimeMillis = detectionEventUptimeMillis
        ).also { transition ->
            activeTransitions[browserPackageName] = transition
            WebsiteBlockingDiagnostics.beginTransition(
                browserPackageName = browserPackageName,
                transitionId = transitionId,
                expectedWindowId = expectedWindowId,
                inspectionGeneration = inspectionGeneration,
                blockedCandidate = blockedCandidate,
                blockedRules = blockedRules,
                strictDestination = destination ==
                    WebsiteRedirectionCoordinator.TerminalDestination.POMODORO
            )
        }
    }

    @Synchronized
    fun isActive(browserPackageName: String): Boolean = browserPackageName in activeTransitions

    @Synchronized
    fun activeTransition(browserPackageName: String): WebsiteBlockTransitionHandle? =
        activeTransitions[browserPackageName]?.also(::invalidateChangedSurfaceBinding)

    @Synchronized
    fun activeBrowserPackages(): Set<String> = activeTransitions.keys.toSet()

    @Synchronized
    fun markSanitizationRequested(
        browserPackageName: String,
        transitionId: Long,
        requestedAtUptimeMillis: Long
    ): Boolean {
        val transition = activeTransition(browserPackageName) ?: return false
        if (transition.id != transitionId || transition.destinationRequested ||
            transition.expectedWindowId < 0 ||
            requestedAtUptimeMillis < transition.detectionEventUptimeMillis
        ) return false
        transition.sanitizationRequested = true
        // Each retry owns a fresh temporal boundary. Evidence from an older submit
        // must never certify a later attempt.
        transition.sanitizationRequestedAtUptimeMillis = requestedAtUptimeMillis
        WebsiteBlockingDiagnostics.markSubmitAccepted(browserPackageName, transitionId)
        return true
    }

    @Synchronized
    fun markDestinationRequested(
        browserPackageName: String,
        transitionId: Long,
        requestedAtUptimeMillis: Long
    ): Boolean {
        val transition = activeTransition(browserPackageName) ?: return false
        if (transition.id != transitionId ||
            transition.expectedWindowId < 0 ||
            !transition.safeRedirectConfirmed.isCompleted ||
            requestedAtUptimeMillis < transition.sanitizationRequestedAtUptimeMillis
        ) return false
        transition.destinationRequested = true
        transition.destinationRequestedAtUptimeMillis = requestedAtUptimeMillis
        WebsiteBlockingDiagnostics.markDestinationRequested(browserPackageName, transitionId)
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
        WebsiteBlockingDiagnostics.markCurtain(browserPackageName, transitionId, curtainGeneration)
        return true
    }

    @Synchronized
    fun confirmRedirect(
        browserPackageName: String,
        windowId: Int,
        eventUptimeMillis: Long
    ): Boolean {
        val transition = activeTransition(browserPackageName) ?: return false
        if (!transition.sanitizationRequested ||
            transition.expectedWindowId < 0 ||
            transition.expectedWindowId != windowId ||
            eventUptimeMillis < transition.sanitizationRequestedAtUptimeMillis ||
            transition.latestNavigationEvidenceEventUptimeMillis < eventUptimeMillis
        ) return false
        transition.latestObservedEventUptimeMillis = maxOf(
            transition.latestObservedEventUptimeMillis,
            eventUptimeMillis
        )
        val confirmed = transition.safeRedirectConfirmed.complete(Unit)
        if (confirmed || transition.safeRedirectConfirmed.isCompleted) {
            WebsiteBlockingDiagnostics.markRedirectConfirmed(
                browserPackageName,
                transition.id
            )
        }
        return confirmed
    }

    @Synchronized
    fun confirmRedirectFromStableCurrentSurface(
        browserPackageName: String,
        windowId: Int,
        observedAtUptimeMillis: Long
    ): Boolean {
        val transition = activeTransition(browserPackageName) ?: return false
        if (!transition.sanitizationRequested ||
            transition.expectedWindowId < 0 ||
            transition.expectedWindowId != windowId ||
            observedAtUptimeMillis < transition.sanitizationRequestedAtUptimeMillis
        ) return false
        transition.latestObservedEventUptimeMillis = maxOf(
            transition.latestObservedEventUptimeMillis,
            observedAtUptimeMillis
        )
        val confirmed = transition.safeRedirectConfirmed.complete(Unit) ||
            transition.safeRedirectConfirmed.isCompleted
        if (confirmed) {
            WebsiteBlockingDiagnostics.markRedirectConfirmed(
                browserPackageName,
                transition.id
            )
        }
        return confirmed
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
     * A surface-epoch mismatch deliberately leaves [expectedWindowId] invalid so the
     * existing verified-rebind path also runs when Android reused the same window id.
     */
    @Synchronized
    fun transitionForDestinationCandidate(
        browserPackageName: String,
        eventUptimeMillis: Long,
        eventType: Int
    ): WebsiteBlockTransitionHandle? {
        val transition = activeTransition(browserPackageName) ?: return null
        return transition.takeIf {
            it.sanitizationRequested &&
                eventUptimeMillis >= it.sanitizationRequestedAtUptimeMillis &&
                isRedirectNavigationEvidenceEvent(eventType)
        }
    }

    /**
     * One-time rebind after the caller independently inspected the exact stable
     * configured destination. This also handles a new surface epoch that reused the
     * same Android window id: the stale binding is first invalidated to -1, making
     * the caller enter this verified path exactly as it would for a new window.
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
        invalidateChangedSurfaceBinding(transition)
        if (transition.id != transitionId ||
            !transition.sanitizationRequested ||
            transition.destinationRequested ||
            windowId < 0 || inspectionGeneration <= 0L ||
            eventUptimeMillis < transition.sanitizationRequestedAtUptimeMillis ||
            !isRedirectNavigationEvidenceEvent(eventType)
        ) return false

        val surfaceIdentity = BrowserSurfaceIdentityRegistry.current(
            browserPackageName,
            windowId
        ) ?: return false
        if (surfaceIdentity.generation != inspectionGeneration) return false

        val alreadyBoundToExactSurface =
            transition.expectedWindowId == windowId &&
                transition.inspectionGeneration == inspectionGeneration &&
                transition.inspectionSurfaceEpoch == surfaceIdentity.surfaceEpoch
        if (alreadyBoundToExactSurface) return true
        if (transition.verifiedDestinationWindowRebound) return false

        transition.expectedWindowId = windowId
        transition.pendingSurfaceWindowId = INVALID_BROWSER_WINDOW_ID
        transition.inspectionGeneration = inspectionGeneration
        transition.inspectionSurfaceEpoch = surfaceIdentity.surfaceEpoch
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
        WebsiteBlockingDiagnostics.markWindowRebound(
            browserPackageName,
            transitionId,
            windowId
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
        val transition = activeTransition(browserPackageName) ?: return
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
        val navigationEvidence = transition.sanitizationRequested &&
            isRedirectNavigationEvidenceEvent(eventType) &&
            eventUptimeMillis >= transition.sanitizationRequestedAtUptimeMillis
        if (navigationEvidence) {
            transition.latestNavigationEvidenceEventUptimeMillis = maxOf(
                transition.latestNavigationEvidenceEventUptimeMillis,
                eventUptimeMillis
            )
        }
        WebsiteBlockingDiagnostics.observeBrowserEvent(
            browserPackageName = browserPackageName,
            transitionId = transition.id,
            eventUptimeMillis = eventUptimeMillis,
            eventType = eventType,
            navigationEvidence = navigationEvidence
        )
    }

    @Synchronized
    fun confirmPomodoro(curtainGeneration: Long, readyAtUptimeMillis: Long): Boolean {
        val transition = activeTransitions.values.singleOrNull {
            it.destinationRequested &&
                it.destination == WebsiteRedirectionCoordinator.TerminalDestination.POMODORO &&
                it.curtainGeneration == curtainGeneration &&
                readyAtUptimeMillis >= it.destinationRequestedAtUptimeMillis
        } ?: return false
        val confirmed = transition.destinationConfirmed.complete(Unit)
        if (confirmed || transition.destinationConfirmed.isCompleted) {
            WebsiteBlockingDiagnostics.markDestinationConfirmed(
                transition.browserPackageName,
                transition.id
            )
        }
        return confirmed
    }

    @Synchronized
    fun finish(browserPackageName: String, transitionId: Long): Boolean {
        if (activeTransitions[browserPackageName]?.id != transitionId) return false
        WebsiteBlockingDiagnostics.finishTransition(browserPackageName, transitionId)
        activeTransitions.remove(browserPackageName)
        return true
    }

    @Synchronized
    fun clear() {
        activeTransitions.values.forEach { transition ->
            WebsiteBlockingDiagnostics.discardTransition(
                transition.browserPackageName,
                transition.id
            )
        }
        activeTransitions.clear()
    }

    /**
     * Invalidates only the browser-surface binding, not transition ownership. The
     * opaque curtain therefore remains fail-closed while safe-destination inspection
     * decides whether this surface may be rebound.
     */
    private fun invalidateChangedSurfaceBinding(transition: WebsiteBlockTransitionHandle) {
        if (transition.inspectionSurfaceEpoch <= 0L) return
        val boundWindowId = when {
            transition.expectedWindowId >= 0 -> transition.expectedWindowId
            transition.pendingSurfaceWindowId >= 0 -> transition.pendingSurfaceWindowId
            else -> return
        }
        val current = BrowserSurfaceIdentityRegistry.current(
            transition.browserPackageName,
            boundWindowId
        )
        val stillSameSurface = current != null &&
            current.generation == transition.inspectionGeneration &&
            current.surfaceEpoch == transition.inspectionSurfaceEpoch
        if (stillSameSurface || transition.expectedWindowId < 0) return

        transition.pendingSurfaceWindowId = transition.expectedWindowId
        transition.expectedWindowId = INVALID_BROWSER_WINDOW_ID
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
