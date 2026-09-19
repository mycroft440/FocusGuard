package com.focusguard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.focusguard.accessibility.website.identification.WebsiteIdentificationEngine
import com.focusguard.accessibility.website.identification.WebsiteIdentificationRecovery
import com.focusguard.accessibility.website.identification.WebsiteIdentificationStatus
import com.focusguard.accessibility.website.compatibility.BrowserCompatibilityStore
import com.focusguard.accessibility.website.compatibility.BrowserActivationMethod
import com.focusguard.accessibility.website.compatibility.BrowserWriteMethod
import com.focusguard.accessibility.website.compatibility.BrowserSubmitMethod
import com.focusguard.accessibility.website.redirection.AddressBarRedirectionActions
import com.focusguard.accessibility.website.redirection.ClipboardPasteFallback
import com.focusguard.MainActivity
import com.focusguard.R
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.data.PredefinedWebsites
import com.focusguard.database.AppDatabase
import com.focusguard.database.BlockSession
import com.focusguard.focusmode.FocusModeKioskController
import com.focusguard.focusmode.FocusModePolicy
import com.focusguard.focusmode.FocusModeStore
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.manager.StrictPomodoroLock
import com.focusguard.security.AccessibilitySettingsPolicy
import com.focusguard.security.AuthenticatedRemovalWindow
import com.focusguard.security.AuthManager
import com.focusguard.security.CurtainDestinationReadyCoordinator
import com.focusguard.security.CurtainSafeWindowPolicy
import com.focusguard.security.DeviceAdminActivationWindow
import com.focusguard.security.ImmediateInterceptionPolicy
import com.focusguard.security.ImmediateInterceptionPolicy.DirectDecision
import com.focusguard.security.ImmediateInterceptionPolicy.SettingsSurface
import com.focusguard.security.LauncherIndexRefreshPolicy
import com.focusguard.security.ManagedSelfProtectionPolicy
import com.focusguard.security.PasswordTargetAccessGrant
import com.focusguard.security.ProtectedSettingsResetWindow
import com.focusguard.security.SettingsInterceptionPolicy
import com.focusguard.security.SelfProtectionStateStore
import com.focusguard.security.UsageAccessPausePolicy
import com.focusguard.ui.BlockNoticeActivity
import com.focusguard.ui.MasterRemovalActivity
import com.focusguard.ui.PomodoroLockActivity
import com.focusguard.utils.AppUsageLimitActivationUsage
import com.focusguard.utils.BrowserInspectionSessionStore
import com.focusguard.utils.BrowserSurfaceInspector
import com.focusguard.utils.BrowserUiCapabilityPolicy
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.utils.PermissionUtils
import com.focusguard.utils.UsageLimitForegroundPolicy
import com.focusguard.utils.WebsiteBlocker
import com.focusguard.utils.WebsiteObservabilityPolicy
import com.focusguard.utils.WebsiteUsageLimitPolicy
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

@AndroidEntryPoint
class BlockingAccessibilityService : AccessibilityService() {

    internal enum class InstantCurtainFailsafeDecision {
        NO_ACTION,
        HIDE,
        EVACUATE_THEN_HIDE
    }

    internal enum class CurtainLaunchFailureDecision {
        NO_ACTION,
        EVACUATE_THEN_HIDE
    }

    internal enum class WebsiteTransitionDestination {
        GOOGLE,
        POMODORO
    }

    internal enum class WebsiteTransitionAction {
        SHOW_CURTAIN,
        NEUTRALIZE_BLOCKED_TAB,
        OPEN_POMODORO,
        HIDE_CURTAIN
    }

    internal enum class WebsiteSanitizationDecision {
        SUBMIT_ADDRESS_BAR,
        AWAIT_GOOGLE_CONFIRMATION,
        ABORT_REDIRECT
    }

    internal enum class WebsiteRestoreDecision {
        BACK_TO_BLOCKED_SURFACE,
        RETRY_CURRENT_BLOCKED_SURFACE,
        KEEP_CURRENT_SURFACE
    }

    internal enum class WebsiteCloseFollowUp {
        REWRITE_SAME_BLOCKED_TAB,
        REQUEST_SAFE_GOOGLE_AFTER_CONFIRMED_CLOSE,
        EVACUATE_WITHOUT_REWRITE
    }

    internal class WebsiteBlockTransitionStateMachine(private val strict: Boolean) {
        private enum class State { NEW, SANITIZATION_PENDING, POMODORO_REQUESTED, FINISHED }

        private var state = State.NEW

        fun begin(): List<WebsiteTransitionAction> {
            check(state == State.NEW)
            state = State.SANITIZATION_PENDING
            return listOf(
                WebsiteTransitionAction.SHOW_CURTAIN,
                WebsiteTransitionAction.NEUTRALIZE_BLOCKED_TAB
            )
        }

        fun afterGoogleSanitized(): WebsiteTransitionAction {
            check(state == State.SANITIZATION_PENDING)
            state = if (strict) State.POMODORO_REQUESTED else State.FINISHED
            return if (strict) WebsiteTransitionAction.OPEN_POMODORO
            else WebsiteTransitionAction.HIDE_CURTAIN
        }

        fun onPomodoroConfirmed(): WebsiteTransitionAction {
            check(state == State.POMODORO_REQUESTED)
            state = State.FINISHED
            return WebsiteTransitionAction.HIDE_CURTAIN
        }

        fun onFailureOrTimeout(): WebsiteTransitionAction {
            check(state != State.FINISHED)
            state = State.FINISHED
            // Failure is terminal for this transition, but the caller must not reveal
            // an unsafe browser surface. It routes to FocusGuard's generic fail-closed
            // notice whenever same-tab sanitization or destination confirmation fails.
            return WebsiteTransitionAction.HIDE_CURTAIN
        }
    }

    internal data class WebsiteBlockTransitionHandle(
        val id: Long,
        val browserPackageName: String,
        val destination: WebsiteTransitionDestination,
        @Volatile internal var expectedWindowId: Int,
        @Volatile internal var inspectionGeneration: Long,
        val blockedCandidate: String?,
        val blockedRules: Set<String>,
        val detectionEventUptimeMillis: Long,
        internal val safeGoogleConfirmed: CompletableDeferred<Unit> = CompletableDeferred(),
        internal val destinationConfirmed: CompletableDeferred<Unit> = CompletableDeferred(),
        internal var sanitizationRequested: Boolean = false,
        internal var sanitizationRequestedAtUptimeMillis: Long = Long.MAX_VALUE,
        internal var externalRedirectRequested: Boolean = false,
        internal var externalRedirectRequestedAtUptimeMillis: Long = Long.MAX_VALUE,
        internal var externalRedirectWindowRebound: Boolean = false,
        internal var destinationRequested: Boolean = false,
        internal var handedOff: Boolean = false,
        internal var destinationRequestedAtUptimeMillis: Long = Long.MAX_VALUE,
        @Volatile internal var latestObservedEventUptimeMillis: Long = detectionEventUptimeMillis,
        @Volatile internal var latestWindowTransitionEventUptimeMillis: Long = detectionEventUptimeMillis,
        internal var latestSurfaceMutationEventUptimeMillis: Long = 0L,
        internal var latestNavigationEvidenceEventUptimeMillis: Long = 0L,
        @Volatile internal var activatedAddressViewId: String? = null,
        @Volatile internal var editorAddressViewId: String? = null,
        internal var closeClickedAtUptimeMillis: Long = 0L,
        internal var closeConfirmed: Boolean = false,
        @Volatile internal var curtainGeneration: Long = 0L
    )

    /** Keeps same-tab address-bar actions bound to the detected browser window. */
    internal class WebsiteTabNeutralizationPolicy(
        private val browserPackageName: String,
        private val expectedWindowId: Int
    ) {
        private enum class State {
            BLOCKED_TAB,
            SAFE_ADDRESS_SET,
            REDIRECT_REQUESTED
        }

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
                latestWindowTransitionEventUptimeMillis =
                    latestWindowTransitionEventUptimeMillis
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
        ): Boolean =
            state == State.SAFE_ADDRESS_SET &&
                activePackageName == browserPackageName &&
                activeWindowId == expectedWindowId

        fun markRedirectRequested() {
            check(state == State.SAFE_ADDRESS_SET)
            state = State.REDIRECT_REQUESTED
        }
    }

    internal class WebsiteBlockTransitionGuard {
        private val activeTransitions = mutableMapOf<String, WebsiteBlockTransitionHandle>()

        @Synchronized
        fun tryStart(
            browserPackageName: String,
            transitionId: Long,
            destination: WebsiteTransitionDestination,
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
        fun isActive(browserPackageName: String): Boolean =
            browserPackageName in activeTransitions

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
        fun markExternalRedirectRequested(
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
            transition.externalRedirectRequested = true
            transition.externalRedirectRequestedAtUptimeMillis = requestedAtUptimeMillis
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
                !transition.safeGoogleConfirmed.isCompleted ||
                requestedAtUptimeMillis < transition.sanitizationRequestedAtUptimeMillis
            ) {
                return false
            }
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
        fun markCloseClicked(
            browserPackageName: String,
            transitionId: Long,
            clickedAtUptimeMillis: Long
        ): Boolean {
            val transition = activeTransitions[browserPackageName] ?: return false
            if (transition.id != transitionId || transition.destinationRequested ||
                clickedAtUptimeMillis < transition.detectionEventUptimeMillis
            ) return false
            transition.closeClickedAtUptimeMillis = clickedAtUptimeMillis
            return true
        }

        @Synchronized
        fun markCloseConfirmed(
            browserPackageName: String,
            transitionId: Long,
            observedAtUptimeMillis: Long
        ): Boolean {
            val transition = activeTransitions[browserPackageName] ?: return false
            if (transition.id != transitionId ||
                transition.closeClickedAtUptimeMillis <= 0L ||
                observedAtUptimeMillis < transition.closeClickedAtUptimeMillis
            ) return false
            transition.closeConfirmed = true
            return true
        }

        @Synchronized
        fun confirmGoogle(
            browserPackageName: String,
            windowId: Int,
            eventUptimeMillis: Long
        ): Boolean {
            val transition = activeTransitions[browserPackageName] ?: return false
            // ACTION_VIEW can open Google in a fresh tab/window while the blocked
            // tab remains intact. A safe destination therefore cannot prove
            // neutralization after an external fallback unless the original tab
            // was independently confirmed closed first.
            if (transition.externalRedirectRequested && !transition.closeConfirmed) return false
            if (!transition.sanitizationRequested ||
                transition.expectedWindowId != windowId ||
                eventUptimeMillis < transition.sanitizationRequestedAtUptimeMillis ||
                transition.latestNavigationEvidenceEventUptimeMillis < eventUptimeMillis
            ) return false
            transition.latestObservedEventUptimeMillis = maxOf(
                transition.latestObservedEventUptimeMillis,
                eventUptimeMillis
            )
            return transition.safeGoogleConfirmed.complete(Unit)
        }

        /**
         * A fresh, stable browser root is itself navigation evidence when the exact
         * safe Google root URL is visible as WEB_CONTENT and no address editor is
         * focused. This covers Fenix when submission succeeds but its mutation event
         * is delayed or lost.
         */
        @Synchronized
        fun confirmGoogleFromStableCurrentSurface(
            browserPackageName: String,
            windowId: Int,
            observedAtUptimeMillis: Long
        ): Boolean {
            val transition = activeTransitions[browserPackageName] ?: return false
            if (transition.externalRedirectRequested ||
                !transition.sanitizationRequested ||
                transition.expectedWindowId != windowId ||
                observedAtUptimeMillis < transition.sanitizationRequestedAtUptimeMillis
            ) return false
            transition.latestObservedEventUptimeMillis = maxOf(
                transition.latestObservedEventUptimeMillis,
                observedAtUptimeMillis
            )
            return transition.safeGoogleConfirmed.complete(Unit) ||
                transition.safeGoogleConfirmed.isCompleted
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
                    (it.expectedWindowId == windowId ||
                        it.closeConfirmed ||
                        it.externalRedirectRequested) &&
                    eventUptimeMillis >= it.sanitizationRequestedAtUptimeMillis &&
                    isGoogleNavigationEvidenceEvent(eventType)
            }
        }

        @Synchronized
        fun rebindPostCloseGoogleWindow(
            browserPackageName: String,
            transitionId: Long,
            windowId: Int,
            eventUptimeMillis: Long
        ): Boolean {
            val transition = activeTransitions[browserPackageName] ?: return false
            if (transition.id != transitionId ||
                (!transition.closeConfirmed && !transition.externalRedirectRequested) ||
                !transition.sanitizationRequested || windowId < 0 ||
                eventUptimeMillis < transition.sanitizationRequestedAtUptimeMillis
            ) return false
            transition.expectedWindowId = windowId
            return true
        }

        @Synchronized
        fun rebindExternalRedirectWindow(
            browserPackageName: String,
            transitionId: Long,
            windowId: Int,
            inspectionGeneration: Long,
            eventUptimeMillis: Long
        ): Boolean {
            val transition = activeTransitions[browserPackageName] ?: return false
            if (transition.id != transitionId ||
                !transition.externalRedirectRequested ||
                transition.externalRedirectWindowRebound ||
                !transition.sanitizationRequested ||
                windowId < 0 || inspectionGeneration <= 0L ||
                eventUptimeMillis < transition.externalRedirectRequestedAtUptimeMillis
            ) return false
            transition.expectedWindowId = windowId
            transition.inspectionGeneration = inspectionGeneration
            transition.externalRedirectWindowRebound = true
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
            val postCloseCandidate = transition.closeClickedAtUptimeMillis > 0L &&
                eventUptimeMillis >= transition.closeClickedAtUptimeMillis
            val externalRedirectCandidate = transition.externalRedirectRequested &&
                eventUptimeMillis >= transition.externalRedirectRequestedAtUptimeMillis
            if (transition.expectedWindowId != windowId &&
                !postCloseCandidate &&
                !externalRedirectCandidate &&
                !(transition.closeConfirmed && transition.sanitizationRequested)
            ) return
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
            if (isGoogleNavigationEvidenceEvent(eventType)) {
                transition.latestSurfaceMutationEventUptimeMillis = maxOf(
                    transition.latestSurfaceMutationEventUptimeMillis,
                    eventUptimeMillis
                )
            }
            if (transition.sanitizationRequested &&
                isGoogleNavigationEvidenceEvent(eventType) &&
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
                    it.destination == WebsiteTransitionDestination.POMODORO &&
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
    }

    @Inject lateinit var authManager: AuthManager

    private lateinit var database: AppDatabase
    private lateinit var sessionManager: BlockingSessionManager
    private lateinit var deviceOwnerManager: DeviceOwnerManager

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val inputEventFilter = AccessibilityInputEventFilter()
    private val isRefreshing = AtomicBoolean(false)
    private val refreshRequested = AtomicBoolean(false)
    private val isRefreshingLauncherIndex = AtomicBoolean(false)
    private val launcherIndexRefreshRequested = AtomicBoolean(false)
    private val lastSlowCallbackLogElapsed = AtomicLong(0L)
    private val browserInspectionCoordinator = BrowserInspectionCoordinator()
    private val browserRecoveryCoordinator = BrowserRecoveryCoordinator()
    private val websiteTreeWorker = WebsiteTreeWorker()

    @Volatile private var blockedAppsSet: Set<String> = emptySet()
    @Volatile private var blockedWebsitesDomainSet: Set<String> = emptySet()
    @Volatile private var passwordWebsiteDomainSet: Set<String> = emptySet()
    @Volatile private var strongerWebsiteDomainSet: Set<String> = emptySet()
    @Volatile private var blockedWebsiteAppDomains: Map<String, String> = emptyMap()
    @Volatile private var limitedWebsiteDomains: Set<String> = emptySet()
    @Volatile private var hardLimitedWebsiteDomains: Set<String> = emptySet()
    @Volatile private var limitedWebsiteAppDomains: Map<String, String> = emptyMap()
    @Volatile private var isBlockingSessionActive = false
    @Volatile private var isPomodoroStrictActive = false
    @Volatile private var focusModeSessionActive = false
    @Volatile private var focusModeFallbackActive = false
    @Volatile private var focusModeBlockedAppsSet: Set<String> = emptySet()
    @Volatile private var focusModeAllowedAppsSet: Set<String> = emptySet()
    @Volatile private var activeAppLimitsByPackage:
        Map<String, com.focusguard.database.AppUsageLimit> = emptyMap()
    @Volatile private var hasActiveAppLimits = false
    @Volatile private var lastEnforcementFingerprint: String? = null

    private var lastLoadTime = 0L
    private var lastBrowserCheck = 0L
    private var lastToastTime = 0L
    private var defaultLauncherPackage: String? = null
    @Volatile private var launcherLabelIndex =
        ImmediateInterceptionPolicy.buildLauncherLabelIndex(emptyList())
    @Volatile private var lastLauncherIndexRefreshRequestElapsed = 0L
    @Volatile private var hasSuccessfulLauncherIndexSnapshot = false
    private var usageStatsManager: UsageStatsManager? = null
    private var powerManager: PowerManager? = null
    private var windowManager: WindowManager? = null
    private var protectedPowerMenuController: ProtectedPowerMenuController? = null
    private var accessibilityServiceConnected = false
    @Volatile private var foregroundPackageName: String? = null
    @Volatile private var deviceOwnerActiveCached = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var instantBlockCurtain: View? = null
    private var instantBlockCurtainMessage: TextView? = null
    private var instantBlockCurtainLayoutParams: WindowManager.LayoutParams? = null
    @Volatile private var instantBlockCurtainAttached = false
    @Volatile private var instantBlockCurtainVisible = false
    private var instantBlockCurtainMode: CurtainMode? = null
    @Volatile private var instantBlockCurtainGeneration = 0L
    private var awaitingSafeSurfaceGeneration = 0L
    private val instantBlockCurtainGenerationCounter = AtomicLong(0L)
    private var failsafeEvacuationGeneration = 0L
    private var pendingReadyWindowValidationGeneration = 0L
    private val instantCurtainFailsafe = Runnable { handleInstantCurtainFailsafe() }
    private val instantCurtainFailsafeRelease = Runnable {
        val generation = failsafeEvacuationGeneration
        if (generation <= 0L ||
            generation != instantBlockCurtainGeneration
        ) return@Runnable
        completeCurtainFailsafeAfterEvacuation(generation)
    }
    private val readyWindowValidation = Runnable { validateReadyDestinationWindows() }
    private val protectionCurtainDismiss = Runnable { handleTimedProtectionCurtainDismiss() }
    @Volatile private var protectionActionUntilElapsed = 0L

    private val websiteTrackingLock = Any()
    @Volatile private var trackedDomain: String? = null
    @Volatile private var trackedPackageName: String? = null
    @Volatile private var trackedSinceMillis = 0L
    private var websiteTrackingJob: Job? = null
    private var appLimitMonitoringJob: Job? = null
    private var hierarchyBoundaryJob: Job? = null
    @Volatile private var hierarchyBoundaryAtMillis = Long.MIN_VALUE
    private val websiteBlockTransitionCounter = AtomicLong(0L)
    private val websiteBlockTransitionGuard = WebsiteBlockTransitionGuard()

    private data class WebsiteUsageSlice(
        val domain: String,
        val deltaMillis: Long,
        val packageName: String
    )

    private enum class CurtainMode {
        BLOCK_NOTICE,
        SELF_PROTECTION
    }

    private val cacheTimeoutMillis = 5_000L
    private val browserDebounceMillis = 0L
    private val websitePulseMillis = 1_000L
    private val appLimitPulseMillis = 1_000L
    private val maxUsageDeltaMillis = 15_000L
    private val channelId = "focusguard_service_channel"
    private val notificationId = 101
    private val dateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }

    private val phonePackages = setOf(
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.phone",
        "com.android.server.telecom",
        "com.samsung.android.dialer",
        "com.samsung.android.incallui"
    )

    // Fonte única em SettingsInterceptionPolicy — estas listas estavam duplicadas aqui.
    private val settingsPackages = SettingsInterceptionPolicy.settingsPackages
    private val interceptionPackages = SettingsInterceptionPolicy.interceptionPackages
    // Locator terms only. Full classification still uses the richer policy
    // dictionaries after a node is found. Keeping this list tiny matters because
    // every entry can become a synchronous accessibility-tree query on a click.
    private val directClickContextSufficientTerms =
        (listOf("FocusGuard", "Focus Guard", "com.focusguard") +
            AccessibilitySettingsPolicy.accessibilityDisclosureNodeSearchTerms +
            AccessibilitySettingsPolicy.installedAccessibilityAppsNodeSearchTerms).distinct()
    // One strong app-identity query per ambiguous click; broad localized terms stay in policy fallbacks.
    private val clickInterceptionSearchTerms = listOf("FocusGuard")
    // Device Admin is a revocation gateway of its own. These short locator prefixes
    // are intentionally separate from the broad localized dictionaries so an
    // ambiguous Settings click can be resolved from the clicked row/parent before
    // falling back to a root-window scan. One UI normally matches the first term.
    private val deviceAdminClickSearchTerms =
        ManagedSelfProtectionPolicy.deviceAdminNodeSearchTerms

    private var pendingSettingsProtectionUntilElapsed = 0L

    @Volatile private var browserPackages: Set<String> = emptySet()
    private var verifiedHttpsHandlerPackages: Set<String> = emptySet()
    private data class BrowserDiscoveryMiss(
        val windowId: Int,
        val checkedAtElapsed: Long
    )
    private val browserDiscoveryMisses = mutableMapOf<String, BrowserDiscoveryMiss>()
    private val knownBrowserPackages = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "com.microsoft.emmx",
        "com.microsoft.emmx.beta",
        "com.microsoft.emmx.dev",
        "com.microsoft.emmx.canary",
        "com.brave.browser",
        "com.brave.browser_beta",
        "com.brave.browser_nightly",
        "com.kiwibrowser.browser",
        "com.kiwibrowser.browser.dev",
        "com.vivaldi.browser",
        "com.vivaldi.browser.snapshot",
        "com.ecosia.android",
        "com.yandex.browser",
        "com.yandex.browser.beta",
        "com.yandex.browser.alpha",
        "com.yandex.browser.lite",
        "com.UCMobile.intl",
        "com.UCMobile.intl.mi",
        "com.transsion.phoenix",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix",
        "org.mozilla.fennec_aurora",
        "org.mozilla.focus",
        "org.mozilla.klar",
        "com.opera.browser",
        "com.opera.browser.beta",
        "com.opera.mini.native",
        "com.opera.gx",
        "com.sec.android.app.sbrowser",
        "com.sec.android.app.sbrowser.beta",
        "mark.via",
        "mark.via.gp",
        "com.duckduckgo.mobile.android",
        "com.google.android.googlequicksearchbox"
    )

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val changedPackage = intent?.data?.schemeSpecificPart.orEmpty()
            calculateBrowserPackages()
            refreshLauncherIndex(force = true)
            lastLoadTime = 0L
            scope.launch {
                if (changedPackage in knownBrowserPackages) {
                    deviceOwnerManager.invalidateWebsitePolicyCache()
                }
                sessionManager.checkAndEnforce()
            }
        }
    }

    private val launcherReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshLauncherIndex(force = true)
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_DEV_RELINQUISH_ACCESSIBILITY) {
                relinquishAccessibilityForDevelopment()
                return
            }
            intent?.let(::applyImmediateBlockingSnapshot)
            lastLoadTime = 0L
            refreshData()
        }
    }

    private val curtainDestinationReadyListener =
        CurtainDestinationReadyCoordinator.Listener { generation ->
            mainHandler.post {
                if (websiteBlockTransitionGuard.confirmPomodoro(
                        curtainGeneration = generation,
                        readyAtUptimeMillis = SystemClock.uptimeMillis()
                    )
                ) return@post
                if (shouldDismissCurtain(instantBlockCurtainGeneration, generation)) {
                    if (pendingReadyWindowValidationGeneration != generation) {
                        pendingReadyWindowValidationGeneration = generation
                        mainHandler.postDelayed(
                            readyWindowValidation,
                            SAFE_WINDOW_SETTLE_MILLIS
                        )
                    }
                }
            }
        }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    browserInspectionCoordinator.invalidate()
                    foregroundPackageName = null
                    stopWebsiteTracking()
                    protectedPowerMenuController?.onScreenOff()
                    when (screenOffCurtainDecision(
                        curtainVisible = instantBlockCurtainVisible,
                        awaitingSafeSurfaceGeneration = awaitingSafeSurfaceGeneration,
                        unsafeWindowVisible = instantBlockCurtainVisible &&
                            hasUnsafeVisibleWindow()
                    )) {
                        InstantCurtainFailsafeDecision.NO_ACTION -> Unit
                        InstantCurtainFailsafeDecision.HIDE -> dismissInstantBlockCurtain()
                        InstantCurtainFailsafeDecision.EVACUATE_THEN_HIDE ->
                            beginCurtainEvacuationBeforeHide()
                    }
                }
                Intent.ACTION_SCREEN_ON -> protectedPowerMenuController?.onScreenOn()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getDatabase(this)
        sessionManager = BlockingSessionManager.getInstance(this)
        deviceOwnerManager = DeviceOwnerManager.getInstance(this)
        deviceOwnerActiveCached = deviceOwnerManager.isDeviceOwnerActive()
        refreshSynchronousProtectionState()
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        protectedPowerMenuController = ProtectedPowerMenuController(this)
        prepareInstantBlockCurtain()
        AuthenticatedRemovalWindow.preload(this)
        DeviceAdminActivationWindow.preload(this)

        registerPackageReceiver()
        registerLauncherReceiver()
        registerRefreshReceiver()
        CurtainDestinationReadyCoordinator.register(curtainDestinationReadyListener)
        registerScreenStateReceiver()
        createNotificationChannel()
        startAsForeground()
    }

    private fun registerPackageReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(packageReceiver, filter)
        }
    }

    private fun registerLauncherReceiver() {
        val filter = IntentFilter().apply {
            addAction("android.intent.action.ACTION_PREFERRED_ACTIVITY_CHANGED")
            addAction("android.app.role.action.ROLE_HOLDER_CHANGED")
            addAction(Intent.ACTION_LOCALE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(launcherReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(launcherReceiver, filter)
        }
    }

    private fun registerRefreshReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_REFRESH_BLOCKING)
            addAction(ACTION_DEV_RELINQUISH_ACCESSIBILITY)
        }
        ContextCompat.registerReceiver(
            this,
            refreshReceiver,
            filter,
            internalRefreshReceiverFlags()
        )
    }
    private fun registerScreenStateReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(screenStateReceiver, filter)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            channelId,
            "FocusGuard Protection Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.mantem_o_focusguard_ativo_para_garantir_)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun startAsForeground() {
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setSmallIcon(R.drawable.ic_shield)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                notificationId,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(notificationId, notification)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        browserInspectionCoordinator.invalidate()
        browserRecoveryCoordinator.clear()
        accessibilityServiceConnected = true
        // Do this before any node/tree work or asynchronous Room refresh. The
        // persisted snapshot exists specifically to cover the first event after
        // Android binds or recreates the service.
        deviceOwnerActiveCached = deviceOwnerManager.isDeviceOwnerActive()
        refreshSynchronousProtectionState()
        prepareInstantBlockCurtain()
        defaultLauncherPackage = calculateDefaultLauncher()
        refreshLauncherIndex(force = true)
        foregroundPackageName = rootInActiveWindow?.packageName?.toString()
        calculateBrowserPackages()
        refreshData()
        startAppLimitMonitoringPulse()
        scope.launch {
            sessionManager.checkAndEnforce()
        }

        // Preserva capacidades estáticas carregadas do XML, especialmente
        // canRetrieveWindowContent; apenas campos dinâmicos podem ser alterados aqui.
        serviceInfo = serviceInfo.apply {
            eventTypes = requestedAccessibilityEventTypes()
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            notificationTimeout = EVENT_NOTIFICATION_TIMEOUT_MILLIS
        }
        syncWarmOverlays()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshLauncherIndex(force = true)
    }

    private fun calculateBrowserPackages() {
        browserDiscoveryMisses.clear()
        browserPackages = try {
            val browserIntent = Intent(
                Intent.ACTION_VIEW,
                android.net.Uri.parse("https://example.com")
            ).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
            val dynamicBrowsers = packageManager.queryIntentActivities(
                browserIntent,
                PackageManagerCompat.MATCH_ALL
            ).mapNotNull { it.activityInfo?.packageName }.toSet()
            verifiedHttpsHandlerPackages = dynamicBrowsers
            knownBrowserPackages + dynamicBrowsers
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError(
                "A11y",
                "Falha ao identificar navegadores",
                error
            )
            verifiedHttpsHandlerPackages = emptySet()
            knownBrowserPackages
        }
    }

    private fun isVerifiedHttpsHandler(packageName: String): Boolean =
        packageName in verifiedHttpsHandlerPackages

    private fun calculateDefaultLauncher(): String? {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
            packageManager.resolveActivity(
                intent,
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo?.packageName
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError("A11y", "Falha ao identificar launcher", error)
            null
        }
    }

    /**
     * Builds the launcher-label index off the accessibility callback. PackageManager
     * is never consulted after the user taps an icon; that path reads one volatile
     * snapshot and the event's direct text only.
     */
    private fun refreshLauncherIndex(force: Boolean = false) {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (!LauncherIndexRefreshPolicy.shouldRequest(
                force = force,
                lastRequestElapsed = lastLauncherIndexRefreshRequestElapsed,
                nowElapsed = nowElapsed
            )
        ) return
        lastLauncherIndexRefreshRequestElapsed = nowElapsed
        launcherIndexRefreshRequested.set(true)
        if (!isRefreshingLauncherIndex.compareAndSet(false, true)) return
        scope.launch {
            try {
                do {
                    launcherIndexRefreshRequested.set(false)
                    val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                    }
                    val queryResult = runCatching {
                        packageManager.queryIntentActivities(
                            launcherIntent,
                            PackageManagerCompat.MATCH_ALL
                        )
                    }
                    val resolveInfos = queryResult.getOrNull()
                    if (resolveInfos == null) {
                        FocusGuardLogger.logError(
                            "A11y",
                            "Falha ao consultar Activities do launcher; snapshot preservado",
                            queryResult.exceptionOrNull()
                                ?: IllegalStateException("Consulta do launcher sem resultado")
                        )
                        // Permit the next normal refresh pulse to retry instead
                        // of leaving the fast path stale for the full 15 minutes.
                        lastLauncherIndexRefreshRequestElapsed = 0L
                        continue
                    }
                    val entries = resolveInfos.flatMap { resolveInfo ->
                        val activityInfo = resolveInfo.activityInfo
                        if (activityInfo == null) {
                            emptyList()
                        } else {
                            val componentName = android.content.ComponentName(
                                activityInfo.packageName,
                                activityInfo.name
                            ).flattenToShortString()
                            buildList {
                                runCatching {
                                    resolveInfo.loadLabel(packageManager)
                                }.getOrNull()?.let { label ->
                                    add(
                                        ImmediateInterceptionPolicy.LauncherLabelEntry(
                                            label = label,
                                            packageName = activityInfo.packageName,
                                            componentName = componentName
                                        )
                                    )
                                }
                                runCatching {
                                    activityInfo.applicationInfo.loadLabel(packageManager)
                                }.getOrNull()?.let { label ->
                                    add(
                                        ImmediateInterceptionPolicy.LauncherLabelEntry(
                                            label = label,
                                            packageName = activityInfo.packageName,
                                            componentName = componentName
                                        )
                                    )
                                }
                            }
                        }
                    }
                    val rebuilt = ImmediateInterceptionPolicy.buildLauncherLabelIndex(entries)
                    val launcher = calculateDefaultLauncher()
                    if (LauncherIndexRefreshPolicy.shouldPublishCandidate(
                            querySucceeded = true,
                            candidateSize = rebuilt.size,
                            hasSuccessfulSnapshot = hasSuccessfulLauncherIndexSnapshot
                        )
                    ) {
                        withContext(Dispatchers.Main) {
                            launcherLabelIndex = rebuilt
                            hasSuccessfulLauncherIndexSnapshot = true
                            if (launcher != null) defaultLauncherPackage = launcher
                        }
                    }
                } while (launcherIndexRefreshRequested.get())
            } finally {
                isRefreshingLauncherIndex.set(false)
                if (launcherIndexRefreshRequested.get() && serviceJob.isActive) {
                    refreshLauncherIndex(force = true)
                }
            }
        }
    }

    private fun resolveEventPackageName(event: AccessibilityEvent): String {
        val directPackage = event.packageName?.toString().orEmpty()
        if (event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            directPackage.isNotBlank()
        ) {
            return directPackage
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            val eventWindowRoot = windows
                .firstOrNull { window -> window.id == event.windowId }
                ?.root
            val eventWindowPackage = try {
                eventWindowRoot?.packageName?.toString().orEmpty()
            } finally {
                recycleSafely(eventWindowRoot)
            }
            if (eventWindowPackage.isNotBlank()) return eventWindowPackage
        }

        val root = rootInActiveWindow
        return try {
            root?.packageName?.toString().orEmpty().ifBlank { directPackage }
        } finally {
            recycleSafely(root)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val eventDetectedAtNanos = SystemClock.elapsedRealtimeNanos()
        val eventDeliveredAtUptimeMillis = SystemClock.uptimeMillis()
        try {
            // Fast path: settings interception decides on `event.packageName` alone,
            // before resolveEventPackageName() is allowed to touch the node tree.
            //
            // That resolution costs one or two synchronous binder calls into the
            // inspected app (windows walk plus rootInActiveWindow), on every event.
            // Those milliseconds are exactly the window in which the user can reach
            // the switch that disables this service, so nothing that can block runs
            // ahead of the decision to bounce them out.
            val directPackage = event.packageName?.toString().orEmpty()
            val browserInspectionEvent =
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED
            val inspectWindowEarly = directPackage.isBlank() ||
                event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            if (consumeInputUiEvent(event, directPackage, inspectWindowEarly)) return

            // An explicit ACTION_VIEW fallback may make the same browser expose a new
            // Android window. Hand the first post-intent browser window to the existing
            // transition before generic window retirement. This is provisional only:
            // the curtain remains until a stable safe Google surface is confirmed.
            val transitionPackage = directPackage.ifBlank { foregroundPackageName.orEmpty() }
            val activeBrowserTransition = transitionPackage
                .takeIf(String::isNotBlank)
                ?.let(websiteBlockTransitionGuard::activeTransition)
            if (activeBrowserTransition != null) {
                val canRebindExternalWindow =
                    activeBrowserTransition.externalRedirectRequested &&
                        !activeBrowserTransition.externalRedirectWindowRebound &&
                        browserInspectionEvent &&
                        event.windowId >= 0 &&
                        event.windowId != activeBrowserTransition.expectedWindowId &&
                        event.eventTime >= activeBrowserTransition.externalRedirectRequestedAtUptimeMillis
                if (canRebindExternalWindow) {
                    val reboundGeneration = browserInspectionCoordinator.observeWindow(
                        transitionPackage,
                        event.windowId
                    )
                    websiteBlockTransitionGuard.rebindExternalRedirectWindow(
                        browserPackageName = transitionPackage,
                        transitionId = activeBrowserTransition.id,
                        windowId = event.windowId,
                        inspectionGeneration = reboundGeneration,
                        eventUptimeMillis = event.eventTime
                    )
                } else if (isWindowOrTabTransitionEvent(event.eventType)) {
                    browserInspectionCoordinator.observeWindow(transitionPackage, event.windowId)
                }
                retireStaleWebsiteTransitions()
                if (websiteBlockTransitionGuard.isActive(transitionPackage)) {
                    websiteBlockTransitionGuard.observeBrowserEvent(
                        browserPackageName = transitionPackage,
                        windowId = event.windowId,
                        eventUptimeMillis = event.eventTime,
                        eventType = event.eventType
                    )
                    scheduleBrowserInspection(event, transitionPackage)
                }
                return
            }

            val observedWindowPackage = transitionPackage
            if ((event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) &&
                observedWindowPackage.isNotBlank()
            ) {
                browserInspectionCoordinator.observeWindow(observedWindowPackage, event.windowId)
                retireStaleWebsiteTransitions()
            }

            // Shield the native System UI power menu before any other handling.
            // A touch-consuming TYPE_ACCESSIBILITY_OVERLAY stays on top while the
            // controller forwards only ACTION_CLICK to native actions; the user
            // never reaches the long-press path that requests Safe Mode.
            if (protectedPowerMenuController?.handleAccessibilityEvent(
                    event = event,
                    protectionActive = isBlockingSessionActive || focusModeSessionActive
                ) == true
            ) {
                return
            }

            if (ImmediateInterceptionPolicy.shouldHandleLauncherClick(
                    isViewClickedEvent =
                        event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED,
                    eventPackageName = directPackage,
                    defaultLauncherPackage = defaultLauncherPackage
                )
            ) {
                val directValues = directEventTextValues(event)
                if (handleBlockedLauncherClick(event, directValues)) return
                if (handleLauncherAppInfoClick(
                        event,
                        directValues,
                        eventDetectedAtNanos,
                        eventDeliveredAtUptimeMillis
                    )
                ) return
            }

            val eligibleForInterception = event.eventType in settingsInterceptionEventTypes
            if (eligibleForInterception &&
                directPackage in interceptionPackages &&
                handleSettingsInterception(
                    event,
                    directPackage,
                    eventDetectedAtNanos,
                    eventDeliveredAtUptimeMillis
                )
            ) {
                return
            }

            // Known IME windows were already handled above without a lookup. For
            // a new named keyboard window, identify its Android window type here,
            // after the immediate self-protection/browser paths and before nodes.
            if (!inspectWindowEarly && consumeInputUiEvent(event, directPackage, true)) return

            // Address-bar text changes are the strongest low-latency signal Chromium
            // exposes through Accessibility. Inspect only the event source here (never the
            // whole tree) so a blocked host can be stopped even when rootInActiveWindow is
            // stale, incomplete, or the toolbar is temporarily absent from the active root.
            if (browserInspectionEvent && directPackage.isNotBlank() &&
                websiteSurfaceInspectionNeeded() &&
                handleImmediateBrowserAddressEvent(event, directPackage)
            ) {
                return
            }

            // Full website inspection stays asynchronous. The immediate source path above
            // complements this root/window walk; it does not replace the normal verification.
            if (browserInspectionEvent && directPackage.isNotBlank() &&
                websiteSurfaceInspectionNeeded()
            ) {
                scheduleBrowserInspection(event, directPackage)
            }

            val packageName = directPackage.ifBlank { foregroundPackageName.orEmpty() }
            if (packageName == this.packageName) {
                observeOwnUiEvent(event)
                return
            }
            // Second chance: `event.packageName` is occasionally blank, and for
            // TYPE_WINDOWS_CHANGED it can name a different window than the one that
            // actually changed. Only reached when the fast path could not decide.
            if (eligibleForInterception &&
                packageName != directPackage &&
                packageName in interceptionPackages &&
                handleSettingsInterception(
                    event,
                    packageName,
                    eventDetectedAtNanos,
                    eventDeliveredAtUptimeMillis
                )
            ) {
                return
            }

            // Refresh is intentionally below the self-protection fast path. Even
            // though refreshData() is asynchronous, scheduling it and touching its
            // atomics before the critical decision is wasted work on the exact event
            // where every millisecond matters.
            val now = System.currentTimeMillis()
            if (now - lastLoadTime > cacheTimeoutMillis) {
                refreshLauncherIndex()
                refreshData()
            }

            val isWindowTransition =
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
            if (isWindowTransition) {
                if (FocusModeStore.isActive(applicationContext) != focusModeSessionActive) {
                    refreshFocusModeFallbackState()
                }
                foregroundPackageName = packageName.takeIf(String::isNotBlank)
                if (packageName !in browserPackages &&
                    packageName !in limitedWebsiteAppDomains
                ) {
                    stopWebsiteTracking(now)
                }
            }

            if (shouldApplyStrictPomodoroToWindow(
                    strictActive = isPomodoroStrictActive,
                    isWindowTransition = isWindowTransition,
                    isBrowserWindow = packageName in browserPackages
                )
            ) {
                handleStrictPomodoro(packageName, event.className?.toString().orEmpty())
                return
            }

            val focusLauncherMustReturn = focusModeSessionActive &&
                packageName == defaultLauncherPackage
            if (packageName in focusModeAllowedAppsSet && !focusLauncherMustReturn) {
                stopWebsiteTracking(now)
                return
            }

            if (!isBlockingSessionActive && limitedWebsiteDomains.isEmpty()) return

            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                    handleWindowStateChanged(event, packageName)
                }
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> Unit // queued above; tree work is off-main
            }
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError("A11y", "Erro no evento de acessibilidade", error)
        } finally {
            val elapsedMillis = (
                SystemClock.elapsedRealtimeNanos() - eventDetectedAtNanos
            ).coerceAtLeast(0L) / 1_000_000L
            if (elapsedMillis >= SLOW_ACCESSIBILITY_CALLBACK_MILLIS) {
                val nowElapsed = SystemClock.elapsedRealtime()
                val previousLog = lastSlowCallbackLogElapsed.get()
                val shouldLog = (previousLog == 0L ||
                    nowElapsed - previousLog >= SLOW_CALLBACK_LOG_INTERVAL_MILLIS) &&
                    lastSlowCallbackLogElapsed.compareAndSet(previousLog, nowElapsed)
                if (shouldLog) {
                    val eventType = event.eventType
                    val eventPackage = event.packageName?.toString().orEmpty()
                    val eventWindowId = event.windowId
                    scope.launch {
                        FocusGuardLogger.log(
                            "A11yPerf",
                            "Callback lento: ${elapsedMillis}ms, type=$eventType, " +
                                "package=$eventPackage, window=$eventWindowId"
                        )
                    }
                }
            }
        }
    }

    private fun consumeInputUiEvent(
        event: AccessibilityEvent,
        directPackage: String,
        allowWindowLookup: Boolean
    ): Boolean = when (inputEventFilter.classify(
        ownPackageName = packageName,
        eventPackageName = directPackage,
        windowId = event.windowId,
        windowsChanged = event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED,
        allowWindowLookup = allowWindowLookup,
        readWindows = {
            // Do not access window.root here: even a package-name read from that
            // root can wait on the same UI thread that is processing the keyboard.
            windows.map { AccessibilityInputEventFilter.Window(it.id, it.type) }
        }
    )) {
        AccessibilityInputEventFilter.Decision.OWN_UI -> {
            observeOwnUiEvent(event)
            true
        }
        AccessibilityInputEventFilter.Decision.INPUT_METHOD -> true
        AccessibilityInputEventFilter.Decision.INSPECT -> false
    }

    private fun observeOwnUiEvent(event: AccessibilityEvent) {
        if (event.windowId >= 0) {
            browserInspectionCoordinator.observeWindow(packageName, event.windowId)
            retireStaleWebsiteTransitions()
        }
        foregroundPackageName = packageName
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            stopWebsiteTracking()
        }
    }

    private fun applyImmediateBlockingSnapshot(intent: Intent) {
        if (!intent.hasExtra(EXTRA_BLOCKING_SNAPSHOT_PRESENT)) return

        refreshFocusModeFallbackState()
        val apps = intent.getStringArrayListExtra(EXTRA_BLOCKED_APPS_SNAPSHOT)
            .orEmpty()
            .filter(String::isNotBlank)
            .toSet()
        val sites = WebsiteBlocker.normalizeRules(
            intent.getStringArrayListExtra(EXTRA_BLOCKED_SITES_SNAPSHOT).orEmpty()
        )
        val passwordSites = WebsiteBlocker.normalizeRules(
            intent.getStringArrayListExtra(EXTRA_PASSWORD_SITES_SNAPSHOT).orEmpty()
        )
        val strongerSites = WebsiteBlocker.normalizeRules(
            intent.getStringArrayListExtra(EXTRA_STRONGER_SITES_SNAPSHOT).orEmpty()
        )
        blockedAppsSet = apps
        blockedWebsitesDomainSet = sites
        passwordWebsiteDomainSet = passwordSites
        strongerWebsiteDomainSet = strongerSites
        blockedWebsiteAppDomains = WebsiteBlocker.appPackageDomainsFor(sites)
        isPomodoroStrictActive = intent.getBooleanExtra(
            EXTRA_STRICT_POMODORO_SNAPSHOT,
            false
        )
        isBlockingSessionActive = intent.getBooleanExtra(
            EXTRA_BLOCKING_ACTIVE_SNAPSHOT,
            apps.isNotEmpty() || sites.isNotEmpty()
        )
        syncWarmOverlays()
        lastLoadTime = System.currentTimeMillis()
        enforceCurrentForegroundFromSnapshot()
    }

    private fun enforceCurrentForegroundFromSnapshot() {
        val currentPackage = foregroundPackageName?.takeIf(String::isNotBlank) ?: return
        if (currentPackage == packageName ||
            currentPackage == defaultLauncherPackage ||
            currentPackage in focusModeAllowedAppsSet
        ) return

        if (currentPackage in browserPackages && blockedWebsitesDomainSet.isNotEmpty()) {
            val token = browserInspectionCoordinator.currentToken(currentPackage) ?: return
            val now = SystemClock.uptimeMillis()
            val offer = browserInspectionCoordinator.offer(
                currentPackage, token.windowId, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                now, now, "", emptyList(), null
            )
            if (offer.startWorker) scope.launch { drainBrowserInspections() }
            return
        }

        if (currentPackage !in blockedAppsSet ||
            PasswordTargetAccessGrant.isPackageGranted(currentPackage)
        ) return
        val root = rootInActiveWindow ?: return
        val targetStillForeground = try {
            root.packageName?.toString() == currentPackage
        } finally {
            recycleSafely(root)
        }
        if (targetStillForeground) blockApp(currentPackage)
    }

    private fun relinquishAccessibilityForDevelopment() {
        if (!AuthenticatedRemovalWindow.isActive(this)) return

        runCatching {
            blockedAppsSet = emptySet()
            blockedWebsitesDomainSet = emptySet()
            passwordWebsiteDomainSet = emptySet()
            strongerWebsiteDomainSet = emptySet()
            blockedWebsiteAppDomains = emptyMap()
            limitedWebsiteDomains = emptySet()
            hardLimitedWebsiteDomains = emptySet()
            limitedWebsiteAppDomains = emptyMap()




            browserDiscoveryMisses.clear()
            isBlockingSessionActive = false
            focusModeSessionActive = false
            focusModeFallbackActive = false
            focusModeBlockedAppsSet = emptySet()
            focusModeAllowedAppsSet = emptySet()
            SelfProtectionStateStore.setArmed(applicationContext, false)
            isPomodoroStrictActive = false
            pendingSettingsProtectionUntilElapsed = 0L
            StrictPomodoroLock.clear(applicationContext)
            PomodoroForegroundService.stop(applicationContext)
            foregroundPackageName = null
            stopWebsiteTracking()
            protectedPowerMenuController?.onProtectionStateChanged(false)
            releaseInstantBlockCurtain()
            stopForeground(STOP_FOREGROUND_REMOVE)
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "DevelopmentUninstall",
                "Falha parcial ao limpar o serviço de Acessibilidade",
                error
            )
        }
        disableSelf()
    }

    override fun onInterrupt() {
        browserInspectionCoordinator.invalidate()
        foregroundPackageName = null
        stopWebsiteTracking()
        // onInterrupt stops accessibility feedback; it does not prove that an
        // Android-owned power window disappeared. Keep shielding until the
        // controller confirms absence through its normal window recheck.
        protectedPowerMenuController?.onFeedbackInterrupted()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val isBackOrHomeKey = event.keyCode == KeyEvent.KEYCODE_BACK ||
            event.keyCode == KeyEvent.KEYCODE_HOME
        if (!isBackOrHomeKey) return false

        // The device-protected store is the fail-closed source during process
        // recreation and immediately after boot; the volatile flag is the fast path.
        val focusModeActiveNow = focusModeSessionActive ||
            FocusModeStore.isActive(applicationContext)
        if (focusModeActiveNow && !focusModeSessionActive) {
            refreshFocusModeFallbackState()
        }

        return when (FocusModePolicy.focusNavigationKeyDecision(
            focusModeActive = focusModeActiveNow,
            focusGuardForeground = foregroundPackageName == packageName,
            powerMenuVisible = protectedPowerMenuController?.isVisible() == true,
            isBackOrHomeKey = true,
            actionDown = event.action == KeyEvent.ACTION_DOWN,
            repeatCount = event.repeatCount
        )) {
            FocusModePolicy.NavigationKeyDecision.PASS -> false
            FocusModePolicy.NavigationKeyDecision.CONSUME -> true
            FocusModePolicy.NavigationKeyDecision.RETURN_TO_FOCUS_GUARD -> {
                val generation = showInstantBlockCurtain(mode = CurtainMode.BLOCK_NOTICE)
                awaitingSafeSurfaceGeneration = generation
                val restored = FocusModeKioskController.launchFocusGuardHome(this)
                if (!restored) beginCurtainEvacuationBeforeHide(generation)
                true
            }
        }
    }

    private fun scheduleHierarchyBoundaryRefresh(
        sessions: Collection<BlockSession>,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        val nextBoundary = HierarchyBoundaryPolicy.nextBoundary(sessions, nowMillis)
        if (nextBoundary == null) {
            hierarchyBoundaryAtMillis = Long.MIN_VALUE
            hierarchyBoundaryJob?.cancel()
            hierarchyBoundaryJob = null
            return
        }
        if (hierarchyBoundaryAtMillis == nextBoundary &&
            hierarchyBoundaryJob?.isActive == true
        ) return

        hierarchyBoundaryJob?.cancel()
        hierarchyBoundaryAtMillis = nextBoundary
        hierarchyBoundaryJob = scope.launch {
            delay(
                HierarchyBoundaryPolicy.delayMillis(
                    boundaryMillis = nextBoundary,
                    nowMillis = System.currentTimeMillis()
                )
            )
            if (hierarchyBoundaryAtMillis != nextBoundary) return@launch
            hierarchyBoundaryAtMillis = Long.MIN_VALUE
            hierarchyBoundaryJob = null

            // AlarmManager remains the process-death/doze fallback. While the
            // accessibility service is alive, this handoff runs at the actual
            // TIME start/end boundary instead of the platform's inexact-alarm window.
            sessionManager.checkAndEnforce()
            refreshData()
        }
    }

    private fun refreshData() {
        refreshRequested.set(true)
        if (!isRefreshing.compareAndSet(false, true)) return
        scope.launch {
            try {
                do {
                    refreshRequested.set(false)
                    try {
                        val deviceOwnerActiveNow = deviceOwnerManager.isDeviceOwnerActive()
                        deviceOwnerActiveCached = deviceOwnerActiveNow
                        val adultFilterEnabled = authManager.isAdultFilterEnabled()
                        val adultRules = if (adultFilterEnabled) {
                            setOf(com.focusguard.data.PredefinedWebsites.PORNOGRAPHY_RULE)
                        } else {
                            emptySet()
                        }
                        val focusModeSession = FocusModeStore.readSession(applicationContext)
                            ?.takeIf { it.isActive() }
                        val nativeFocusLockdownActive = focusModeSession != null &&
                            FocusModePolicy.usesNativeFocusLockdown(
                                deviceOwnerActive = deviceOwnerActiveNow,
                                systemLockdownSupported =
                                    deviceOwnerManager.isFocusModeSystemLockdownSupported()
                            )
                        val focusFallbackActive =
                            focusModeSession != null && !nativeFocusLockdownActive
                        val focusFallbackApps = if (focusFallbackActive) {
                            focusModeSession?.blockedPackages.orEmpty()
                        } else {
                            emptySet()
                        }
                        val focusAllowedApps = focusModeSession?.allowedPackages.orEmpty()

                        val activeSessions = database.blockSessionDao().getAllActiveSessionsStatic()
                        scheduleHierarchyBoundaryRefresh(activeSessions)
                        val enforcingSessions = activeSessions.filter {
                            BlockingSessionManager.participatesInBlocking(it) &&
                                sessionManager.isCurrentlyInBlockingWindow(it)
                        }
                        val enforcingIds = enforcingSessions.map { it.id }
                        val passwordSessionIds = enforcingSessions
                            .filter { it.sessionType.equals("PASSWORD", ignoreCase = true) }
                            .map { it.id }
                        val strongerSessionIds = enforcingSessions
                            .filter { !it.sessionType.equals("PASSWORD", ignoreCase = true) }
                            .map { it.id }

                        val sessionApps = getAppsForSessions(enforcingIds).toSet()
                        val sessionSites = WebsiteBlocker.normalizeRules(
                            getSitesForSessions(enforcingIds)
                        )
                        val passwordSessionSites = WebsiteBlocker.normalizeRules(
                            getSitesForSessions(passwordSessionIds)
                        )
                        val strongerSessionSites = WebsiteBlocker.normalizeRules(
                            getSitesForSessions(strongerSessionIds)
                        )

                        val activeAppLimits = database.appUsageLimitDao()
                            .getAllActiveLimitsStatic()
                        val limitApps = calculateExceededAppLimits(activeAppLimits)
                        val websiteLimits = database.websiteUsageLimitDao().getAllStatic()
                            .filter { it.isEnabled }
                        val configuredWebsiteDomains = WebsiteBlocker.normalizeRules(
                            websiteLimits.map { it.domain }
                        )
                        val hardConfiguredWebsiteDomains = WebsiteBlocker.normalizeRules(
                            websiteLimits.filter { limit ->
                                WebsiteUsageLimitPolicy.requiresUrlObservationForHardLimit(
                                    lockMode = limit.lockMode,
                                    lockUntilTimestamp = limit.lockUntilTimestamp,
                                    nowMillis = System.currentTimeMillis()
                                )
                            }.map { it.domain }
                        )
                        val exceededWebsiteDomains = calculateExceededWebsiteLimits(websiteLimits)
                        val strongerWebsiteDomains = WebsiteBlocker.normalizeRules(
                            strongerSessionSites + exceededWebsiteDomains + adultRules
                        )
                        val blockedWebsiteDomains = WebsiteBlocker.normalizeRules(
                            sessionSites + exceededWebsiteDomains + adultRules
                        )
                        // Native apps are enforced only when their package was
                        // explicitly persisted. A website rule must not silently
                        // acquire the companion app after the user declined it.
                        val blockedWebsiteApps = emptyMap<String, String>()
                        val limitedWebsiteApps = emptyMap<String, String>()
                        val enforcedApps = FocusModePolicy.packagesToEnforce(
                            configuredBlockedPackages = sessionApps + limitApps,
                            focusModeBlockedPackages =
                                focusModeSession?.blockedPackages.orEmpty(),
                            focusModeAllowedPackages = focusAllowedApps
                        )
                        val accessibilityApps = FocusModePolicy.packagesForAccessibility(
                            enforcedPackages = enforcedApps,
                            focusModeBlockedPackages =
                                focusModeSession?.blockedPackages.orEmpty(),
                            nativeFocusLockdownActive = nativeFocusLockdownActive
                        )
                        val enforcementFingerprint = listOf(
                            enforcingIds.sorted().joinToString(","),
                            sessionApps.sorted().joinToString(","),
                            sessionSites.sorted().joinToString(","),
                            limitApps.sorted().joinToString(","),
                            exceededWebsiteDomains.sorted().joinToString(","),
                            adultFilterEnabled.toString(),
                            focusModeSession?.startedAtMillis?.toString().orEmpty()
                        ).joinToString("|")
                        val shouldReconcilePolicies = lastEnforcementFingerprint?.let {
                            it != enforcementFingerprint
                        } == true

                        withContext(Dispatchers.Main) {
                            isPomodoroStrictActive = enforcingSessions.any {
                                it.sessionType == "POMODORO" && it.isBlockingEnabled
                            }
                            focusModeSessionActive = focusModeSession != null
                            focusModeFallbackActive = focusFallbackActive
                            focusModeBlockedAppsSet = focusFallbackApps
                            focusModeAllowedAppsSet = focusAllowedApps
                            blockedAppsSet = accessibilityApps
                            blockedWebsitesDomainSet = blockedWebsiteDomains
                            passwordWebsiteDomainSet = passwordSessionSites
                            strongerWebsiteDomainSet = strongerWebsiteDomains
                            blockedWebsiteAppDomains = blockedWebsiteApps
                            limitedWebsiteDomains = configuredWebsiteDomains
                            hardLimitedWebsiteDomains = hardConfiguredWebsiteDomains
                            limitedWebsiteAppDomains = limitedWebsiteApps
                            if (
                                blockedWebsiteDomains.isEmpty() &&
                                hardConfiguredWebsiteDomains.isEmpty()
                            ) {




                            }
                            if (blockedWebsiteDomains.isEmpty() && configuredWebsiteDomains.isEmpty()) {
                                browserDiscoveryMisses.clear()
                            }
                            activeAppLimitsByPackage = activeAppLimits.associateBy { it.packageName }
                            hasActiveAppLimits = activeAppLimits.isNotEmpty()
                            isBlockingSessionActive = isSelfProtectionEngaged(
                                cachedActive = enforcingSessions.isNotEmpty() ||
                                    limitApps.isNotEmpty() ||
                                    exceededWebsiteDomains.isNotEmpty() ||
                                    adultFilterEnabled,
                                persistedActive = SelfProtectionStateStore.isArmed(
                                    applicationContext
                                ),
                                focusModeActive = FocusModeStore.isActive(
                                    applicationContext
                                ),
                                armoredDeviceOwnerActive =
                                    deviceOwnerActiveNow &&
                                        deviceOwnerManager.isArmoredProtectionArmed()
                            )
                            lastEnforcementFingerprint = enforcementFingerprint
                            lastLoadTime = System.currentTimeMillis()
                            syncWarmOverlays()
                        }
                        if (shouldReconcilePolicies) {
                            sessionManager.checkAndEnforce()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        FocusGuardLogger.logError("A11y", "Falha ao atualizar bloqueios", error)
                    }
                } while (refreshRequested.get())
            } finally {
                isRefreshing.set(false)
                if (refreshRequested.get() && serviceJob.isActive) refreshData()
            }
        }
    }

    private suspend fun calculateExceededAppLimits(): Set<String> {
        return calculateExceededAppLimits(
            database.appUsageLimitDao().getAllActiveLimitsStatic()
        )
    }

    private fun calculateExceededAppLimits(
        limits: List<com.focusguard.database.AppUsageLimit>
    ): Set<String> {
        val manager = usageStatsManager ?: return emptySet()
        if (limits.isEmpty()) return emptySet()

        // Without Usage Access, queryAndAggregateUsageStats returns an empty map and
        // every limit below reads as "0 minutes used" — indistinguishable from a
        // limit that is genuinely satisfied. Enforcement stops with nothing in the
        // logs to say why, so record it explicitly. UsageAccessStateMonitor turns
        // the same condition into a user-visible warning.
        if (UsageAccessPausePolicy.measurementIsUnavailable(
                usageAccessGranted = PermissionUtils.isUsageAccessEnabled(this),
                enabledAppLimitCount = limits.size
            )
        ) {
            FocusGuardLogger.log(
                "A11y",
                "Acesso de uso revogado: ${limits.size} limite(s) de app sem medicao"
            )
            return emptySet()
        }

        val now = System.currentTimeMillis()
        val startOfDay = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val usage = manager.queryAndAggregateUsageStats(startOfDay, now)

        return limits.filter { limit ->
            val stat = usage[limit.packageName]
            val totalDayUsageMillis = UsageLimitForegroundPolicy.includeOpenForegroundInterval(
                aggregatedForegroundMillis = stat?.totalTimeInForeground ?: 0L,
                lastUsageEventMillis = stat?.lastTimeUsed ?: 0L,
                nowMillis = now,
                isCurrentForeground = foregroundPackageName == limit.packageName,
                isDeviceInteractive = powerManager?.isInteractive == true
            )
            val effectiveUsageMillis = AppUsageLimitActivationUsage.effectiveUsageMillis(
                context = this,
                usageStatsManager = manager,
                limit = limit,
                currentDayUsageMillis = totalDayUsageMillis,
                dayStartMillis = startOfDay,
                nowMillis = now
            )
            UsageLimitForegroundPolicy.usedMinutes(effectiveUsageMillis) >=
                limit.dailyLimitMinutes &&
                limit.preventOpeningAfterLimit &&
                WebsiteUsageLimitPolicy.isBlockingModeActive(
                    limit.lockMode,
                    limit.lockUntilTimestamp,
                    now
                )
        }.mapTo(mutableSetOf()) { it.packageName }
    }

    private fun startAppLimitMonitoringPulse() {
        if (appLimitMonitoringJob?.isActive == true) return
        appLimitMonitoringJob = scope.launch {
            while (isActive) {
                delay(appLimitPulseMillis)
                if (!hasActiveAppLimits || powerManager?.isInteractive != true) continue

                try {
                    val packageName = foregroundPackageName ?: continue
                    val activeLimits = activeAppLimitsByPackage
                    if (!UsageLimitForegroundPolicy.shouldMeasureCurrentApp(
                            foregroundPackageName = packageName,
                            activeLimitPackages = activeLimits.keys,
                            focusGuardPackageName = this@BlockingAccessibilityService.packageName,
                            launcherPackageName = defaultLauncherPackage,
                            isDeviceInteractive = true
                        )
                    ) continue
                    val currentLimit = activeLimits[packageName] ?: continue
                    val shouldEnforce = UsageLimitForegroundPolicy.shouldEnforceCurrentApp(
                        foregroundPackageName = packageName,
                        exceededPackages = calculateExceededAppLimits(listOf(currentLimit)),
                        focusGuardPackageName = this@BlockingAccessibilityService.packageName,
                        launcherPackageName = defaultLauncherPackage,
                        isDeviceInteractive = true
                    )
                    if (!shouldEnforce) continue

                    // Claim the stronger owner before rendering the block.
                    // A live PASSWORD grant must stop on this threshold pulse.
                    PasswordTargetAccessGrant.claimStrongerAppProtection(packageName)

                    withContext(Dispatchers.Main) {
                        if (foregroundPackageName != packageName) return@withContext
                        blockedAppsSet = blockedAppsSet + packageName
                        blockApp(packageName)
                    }
                    sessionManager.checkAndEnforce()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    FocusGuardLogger.logError(
                        "A11y",
                        "Falha ao monitorar limite do app em primeiro plano",
                        error
                    )
                }
            }
        }
    }

    private suspend fun calculateExceededWebsiteLimits(
        limits: List<com.focusguard.database.WebsiteUsageLimit>
    ): Set<String> {
        if (limits.isEmpty()) return emptySet()
        val today = dateFormat.get()!!.format(Date())
        val usage = WebsiteUsageLimitPolicy.aggregateUsageByRule(
            usageByIdentifier = database.dailyUsageStatDao()
                .getStatsForDateStatic(today)
                .map { it.identifier to it.timeSpentMs },
            configuredRules = limits.map { it.domain }
        )
        val now = System.currentTimeMillis()

        return limits.filter { limit ->
            val domain = WebsiteBlocker.normalizeRule(limit.domain)
            WebsiteUsageLimitPolicy.shouldBlock(
                usedMillis = usage[domain] ?: 0L,
                dailyLimitMinutes = limit.dailyLimitMinutes,
                lockMode = limit.lockMode,
                lockUntilTimestamp = limit.lockUntilTimestamp,
                nowMillis = now
            )
        }.mapTo(mutableSetOf()) { WebsiteBlocker.normalizeRule(it.domain) }
    }

    private fun handleWindowStateChanged(
        event: AccessibilityEvent,
        packageName: String = event.packageName?.toString().orEmpty()
    ) {
        if (packageName.isBlank()) return
        val className = event.className?.toString().orEmpty()
        if (className.contains("Toast") || className.contains("PopupWindow")) return
        if (packageName == this.packageName) return
        if (FocusModePolicy.shouldRedirectToFocusGuard(
                focusModeFallbackActive = focusModeFallbackActive,
                foregroundPackage = packageName,
                focusGuardPackage = this.packageName,
                launcherPackage = defaultLauncherPackage,
                focusModeBlockedPackages = focusModeBlockedAppsSet,
                focusModeActive = focusModeSessionActive
            )
        ) {
            redirectToFocusGuard(packageName, event.eventTime)
            return
        }
        if (packageName in focusModeAllowedAppsSet) return
        if (packageName == defaultLauncherPackage) return

        val blockedWebsiteDomain = blockedWebsiteAppDomains[packageName]
        val limitedWebsiteDomain = limitedWebsiteAppDomains[packageName]
        when {
            // Website/focus/strict protections keep precedence over a PASSWORD
            // visit. The grant only bypasses this app's PASSWORD-session edge.
            blockedWebsiteDomain != null -> blockWebsiteApp(blockedWebsiteDomain)
            PasswordTargetAccessGrant.isPackageGranted(packageName) -> Unit
            ImmediateInterceptionPolicy.isBlockedTargetWindow(
                packageName,
                blockedAppsSet
            ) -> blockApp(packageName, event.eventTime)
            limitedWebsiteDomain != null -> updateWebsiteTracking(
                urlOrDomain = limitedWebsiteDomain,
                packageName = packageName,
                now = System.currentTimeMillis()
            )
            websiteSurfaceInspectionNeeded() && packageName in browserPackages -> Unit
        }
    }

    /**
     * Best-effort consumer-mode fast path. Accessibility delivers this after the
     * launcher click, so the native Device Owner suspension remains the only path
     * that can guarantee the target Activity never starts. This path avoids all
     * binder/tree work and normally covers the transition before a useful frame.
     */
    private fun handleBlockedLauncherClick(
        event: AccessibilityEvent,
        directValues: List<CharSequence?>
    ): Boolean {
        if (!ImmediateInterceptionPolicy.isLikelyLauncherAppIconClass(
                event.className?.toString().orEmpty()
            )
        ) return false
        val blockedPackage = launcherLabelIndex.matchBlockedPackage(
            values = directValues,
            blockedPackages = blockedAppsSet,
            additionalBlockedPackages = if (focusModeFallbackActive) {
                focusModeBlockedAppsSet
            } else {
                emptySet()
            }
        ) ?: return false

        if (focusModeFallbackActive && blockedPackage in focusModeBlockedAppsSet) {
            redirectToFocusGuard(blockedPackage, event.eventTime)
            return true
        }

        launchBlockNotice(
            blockedPackage = blockedPackage,
            blockedDomain = null,
            eventUptimeMillis = event.eventTime
        )
        return true
    }

    /** Covers the launcher's long-press “App info” shortcut for FocusGuard. */
    private fun handleLauncherAppInfoClick(
        event: AccessibilityEvent,
        directValues: List<CharSequence?>,
        eventDetectedAtNanos: Long,
        eventDeliveredAtUptimeMillis: Long
    ): Boolean {
        val directDecision = ImmediateInterceptionPolicy.classifyLauncherAppInfoClick(
            directValues
        )
        if (directDecision == DirectDecision.IGNORE) return false
        if (AuthenticatedRemovalWindow.isActive(this) || !isSelfProtectionEngagedNow()) {
            return false
        }
        if (deviceOwnerActiveCached &&
            com.focusguard.security.DeviceOwnerMaintenanceGate.isTemporarilyUnlocked(this)) return false

        val decision = if (directDecision == DirectDecision.NEED_TREE) {
            ImmediateInterceptionPolicy.classifyLauncherAppInfoClick(eventTextValues(event))
        } else {
            directDecision
        }
        if (decision != DirectDecision.PROTECT) return false

        pendingSettingsProtectionUntilElapsed =
            SystemClock.elapsedRealtime() + SETTINGS_TRANSITION_GUARD_MILLIS
        val generation = executeProtectionAction(
            eventTimeUptimeMillis = event.eventTime,
            eventDeliveredAtUptimeMillis = eventDeliveredAtUptimeMillis,
            eventDetectedAtNanos = eventDetectedAtNanos,
            holdUntilSafeSurface = true
        )
        launchMasterRemovalGate(MasterRemovalActivity.Target.APP_INFO, generation)
        return true
    }

    private fun directEventTextValues(event: AccessibilityEvent): List<CharSequence?> =
        buildList {
            addAll(event.text.orEmpty())
            add(event.contentDescription)
        }

    private fun isSelfProtectionEngagedNow(): Boolean =
        isBlockingSessionActive || focusModeSessionActive

    private fun handleStrictPomodoro(packageName: String, className: String) {
        if (packageName.isBlank() || packageName == this.packageName || packageName in phonePackages) {
            return
        }

        if (packageName == "com.android.systemui") {
            performGlobalAction(GLOBAL_ACTION_HOME)
            launchPomodoroLockScreen()
            return
        }

        if (packageName == defaultLauncherPackage || packageName in settingsPackages) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            launchPomodoroLockScreen()
            return
        }

        FocusGuardLogger.log(
            "FocusMode",
            "Pomodoro rigoroso bloqueou $packageName ($className)"
        )
        performGlobalAction(GLOBAL_ACTION_HOME)
        blockApp(packageName)
    }

    /**
     * @param packageName o app já resolvido pelo chamador. Reler
     *   `event.packageName` aqui anulava a segunda chance: ela existe justamente
     *   para os eventos em que esse campo vem vazio ou nomeia outra janela, e o
     *   guard abaixo então rejeitava todos eles.
     */
    private fun handleSettingsInterception(
        event: AccessibilityEvent,
        packageName: String,
        eventDetectedAtNanos: Long,
        eventDeliveredAtUptimeMillis: Long
    ): Boolean {
        // Cheap guards stay ahead of the signal extraction below: this runs on every
        // accessibility event, and eventTextValues() plus the classifiers are not free.
        if (packageName !in interceptionPackages) return false
        if (packageName in SettingsInterceptionPolicy.systemUiPackages &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED
        ) return false

        // ACTION_DELETE was opened by FocusGuard itself after the master
        // credential (or the day-15 window) authorized removal. Do not block
        // the Android-owned confirmation screen during that short hand-off.
        if (AuthenticatedRemovalWindow.isActive(this)) return false

        // A proteção contra a própria remoção vale em dois casos. O mais forte é
        // o Device Owner blindado. O outro é o modo consumidor: sem Device Owner,
        // mas com um bloqueio, limite ou o filtro adulto ativo agora — é o estado
        // em que desativar o app derruba justamente o que o usuário pediu para
        // segurar. `isBlockingSessionActive` já é a verdade em tempo real desse
        // estado, atualizada pelo snapshot no mesmo instante em que um bloqueio é
        // armado, então a defesa sobe junto com o bloqueio, sem depender de uma
        // recarga posterior.
        //
        // O cache e os dois snapshots em Device Protected Storage vêm antes das
        // chamadas ao DevicePolicyManager. Assim o primeiro evento após um novo
        // bind falha fechado, sem esperar Room, e o caminho ocioso ainda sai antes
        // das consultas binder mais caras.
        // Kotlin OR short-circuits left-to-right. The common consumer-mode
        // path therefore exits on the volatile snapshot and performs zero SharedPrefs
        // reads and zero DevicePolicyManager binder calls. Persistent/Device Owner
        // state remains as a fail-closed fallback for process recreation.
        if (!isSelfProtectionEngagedNow()) return false

        // During maintenance ordinary Settings stay available, but expiry-critical
        // special accesses are still evaluated by SettingsInterceptionPolicy.
        val deviceOwnerMaintenanceActive = deviceOwnerActiveCached &&
            com.focusguard.security.DeviceOwnerMaintenanceGate.isTemporarilyUnlocked(this)

        val nowElapsed = SystemClock.elapsedRealtime()
        val isSystemUi = packageName in SettingsInterceptionPolicy.systemUiPackages

        // MasterRemovalActivity deliberately opens Settings at its root for one
        // frame to clear the protected task. The same curtain generation proves
        // this is our internal reset, not a user attempt; do not HOME-bounce it.
        // The reset window exists only for the programmatic ACTION_SETTINGS
        // transition created by MasterRemovalActivity. It must NEVER swallow a real
        // TYPE_VIEW_CLICKED: returning true from this callback does not cancel the
        // Android click, so the old code created a short re-entry bypass.
        if (!isSystemUi &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            ProtectedSettingsResetWindow.isActive(
                curtainGeneration = awaitingSafeSurfaceGeneration,
                nowElapsed = nowElapsed
            )
        ) return true

        // Strict Pomodoro keeps ownership of Settings. System UI clicks still need
        // their dedicated disclosure/admin classifier, matching the policy order.
        if (!isSystemUi &&
            isPomodoroStrictActive &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED
        ) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            launchPomodoroLockScreen()
            return true
        }

        // A click already classified as protected arms a short transition guard.
        // Follow-up window/focus/content events need no class, text, source or root
        // inspection at all: cover and evict immediately.
        if (!isSystemUi &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            nowElapsed <= pendingSettingsProtectionUntilElapsed
        ) {
            executeProtectionAction(
                eventTimeUptimeMillis = event.eventTime,
                eventDeliveredAtUptimeMillis = eventDeliveredAtUptimeMillis,
                eventDetectedAtNanos = eventDetectedAtNanos
            )
            return true
        }

        // The cached inactive state costs no Preferences/Settings read. A DPM
        // binder call happens only while FocusGuard itself opened enrollment.
        val deviceAdminActivationAuthorized =
            DeviceAdminActivationWindow.isPotentiallyAuthorized(this) &&
                DeviceAdminActivationWindow.isAuthorized(this)
        val className = event.className?.toString().orEmpty()

        // Destination fallback for OEMs whose menu-row click exposes no usable text.
        // Once Android reports a Device Admin Activity/class, bounce immediately —
        // before source/root reads — and clear the Settings task through the same
        // master-gate path used by the faster revocation gateways. Legitimate
        // enrollment initiated by FocusGuard keeps its short authorization window.
        if (!isSystemUi &&
            !deviceAdminActivationAuthorized &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            ManagedSelfProtectionPolicy.classTargetsDeviceAdmin(className)
        ) {
            pendingSettingsProtectionUntilElapsed =
                nowElapsed + SETTINGS_TRANSITION_GUARD_MILLIS
            val generation = executeProtectionAction(
                eventTimeUptimeMillis = event.eventTime,
                eventDeliveredAtUptimeMillis = eventDeliveredAtUptimeMillis,
                eventDetectedAtNanos = eventDetectedAtNanos,
                holdUntilSafeSurface = true,
                forceLauncherFallback = true
            )
            launchMasterRemovalGate(MasterRemovalActivity.Target.DEVICE_ADMIN, generation)
            return true
        }

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val directValues = directEventTextValues(event)
            val direct = if (isSystemUi) {
                ImmediateInterceptionPolicy.classifySystemUiClickWithContext(
                    className = className,
                    directValues = directValues,
                    contextualValues = {
                        eventTextValues(event, forceExpandClickContext = true)
                    }
                )
            } else {
                val directResult = ImmediateInterceptionPolicy.classifySettingsClick(
                    packageName = packageName,
                    className = className,
                    values = directValues
                )
                if (directResult.decision == DirectDecision.NEED_TREE &&
                    fastDeviceAdminClickConfirmed(event)
                ) {
                    ImmediateInterceptionPolicy.SettingsClickDecision(
                        DirectDecision.PROTECT,
                        SettingsSurface.DEVICE_ADMIN
                    )
                } else {
                    directResult
                }
            }
            val authorizedAdminNeedsFullPolicy =
                ImmediateInterceptionPolicy.requiresFullPolicyForAuthorizedAdmin(
                    deviceAdminActivationAuthorized = deviceAdminActivationAuthorized,
                    className = className,
                    directSurface = direct.surface
                )
            if (direct.decision == DirectDecision.PROTECT &&
                !authorizedAdminNeedsFullPolicy
            ) {
                val target = direct.surface?.toMasterRemovalTarget() ?: return false
                pendingSettingsProtectionUntilElapsed =
                    nowElapsed + SETTINGS_TRANSITION_GUARD_MILLIS
                val generation = executeProtectionAction(
                    eventTimeUptimeMillis = event.eventTime,
                    eventDeliveredAtUptimeMillis = eventDeliveredAtUptimeMillis,
                    eventDetectedAtNanos = eventDetectedAtNanos,
                    holdUntilSafeSurface = true,
                    forceLauncherFallback = target == MasterRemovalActivity.Target.DEVICE_ADMIN
                )
                launchMasterRemovalGate(target, generation)
                return true
            }
            if (direct.decision == DirectDecision.IGNORE && !isPomodoroStrictActive) {
                return false
            }
            // System UI is intentionally limited to two exact deep links. An
            // expanded notification subtree that still cannot prove either one
            // must not inherit the broader Settings policy below.
            if (isSystemUi && direct.decision == DirectDecision.NEED_TREE) {
                return false
            }
        }

        val classTargetsAccessibilityServiceToggle =
            AccessibilitySettingsPolicy.classTargetsAccessibilityServiceToggle(className)
        val classTargetsAccessibilityList =
            AccessibilitySettingsPolicy.classTargetsAccessibilityList(className)
        val classTargetsDeviceAdmin =
            ManagedSelfProtectionPolicy.classTargetsDeviceAdmin(className)
        val classTargetsAppDetails =
            ManagedSelfProtectionPolicy.classTargetsAppDetails(className)
        val classTargetsUninstall =
            ManagedSelfProtectionPolicy.classTargetsUninstall(className)
        val classTargetsEssentialSpecialAccess =
            ManagedSelfProtectionPolicy.classTargetsEssentialSpecialAccess(className)
        val isGenericSubSettings = className.contains("SubSettings", ignoreCase = true)
        val eventValues = eventTextValues(event)
        val accessibilityTextSignals = AccessibilitySettingsPolicy.classifyText(eventValues)
        val accessibilityContextConfirmed = confirmAccessibilityContextForInstalledEntry(
            directAccessibility = accessibilityTextSignals.accessibility,
            installedAccessibilityApps = accessibilityTextSignals.installedAccessibilityApps,
            rootMentionsAccessibility = ::rootMentionsAccessibility
        )
        val managedTextSignals = ManagedSelfProtectionPolicy.classifyText(eventValues)

        val signals = SettingsInterceptionPolicy.EventSignals(
            packageName = packageName,
            isViewClickedEvent = event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED,
            isWindowTransitionEvent =
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            guardArmed = nowElapsed <= pendingSettingsProtectionUntilElapsed,
            classTargetsAccessibilityServiceToggle =
                classTargetsAccessibilityServiceToggle,
            classTargetsAccessibilityList = classTargetsAccessibilityList,
            classTargetsDeviceAdmin = classTargetsDeviceAdmin,
            classTargetsAppDetails = classTargetsAppDetails,
            classTargetsUninstall = classTargetsUninstall,
            classTargetsEssentialSpecialAccess = classTargetsEssentialSpecialAccess,
            isGenericSubSettings = isGenericSubSettings,
            textMentionsAccessibility = accessibilityContextConfirmed,
            textMentionsInstalledAccessibilityApps =
                accessibilityTextSignals.installedAccessibilityApps,
            textMentionsAccessibilityDisclosure =
                accessibilityTextSignals.accessibilityDisclosure,
            textMentionsDeviceAdmin = managedTextSignals.deviceAdmin,
            textMentionsFocusGuard = managedTextSignals.focusGuard,
            textMentionsDestructiveControl = managedTextSignals.destructiveControl,
            textMentionsEssentialSpecialAccess = managedTextSignals.essentialSpecialAccess,
            textMentionsAppInfoGateway = managedTextSignals.appInfoGateway
        )

        val masterRemovalTarget = when {
            // A destination-class fallback is still a confirmed Device Admin
            // removal gateway. Treat it exactly like the click path so Settings is
            // cleared instead of merely receiving HOME and remaining ready behind it.
            classTargetsDeviceAdmin && !deviceAdminActivationAuthorized ->
                MasterRemovalActivity.Target.DEVICE_ADMIN
            event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED -> when {
                signals.textMentionsDeviceAdmin || classTargetsDeviceAdmin ->
                    MasterRemovalActivity.Target.DEVICE_ADMIN
                (signals.textMentionsInstalledAccessibilityApps && signals.textMentionsAccessibility) ||
                    classTargetsAccessibilityServiceToggle ||
                    (signals.textMentionsAccessibilityDisclosure && signals.textMentionsFocusGuard) ->
                    MasterRemovalActivity.Target.ACCESSIBILITY
                signals.textMentionsAppInfoGateway || classTargetsAppDetails ->
                    MasterRemovalActivity.Target.APP_INFO
                classTargetsUninstall ||
                    packageName in SettingsInterceptionPolicy.packageInstallerPackages ||
                    (signals.textMentionsDestructiveControl && signals.textMentionsFocusGuard) ->
                    MasterRemovalActivity.Target.UNINSTALL
                signals.textMentionsFocusGuard -> MasterRemovalActivity.Target.APP_INFO
                else -> null
            }
            else -> null
        }

        val decision = SettingsInterceptionPolicy.decide(
            signals = signals,
            // Já confirmado pelas guardas acima; a política revalida por conta
            // própria porque é testada isoladamente.
            selfProtectionEngaged = true,
            strictPomodoroActive = isPomodoroStrictActive,
            deviceAdminActivationAuthorized = deviceAdminActivationAuthorized,
            maintenanceActive = deviceOwnerMaintenanceActive,
            rootSignals = SettingsInterceptionPolicy.RootSignals(
                mentionsAccessibility = ::rootMentionsAccessibility,
                mentionsDeviceAdmin = ::rootMentionsDeviceAdmin,
                mentionsFocusGuard = ::rootMentionsFocusGuard,
                mentionsDestructiveControl = ::rootMentionsDestructiveControl,
                mentionsEssentialSpecialAccess = ::rootMentionsEssentialSpecialAccess
            )
        )

        return when (decision) {
            SettingsInterceptionPolicy.Decision.IGNORE -> false

            SettingsInterceptionPolicy.Decision.POMODORO_LOCK -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                launchPomodoroLockScreen()
                true
            }

            SettingsInterceptionPolicy.Decision.PROTECT -> {
                val generation = executeProtectionAction(
                    eventTimeUptimeMillis = event.eventTime,
                    eventDeliveredAtUptimeMillis = eventDeliveredAtUptimeMillis,
                    eventDetectedAtNanos = eventDetectedAtNanos,
                    holdUntilSafeSurface = masterRemovalTarget != null,
                    forceLauncherFallback =
                        masterRemovalTarget == MasterRemovalActivity.Target.DEVICE_ADMIN
                )
                masterRemovalTarget?.let { launchMasterRemovalGate(it, generation) }
                true
            }

            SettingsInterceptionPolicy.Decision.PROTECT_AND_ARM_GUARD -> {
                pendingSettingsProtectionUntilElapsed =
                    nowElapsed + SETTINGS_TRANSITION_GUARD_MILLIS
                val generation = executeProtectionAction(
                    eventTimeUptimeMillis = event.eventTime,
                    eventDeliveredAtUptimeMillis = eventDeliveredAtUptimeMillis,
                    eventDetectedAtNanos = eventDetectedAtNanos,
                    holdUntilSafeSurface = masterRemovalTarget != null,
                    forceLauncherFallback =
                        masterRemovalTarget == MasterRemovalActivity.Target.DEVICE_ADMIN
                )
                masterRemovalTarget?.let { launchMasterRemovalGate(it, generation) }
                true
            }
        }
    }

    private fun SettingsSurface.toMasterRemovalTarget(): MasterRemovalActivity.Target = when (this) {
        SettingsSurface.APP_INFO -> MasterRemovalActivity.Target.APP_INFO
        SettingsSurface.DEVICE_ADMIN -> MasterRemovalActivity.Target.DEVICE_ADMIN
        SettingsSurface.ACCESSIBILITY -> MasterRemovalActivity.Target.ACCESSIBILITY
        SettingsSurface.UNINSTALL -> MasterRemovalActivity.Target.UNINSTALL
    }

    private fun launchMasterRemovalGate(
        target: MasterRemovalActivity.Target,
        curtainGeneration: Long
    ) {
        val intent = MasterRemovalActivity.createIntent(
            context = this,
            target = target,
            curtainGeneration = curtainGeneration
        ).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
        }
        runCatching {
            startActivity(intent)
        }.onFailure { error ->
            FocusGuardLogger.logError("MasterRemoval", "Falha ao abrir senha mestre", error)
        }
    }

    private fun sourceNodeForEvent(event: AccessibilityEvent): AccessibilityNodeInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            event.getSource(0)
        } else {
            event.source
        }

    /**
     * Bounded Device Admin row probe for textless/generic Settings clicks.
     *
     * One UI often reports the clicked row container instead of its visible label.
     * Search only that subtree and its immediate parent with the short locator
     * prefixes, stopping at the first confirmed Device Admin match. This avoids the
     * old broad root scan in the common Samsung path while preserving the general
     * OEM fallback if neither local probe is enough.
     */
    private fun fastDeviceAdminClickConfirmed(event: AccessibilityEvent): Boolean {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return false
        val source = sourceNodeForEvent(event) ?: return false
        return try {
            if (nodeTreeMentionsDeviceAdmin(source)) {
                true
            } else {
                val parent = runCatching { source.parent }.getOrNull()
                try {
                    parent?.let(::nodeTreeMentionsDeviceAdmin) == true
                } finally {
                    recycleSafely(parent)
                }
            }
        } finally {
            recycleSafely(source)
        }
    }

    private fun nodeTreeMentionsDeviceAdmin(node: AccessibilityNodeInfo): Boolean {
        if (ManagedSelfProtectionPolicy.textTargetsDeviceAdmin(
                listOf(node.text, node.contentDescription, node.viewIdResourceName)
            )
        ) return true

        return deviceAdminClickSearchTerms.any { term ->
            val nodes = runCatching {
                node.findAccessibilityNodeInfosByText(term)
            }.getOrDefault(emptyList())
            try {
                nodes.any { candidate ->
                    ManagedSelfProtectionPolicy.textTargetsDeviceAdmin(
                        listOf(
                            candidate.text,
                            candidate.contentDescription,
                            candidate.viewIdResourceName
                        )
                    )
                }
            } finally {
                nodes.forEach(::recycleSafely)
            }
        }
    }

    private fun eventTextValues(
        event: AccessibilityEvent,
        forceExpandClickContext: Boolean = false
    ): List<CharSequence?> {
        return buildList {
            addAll(event.text.orEmpty())
            add(event.contentDescription)
            sourceNodeForEvent(event)?.let { source ->
                add(source.text)
                add(source.contentDescription)
                add(source.viewIdResourceName)
                if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED &&
                    (forceExpandClickContext || shouldExpandClickContext(this))
                ) {
                    // Most buttons already expose enough text directly. Only pay
                    // for subtree/root queries when the clicked node itself has no
                    // locator that can identify FocusGuard, Device Admin or the
                    // accessibility disclosure.
                    clickInterceptionSearchTerms.forEach { term ->
                        val matchingNodes = runCatching {
                            source.findAccessibilityNodeInfosByText(term)
                        }.getOrDefault(emptyList())
                        matchingNodes.forEach { node ->
                            add(node.text)
                            add(node.contentDescription)
                            add(node.viewIdResourceName)
                            recycleSafely(node)
                        }
                    }
                    // A switch is commonly a sibling of the FocusGuard label,
                    // not its parent. Match only markers sharing the clicked
                    // control's horizontal row, instead of scanning/classifying
                    // the whole list and accidentally protecting other services.
                    addAll(sameRowClickTextValues(source))
                }
                recycleSafely(source)
            }
        }
    }

    private fun refreshSynchronousProtectionState() {
        refreshFocusModeFallbackState()
        val snapshot = SelfProtectionStateStore.read(applicationContext)
        blockedAppsSet = snapshot.blockedApps
        blockedWebsitesDomainSet = WebsiteBlocker.normalizeRules(snapshot.blockedSites)
        blockedWebsiteAppDomains = WebsiteBlocker.appPackageDomainsFor(blockedWebsitesDomainSet)
        isPomodoroStrictActive = snapshot.strictPomodoro
        isBlockingSessionActive = isSelfProtectionEngaged(
            cachedActive = isBlockingSessionActive,
            persistedActive = snapshot.armed,
            focusModeActive = focusModeSessionActive,
            armoredDeviceOwnerActive = deviceOwnerActiveCached &&
                deviceOwnerManager.isArmoredProtectionArmed()
        )
    }

    private fun refreshFocusModeFallbackState() {
        val session = FocusModeStore.readSession(applicationContext)
            ?.takeIf { it.isActive() }
        focusModeSessionActive = session != null
        val nativeLockdownActive = session != null &&
            FocusModePolicy.usesNativeFocusLockdown(
                deviceOwnerActive = deviceOwnerActiveCached,
                systemLockdownSupported =
                    deviceOwnerManager.isFocusModeSystemLockdownSupported()
            )
        focusModeFallbackActive = session != null && !nativeLockdownActive
        focusModeAllowedAppsSet = session?.allowedPackages.orEmpty()
        focusModeBlockedAppsSet = if (focusModeFallbackActive) {
            session?.blockedPackages.orEmpty()
        } else {
            emptySet()
        }
    }

    private fun redirectToFocusGuard(
        blockedPackage: String,
        eventUptimeMillis: Long = SystemClock.uptimeMillis()
    ) {
        val generation = showInstantBlockCurtain(mode = CurtainMode.BLOCK_NOTICE)
        awaitingSafeSurfaceGeneration = generation
        evictBlockedAppFromForeground()
        FocusGuardLogger.log(
            "FocusMode",
            "Modo Foco redirecionou $blockedPackage para o HardBlock"
        )
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                    )
                    putExtra(EXTRA_CURTAIN_GENERATION, generation)
                    putExtra(EXTRA_BLOCK_EVENT_UPTIME_MILLIS, eventUptimeMillis)
                    putExtra(FocusModeKioskController.EXTRA_RESTORE_FOCUS_MODE, true)
                }
            )
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "FocusMode",
                "Falha ao retornar ao FocusGuard",
                error
            )
            beginCurtainEvacuationBeforeHide(generation)
        }
    }

    private fun shouldExpandClickContext(values: Iterable<CharSequence?>): Boolean {
        // “admin” is deliberately only a locator: by itself it is too weak to prove
        // that the clicked node already contains the whole Device Admin context.
        if (ManagedSelfProtectionPolicy.textTargetsDeviceAdmin(values)) return false
        return values.none { value ->
            val text = value?.toString().orEmpty()
            text.isNotBlank() && directClickContextSufficientTerms.any { term ->
                text.contains(term, ignoreCase = true)
            }
        }
    }

    private fun sameRowClickTextValues(source: AccessibilityNodeInfo): List<CharSequence?> {
        val sourceBounds = Rect().also(source::getBoundsInScreen)
        if (sourceBounds.isEmpty || source.isScrollable) return emptyList()

        val root = rootInActiveWindow ?: return emptyList()
        return try {
            val rootBounds = Rect().also(root::getBoundsInScreen)
            if (!shouldSearchSameRowMarkers(sourceBounds, rootBounds)) return emptyList()

            buildList {
                clickInterceptionSearchTerms.forEach { term ->
                    val matchingNodes = runCatching {
                        root.findAccessibilityNodeInfosByText(term)
                    }.getOrDefault(emptyList())
                    matchingNodes.forEach { node ->
                        val nodeBounds = Rect().also(node::getBoundsInScreen)
                        if (boundsShareHorizontalRow(sourceBounds, nodeBounds)) {
                            add(node.text)
                            add(node.contentDescription)
                            add(node.viewIdResourceName)
                        }
                        recycleSafely(node)
                    }
                }
            }
        } finally {
            recycleSafely(root)
        }
    }

    private fun rootMentionsAccessibility(): Boolean {
        return rootContainsAny(
            searchTerms = AccessibilitySettingsPolicy.searchTerms,
            classifier = AccessibilitySettingsPolicy::textTargetsAccessibility,
            screenLabel = "Acessibilidade"
        )
    }

    private fun rootMentionsDeviceAdmin(): Boolean {
        return rootContainsAny(
            searchTerms = ManagedSelfProtectionPolicy.deviceAdminSearchTerms,
            classifier = ManagedSelfProtectionPolicy::textTargetsDeviceAdmin,
            screenLabel = "Administrador do dispositivo"
        )
    }

    private fun rootMentionsFocusGuard(): Boolean {
        return rootContainsAny(
            searchTerms = ManagedSelfProtectionPolicy.focusGuardSearchTerms,
            classifier = ManagedSelfProtectionPolicy::textTargetsFocusGuard,
            screenLabel = "controles do FocusGuard"
        )
    }

    private fun rootMentionsDestructiveControl(): Boolean {
        return rootContainsAny(
            searchTerms = ManagedSelfProtectionPolicy.destructiveControlSearchTerms,
            classifier = ManagedSelfProtectionPolicy::textTargetsDestructiveControl,
            screenLabel = "ação destrutiva"
        )
    }

    private fun rootMentionsEssentialSpecialAccess(): Boolean {
        return rootContainsAny(
            searchTerms = ManagedSelfProtectionPolicy.essentialSpecialAccessSearchTerms,
            classifier = ManagedSelfProtectionPolicy::textTargetsEssentialSpecialAccess,
            screenLabel = "acesso especial essencial"
        )
    }

    private fun rootContainsAny(
        searchTerms: Iterable<String>,
        classifier: (Iterable<CharSequence?>) -> Boolean,
        screenLabel: String
    ): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            searchTerms.any { term ->
                val nodes = root.findAccessibilityNodeInfosByText(term)
                val found = nodes.any { node ->
                    classifier(listOf(node.text, node.contentDescription, node.viewIdResourceName))
                }
                nodes.forEach(::recycleSafely)
                found
            }
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError(
                "A11y",
                "Falha ao identificar tela de $screenLabel",
                error
            )
            false
        } finally {
            recycleSafely(root)
        }
    }

    private fun executeProtectionAction(
        eventTimeUptimeMillis: Long,
        eventDeliveredAtUptimeMillis: Long,
        eventDetectedAtNanos: Long,
        holdUntilSafeSurface: Boolean = false,
        forceLauncherFallback: Boolean = false
    ): Long {
        // Segurança nunca sofre debounce: cada tentativa protegida expulsa Settings
        // imediatamente. A cortina, porém, é desenhada ANTES de pedir HOME para
        // cobrir também os frames da animação/transição do sistema.
        val nowElapsed = SystemClock.elapsedRealtime()
        val shouldReport = shouldExecuteProtectionAction(
            protectionActionUntilElapsed,
            nowElapsed
        )

        if (shouldReport) {
            protectionActionUntilElapsed = nowElapsed + SELF_PROTECTION_ACTION_DEBOUNCE_MILLIS
        }
        val alreadyAwaitingSafeSurface = shouldReuseAwaitedCurtain(
            holdUntilSafeSurface = holdUntilSafeSurface,
            awaitingGeneration = awaitingSafeSurfaceGeneration,
            curtainVisible = instantBlockCurtainVisible
        )
        val generation = if (!holdUntilSafeSurface && alreadyAwaitingSafeSurface) {
            renewInstantCurtainFailsafe()
            awaitingSafeSurfaceGeneration
        } else {
            showInstantBlockCurtain(
                mode = CurtainMode.SELF_PROTECTION,
                messageRes = R.string.accessibility_protection_blocked_notice
            )
        }
        if (holdUntilSafeSurface) awaitingSafeSurfaceGeneration = generation
        val curtainReadyAtNanos = SystemClock.elapsedRealtimeNanos()
        if (shouldReport) {
            CurtainFrameCommitTelemetry.register(
                curtain = instantBlockCurtain,
                generation = generation,
                currentGeneration = { instantBlockCurtainGeneration },
                eventDetectedAtNanos = eventDetectedAtNanos,
                curtainReadyAtNanos = curtainReadyAtNanos
            ) { sample ->
                scope.launch {
                    FocusGuardLogger.log(
                        "A11yLatency",
                        "Cortina frame commit: callback→frame=${sample.eventToFrameMicros}µs, " +
                            "cortina→frame=${sample.curtainToFrameMicros}µs"
                    )
                }
            }
        }
        mainHandler.removeCallbacks(protectionCurtainDismiss)
        if (!holdUntilSafeSurface && !alreadyAwaitingSafeSurface) {
            mainHandler.postDelayed(
                protectionCurtainDismiss,
                SELF_PROTECTION_NOTICE_DURATION_MILLIS
            )
        }

        if (shouldEvictForProtectionAttempt(alreadyAwaitingSafeSurface)) {
            evictBlockedAppFromForeground(forceLauncherFallback = forceLauncherFallback)
        }
        val homeRequestedAtNanos = SystemClock.elapsedRealtimeNanos()

        if (shouldReport) {
            showToastThrottled(getString(R.string.accessibility_protection_blocked_toast))
            recordSelfProtectionLatency(
                eventTimeUptimeMillis = eventTimeUptimeMillis,
                eventDeliveredAtUptimeMillis = eventDeliveredAtUptimeMillis,
                eventDetectedAtNanos = eventDetectedAtNanos,
                curtainReadyAtNanos = curtainReadyAtNanos,
                homeRequestedAtNanos = homeRequestedAtNanos
            )
        }
        return generation
    }

    private fun recordSelfProtectionLatency(
        eventTimeUptimeMillis: Long,
        eventDeliveredAtUptimeMillis: Long,
        eventDetectedAtNanos: Long,
        curtainReadyAtNanos: Long,
        homeRequestedAtNanos: Long
    ) {
        val eventDeliveryMicros = if (eventTimeUptimeMillis > 0L) {
            (eventDeliveredAtUptimeMillis - eventTimeUptimeMillis)
                .coerceAtLeast(0L) * 1_000L
        } else {
            0L
        }
        val eventToCurtainMicros =
            (curtainReadyAtNanos - eventDetectedAtNanos).coerceAtLeast(0L) / 1_000L
        val curtainToHomeMicros =
            (homeRequestedAtNanos - curtainReadyAtNanos).coerceAtLeast(0L) / 1_000L
        val totalMicros =
            (homeRequestedAtNanos - eventDetectedAtNanos).coerceAtLeast(0L) / 1_000L
        scope.launch {
            FocusGuardLogger.log(
                "A11yLatency",
                "Autoproteção: entrega=${eventDeliveryMicros}µs, " +
                    "callback→cortina=${eventToCurtainMicros}µs, " +
                    "cortina→HOME=${curtainToHomeMicros}µs, total=${totalMicros}µs"
            )
        }
    }

    private fun recordBrowserDiscoveryMiss(
        packageName: String,
        windowId: Int,
        checkedAtElapsed: Long
    ) {
        if (packageName !in browserDiscoveryMisses &&
            browserDiscoveryMisses.size >= MAX_BROWSER_DISCOVERY_MISSES
        ) {
            browserDiscoveryMisses.minByOrNull { it.value.checkedAtElapsed }
                ?.key
                ?.let(browserDiscoveryMisses::remove)
        }
        browserDiscoveryMisses[packageName] = BrowserDiscoveryMiss(
            windowId = windowId,
            checkedAtElapsed = checkedAtElapsed
        )
    }

    private fun currentBrowserSurfaceMatchesBlockedTransition(
        transition: WebsiteBlockTransitionHandle
    ): Boolean {
        if (!transitionWindowIsCurrent(transition)) return false
        val root = activeBrowserRoot(transition.browserPackageName, transition.expectedWindowId)
            ?: return false
        val currentAddress = try {
            WebsiteIdentificationEngine.identifyFromRoot(
                root, transition.browserPackageName, transition.expectedWindowId,
                isVerifiedHttpsHandler(transition.browserPackageName),
                isCurrent = { transitionWindowIsCurrent(transition) }
            ).bestCandidate
        } finally { recycleSafely(root) }
        if (!transitionWindowIsCurrent(transition)) return false
        val detected = transition.blockedCandidate?.takeIf(String::isNotBlank) ?: return false
        val detectedRule = WebsiteBlocker.findMatchingRule(detected, transition.blockedRules) ?: return false
        val currentRule = currentAddress?.let {
            WebsiteBlocker.findMatchingRule(it, transition.blockedRules)
        } ?: return false
        return detectedRule == currentRule
    }

    private fun activeBrowserRoot(
        browserPackageName: String,
        expectedWindowId: Int
    ): AccessibilityNodeInfo? = browserRootForExpectedWindow(
        browserPackageName = browserPackageName,
        expectedWindowId = expectedWindowId,
        requireActive = true
    )

    private fun browserRootForExpectedWindow(
        browserPackageName: String,
        expectedWindowId: Int,
        requireActive: Boolean
    ): AccessibilityNodeInfo? {
        if (browserPackageName.isBlank() || expectedWindowId < 0) return null
        val window = windows.firstOrNull {
            it.id == expectedWindowId && (!requireActive || it.isActive)
        } ?: return null
        val root = runCatching { window.root }.getOrNull() ?: return null
        val rootMatches = runCatching {
            root.packageName?.toString() == browserPackageName &&
                root.windowId == expectedWindowId
        }.getOrDefault(false)
        if (rootMatches) return root
        recycleSafely(root)
        return null
    }

    private fun handleImmediateBrowserAddressEvent(
        event: AccessibilityEvent,
        packageName: String
    ): Boolean {
        if (event.eventType !in immediateBrowserBlockEventTypes ||
            packageName !in browserPackages ||
            event.windowId < 0 ||
            blockedWebsitesDomainSet.isEmpty() ||
            websiteBlockTransitionGuard.isActive(packageName)
        ) return false

        val addressText = WebsiteBlocker.extractAddressBarTextFromEvent(
            event = event,
            browserPackageName = packageName,
            httpsHandlerRecognized = isVerifiedHttpsHandler(packageName)
        ) ?: return false
        val url = WebsiteBlocker.extractUrlCandidate(addressText)
        val blocked = immediateWebsiteBlockTarget(
            addressText = addressText,
            url = url,
            blockedRules = blockedWebsitesDomainSet
        ) ?: return false
        val candidate = url ?: addressText

        // Establish/update the same package/window generation used by the async path
        // before routing the block. This keeps transition guards bound to the exact
        // browser window that emitted the address-bar event.
        val offer = browserInspectionCoordinator.offer(
            packageName = packageName,
            windowId = event.windowId,
            eventType = event.eventType,
            eventUptimeMillis = event.eventTime,
            receivedUptimeMillis = SystemClock.uptimeMillis(),
            className = event.className?.toString().orEmpty(),
            directText = listOf(addressText),
            contentDescription = event.contentDescription?.toString()
        )
        if (offer.startWorker) scope.launch { drainBrowserInspections() }
        foregroundPackageName = packageName
        routeWebsiteBlockByHierarchy(
            browserPackageName = packageName,
            browserWindowId = event.windowId,
            blockedCandidate = candidate,
            detectionEventUptimeMillis = event.eventTime
        )
        return true
    }

    private fun scheduleBrowserInspection(event: AccessibilityEvent, packageName: String) {
        if (packageName.isBlank() || event.windowId < 0) return
        val offer = browserInspectionCoordinator.offer(
            packageName = packageName,
            windowId = event.windowId,
            eventType = event.eventType,
            eventUptimeMillis = event.eventTime,
            receivedUptimeMillis = SystemClock.uptimeMillis(),
            className = event.className?.toString().orEmpty(),
            directText = event.text.orEmpty().mapNotNull { it?.toString() },
            contentDescription = event.contentDescription?.toString()
        )
        retireStaleWebsiteTransitions()
        if (!offer.startWorker) return
        scope.launch { drainBrowserInspections() }
    }

    private suspend fun drainBrowserInspections() {
        var snapshot = browserInspectionCoordinator.takePending()
        while (snapshot != null) {
            try {
                val outcome = inspectBrowserSnapshot(snapshot)
                if (outcome != null) withContext(Dispatchers.Main.immediate) {
                    applyBrowserInspectionOutcome(outcome)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: RuntimeException) {
                FocusGuardLogger.logError("A11y", "Falha na inspeção do navegador", error)
            } finally {
                snapshot = browserInspectionCoordinator.finishPass()
            }
        }
    }

    private fun inspectBrowserSnapshot(
        snapshot: BrowserInspectionCoordinator.Snapshot
    ): BrowserInspectionOutcome? {
        val token = snapshot.token
        fun current() = browserInspectionCoordinator.isCurrent(token, requireLatestSequence = true)
        if (!current()) return null
        val root = activeBrowserRoot(token.packageName, token.windowId) ?: return null
        return try {
            if (!current() || root.packageName?.toString() != token.packageName ||
                root.windowId != token.windowId
            ) return null
            BrowserInspectionSessionStore.withInspection(root, token.packageName, ::current) {
                val identification = WebsiteIdentificationEngine.identifyFromRoot(
                    root, token.packageName, token.windowId,
                    isVerifiedHttpsHandler(token.packageName), isCurrent = ::current
                )
                val session = BrowserInspectionSessionStore.sessionFor(root, token.packageName)
                if (!current()) null else BrowserInspectionOutcome.from(
                    snapshot = snapshot,
                    identification = identification,
                    surface = session.surface ?: BrowserSurfaceInspector.Surface.UNKNOWN,
                    focusedAddressEditor = session.focusedAddressEditor,
                    recognizedBrowser = token.packageName in browserPackages ||
                        identification.addressBarObservable
                )
            }
        } finally {
            recycleSafely(root)
        }
    }

    private fun applyBrowserInspectionOutcome(outcome: BrowserInspectionOutcome) {
        val token = outcome.token
        if (!browserInspectionCoordinator.isCurrent(token, requireLatestSequence = true)) return
        val packageName = token.packageName
        if (!outcome.recognizedBrowser) {
            recordBrowserDiscoveryMiss(packageName, token.windowId, SystemClock.elapsedRealtime())
            return
        }
        if (packageName !in browserPackages) {
            browserPackages = browserPackages + packageName
            browserDiscoveryMisses.remove(packageName)
        }
        foregroundPackageName = packageName

        if (websiteBlockTransitionGuard.isActive(packageName)) {
            observeSafeDestinationFromInspection(outcome)
            return
        }

        if (outcome.surface == BrowserSurfaceInspector.Surface.NATIVE_PANEL) {
            clearOpaqueBrowserObservation(packageName)
            stopWebsiteTracking()
            return
        }

        val candidate = outcome.bestCandidate
        val blocked = immediateWebsiteBlockTarget(
            addressText = outcome.rawAddressText,
            url = outcome.urlCandidate,
            blockedRules = blockedWebsitesDomainSet
        )
        if (blocked != null && candidate != null &&
            browserInspectionCoordinator.isCurrent(token, requireLatestSequence = true)
        ) {
            routeWebsiteBlockByHierarchy(
                browserPackageName = packageName,
                browserWindowId = token.windowId,
                blockedCandidate = candidate,
                detectionEventUptimeMillis = outcome.eventUptimeMillis
            )
            return
        }

        val url = outcome.urlCandidate
        if (blockedWebsitesDomainSet.any(WebsiteBlocker::isPornographyRule) &&
            outcome.hasBlockedGoogleSearch()
        ) {
            blockWebsite(packageName, token.windowId, url, outcome.eventUptimeMillis)
            return
        }
        if (!url.isNullOrBlank()) {
            clearOpaqueBrowserObservation(packageName)
            updateWebsiteTracking(url, packageName, System.currentTimeMillis())
            return
        }

        if (outcome.surface == BrowserSurfaceInspector.Surface.WEB_CONTENT &&
            websiteObservationRequired() && !outcome.focusedAddressEditor
        ) {
            scheduleAsyncWebsiteRecovery(outcome)
        } else {
            clearOpaqueBrowserObservation(packageName)
            if (outcome.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) stopWebsiteTracking()
        }
    }

    private fun observeSafeDestinationFromInspection(outcome: BrowserInspectionOutcome) {
        val token = outcome.token
        val transition = websiteBlockTransitionGuard.transitionForConfirmation(
            browserPackageName = token.packageName,
            windowId = token.windowId,
            eventUptimeMillis = outcome.eventUptimeMillis,
            eventType = outcome.eventType
        ) ?: return
        if (!transitionWindowIsCurrent(transition) || !isSafeGoogleRedirectSurface(outcome.bestCandidate) ||
            outcome.surface != BrowserSurfaceInspector.Surface.WEB_CONTENT ||
            outcome.focusedAddressEditor
        ) return
        scope.launch {
            delay(WEBSITE_GOOGLE_SURFACE_SETTLE_MILLIS)
            if (!browserInspectionCoordinator.isCurrent(token, requireLatestSequence = true)) return@launch
            val stable = inspectBrowserSnapshot(BrowserInspectionCoordinator.Snapshot(
                token, outcome.eventType, outcome.eventUptimeMillis, SystemClock.uptimeMillis(),
                outcome.eventClassName, outcome.directEventText, outcome.eventContentDescription
            )) ?: return@launch
            if (!isSafeGoogleRedirectSurface(stable.bestCandidate) ||
                stable.surface != BrowserSurfaceInspector.Surface.WEB_CONTENT ||
                stable.focusedAddressEditor
            ) return@launch
            withContext(Dispatchers.Main.immediate) {
                if (!browserInspectionCoordinator.isCurrent(token, requireLatestSequence = true)) return@withContext
                val current = websiteBlockTransitionGuard.transitionForConfirmation(
                    browserPackageName = token.packageName,
                    windowId = token.windowId,
                    eventUptimeMillis = outcome.eventUptimeMillis,
                    eventType = outcome.eventType
                ) ?: return@withContext
                if (current.id != transition.id || !transitionWindowIsCurrent(current)) return@withContext
                if (websiteBlockTransitionGuard.confirmGoogle(
                        browserPackageName = token.packageName,
                        windowId = token.windowId,
                        eventUptimeMillis = outcome.eventUptimeMillis
                    )
                ) BrowserCompatibilityStore.recordNavigationConfirmed(token.packageName)
            }
        }
    }

    private fun scheduleAsyncWebsiteRecovery(outcome: BrowserInspectionOutcome) {
        if (!browserRecoveryCoordinator.offer(outcome.token)) return
        scope.launch {
            var next: BrowserInspectionCoordinator.Token? = outcome.token
            while (next != null) {
                val token = next
                val packageName = token.packageName
                fun current(): Boolean =
                    browserRecoveryCoordinator.isCurrent(token) &&
                        browserInspectionCoordinator.isCurrent(token, requireLatestSequence = true) &&
                        foregroundPackageName == packageName && websiteObservationRequired() &&
                        !websiteBlockTransitionGuard.isActive(packageName)
                try {
                    if (current()) {
                        delay(WebsiteObservabilityPolicy.OPAQUE_BROWSER_GRACE_MILLIS)
                        if (current()) {
                            val identification = WebsiteIdentificationRecovery(
                                browserPackage = packageName,
                                windowId = token.windowId,
                                httpsHandlerRecognized = isVerifiedHttpsHandler(packageName),
                                rootProvider = {
                                    if (current()) activeBrowserRoot(packageName, token.windowId) else null
                                },
                                isCurrent = ::current
                            ).recover()
                            withContext(Dispatchers.Main.immediate) {
                                if (!current()) return@withContext
                                val candidate = identification.bestCandidate
                                val url = identification.urlCandidate
                                val blocked = immediateWebsiteBlockTarget(
                                    identification.rawAddressText, url, blockedWebsitesDomainSet
                                )
                                when {
                                    blocked != null && candidate != null -> routeWebsiteBlockByHierarchy(
                                        packageName, token.windowId, candidate, SystemClock.uptimeMillis()
                                    )
                                    url != null -> updateWebsiteTracking(url, packageName, System.currentTimeMillis())
                                    identification.webContentObserved && !identification.addressBarObservable &&
                                        identification.status != WebsiteIdentificationStatus.REJECTED_CONTEXT ->
                                        blockOpaqueBrowser(packageName, ::current)
                                }
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: RuntimeException) {
                    if (current()) FocusGuardLogger.logError("A11y", "Falha na recuperação do navegador", error)
                } finally {
                    next = browserRecoveryCoordinator.finish(token)
                }
            }
        }
    }

    private fun websiteSurfaceInspectionNeeded(): Boolean =
        blockedWebsitesDomainSet.isNotEmpty() || limitedWebsiteDomains.isNotEmpty()

    private fun websiteObservationRequired(): Boolean =
        blockedWebsitesDomainSet.isNotEmpty() || hardLimitedWebsiteDomains.isNotEmpty()

    private fun clearOpaqueBrowserObservation(packageName: String) {
        browserRecoveryCoordinator.cancel(packageName)
    }

    private fun blockOpaqueBrowser(packageName: String, isCurrent: () -> Boolean) {
        if (!isCurrent()) return
        stopWebsiteTracking()
        if (!websiteObservationRequired() || foregroundPackageName != packageName) return
        FocusGuardLogger.log(
            "A11y",
            "URL não observável em $packageName; bloqueando o navegador fail-closed " +
                "enquanto a proteção de sites exige observação"
        )
        launchOpaqueBrowserFailClosedNotice(
            browserPackageName = packageName,
            eventUptimeMillis = SystemClock.uptimeMillis(),
            isCurrent = isCurrent
        )
    }

    private fun failClosedWebsiteTransition(transition: WebsiteBlockTransitionHandle) {
        if (!curtainReadyForTransition(transition)) return
        transition.handedOff = true
        launchOpaqueBrowserFailClosedNotice(
            transition.browserPackageName, SystemClock.uptimeMillis(), transition.curtainGeneration,
            isCurrent = { transitionWindowIsCurrent(transition) }
        )
    }

    private fun launchOpaqueBrowserFailClosedNotice(
        browserPackageName: String,
        eventUptimeMillis: Long,
        curtainGeneration: Long = 0L,
        isCurrent: () -> Boolean
    ) {
        if (!isCurrent()) return
        val canReuseCurtain = curtainGeneration > 0L && instantBlockCurtainAttached &&
            instantBlockCurtainVisible && instantBlockCurtainGeneration == curtainGeneration
        if (!canReuseCurtain) {
            launchBlockNotice(null, null, eventUptimeMillis = eventUptimeMillis, isCurrent = isCurrent)
            return
        }
        val intent = createBlockNoticeIntent(
            context = this,
            strictBlock = isPomodoroStrictActive,
            blockedPackage = null,
            blockedDomain = null,
            redirectBrowserPackage = null,
            curtainGeneration = curtainGeneration,
            eventUptimeMillis = eventUptimeMillis
        )
        if (!isCurrent()) return
        awaitingSafeSurfaceGeneration = curtainGeneration
        try {
            if (!isCurrent()) return
            startActivity(intent)
        } catch (error: RuntimeException) {
            if (isCurrent()) {
                FocusGuardLogger.logError("A11y", "Falha ao abrir bloqueio de $browserPackageName", error)
                beginCurtainEvacuationBeforeHide(curtainGeneration)
            } else dismissInstantBlockCurtain(curtainGeneration)
        }
    }

    private fun updateWebsiteTracking(urlOrDomain: String, packageName: String, now: Long) {
        val matchingRules = WebsiteBlocker.findMatchingRulesIgnoringGrants(
            urlOrDomain,
            limitedWebsiteDomains
        )
        if (matchingRules.isEmpty()) {
            stopWebsiteTracking(now)
            return
        }
        val pornographyGoogleSurface =
            WebsiteBlocker.isPornographySearchUrl(urlOrDomain) ||
                WebsiteBlocker.isGoogleImagesUrl(urlOrDomain)
        val usageDomain = if (
            PredefinedWebsites.PORNOGRAPHY_RULE in matchingRules &&
            pornographyGoogleSurface
        ) {
            PredefinedWebsites.PORNOGRAPHY_RULE
        } else {
            WebsiteBlocker.extractDomain(urlOrDomain)
                .ifBlank { WebsiteBlocker.normalizeRule(urlOrDomain) }
        }

        var usageToPersist: WebsiteUsageSlice? = null
        synchronized(websiteTrackingLock) {
            val previousDomain = trackedDomain
            val previousPackage = trackedPackageName
            if (previousDomain == usageDomain && previousPackage == packageName) {
                val delta = (now - trackedSinceMillis).coerceIn(0L, maxUsageDeltaMillis)
                if (delta >= 1_000L) {
                    usageToPersist = WebsiteUsageSlice(usageDomain, delta, packageName)
                    trackedSinceMillis = now
                }
            } else {
                if (previousDomain != null && previousPackage != null) {
                    val delta = (now - trackedSinceMillis).coerceIn(0L, maxUsageDeltaMillis)
                    if (delta >= 1_000L) {
                        usageToPersist = WebsiteUsageSlice(
                            previousDomain,
                            delta,
                            previousPackage
                        )
                    }
                }
                trackedDomain = usageDomain
                trackedPackageName = packageName
                trackedSinceMillis = now
            }
        }
        usageToPersist?.let(::persistWebsiteUsage)
        startWebsiteTrackingPulse()
    }

    private fun startWebsiteTrackingPulse() {
        if (websiteTrackingJob?.isActive == true) return
        websiteTrackingJob = scope.launch {
            while (isActive) {
                delay(websitePulseMillis)
                var usageToPersist: WebsiteUsageSlice? = null
                var shouldStopTracking = false
                synchronized(websiteTrackingLock) {
                    val domain = trackedDomain
                    val packageName = trackedPackageName
                    if (domain == null || packageName == null) {
                        return@launch
                    }
                    if (
                        !UsageLimitForegroundPolicy.shouldCountWebsiteUsage(
                            trackedPackageName = packageName,
                            foregroundPackageName = foregroundPackageName,
                            isDeviceInteractive = powerManager?.isInteractive == true
                        )
                    ) {
                        trackedDomain = null
                        trackedPackageName = null
                        trackedSinceMillis = 0L
                        shouldStopTracking = true
                        return@synchronized
                    }
                    val now = System.currentTimeMillis()
                    val delta = (now - trackedSinceMillis).coerceIn(0L, maxUsageDeltaMillis)
                    if (delta >= 1_000L) {
                        trackedSinceMillis = now
                        usageToPersist = WebsiteUsageSlice(domain, delta, packageName)
                    }
                }
                if (shouldStopTracking) return@launch
                usageToPersist?.let { persistWebsiteUsageNow(it) }
            }
        }
    }

    private fun stopWebsiteTracking(now: Long = System.currentTimeMillis()) {
        var usageToPersist: WebsiteUsageSlice? = null
        synchronized(websiteTrackingLock) {
            val domain = trackedDomain
            val packageName = trackedPackageName
            if (domain != null && packageName != null) {
                val delta = (now - trackedSinceMillis).coerceIn(0L, maxUsageDeltaMillis)
                if (delta >= 1_000L) {
                    usageToPersist = WebsiteUsageSlice(domain, delta, packageName)
                }
            }
            trackedDomain = null
            trackedPackageName = null
            trackedSinceMillis = 0L
        }
        websiteTrackingJob?.cancel()
        websiteTrackingJob = null
        usageToPersist?.let(::persistWebsiteUsage)
    }

    private fun persistWebsiteUsage(usage: WebsiteUsageSlice) {
        scope.launch {
            persistWebsiteUsageNow(usage)
        }
    }

    private suspend fun persistWebsiteUsageNow(usage: WebsiteUsageSlice) {
        try {
            val today = dateFormat.get()!!.format(Date())
            database.dailyUsageStatDao().addUsage(
                usage.domain,
                today,
                usage.deltaMillis
            )
            val limits = database.websiteUsageLimitDao().getAllStatic()
                .filter { it.isEnabled }
            val matchingRules = WebsiteBlocker.findMatchingRulesIgnoringGrants(
                usage.domain,
                WebsiteBlocker.normalizeRules(limits.map { it.domain })
            )
            if (matchingRules.isEmpty()) return

            val usageByRule = WebsiteUsageLimitPolicy.aggregateUsageByRule(
                usageByIdentifier = database.dailyUsageStatDao()
                    .getStatsForDateStatic(today)
                    .map { it.identifier to it.timeSpentMs },
                configuredRules = limits.map { it.domain }
            )
            val now = System.currentTimeMillis()
            val exceededRules = limits.mapNotNullTo(linkedSetOf()) { limit ->
                val rule = WebsiteBlocker.normalizeRule(limit.domain)
                rule.takeIf {
                    rule in matchingRules && WebsiteUsageLimitPolicy.shouldBlock(
                        usedMillis = usageByRule[rule] ?: 0L,
                        dailyLimitMinutes = limit.dailyLimitMinutes,
                        lockMode = limit.lockMode,
                        lockUntilTimestamp = limit.lockUntilTimestamp,
                        nowMillis = now
                    )
                }
            }
            if (exceededRules.isNotEmpty()) {
                enforceExceededWebsiteImmediately(usage, exceededRules)
                sessionManager.checkAndEnforce()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            FocusGuardLogger.logError(
                "A11y",
                "Falha ao registrar uso de ${usage.domain}",
                error
            )
        }
    }

    private fun enforceExceededWebsiteImmediately(
        usage: WebsiteUsageSlice,
        exceededRules: Set<String>
    ) {
        scope.launch(Dispatchers.Main) {
            val stillActive = synchronized(websiteTrackingLock) {
                trackedDomain == usage.domain && trackedPackageName == usage.packageName
            }
            if (!stillActive) return@launch

            strongerWebsiteDomainSet = WebsiteBlocker.normalizeRules(
                strongerWebsiteDomainSet + exceededRules
            )
            exceededRules.forEach(PasswordTargetAccessGrant::claimStrongerWebsiteProtection)
            blockedWebsitesDomainSet = blockedWebsitesDomainSet + exceededRules
            if (usage.packageName in browserPackages) {
                enforceCurrentForegroundFromSnapshot()
            } else {
                val displayRule = exceededRules.firstOrNull() ?: usage.domain
                blockedWebsiteAppDomains = blockedWebsiteAppDomains +
                    (usage.packageName to displayRule)
                blockWebsiteApp(displayRule)
            }
        }
    }

    private fun blockApp(
        packageName: String,
        eventUptimeMillis: Long = SystemClock.uptimeMillis()
    ) {
        launchBlockNotice(
            blockedPackage = packageName,
            blockedDomain = null,
            eventUptimeMillis = eventUptimeMillis
        )
    }

    private fun routeWebsiteBlockByHierarchy(
        browserPackageName: String,
        browserWindowId: Int,
        blockedCandidate: String,
        detectionEventUptimeMillis: Long
    ) {
        val token = browserInspectionCoordinator.currentToken(browserPackageName) ?: return
        if (token.windowId != browserWindowId) return
        if (!isPomodoroStrictActive) {
            val resolution = WebsiteProtectionHierarchyPolicy.resolve(
                candidate = blockedCandidate,
                passwordRules = passwordWebsiteDomainSet,
                strongerRules = strongerWebsiteDomainSet
            )
            if (resolution.owner == WebsiteProtectionHierarchyPolicy.Owner.PASSWORD) {
                stopWebsiteTracking()
                launchBlockNotice(
                    blockedPackage = null,
                    blockedDomain = WebsiteBlocker.displayRule(
                        resolution.matchedRule ?: blockedCandidate
                    ),
                    redirectBrowserPackage = browserPackageName,
                    eventUptimeMillis = detectionEventUptimeMillis,
                    isCurrent = { browserInspectionCoordinator.isCurrent(token, requireLatestSequence = true) }
                )
                return
            }
        }

        // HARD or stale/unknown ownership stays fail-closed and uses the
        // existing opaque-curtain + safe-browser redirect pipeline.
        blockWebsite(
            browserPackageName = browserPackageName,
            browserWindowId = browserWindowId,
            blockedCandidate = blockedCandidate,
            detectionEventUptimeMillis = detectionEventUptimeMillis
        )
    }

    private fun blockWebsite(
        browserPackageName: String,
        browserWindowId: Int = INVALID_BROWSER_WINDOW_ID,
        blockedCandidate: String? = null,
        detectionEventUptimeMillis: Long = 0L
    ) {
        val now = System.currentTimeMillis()
        stopWebsiteTracking(now)
        startWebsiteBlockTransition(
            browserPackageName = browserPackageName,
            browserWindowId = browserWindowId,
            blockedCandidate = blockedCandidate,
            detectionEventUptimeMillis = detectionEventUptimeMillis
        )
    }

    private fun blockWebsiteApp(domain: String) {
        stopWebsiteTracking()
        launchBlockNotice(
            blockedPackage = null,
            blockedDomain = WebsiteBlocker.displayRule(domain)
        )
    }

    private fun startWebsiteBlockTransition(
        browserPackageName: String,
        browserWindowId: Int = INVALID_BROWSER_WINDOW_ID,
        blockedCandidate: String? = null,
        detectionEventUptimeMillis: Long = 0L
    ) {
        val expectedWindowId = browserWindowId.takeIf { it >= 0 } ?: return
        val inspectionGeneration = browserInspectionCoordinator.currentGeneration(
            browserPackageName,
            expectedWindowId
        ) ?: return
        val strict = isPomodoroStrictActive
        val transitionId = websiteBlockTransitionCounter.incrementAndGet()
        val transition = websiteBlockTransitionGuard.tryStart(
            browserPackageName = browserPackageName,
            transitionId = transitionId,
            destination = if (strict) {
                WebsiteTransitionDestination.POMODORO
            } else {
                WebsiteTransitionDestination.GOOGLE
            },
            expectedWindowId = expectedWindowId,
            inspectionGeneration = inspectionGeneration,
            blockedCandidate = blockedCandidate,
            blockedRules = blockedWebsitesDomainSet,
            detectionEventUptimeMillis = detectionEventUptimeMillis
        ) ?: return
        val stateMachine = WebsiteBlockTransitionStateMachine(strict = strict)
        val initialActions = stateMachine.begin()
        val curtainGeneration = if (
            WebsiteTransitionAction.SHOW_CURTAIN in initialActions
        ) {
            showInstantBlockCurtain(mode = CurtainMode.BLOCK_NOTICE)
        } else {
            0L
        }
        if (curtainGeneration <= 0L ||
            !websiteBlockTransitionGuard.markCurtainGeneration(
                browserPackageName = browserPackageName,
                transitionId = transitionId,
                curtainGeneration = curtainGeneration
            )
        ) {
            websiteBlockTransitionGuard.finish(browserPackageName, transitionId)
            return
        }
        awaitingSafeSurfaceGeneration = curtainGeneration
        val curtainShownAtUptimeMillis = SystemClock.uptimeMillis()

        scope.launch(Dispatchers.Main.immediate) {
            try {
                // Let the already-warm overlay commit one display frame before
                // touching the browser UI. This is normally only 8-17 ms and
                // avoids exposing a flash of the blocked page during redirect.
                awaitNextWebsiteRedirectFrame()
                if (!curtainReadyForTransition(transition)) return@launch

                val rewritePolicy = WebsiteTabNeutralizationPolicy(
                    browserPackageName = browserPackageName,
                    expectedWindowId = expectedWindowId
                )
                var redirectRequested = requestSafeGoogleInCurrentTab(
                    browserPackageName = browserPackageName,
                    expectedWindowId = expectedWindowId,
                    policy = rewritePolicy,
                    transition = transition
                )

                // Same-tab rewrite is the only website redirect path. If the first
                // attempt raced an editor/focus animation, restore the exact blocked surface
                // and retry once with a fresh capability state. We still never close a tab,
                // launch a second browser document or evict the browser to HOME.
                if (!redirectRequested) {
                    val blockedSurfaceRestored =
                        restoreBlockedSurfaceAfterAddressEdit(transition)
                    if (blockedSurfaceRestored && curtainReadyForTransition(transition)) {
                        FocusGuardLogger.log(
                            "A11y",
                            "Repetindo redirecionamento seguro na mesma aba de $browserPackageName"
                        )
                        delay(WEBSITE_ADDRESS_BAR_ACTION_RETRY_MILLIS)
                        if (!curtainReadyForTransition(transition)) return@launch
                        redirectRequested = requestSafeGoogleInCurrentTab(
                            browserPackageName = browserPackageName,
                            expectedWindowId = expectedWindowId,
                            policy = WebsiteTabNeutralizationPolicy(
                                browserPackageName = browserPackageName,
                                expectedWindowId = expectedWindowId
                            ),
                            transition = transition
                        )
                    }
                }

                if (!redirectRequested &&
                    supportsCapabilityBasedIntentRedirectFallback(
                        knownBrowser = browserPackageName in knownBrowserPackages,
                        verifiedHttpsHandler = isVerifiedHttpsHandler(browserPackageName)
                    )
                ) {
                    // Any recognized browser with independently verified browser
                    // capability may use the safe ACTION_VIEW fallback after the two
                    // certified same-tab attempts are exhausted. The blocked surface
                    // is revalidated by rule before the browser is asked to open the
                    // Google homepage; the curtain remains until Google is confirmed.
                    val blockedSurfaceRestored =
                        restoreBlockedSurfaceForSafeIntentFallback(transition)
                    if (blockedSurfaceRestored && curtainReadyForTransition(transition)) {
                        FocusGuardLogger.log(
                            "A11y",
                            "Usando fallback seguro por intent para $browserPackageName"
                        )
                        redirectRequested = requestSafeGoogleThroughBrowserIntent(transition)
                    }
                }

                if (!curtainReadyForTransition(transition)) return@launch
                if (!redirectRequested) {
                    FocusGuardLogger.log(
                        "A11y",
                        "Redirecionamento seguro não pôde ser certificado para " +
                            "$browserPackageName (API ${Build.VERSION.SDK_INT}); bloqueando fail-closed"
                    )
                    stateMachine.onFailureOrTimeout()
                    failClosedWebsiteTransition(transition)
                    return@launch
                }

                val googleConfirmed = withTimeoutOrNull(
                    WEBSITE_DESTINATION_CONFIRM_TIMEOUT_MILLIS
                ) {
                    transition.safeGoogleConfirmed.await()
                    true
                } == true
                if (!curtainReadyForTransition(transition)) return@launch
                if (!googleConfirmed) {
                    stateMachine.onFailureOrTimeout()
                    failClosedWebsiteTransition(transition)
                    return@launch
                }

                when (stateMachine.afterGoogleSanitized()) {
                    WebsiteTransitionAction.HIDE_CURTAIN -> {
                        releaseWebsiteCurtainAfterMinimumNotice(
                            curtainGeneration = curtainGeneration,
                            curtainShownAtUptimeMillis = curtainShownAtUptimeMillis
                        )
                    }

                    WebsiteTransitionAction.OPEN_POMODORO -> {
                        if (completeStrictWebsiteDestination(
                                transition = transition,
                                curtainGeneration = curtainGeneration
                            )
                        ) {
                            stateMachine.onPomodoroConfirmed()
                            releaseWebsiteCurtainAfterMinimumNotice(
                                curtainGeneration = curtainGeneration,
                                curtainShownAtUptimeMillis = curtainShownAtUptimeMillis
                            )
                        } else {
                            stateMachine.onFailureOrTimeout()
                            failClosedWebsiteTransition(transition)
                        }
                    }

                    else -> {
                        stateMachine.onFailureOrTimeout()
                        failClosedWebsiteTransition(transition)
                    }
                }
            } finally {
                finishWebsiteTransition(transition)
            }
        }
    }

    private suspend fun releaseWebsiteCurtainAfterMinimumNotice(
        curtainGeneration: Long,
        curtainShownAtUptimeMillis: Long
    ) {
        val visibleFor = SystemClock.uptimeMillis() - curtainShownAtUptimeMillis
        val remaining = WEBSITE_MIN_BLOCK_NOTICE_MILLIS - visibleFor
        if (remaining > 0L) delay(remaining)
        dismissInstantBlockCurtain(curtainGeneration)
    }

    private suspend fun awaitNextWebsiteRedirectFrame() {
        suspendCancellableCoroutine<Unit> { continuation ->
            val choreographer = Choreographer.getInstance()
            val callback = Choreographer.FrameCallback {
                if (continuation.isActive) continuation.resume(Unit)
            }
            continuation.invokeOnCancellation {
                choreographer.removeFrameCallback(callback)
            }
            choreographer.postFrameCallback(callback)
        }
    }

    private suspend fun requestSafeGoogleInCurrentTab(
        browserPackageName: String,
        expectedWindowId: Int,
        policy: WebsiteTabNeutralizationPolicy,
        transition: WebsiteBlockTransitionHandle
    ): Boolean {
        if (!curtainReadyForTransition(transition)) return false
        val setRequestedAt = websiteTreeWorker.run {
            prepareSafeAddressBar(
                browserPackageName = browserPackageName,
                expectedWindowId = expectedWindowId,
                policy = policy,
                transition = transition,
                phaseStartedAtUptimeMillis = transition.detectionEventUptimeMillis
            )
        }
        if (!curtainReadyForTransition(transition)) return false
        if (afterSafeAddressSet(setRequestedAt > 0L) !=
            WebsiteSanitizationDecision.SUBMIT_ADDRESS_BAR
        ) return false

        policy.markSafeAddressSet(setRequestedAt)
        val submitRequestedAt = websiteTreeWorker.run {
            submitSafeAddressBar(
                browserPackageName = browserPackageName,
                expectedWindowId = expectedWindowId,
                policy = policy,
                transition = transition
            )
        }
        if (!curtainReadyForTransition(transition)) return false
        if (afterSafeAddressSubmit(submitRequestedAt > 0L) !=
            WebsiteSanitizationDecision.AWAIT_GOOGLE_CONFIRMATION
        ) return false

        policy.markRedirectRequested()
        return websiteBlockTransitionGuard.markSanitizationRequested(
            browserPackageName = browserPackageName,
            transitionId = transition.id,
            requestedAtUptimeMillis = submitRequestedAt
        )
    }

    private fun performTransitionBack(transition: WebsiteBlockTransitionHandle): Boolean {
        if (!curtainReadyForTransition(transition)) return false
        val accepted = performGlobalAction(GLOBAL_ACTION_BACK)
        return curtainReadyForTransition(transition) && accepted
    }

    private suspend fun restoreBlockedSurfaceAfterAddressEdit(
        transition: WebsiteBlockTransitionHandle
    ): Boolean {
        if (!curtainReadyForTransition(transition)) return false
        if (transition.activatedAddressViewId == null && transition.editorAddressViewId == null) return true

        // BACK is allowed only while the exact safe URL is still being edited.
        // If submission already navigated away from the blocked page, going BACK
        // would resurrect that blocked page from Firefox history.
        val https = isVerifiedHttpsHandler(transition.browserPackageName)
        val editorRoot = activeBrowserRoot(
            transition.browserPackageName,
            transition.expectedWindowId
        ) ?: return false
        val safeRedirectStillEditing = try {
            AddressBarRedirectionActions.hasFocusedAddressEditor(
                editorRoot,
                transition.browserPackageName,
                transition.expectedWindowId,
                https,
                ::isSafeGoogleRedirectSurface
            )
        } finally { recycleSafely(editorRoot) }
        if (!curtainReadyForTransition(transition)) return false

        val blockedSurfaceStillCurrent = if (safeRedirectStillEditing) {
            false
        } else {
            websiteTreeWorker.run { currentBrowserSurfaceMatchesBlockedTransition(transition) }
        }
        if (!curtainReadyForTransition(transition)) return false

        when (websiteRestoreDecision(
            safeRedirectStillEditing = safeRedirectStillEditing,
            blockedSurfaceStillCurrent = blockedSurfaceStillCurrent
        )) {
            WebsiteRestoreDecision.RETRY_CURRENT_BLOCKED_SURFACE -> {
                transition.activatedAddressViewId = null
                transition.editorAddressViewId = null
                return true
            }
            WebsiteRestoreDecision.KEEP_CURRENT_SURFACE -> {
                transition.activatedAddressViewId = null
                transition.editorAddressViewId = null
                return false
            }
            WebsiteRestoreDecision.BACK_TO_BLOCKED_SURFACE -> Unit
        }

        if (!performTransitionBack(transition)) return false
        delay(WEBSITE_ADDRESS_BAR_FOCUS_SETTLE_MILLIS)
        if (!curtainReadyForTransition(transition)) return false
        val restored = websiteTreeWorker.run {
            currentBrowserSurfaceMatchesBlockedTransition(transition)
        }
        if (!curtainReadyForTransition(transition)) return false
        transition.activatedAddressViewId = null
        transition.editorAddressViewId = null
        return restored
    }

    private suspend fun restoreBlockedSurfaceForSafeIntentFallback(
        transition: WebsiteBlockTransitionHandle
    ): Boolean {
        if (!restoreBlockedSurfaceAfterAddressEdit(transition)) return false
        if (!curtainReadyForTransition(transition)) return false
        val blockedSurfaceStillCurrent = websiteTreeWorker.run {
            currentBrowserSurfaceMatchesBlockedTransition(transition)
        }
        return curtainReadyForTransition(transition) && blockedSurfaceStillCurrent
    }

    private suspend fun requestSafeGoogleThroughBrowserIntent(
        transition: WebsiteBlockTransitionHandle
    ): Boolean {
        if (!curtainReadyForTransition(transition)) return false
        val blockedSurfaceStillCurrent = websiteTreeWorker.run {
            currentBrowserSurfaceMatchesBlockedTransition(transition)
        }
        if (!curtainReadyForTransition(transition) || !blockedSurfaceStillCurrent) return false

        return withContext(Dispatchers.Main.immediate) {
            if (!curtainReadyForTransition(transition)) return@withContext false
            val intent = createSafeBrowserRedirectIntent(transition.browserPackageName)
            if (!websiteBlockTransitionGuard.markExternalRedirectRequested(
                    transition.browserPackageName, transition.id, SystemClock.uptimeMillis()
                )
            ) return@withContext false
            try {
                if (!curtainReadyForTransition(transition)) return@withContext false
                startActivity(intent)
                true
            } catch (error: RuntimeException) {
                if (transitionWindowIsCurrent(transition)) {
                    FocusGuardLogger.logError(
                        "A11y",
                        "Falha ao solicitar redirecionamento seguro",
                        error
                    )
                }
                false
            }
        }
    }

    private suspend fun completeStrictWebsiteDestination(
        transition: WebsiteBlockTransitionHandle,
        curtainGeneration: Long
    ): Boolean {
        if (!curtainReadyForTransition(transition)) return false
        val requestedAt = SystemClock.uptimeMillis()
        if (!websiteBlockTransitionGuard.markDestinationRequested(
                browserPackageName = transition.browserPackageName,
                transitionId = transition.id,
                requestedAtUptimeMillis = requestedAt
            ) || !launchPomodoroLockForWebsiteTransition(curtainGeneration, transition)
        ) return false
        return withTimeoutOrNull(WEBSITE_DESTINATION_CONFIRM_TIMEOUT_MILLIS) {
            transition.destinationConfirmed.await()
            true
        } == true
    }

    private suspend fun prepareSafeAddressBar(
        browserPackageName: String,
        expectedWindowId: Int,
        policy: WebsiteTabNeutralizationPolicy,
        transition: WebsiteBlockTransitionHandle,
        phaseStartedAtUptimeMillis: Long
    ): Long {
        if (!curtainReadyForTransition(transition)) return 0L
        val https = isVerifiedHttpsHandler(browserPackageName)
        val clickFirst = BrowserUiCapabilityPolicy.prefersClickAddressBarActivation(browserPackageName)
        val activations = if (clickFirst) listOf(BrowserUiCapabilityPolicy.NodeAction.CLICK, BrowserUiCapabilityPolicy.NodeAction.FOCUS)
            else listOf(BrowserUiCapabilityPolicy.NodeAction.FOCUS, BrowserUiCapabilityPolicy.NodeAction.CLICK)
        var activated = false
        for (action in activations) {
            if (!curtainReadyForTransition(transition)) return 0L
            val root = activeBrowserRoot(browserPackageName, expectedWindowId) ?: return 0L
            val result = try {
                if (!policy.mayActivateBlockedAddressBar(browserPackageName, root.windowId,
                        phaseStartedAtUptimeMillis, transition.latestWindowTransitionEventUptimeMillis) ||
                    (!activated && !rootStillShowsDetectedBlockedTarget(root, transition)) ||
                    BrowserSurfaceInspector.inspect(root, browserPackageName) == BrowserSurfaceInspector.Surface.NATIVE_PANEL
                ) return 0L
                AddressBarRedirectionActions.activate(
                    root, browserPackageName, expectedWindowId, action, https,
                    isCurrent = { curtainReadyForTransition(transition) }
                )
            } finally { recycleSafely(root) }
            if (!curtainReadyForTransition(transition)) return 0L
            if (result.status == AddressBarRedirectionActions.Status.AMBIGUOUS) return 0L
            activated = activated || result.accepted
            if (result.accepted) transition.activatedAddressViewId = result.selectedViewId
            val deadline = SystemClock.uptimeMillis() +
                websiteAddressBarActionTimeoutMillis(browserPackageName)
            var editorReady = false
            while (curtainReadyForTransition(transition) && SystemClock.uptimeMillis() <= deadline) {
                if (!curtainReadyForTransition(transition)) return 0L
                delay(WEBSITE_ADDRESS_BAR_FOCUS_SETTLE_MILLIS)
                if (!curtainReadyForTransition(transition)) return 0L
                val fresh = activeBrowserRoot(browserPackageName, expectedWindowId) ?: continue
                editorReady = try {
                    AddressBarRedirectionActions.hasFocusedAddressEditor(fresh, browserPackageName, expectedWindowId, https)
                } finally { recycleSafely(fresh) }
                if (editorReady) break
            }
            if (!editorReady) continue // Accepted focus/click without an editor is not success.
            val writes = (listOfNotNull(BrowserCompatibilityStore.preferredWriteMethod(browserPackageName)) +
                listOf(BrowserWriteMethod.SET_TEXT, BrowserWriteMethod.PASTE)).distinct()
            for (method in writes) {
                if (!curtainReadyForTransition(transition)) return 0L
                if (method == BrowserWriteMethod.PASTE) {
                    val selectionRoot = activeBrowserRoot(browserPackageName, expectedWindowId) ?: return 0L
                    val selection = try {
                        AddressBarRedirectionActions.selectAll(
                            selectionRoot, browserPackageName, expectedWindowId, https,
                            isCurrent = { curtainReadyForTransition(transition) }
                        )
                    } finally { recycleSafely(selectionRoot) }
                    if (selection.status == AddressBarRedirectionActions.Status.AMBIGUOUS) return 0L
                    if (!selection.accepted) continue // Never append the safe URL to existing text.
                    if (!curtainReadyForTransition(transition)) return 0L
                    delay(WEBSITE_ADDRESS_BAR_FOCUS_SETTLE_MILLIS)
                    if (!curtainReadyForTransition(transition)) return 0L
                }
                val editRoot = activeBrowserRoot(browserPackageName, expectedWindowId) ?: return 0L
                val writtenAt = SystemClock.uptimeMillis()
                val written = try {
                    if (!curtainReadyForTransition(transition)) return 0L
                    if (!policy.mayTouchBlockedTab(browserPackageName, editRoot.windowId)) return 0L
                    when (method) {
                        BrowserWriteMethod.SET_TEXT -> AddressBarRedirectionActions.setText(
                            editRoot, browserPackageName, expectedWindowId, SAFE_REDIRECT_URL, https,
                            isCurrent = { curtainReadyForTransition(transition) }
                        )
                        BrowserWriteMethod.PASTE -> ClipboardPasteFallback.pasteSafely(SAFE_REDIRECT_URL) {
                            AddressBarRedirectionActions.paste(
                                editRoot, browserPackageName, expectedWindowId, https,
                                isCurrent = { curtainReadyForTransition(transition) }
                            )
                        }
                    }
                } finally { recycleSafely(editRoot) }
                if (!curtainReadyForTransition(transition)) return 0L
                if (written.status == AddressBarRedirectionActions.Status.AMBIGUOUS) return 0L
                val writeDeadline = SystemClock.uptimeMillis() +
                    websiteAddressBarActionTimeoutMillis(browserPackageName)
                while (curtainReadyForTransition(transition) && SystemClock.uptimeMillis() <= writeDeadline) {
                    if (!curtainReadyForTransition(transition)) return 0L
                    delay(WEBSITE_ADDRESS_BAR_FOCUS_SETTLE_MILLIS)
                    if (!curtainReadyForTransition(transition)) return 0L
                    val fresh = activeBrowserRoot(browserPackageName, expectedWindowId) ?: continue
                    val verified = try {
                        AddressBarRedirectionActions.hasFocusedAddressEditor(fresh, browserPackageName,
                            expectedWindowId, https, ::isSafeGoogleRedirectSurface)
                    } finally { recycleSafely(fresh) }
                    if (verified) {
                        if (!curtainReadyForTransition(transition)) return 0L
                        transition.editorAddressViewId = written.selectedViewId
                        BrowserCompatibilityStore.recordActivationSuccess(browserPackageName, result.selectedViewId,
                            if (action == BrowserUiCapabilityPolicy.NodeAction.CLICK) BrowserActivationMethod.CLICK else BrowserActivationMethod.FOCUS)
                        BrowserCompatibilityStore.recordWriteSuccess(browserPackageName, written.selectedViewId, method, SAFE_REDIRECT_URL)
                        return writtenAt
                    }
                }
            }
        }
        return 0L
    }

    private suspend fun submitSafeAddressBar(
        browserPackageName: String,
        expectedWindowId: Int,
        policy: WebsiteTabNeutralizationPolicy,
        transition: WebsiteBlockTransitionHandle
    ): Long {
        if (!curtainReadyForTransition(transition)) return 0L
        val https = isVerifiedHttpsHandler(browserPackageName)
        val methods = (listOfNotNull(BrowserCompatibilityStore.preferredSubmitMethod(browserPackageName)) +
            listOf(BrowserSubmitMethod.IME_ENTER, BrowserSubmitMethod.ANNOUNCED_EDITOR_ACTION,
                BrowserSubmitMethod.CERTIFIED_GO_BUTTON)).distinct()
        for (method in methods) {
            if (method == BrowserSubmitMethod.IME_ENTER && !canUseCertifiableImeSubmit(Build.VERSION.SDK_INT)) continue
            if (!curtainReadyForTransition(transition)) return 0L
            val root = activeBrowserRoot(browserPackageName, expectedWindowId) ?: return 0L
            val submittedAt = SystemClock.uptimeMillis()
            val submitted = try {
                if (!policy.maySubmitSafeAddress(browserPackageName, root.windowId,
                        transition.latestWindowTransitionEventUptimeMillis) ||
                    !AddressBarRedirectionActions.hasFocusedAddressEditor(root, browserPackageName,
                        expectedWindowId, https, ::isSafeGoogleRedirectSurface)
                ) return 0L
                when (method) {
                    BrowserSubmitMethod.IME_ENTER -> AddressBarRedirectionActions.submitImeEnter(
                        root, browserPackageName, expectedWindowId, ::isSafeGoogleRedirectSurface, https,
                        isCurrent = { curtainReadyForTransition(transition) }
                    )
                    BrowserSubmitMethod.ANNOUNCED_EDITOR_ACTION -> AddressBarRedirectionActions.submitAnnouncedEditorAction(
                        root, browserPackageName, expectedWindowId, ::isSafeGoogleRedirectSurface, https,
                        isCurrent = { curtainReadyForTransition(transition) }
                    )
                    BrowserSubmitMethod.CERTIFIED_GO_BUTTON -> AddressBarRedirectionActions.clickCertifiedGoButton(
                        root, browserPackageName, expectedWindowId,
                        isCurrent = { curtainReadyForTransition(transition) }
                    )
                }
            } finally { recycleSafely(root) }
            if (!curtainReadyForTransition(transition)) return 0L
            if (submitted.status == AddressBarRedirectionActions.Status.AMBIGUOUS) return 0L
            if (!submitted.accepted) {
                if (!curtainReadyForTransition(transition)) return 0L
                delay(WEBSITE_ADDRESS_BAR_FOCUS_SETTLE_MILLIS)
                if (!curtainReadyForTransition(transition)) return 0L
                continue
            }
            if (!curtainReadyForTransition(transition)) return 0L
            BrowserCompatibilityStore.recordSubmitAccepted(browserPackageName, submitted.selectedViewId, method)
            websiteBlockTransitionGuard.markSanitizationRequested(browserPackageName, transition.id, submittedAt)
            val deadline = SystemClock.uptimeMillis() + WEBSITE_DESTINATION_CONFIRM_TIMEOUT_MILLIS
            while (curtainReadyForTransition(transition) && SystemClock.uptimeMillis() <= deadline) {
                if (transition.safeGoogleConfirmed.isCompleted) return submittedAt
                if (!curtainReadyForTransition(transition)) return 0L
                delay(WEBSITE_GOOGLE_SURFACE_SETTLE_MILLIS)
                if (!curtainReadyForTransition(transition)) return 0L
            }
            // An accepted submit action is not navigation proof. Only a positively
            // confirmed Google surface may complete this attempt. If the editor
            // disappeared without confirmation, stop touching that surface and let
            // the guarded restore/retry/intent fallback revalidate what is current.
            if (!curtainReadyForTransition(transition)) return 0L
            if (mayOpenDestinationAfterSanitization(
                    safeGoogleConfirmed = transition.safeGoogleConfirmed.isCompleted
                )
            ) return submittedAt
            if (confirmSafeGoogleFromFreshBrowserSurface(transition)) return submittedAt
            val fresh = activeBrowserRoot(browserPackageName, expectedWindowId) ?: return 0L
            val stillEditing = try {
                AddressBarRedirectionActions.hasFocusedAddressEditor(fresh, browserPackageName,
                    expectedWindowId, https, ::isSafeGoogleRedirectSurface)
            } finally { recycleSafely(fresh) }
            if (!stillEditing) return 0L
        }
        return 0L
    }

    private suspend fun confirmSafeGoogleFromFreshBrowserSurface(
        transition: WebsiteBlockTransitionHandle
    ): Boolean {
        if (!curtainReadyForTransition(transition) ||
            !transition.sanitizationRequested ||
            transition.externalRedirectRequested
        ) return false

        repeat(2) { pass ->
            val root = activeBrowserRoot(
                transition.browserPackageName,
                transition.expectedWindowId
            ) ?: return false
            val stableSafeSurface = try {
                val identification = WebsiteIdentificationEngine.identifyFromRoot(
                    root,
                    transition.browserPackageName,
                    transition.expectedWindowId,
                    isVerifiedHttpsHandler(transition.browserPackageName),
                    isCurrent = { transitionWindowIsCurrent(transition) }
                )
                val session = BrowserInspectionSessionStore.sessionFor(
                    root,
                    transition.browserPackageName
                )
                isSafeGoogleRedirectSurface(identification.bestCandidate) &&
                    (session.surface ?: BrowserSurfaceInspector.inspect(
                        root,
                        transition.browserPackageName
                    )) == BrowserSurfaceInspector.Surface.WEB_CONTENT &&
                    !session.focusedAddressEditor
            } finally { recycleSafely(root) }
            if (!stableSafeSurface || !curtainReadyForTransition(transition)) return false
            if (pass == 0) {
                delay(WEBSITE_GOOGLE_SURFACE_SETTLE_MILLIS)
                if (!curtainReadyForTransition(transition)) return false
            }
        }

        val confirmed = websiteBlockTransitionGuard.confirmGoogleFromStableCurrentSurface(
            browserPackageName = transition.browserPackageName,
            windowId = transition.expectedWindowId,
            observedAtUptimeMillis = SystemClock.uptimeMillis()
        )
        if (confirmed) {
            BrowserCompatibilityStore.recordNavigationConfirmed(transition.browserPackageName)
        }
        return confirmed
    }

    private fun transitionWindowIsCurrent(transition: WebsiteBlockTransitionHandle): Boolean =
        websiteBlockTransitionGuard.activeTransition(transition.browserPackageName)?.id == transition.id &&
            browserInspectionCoordinator.isCurrentWindow(
                transition.browserPackageName, transition.expectedWindowId, transition.inspectionGeneration
            )

    private fun curtainReadyForTransition(transition: WebsiteBlockTransitionHandle): Boolean =
        transitionWindowIsCurrent(transition) && curtainReadyForTabAction(
            attached = instantBlockCurtainAttached,
            visible = instantBlockCurtainVisible,
            currentGeneration = instantBlockCurtainGeneration,
            expectedGeneration = transition.curtainGeneration
        )

    private fun retireStaleWebsiteTransitions() {
        websiteBlockTransitionGuard.activeBrowserPackages().forEach { packageName ->
            val transition = websiteBlockTransitionGuard.activeTransition(packageName) ?: return@forEach
            if (!transition.handedOff && !transition.destinationRequested &&
                !transitionWindowIsCurrent(transition)
            ) finishWebsiteTransition(transition)
        }
    }

    private fun finishWebsiteTransition(transition: WebsiteBlockTransitionHandle) {
        val current = transitionWindowIsCurrent(transition)
        if (!websiteBlockTransitionGuard.finish(transition.browserPackageName, transition.id)) return
        if (current && !transition.safeGoogleConfirmed.isCompleted) {
            BrowserCompatibilityStore.recordRedirectionFailure(transition.browserPackageName)
        }
        BrowserCompatibilityStore.finishRedirection(transition.browserPackageName)
        if (!current && !transition.handedOff && !transition.destinationRequested) {
            dismissInstantBlockCurtain(transition.curtainGeneration)
        }
    }

    private fun rootStillShowsDetectedBlockedTarget(
        root: AccessibilityNodeInfo,
        transition: WebsiteBlockTransitionHandle
    ): Boolean {
        if (!transitionWindowIsCurrent(transition)) return false
        val currentAddress = WebsiteIdentificationEngine.identifyFromRoot(
            root, transition.browserPackageName, transition.expectedWindowId,
            isVerifiedHttpsHandler(transition.browserPackageName),
            isCurrent = { transitionWindowIsCurrent(transition) }
        ).bestCandidate
        if (!transitionWindowIsCurrent(transition)) return false
        return detectedBrowserTargetStillCurrent(
            blockedCandidate = transition.blockedCandidate,
            currentAddress = currentAddress,
            blockedRules = transition.blockedRules,
            detectionEventUptimeMillis = transition.detectionEventUptimeMillis,
            latestObservedEventUptimeMillis = transition.latestObservedEventUptimeMillis
        )
    }

    private fun launchPomodoroLockForWebsiteTransition(
        curtainGeneration: Long,
        transition: WebsiteBlockTransitionHandle
    ): Boolean {
        if (!curtainReadyForTransition(transition)) return false
        return runCatching {
            if (!curtainReadyForTransition(transition)) return false
            startActivity(
                Intent(this, PomodoroLockActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                    putExtra(EXTRA_CURTAIN_GENERATION, curtainGeneration)
                }
            )
            true
        }.getOrElse { error ->
            FocusGuardLogger.logError(
                "A11y",
                "Falha ao abrir bloqueio Pomodoro após dispensar site",
                error
            )
            false
        }
    }

    private fun evictBlockedAppFromForeground(forceLauncherFallback: Boolean = false) {
        val globalHomeAccepted = performGlobalAction(GLOBAL_ACTION_HOME)
        if (!shouldLaunchBlockedAppEvictionFallback(
                globalHomeAccepted = globalHomeAccepted,
                forceLauncherFallback = forceLauncherFallback
            )
        ) return

        runCatching {
            startActivity(createBlockedAppEvictionIntent())
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "A11y",
                "Falha ao tirar app bloqueado do primeiro plano",
                error
            )
        }
    }

    private fun launchBlockNotice(
        blockedPackage: String?,
        blockedDomain: String?,
        redirectBrowserPackage: String? = null,
        eventUptimeMillis: Long = SystemClock.uptimeMillis(),
        isCurrent: (() -> Boolean)? = null
    ): Boolean {
        if (isCurrent?.invoke() == false) return false
        // Every attempt renews the touch-blocking curtain. BlockNoticeActivity
        // becomes the safe foreground surface; HOME eviction is reserved for the
        // existing fail-safe path when that surface cannot be presented.
        val generation = showInstantBlockCurtain(mode = CurtainMode.BLOCK_NOTICE)
        awaitingSafeSurfaceGeneration = generation
        if (shouldEvictBlockedAppBeforeNotice(blockedPackage)) {
            evictBlockedAppFromForeground()
        }
        return try {
            if (isCurrent?.invoke() == false) {
                dismissInstantBlockCurtain(generation)
                return false
            }
            startActivity(
                createBlockNoticeIntent(
                    context = this,
                    strictBlock = isPomodoroStrictActive,
                    blockedPackage = blockedPackage,
                    blockedDomain = blockedDomain,
                    redirectBrowserPackage = redirectBrowserPackage,
                    curtainGeneration = generation,
                    eventUptimeMillis = eventUptimeMillis
                )
            )
            true
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError("A11y", "Falha ao abrir tela de bloqueio", error)
            if (isCurrent?.invoke() != false) beginCurtainEvacuationBeforeHide(generation)
            else dismissInstantBlockCurtain(generation)
            false
        }
    }

    private fun prepareInstantBlockCurtain() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::prepareInstantBlockCurtain)
            return
        }
        if (instantBlockCurtain != null) return

        val density = resources.displayMetrics.density
        val iconSize = (72 * density).toInt()
        val spacing = (18 * density).toInt()
        val curtain = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(16, 17, 23))
            isClickable = true
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            contentDescription = getString(R.string.block_notice_instant_content_description)

            addView(
                ImageView(this@BlockingAccessibilityService).apply {
                    setImageResource(R.drawable.ic_shield)
                    setColorFilter(Color.rgb(38, 198, 218))
                },
                LinearLayout.LayoutParams(iconSize, iconSize)
            )
            addView(
                TextView(this@BlockingAccessibilityService).apply {
                    text = getString(R.string.block_notice_instant_title)
                    setTextColor(Color.WHITE)
                    textSize = 20f
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = spacing
                }
            )
            addView(
                TextView(this@BlockingAccessibilityService).apply {
                    setTextColor(Color.LTGRAY)
                    textSize = 14f
                    gravity = Gravity.CENTER
                    visibility = View.GONE
                    instantBlockCurtainMessage = this
                },
                LinearLayout.LayoutParams(
                    (280 * density).toInt(),
                    WindowManager.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (10 * density).toInt()
                }
            )
        }
        instantBlockCurtain = curtain
        instantBlockCurtainLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "FocusGuardInstantBlock"
            alpha = 0f
        }
    }

    private fun armInstantBlockCurtain() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::armInstantBlockCurtain)
            return
        }
        prepareInstantBlockCurtain()
        if (instantBlockCurtainAttached) return
        val curtain = instantBlockCurtain ?: return
        val params = instantBlockCurtainLayoutParams ?: return
        params.alpha = 0f
        params.flags = hiddenOverlayFlags(params.flags)
        runCatching {
            (windowManager ?: return).addView(curtain, params)
            instantBlockCurtainAttached = true
            instantBlockCurtainVisible = false
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "A11y",
                "Falha ao pré-anexar cortina instantânea",
                error
            )
        }
    }

    private fun showInstantBlockCurtain(
        mode: CurtainMode,
        messageRes: Int? = null
    ): Long {
        val generation = instantBlockCurtainGenerationCounter.incrementAndGet()
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { showInstantBlockCurtain(mode, messageRes, generation) }
            return generation
        }
        showInstantBlockCurtain(mode, messageRes, generation)
        return generation
    }

    private fun showInstantBlockCurtain(
        mode: CurtainMode,
        messageRes: Int?,
        generation: Long
    ) {
        mainHandler.removeCallbacks(instantCurtainFailsafeRelease)
        mainHandler.removeCallbacks(readyWindowValidation)
        failsafeEvacuationGeneration = 0L
        pendingReadyWindowValidationGeneration = 0L
        armInstantBlockCurtain()
        val params = instantBlockCurtainLayoutParams ?: return
        if (!instantBlockCurtainAttached) return

        instantBlockCurtainMode = mode
        instantBlockCurtainGeneration = generation
        instantBlockCurtainMessage?.apply {
            if (messageRes == null) {
                text = ""
                visibility = View.GONE
            } else {
                setText(messageRes)
                visibility = View.VISIBLE
            }
        }

        params.alpha = 1f
        params.flags = visibleOverlayFlags(params.flags)
        val curtain = instantBlockCurtain ?: return
        val manager = windowManager ?: return
        runCatching {
            manager.updateViewLayout(curtain, params)
            instantBlockCurtainVisible = true
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "A11y",
                "Falha ao exibir cortina instantânea",
                error
            )
        }

        renewInstantCurtainFailsafe()
    }

    private fun renewInstantCurtainFailsafe() {
        mainHandler.removeCallbacks(instantCurtainFailsafe)
        mainHandler.postDelayed(instantCurtainFailsafe, INSTANT_CURTAIN_FAILSAFE_MILLIS)
    }

    private fun handleInstantCurtainFailsafe() {
        when (instantCurtainFailsafeDecision(
            curtainVisible = instantBlockCurtainVisible,
            awaitingSafeSurfaceGeneration = awaitingSafeSurfaceGeneration,
            unsafeWindowVisible = hasUnsafeVisibleWindow()
        )) {
            InstantCurtainFailsafeDecision.NO_ACTION -> Unit
            InstantCurtainFailsafeDecision.HIDE -> dismissInstantBlockCurtain()
            InstantCurtainFailsafeDecision.EVACUATE_THEN_HIDE ->
                beginCurtainEvacuationBeforeHide()
        }
    }

    private fun beginCurtainEvacuationBeforeHide(
        expectedGeneration: Long = instantBlockCurtainGeneration
    ) {
        // A missing Activity acknowledgement (including SCREEN_OFF between the
        // click and ACK) must never reveal the app or protected Settings surface
        // later. Move to HOME first, keep consuming touch across the next frames,
        // and only then release this exact generation.
        val generation = instantBlockCurtainGeneration
        if (curtainLaunchFailureDecision(
                currentGeneration = generation,
                failedGeneration = expectedGeneration
            ) == CurtainLaunchFailureDecision.NO_ACTION
        ) return
        failsafeEvacuationGeneration = generation
        evictBlockedAppFromForeground(forceLauncherFallback = true)
        mainHandler.removeCallbacks(instantCurtainFailsafe)
        mainHandler.removeCallbacks(instantCurtainFailsafeRelease)
        mainHandler.postDelayed(
            instantCurtainFailsafeRelease,
            FAILSAFE_EVACUATION_HOLD_MILLIS
        )
    }

    private fun validateReadyDestinationWindows() {
        val generation = pendingReadyWindowValidationGeneration
        if (!shouldDismissCurtain(instantBlockCurtainGeneration, generation)) {
            pendingReadyWindowValidationGeneration = 0L
            return
        }
        when (CurtainSafeWindowPolicy.decide(
            settleElapsed = true,
            unsafeWindowVisible = hasUnsafeVisibleWindow()
        )) {
            CurtainSafeWindowPolicy.Decision.WAIT_FOR_SETTLE -> {
                mainHandler.postDelayed(readyWindowValidation, SAFE_WINDOW_SETTLE_MILLIS)
            }
            CurtainSafeWindowPolicy.Decision.KEEP_AND_EVACUATE -> {
                evictBlockedAppFromForeground()
                mainHandler.postDelayed(
                    readyWindowValidation,
                    UNSAFE_WINDOW_RECHECK_MILLIS
                )
            }
            CurtainSafeWindowPolicy.Decision.DISMISS -> {
                pendingReadyWindowValidationGeneration = 0L
                pendingSettingsProtectionUntilElapsed = 0L
                ProtectedSettingsResetWindow.close(generation)
                dismissInstantBlockCurtain(generation)
            }
        }
    }

    private fun completeCurtainFailsafeAfterEvacuation(generation: Long) {
        if (generation <= 0L ||
            generation != instantBlockCurtainGeneration
        ) return
        if (hasUnsafeVisibleWindow()) {
            evictBlockedAppFromForeground()
            mainHandler.postDelayed(
                instantCurtainFailsafeRelease,
                FAILSAFE_EVACUATION_HOLD_MILLIS
            )
            return
        }
        ProtectedSettingsResetWindow.close(generation)
        pendingSettingsProtectionUntilElapsed = 0L
        dismissInstantBlockCurtain(generation)
    }

    private fun handleTimedProtectionCurtainDismiss() {
        if (instantBlockCurtainMode != CurtainMode.SELF_PROTECTION) return
        if (hasUnsafeVisibleWindow()) {
            evictBlockedAppFromForeground()
            mainHandler.postDelayed(
                protectionCurtainDismiss,
                UNSAFE_WINDOW_RECHECK_MILLIS
            )
            return
        }
        dismissInstantBlockCurtain()
    }

    private fun hasUnsafeVisibleWindow(): Boolean {
        val blockedTargets = blockedAppsSet + focusModeBlockedAppsSet +
            websiteBlockTransitionGuard.activeBrowserPackages()
        val protectSettings = instantBlockCurtainMode == CurtainMode.SELF_PROTECTION
        val protectedSettingsPackages = SettingsInterceptionPolicy.settingsPackages +
            SettingsInterceptionPolicy.packageInstallerPackages
        windows.forEach { window ->
            // During an app block, the protected task may remain underneath the
            // full-screen FocusGuard credential Activity. Ignore that inactive
            // background task only in BLOCK_NOTICE mode. SELF_PROTECTION keeps
            // scanning every settings/installer window fail-closed.
            if (
                instantBlockCurtainMode == CurtainMode.BLOCK_NOTICE &&
                !window.isActive && !window.isFocused
            ) return@forEach
            val root = runCatching { window.root }.getOrNull() ?: return@forEach
            try {
                val visiblePackage = root.packageName?.toString().orEmpty()
                if (CurtainSafeWindowPolicy.isUnsafePackage(
                        visiblePackage = visiblePackage,
                        focusGuardPackage = packageName,
                        blockedPackages = blockedTargets,
                        protectSettings = protectSettings,
                        protectedSettingsPackages = protectedSettingsPackages
                    )
                ) return true
            } finally {
                recycleSafely(root)
            }
        }
        return false
    }

    private fun dismissInstantBlockCurtain(expectedGeneration: Long? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { dismissInstantBlockCurtain(expectedGeneration) }
            return
        }
        if (expectedGeneration != null &&
            expectedGeneration != instantBlockCurtainGeneration
        ) return

        mainHandler.removeCallbacks(instantCurtainFailsafe)
        mainHandler.removeCallbacks(instantCurtainFailsafeRelease)
        mainHandler.removeCallbacks(readyWindowValidation)
        mainHandler.removeCallbacks(protectionCurtainDismiss)
        failsafeEvacuationGeneration = 0L
        pendingReadyWindowValidationGeneration = 0L
        awaitingSafeSurfaceGeneration = 0L
        instantBlockCurtainMode = null
        if (!instantBlockCurtainAttached || !instantBlockCurtainVisible) return
        val params = instantBlockCurtainLayoutParams ?: return
        params.alpha = 0f
        params.flags = hiddenOverlayFlags(params.flags)
        val curtain = instantBlockCurtain ?: return
        val manager = windowManager ?: return
        runCatching { manager.updateViewLayout(curtain, params) }
            .onFailure { error ->
                FocusGuardLogger.logError(
                    "A11y",
                    "Falha ao ocultar cortina instantânea",
                    error
                )
                releaseInstantBlockCurtain()
            }
        instantBlockCurtainVisible = false
    }

    private fun releaseInstantBlockCurtain() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::releaseInstantBlockCurtain)
            return
        }
        mainHandler.removeCallbacks(instantCurtainFailsafe)
        mainHandler.removeCallbacks(instantCurtainFailsafeRelease)
        mainHandler.removeCallbacks(readyWindowValidation)
        mainHandler.removeCallbacks(protectionCurtainDismiss)
        failsafeEvacuationGeneration = 0L
        pendingReadyWindowValidationGeneration = 0L
        awaitingSafeSurfaceGeneration = 0L
        instantBlockCurtainMode = null
        instantBlockCurtainVisible = false
        val curtain = instantBlockCurtain
        if (curtain != null && instantBlockCurtainAttached) {
            runCatching { windowManager?.removeViewImmediate(curtain) }
                .onFailure { error ->
                    FocusGuardLogger.logError(
                        "A11y",
                        "Falha ao liberar cortina instantânea",
                        error
                    )
                }
        }
        instantBlockCurtainAttached = false
    }

    private fun syncWarmOverlays() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::syncWarmOverlays)
            return
        }
        if (!accessibilityServiceConnected) return
        val active = isBlockingSessionActive || focusModeSessionActive
        if (active) {
            armInstantBlockCurtain()
        } else {
            releaseInstantBlockCurtain()
        }
        protectedPowerMenuController?.onProtectionStateChanged(active)
    }

    private fun launchPomodoroLockScreen() {
        try {
            startActivity(
                Intent(this, PomodoroLockActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                }
            )
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError("A11y", "Falha ao abrir Pomodoro", error)
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun showToastThrottled(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastToastTime < 3_000L) return
        lastToastTime = now
        scope.launch(Dispatchers.Main) {
            Toast.makeText(this@BlockingAccessibilityService, message, Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun getAppsForSessions(ids: List<Int>): List<String> {
        return if (ids.isEmpty()) emptyList()
        else database.sessionAppCrossRefDao().getAppsForSessions(ids)
    }

    private suspend fun getSitesForSessions(ids: List<Int>): List<String> {
        return if (ids.isEmpty()) emptyList()
        else database.sessionWebsiteCrossRefDao().getWebsitesForSessions(ids)
    }

    private fun recycleSafely(node: AccessibilityNodeInfo?) {
        if (node == null) return
        runCatching { node.recycle() }
    }

    override fun onDestroy() {
        browserInspectionCoordinator.invalidate()
        browserRecoveryCoordinator.clear()
        accessibilityServiceConnected = false
        stopWebsiteTracking()
        websiteBlockTransitionGuard.clear()
        mainHandler.removeCallbacks(protectionCurtainDismiss)
        protectionActionUntilElapsed = 0L
        protectedPowerMenuController?.destroy()
        releaseInstantBlockCurtain()
        runCatching { unregisterReceiver(packageReceiver) }
        runCatching { unregisterReceiver(launcherReceiver) }
        runCatching { unregisterReceiver(refreshReceiver) }
        CurtainDestinationReadyCoordinator.unregister(curtainDestinationReadyListener)
        runCatching { unregisterReceiver(screenStateReceiver) }
        scope.cancel()
        super.onDestroy()

        if (StrictPomodoroLock.isActive(applicationContext)) {
            FocusGuardLogger.log(
                "A11y",
                "Serviço destruído durante Pomodoro; reativando watchdog"
            )
            PomodoroForegroundService.start(applicationContext)
            PomodoroForegroundService.scheduleWatchdogAlarm(applicationContext)
        } else {
            runCatching {
                Toast.makeText(
                    this,
                    getString(R.string.servico_focusguard_parado),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    companion object {
        private const val SAFE_REDIRECT_URL = "https://www.google.com"
        private const val INVALID_BROWSER_WINDOW_ID = -1
        private val CHROMIUM_TAB_SWITCHER_ENTRY_NAMES = listOf(
            "tab_switcher_button",
            "bottom_tab_switcher_button"
        )
        private const val WEBSITE_TAB_MENU_SETTLE_MILLIS = 120L
        private const val WEBSITE_TAB_CLOSE_CONFIRM_MILLIS = 180L
        private const val WEBSITE_ADDRESS_BAR_FOCUS_SETTLE_MILLIS = 48L
        private const val WEBSITE_ADDRESS_BAR_ACTION_RETRY_MILLIS = 32L
        private const val WEBSITE_ADDRESS_BAR_ACTION_TIMEOUT_MILLIS = 360L
        private const val WEBSITE_FIREFOX_ADDRESS_BAR_ACTION_TIMEOUT_MILLIS = 800L
        private const val WEBSITE_GOOGLE_SURFACE_SETTLE_MILLIS = 120L
        internal const val WEBSITE_MIN_BLOCK_NOTICE_MILLIS = 1_000L
        /** Exact hosts published by Google's supported-domains endpoint. */
        private val SAFE_GOOGLE_HOSTS = """
            google.com google.ad google.ae google.com.af google.com.ag google.al google.am
            google.co.ao google.com.ar google.as google.at google.com.au google.az google.ba
            google.com.bd google.be google.bf google.bg google.com.bh google.bi google.bj
            google.com.bn google.com.bo google.com.br google.bs google.bt google.co.bw google.by
            google.com.bz google.ca google.cd google.cf google.cg google.ch google.ci google.co.ck
            google.cl google.cm google.cn google.com.co google.co.cr google.com.cu google.cv
            google.com.cy google.cz google.de google.dj google.dk google.dm google.com.do google.dz
            google.com.ec google.ee google.com.eg google.es google.com.et google.fi google.com.fj
            google.fm google.fr google.ga google.ge google.gg google.com.gh google.com.gi google.gl
            google.gm google.gr google.com.gt google.gy google.com.hk google.hn google.hr google.ht
            google.hu google.co.id google.ie google.co.il google.im google.co.in google.iq google.is
            google.it google.je google.com.jm google.jo google.co.jp google.co.ke google.com.kh
            google.ki google.kg google.co.kr google.com.kw google.kz google.la google.com.lb google.li
            google.lk google.co.ls google.lt google.lu google.lv google.com.ly google.co.ma google.md
            google.me google.mg google.mk google.ml google.com.mm google.mn google.com.mt google.mu
            google.mv google.mw google.com.mx google.com.my google.co.mz google.com.na google.com.ng
            google.com.ni google.ne google.nl google.no google.com.np google.nr google.nu google.co.nz
            google.com.om google.com.pa google.com.pe google.com.pg google.com.ph google.com.pk
            google.pl google.pn google.com.pr google.ps google.pt google.com.py google.com.qa google.ro
            google.ru google.rw google.com.sa google.com.sb google.sc google.se google.com.sg google.sh
            google.si google.sk google.com.sl google.sn google.so google.sm google.sr google.st
            google.com.sv google.td google.tg google.co.th google.com.tj google.tl google.tm google.tn
            google.to google.com.tr google.tt google.com.tw google.co.tz google.com.ua google.co.ug
            google.co.uk google.com.uy google.co.uz google.com.vc google.co.ve google.co.vi google.com.vn
            google.vu google.ws google.rs google.co.za google.co.zm google.co.zw google.cat
        """.trimIndent().split(Regex("\\s+")).toSet()
        /**
         * How long a relevant click keeps intercepting follow-up events.
         *
         * This is the main defence against the race the user can win: the click on
         * the menu entry is seen *before* the destination screen exists, so the
         * guard bounces the transition itself instead of waiting for the new
         * window. Sized for a cold Settings start on a slow device — the cost of
         * being generous is only that Settings stays interceptive for a few
         * seconds after such a click.
         */
        private const val SETTINGS_TRANSITION_GUARD_MILLIS = 2_000L
        private const val SELF_PROTECTION_ACTION_DEBOUNCE_MILLIS = 2_500L
        private const val SELF_PROTECTION_NOTICE_DURATION_MILLIS = 1_200L
        private const val INSTANT_CURTAIN_FAILSAFE_MILLIS = 5_000L
        internal const val FAILSAFE_EVACUATION_HOLD_MILLIS = 450L
        internal const val SAFE_WINDOW_SETTLE_MILLIS = 160L
        internal const val UNSAFE_WINDOW_RECHECK_MILLIS = 240L
        private const val SLOW_ACCESSIBILITY_CALLBACK_MILLIS = 250L
        private const val SLOW_CALLBACK_LOG_INTERVAL_MILLIS = 5_000L
        private const val UNKNOWN_BROWSER_DISCOVERY_RETRY_MILLIS = 750L
        private const val MAX_BROWSER_DISCOVERY_MISSES = 32
        internal const val EVENT_NOTIFICATION_TIMEOUT_MILLIS = 0L
        /**
         * Event types that can trigger settings interception.
         *
         * Ordered by how early they arrive, not by how much they tell us.
         * TYPE_WINDOWS_CHANGED and TYPE_VIEW_FOCUSED carry almost no class name or
         * text — on their own they decide nothing — but they are the first signals
         * that a new window exists, and once the transition guard is armed by a
         * click that is all it takes to bounce out. Waiting for
         * TYPE_WINDOW_STATE_CHANGED costs the frames in which the switch is already
         * on screen and tappable.
         *
         * All four are already in [requestedAccessibilityEventTypes], so this
         * widens nothing about what the service observes.
         */
        private val settingsInterceptionEventTypes = setOf(
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED
        )
        // Events whose source can expose an address bar before a root/window walk.
        // Zero notification timeout means Android delivers them without batching.
        private val immediateBrowserBlockEventTypes = setOf(
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED
        )
        internal const val WEBSITE_DESTINATION_CONFIRM_TIMEOUT_MILLIS = 2_000L
        internal const val STRICT_BLOCK_NOTICE_DURATION_MILLIS = 1_000L
        private val SAFE_GOOGLE_ROOT_QUERY_PARAMETERS = setOf("gl", "gws_rd", "hl")
        const val ACTION_REFRESH_BLOCKING = "com.focusguard.ACTION_REFRESH_BLOCKING"
        internal const val ACTION_DEV_RELINQUISH_ACCESSIBILITY =
            "com.focusguard.ACTION_DEV_RELINQUISH_ACCESSIBILITY"
        const val EXTRA_STRICT_BLOCK = "STRICT_BLOCK"
        const val EXTRA_BLOCKED_PACKAGE = "BLOCKED_PACKAGE"
        const val EXTRA_BLOCKED_DOMAIN = "BLOCKED_DOMAIN"
        const val EXTRA_REDIRECT_BROWSER_PACKAGE = "REDIRECT_BROWSER_PACKAGE"
        const val EXTRA_BLOCK_EVENT_UPTIME_MILLIS = "BLOCK_EVENT_UPTIME_MILLIS"
        const val EXTRA_CURTAIN_GENERATION = "CURTAIN_GENERATION"
        internal const val EXTRA_BLOCKING_SNAPSHOT_PRESENT = "BLOCKING_SNAPSHOT_PRESENT"
        internal const val EXTRA_BLOCKED_APPS_SNAPSHOT = "BLOCKED_APPS_SNAPSHOT"
        internal const val EXTRA_BLOCKED_SITES_SNAPSHOT = "BLOCKED_SITES_SNAPSHOT"
        internal const val EXTRA_PASSWORD_SITES_SNAPSHOT = "PASSWORD_SITES_SNAPSHOT"
        internal const val EXTRA_STRONGER_SITES_SNAPSHOT = "STRONGER_SITES_SNAPSHOT"
        internal const val EXTRA_BLOCKING_ACTIVE_SNAPSHOT = "BLOCKING_ACTIVE_SNAPSHOT"
        internal const val EXTRA_STRICT_POMODORO_SNAPSHOT = "STRICT_POMODORO_SNAPSHOT"

        internal fun confirmAccessibilityContextForInstalledEntry(
            directAccessibility: Boolean,
            installedAccessibilityApps: Boolean,
            rootMentionsAccessibility: () -> Boolean
        ): Boolean = directAccessibility ||
            (installedAccessibilityApps && rootMentionsAccessibility())

        internal fun settingsInterceptionEventTypesForTest(): Set<Int> =
            settingsInterceptionEventTypes

        internal fun immediateBrowserBlockEventTypesForTest(): Set<Int> =
            immediateBrowserBlockEventTypes

        internal fun chromiumTabSwitcherEntryNamesForTest(): List<String> =
            CHROMIUM_TAB_SWITCHER_ENTRY_NAMES

        internal fun shouldApplyStrictPomodoroToWindow(
            strictActive: Boolean,
            isWindowTransition: Boolean,
            isBrowserWindow: Boolean
        ): Boolean = strictActive && isWindowTransition && !isBrowserWindow

        internal fun immediateWebsiteBlockTarget(
            addressText: String?,
            url: String?,
            blockedRules: Collection<String>
        ): String? {
            val rules = WebsiteBlocker.normalizeRules(blockedRules)
            if (rules.isEmpty()) return null

            val pornographyActive = rules.any(WebsiteBlocker::isPornographyRule)
            if (pornographyActive &&
                !addressText.isNullOrBlank() &&
                WebsiteBlocker.isPornographySearchInput(addressText)
            ) {
                return PredefinedWebsites.PORNOGRAPHY_RULE
            }

            val candidate = url?.takeIf(String::isNotBlank)
                ?: addressText?.let(WebsiteBlocker::extractUrlCandidate)
                ?: return null
            val matchingRule = WebsiteBlocker.findMatchingRule(candidate, rules)
                ?: return null
            return if (WebsiteBlocker.isPornographyRule(matchingRule)) {
                matchingRule
            } else {
                WebsiteBlocker.extractDomain(candidate)
                    .ifBlank { WebsiteBlocker.displayRule(matchingRule) }
            }
        }

        internal fun isSafeGoogleRedirectSurface(urlOrAddress: String?): Boolean {
            val raw = urlOrAddress?.trim()?.takeIf(String::isNotEmpty) ?: return false
            val candidate = WebsiteBlocker.extractUrlCandidate(raw) ?: raw
            val withScheme = if ("://" in candidate) candidate else "https://$candidate"
            val uri = runCatching { Uri.parse(withScheme) }.getOrNull() ?: return false
            val host = uri.host?.lowercase(Locale.US)?.removePrefix("www.") ?: return false
            return host in SAFE_GOOGLE_HOSTS &&
                !WebsiteBlocker.isGoogleImagesUrl(withScheme) &&
                !WebsiteBlocker.isPornographySearchUrl(withScheme) &&
                (uri.path.isNullOrEmpty() || uri.path == "/") &&
                uri.queryParameterNames.all(SAFE_GOOGLE_ROOT_QUERY_PARAMETERS::contains) &&
                uri.fragment.isNullOrEmpty()
        }

        internal fun curtainReadyForTabAction(
            attached: Boolean,
            visible: Boolean,
            currentGeneration: Long,
            expectedGeneration: Long
        ): Boolean = attached && visible && expectedGeneration > 0L &&
            currentGeneration == expectedGeneration

        internal fun detectedBrowserTargetStillCurrent(
            blockedCandidate: String?,
            currentAddress: String?,
            blockedRules: Collection<String>,
            detectionEventUptimeMillis: Long,
            latestObservedEventUptimeMillis: Long
        ): Boolean {
            if (detectionEventUptimeMillis <= 0L ||
                latestObservedEventUptimeMillis < detectionEventUptimeMillis
            ) return false
            val rules = WebsiteBlocker.normalizeRules(blockedRules)
            val candidate = blockedCandidate?.takeIf(String::isNotBlank) ?: return false
            val current = currentAddress?.takeIf(String::isNotBlank) ?: return false
            val candidateRule = WebsiteBlocker.findMatchingRule(candidate, rules) ?: return false
            val currentRule = WebsiteBlocker.findMatchingRule(current, rules) ?: return false
            if (candidateRule != currentRule) return false
            val candidateDomain = WebsiteBlocker.extractDomain(candidate)
            val currentDomain = WebsiteBlocker.extractDomain(current)
            if (candidateDomain.isNotBlank() && currentDomain.isNotBlank() &&
                candidateDomain != currentDomain
            ) return false
            return browserTargetIdentity(candidate) == browserTargetIdentity(current)
        }

        private fun browserTargetIdentity(value: String): String {
            val candidate = WebsiteBlocker.extractUrlCandidate(value)
                ?: return value.trim().lowercase(Locale.ROOT)
            val withScheme = if ("://" in candidate) candidate else "https://$candidate"
            val uri = runCatching { Uri.parse(withScheme) }.getOrNull()
                ?: return candidate.trim().lowercase(Locale.ROOT)
            return buildString {
                append(uri.host?.lowercase(Locale.ROOT).orEmpty())
                append(uri.encodedPath.orEmpty().ifBlank { "/" })
                uri.encodedQuery?.let { append('?').append(it) }
                uri.encodedFragment?.let { append('#').append(it) }
            }
        }

        internal fun supportsCapabilityBasedIntentRedirectFallback(
            knownBrowser: Boolean,
            verifiedHttpsHandler: Boolean
        ): Boolean = knownBrowser || verifiedHttpsHandler

        internal fun shouldStartWebsiteIdentityRecovery(
            websiteIdentified: Boolean,
            addressBarObservable: Boolean,
            focusedAddressEditor: Boolean
        ): Boolean = !websiteIdentified &&
            (!addressBarObservable || !focusedAddressEditor)

        internal fun mayOpenDestinationAfterSanitization(
            safeGoogleConfirmed: Boolean
        ): Boolean = safeGoogleConfirmed

        internal fun afterSafeAddressSet(
            accepted: Boolean
        ): WebsiteSanitizationDecision = if (accepted) {
            WebsiteSanitizationDecision.SUBMIT_ADDRESS_BAR
        } else {
            WebsiteSanitizationDecision.ABORT_REDIRECT
        }

        internal fun afterSafeAddressSubmit(
            accepted: Boolean
        ): WebsiteSanitizationDecision = if (accepted) {
            WebsiteSanitizationDecision.AWAIT_GOOGLE_CONFIRMATION
        } else {
            WebsiteSanitizationDecision.ABORT_REDIRECT
        }

        internal fun afterChromiumCloseAttempt(
            closeActionAccepted: Boolean,
            closeConfirmed: Boolean,
            originalBlockedSurfaceStillCurrent: Boolean
        ): WebsiteCloseFollowUp = when {
            closeConfirmed ->
                WebsiteCloseFollowUp.REQUEST_SAFE_GOOGLE_AFTER_CONFIRMED_CLOSE
            BrowserUiCapabilityPolicy.mayRewriteBlockedTabAfterCloseAttempt(
                closeActionAccepted = closeActionAccepted,
                originalBlockedSurfaceStillCurrent = originalBlockedSurfaceStillCurrent
            ) -> WebsiteCloseFollowUp.REWRITE_SAME_BLOCKED_TAB
            else -> WebsiteCloseFollowUp.EVACUATE_WITHOUT_REWRITE
        }

        internal fun isClosedSurfaceConfirmed(
            closeActionAccepted: Boolean,
            browserSurfaceMutationObservedAfterClick: Boolean,
            originalBlockedSurfaceStillCurrent: Boolean
        ): Boolean = closeActionAccepted &&
            browserSurfaceMutationObservedAfterClick &&
            !originalBlockedSurfaceStillCurrent

        internal fun isGoogleNavigationEvidenceEvent(eventType: Int): Boolean =
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED

        internal fun isWindowOrTabTransitionEvent(eventType: Int): Boolean =
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED

        internal fun canUseCertifiableImeSubmit(apiLevel: Int): Boolean =
            BrowserUiCapabilityPolicy.canUseImeEnter(apiLevel)

        internal fun websiteAddressBarActionTimeoutMillis(
            browserPackageName: String
        ): Long = if (BrowserUiCapabilityPolicy.isFirefoxPackage(browserPackageName)) {
            WEBSITE_FIREFOX_ADDRESS_BAR_ACTION_TIMEOUT_MILLIS
        } else {
            WEBSITE_ADDRESS_BAR_ACTION_TIMEOUT_MILLIS
        }

        internal fun websiteRestoreDecision(
            safeRedirectStillEditing: Boolean,
            blockedSurfaceStillCurrent: Boolean
        ): WebsiteRestoreDecision = when {
            safeRedirectStillEditing -> WebsiteRestoreDecision.BACK_TO_BLOCKED_SURFACE
            blockedSurfaceStillCurrent -> WebsiteRestoreDecision.RETRY_CURRENT_BLOCKED_SURFACE
            else -> WebsiteRestoreDecision.KEEP_CURRENT_SURFACE
        }

        internal fun settingsTransitionGuardMillisForTest(): Long =
            SETTINGS_TRANSITION_GUARD_MILLIS

        internal fun selfProtectionActionDebounceMillisForTest(): Long =
            SELF_PROTECTION_ACTION_DEBOUNCE_MILLIS

        internal fun shouldExecuteProtectionAction(
            blockedUntilElapsed: Long,
            nowElapsed: Long
        ): Boolean = blockedUntilElapsed <= 0L || nowElapsed > blockedUntilElapsed

        internal fun isSelfProtectionEngaged(
            cachedActive: Boolean,
            persistedActive: Boolean,
            focusModeActive: Boolean,
            armoredDeviceOwnerActive: Boolean
        ): Boolean = cachedActive ||
            persistedActive ||
            focusModeActive ||
            armoredDeviceOwnerActive

        internal fun shouldSearchSameRowMarkers(clicked: Rect, root: Rect): Boolean =
            !clicked.isEmpty &&
                !root.isEmpty &&
                clicked.height() * 3 < root.height()

        internal fun boundsShareHorizontalRow(clicked: Rect, marker: Rect): Boolean =
            !clicked.isEmpty &&
                !marker.isEmpty &&
                minOf(clicked.bottom, marker.bottom) > maxOf(clicked.top, marker.top)

        internal fun requestedAccessibilityEventTypes(): Int =
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED

        internal fun shouldDismissCurtain(
            currentGeneration: Long,
            readyGeneration: Long
        ): Boolean = currentGeneration > 0L && currentGeneration == readyGeneration

        internal fun curtainLaunchFailureDecision(
            currentGeneration: Long,
            failedGeneration: Long
        ): CurtainLaunchFailureDecision = if (
            currentGeneration > 0L && currentGeneration == failedGeneration
        ) {
            CurtainLaunchFailureDecision.EVACUATE_THEN_HIDE
        } else {
            CurtainLaunchFailureDecision.NO_ACTION
        }

        internal fun instantCurtainFailsafeDecision(
            curtainVisible: Boolean,
            awaitingSafeSurfaceGeneration: Long,
            unsafeWindowVisible: Boolean
        ): InstantCurtainFailsafeDecision = when {
            !curtainVisible -> InstantCurtainFailsafeDecision.NO_ACTION
            awaitingSafeSurfaceGeneration > 0L || unsafeWindowVisible ->
                InstantCurtainFailsafeDecision.EVACUATE_THEN_HIDE
            else -> InstantCurtainFailsafeDecision.HIDE
        }

        internal fun screenOffCurtainDecision(
            curtainVisible: Boolean,
            awaitingSafeSurfaceGeneration: Long,
            unsafeWindowVisible: Boolean
        ): InstantCurtainFailsafeDecision = instantCurtainFailsafeDecision(
            curtainVisible = curtainVisible,
            awaitingSafeSurfaceGeneration = awaitingSafeSurfaceGeneration,
            unsafeWindowVisible = unsafeWindowVisible
        )

        internal fun shouldReuseAwaitedCurtain(
            holdUntilSafeSurface: Boolean,
            awaitingGeneration: Long,
            curtainVisible: Boolean
        ): Boolean = !holdUntilSafeSurface &&
            awaitingGeneration > 0L &&
            curtainVisible

        internal fun shouldEvictForProtectionAttempt(
            alreadyAwaitingSafeSurface: Boolean
        ): Boolean = !alreadyAwaitingSafeSurface

        internal fun hiddenOverlayFlags(flags: Int): Int =
            flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

        internal fun visibleOverlayFlags(flags: Int): Int =
            (flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()) or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

        /**
         * The opaque accessibility curtain already prevents interaction with the
         * blocked app while BlockNoticeActivity is coming forward. Evicting to
         * HOME first destroys the task context and makes PASSWORD authentication
         * arrive too late. HOME remains the fail-safe only when the safe Activity
         * cannot be presented/acknowledged.
         */
        internal fun shouldEvictBlockedAppBeforeNotice(
            @Suppress("UNUSED_PARAMETER") blockedPackage: String?
        ): Boolean = false

        internal fun createBlockedAppEvictionIntent(): Intent =
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

        internal fun shouldLaunchBlockedAppEvictionFallback(
            globalHomeAccepted: Boolean,
            forceLauncherFallback: Boolean
        ): Boolean = !globalHomeAccepted || forceLauncherFallback

        internal fun createRefreshBlockingIntent(
            context: Context,
            blockedApps: Collection<String>,
            blockedSites: Collection<String>,
            blockingActive: Boolean,
            strictPomodoro: Boolean,
            passwordSites: Collection<String> = emptyList(),
            strongerSites: Collection<String> = emptyList()
        ): Intent {
            val normalizedApps = blockedApps.filter(String::isNotBlank).distinct()
            val normalizedSites = WebsiteBlocker.normalizeRules(blockedSites)
            val normalizedPasswordSites = WebsiteBlocker.normalizeRules(passwordSites)
            val normalizedStrongerSites = WebsiteBlocker.normalizeRules(strongerSites)
            SelfProtectionStateStore.setSnapshot(
                context = context,
                armed = blockingActive,
                blockedApps = normalizedApps,
                blockedSites = normalizedSites,
                strictPomodoro = strictPomodoro
            )

            return Intent(ACTION_REFRESH_BLOCKING).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_BLOCKING_SNAPSHOT_PRESENT, true)
                putStringArrayListExtra(
                    EXTRA_BLOCKED_APPS_SNAPSHOT,
                    ArrayList(normalizedApps)
                )
                putStringArrayListExtra(
                    EXTRA_BLOCKED_SITES_SNAPSHOT,
                    ArrayList(normalizedSites)
                )
                putStringArrayListExtra(
                    EXTRA_PASSWORD_SITES_SNAPSHOT,
                    ArrayList(normalizedPasswordSites)
                )
                putStringArrayListExtra(
                    EXTRA_STRONGER_SITES_SNAPSHOT,
                    ArrayList(normalizedStrongerSites)
                )
                putExtra(EXTRA_BLOCKING_ACTIVE_SNAPSHOT, blockingActive)
                putExtra(EXTRA_STRICT_POMODORO_SNAPSHOT, strictPomodoro)
            }
        }

        internal fun internalRefreshReceiverFlags(): Int =
            ContextCompat.RECEIVER_NOT_EXPORTED

        internal fun createDevelopmentRelinquishIntent(context: Context): Intent =
            Intent(ACTION_DEV_RELINQUISH_ACCESSIBILITY).setPackage(context.packageName)

        internal fun createSafeBrowserRedirectIntent(
            browserPackageName: String
        ): Intent {
            require(browserPackageName.isNotBlank())
            return Intent(Intent.ACTION_VIEW, Uri.parse(SAFE_REDIRECT_URL)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                setPackage(browserPackageName)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
        }

        internal fun createBlockNoticeIntent(
            context: Context,
            strictBlock: Boolean,
            blockedPackage: String?,
            blockedDomain: String?,
            redirectBrowserPackage: String?,
            curtainGeneration: Long = 0L,
            eventUptimeMillis: Long = SystemClock.uptimeMillis()
        ): Intent = Intent(context, BlockNoticeActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
            putExtra(EXTRA_STRICT_BLOCK, strictBlock)
            putExtra(EXTRA_BLOCKED_PACKAGE, blockedPackage)
            putExtra(EXTRA_BLOCKED_DOMAIN, blockedDomain)
            putExtra(EXTRA_BLOCK_EVENT_UPTIME_MILLIS, eventUptimeMillis)
            putExtra(EXTRA_CURTAIN_GENERATION, curtainGeneration)
            redirectBrowserPackage
                ?.takeIf(String::isNotBlank)
                ?.let { putExtra(EXTRA_REDIRECT_BROWSER_PACKAGE, it) }
        }

    }

    private object PackageManagerCompat {
        const val MATCH_ALL: Int = 0x00020000
    }
}
