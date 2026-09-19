package com.focusguard.service

import com.focusguard.accessibility.website.redirection.WebsiteRedirectionCoordinator

import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.focusguard.accessibility.website.compatibility.BrowserCompatibilityStore
import com.focusguard.accessibility.website.identification.WebsiteIdentificationEngine
import com.focusguard.accessibility.website.identification.WebsiteIdentificationResult
import com.focusguard.accessibility.website.identification.WebsiteIdentificationStatus
import com.focusguard.accessibility.website.redirection.AddressBarRedirectionActions
import com.focusguard.accessibility.website.redirection.WebsiteTabNeutralizationPolicy
import com.focusguard.utils.BrowserSurfaceInspector
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BrowserTransitionRaceTest {
    private val pkg = "com.android.chrome"
    private lateinit var service: BlockingAccessibilityService
    private lateinit var coordinator: BrowserInspectionCoordinator
    private lateinit var guard: BlockingAccessibilityService.WebsiteBlockTransitionGuard
    private lateinit var transition: BlockingAccessibilityService.WebsiteBlockTransitionHandle

    @Before
    fun setUp() {
        service = spyk(BlockingAccessibilityService())
        every { service.packageName } returns "com.focusguard.v2"
        every { service.startActivity(any()) } just Runs
        every { service.performGlobalAction(any()) } returns true
        every { service.windows } returns emptyList()
        mockkObject(BrowserCompatibilityStore)
        every { BrowserCompatibilityStore.recordRedirectionFailure(any()) } just Runs
        every { BrowserCompatibilityStore.finishRedirection(any()) } just Runs
        coordinator = ReflectionHelpers.getField(service, "browserInspectionCoordinator")
        guard = ReflectionHelpers.getField(service, "websiteBlockTransitionGuard")
        val token = coordinator.offer(pkg, 10, 32, 1L, 1L, "", emptyList(), null).snapshot.token
        transition = guard.tryStart(
            pkg, 1L, WebsiteRedirectionCoordinator.TerminalDestination.REDIRECT,
            expectedWindowId = 10, inspectionGeneration = token.generation,
            blockedCandidate = "https://blocked.example", blockedRules = setOf("blocked.example"),
            detectionEventUptimeMillis = 1L
        )!!
        guard.markCurtainGeneration(pkg, 1L, 1L)
        ReflectionHelpers.setField(service, "instantBlockCurtainAttached", true)
        ReflectionHelpers.setField(service, "instantBlockCurtainVisible", true)
        ReflectionHelpers.setField(service, "instantBlockCurtainGeneration", 1L)
    }

    @After
    fun tearDown() = unmockkAll()

    private fun call(name: String, vararg args: Any): Any? {
        val method = BlockingAccessibilityService::class.java.declaredMethods.single {
            it.name == name && it.parameterCount == args.size
        }
        method.isAccessible = true
        return method.invoke(service, *args)
    }

    private suspend fun callSuspend(name: String, vararg args: Any): Any? = suspendCoroutine { continuation ->
        val method = BlockingAccessibilityService::class.java.declaredMethods.single {
            it.name == name && it.parameterCount == args.size + 1
        }
        method.isAccessible = true
        val result = method.invoke(service, *args, continuation)
        if (result !== COROUTINE_SUSPENDED) continuation.resume(result)
    }

    @Test
    fun windowChangeBeforeBackDoesNotPressBack() {
        coordinator.observeWindow(pkg, 11)
        assertEquals(false, call("performTransitionBack", transition))
        verify(exactly = 0) { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }
    }

    @Test
    fun windowChangeDuringBackRejectsItsResult() {
        every { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) } answers {
            coordinator.observeWindow(pkg, 11)
            true
        }
        assertEquals(false, call("performTransitionBack", transition))
        verify(exactly = 1) { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }
    }

    @Test
    fun windowChangeBeforeGuardedIntentStillStartsExplicitGoogleFallback() = runTest {
        coordinator.observeWindow(pkg, 11)
        assertEquals(true, callSuspend("requestSafeRedirectThroughBrowserIntent", transition))
        verify(exactly = 1) {
            service.startActivity(match {
                it.action == Intent.ACTION_VIEW &&
                    it.data?.toString() == "https://www.google.com" &&
                    it.`package` == pkg
            })
        }
    }

    @Test
    fun supersededCurtainStillPreventsExplicitGoogleFallback() = runTest {
        coordinator.observeWindow(pkg, 11)
        ReflectionHelpers.setField(service, "instantBlockCurtainGeneration", 2L)
        assertEquals(false, callSuspend("requestSafeRedirectThroughBrowserIntent", transition))
        verify(exactly = 0) { service.startActivity(any()) }
    }

    @Test
    fun windowChangeDuringOwnedCurtainStillAllowsFailClosedHandoff() {
        coordinator.observeWindow(pkg, 11)
        call("failClosedWebsiteTransition", transition)
        call("finishWebsiteTransition", transition)
        verify(exactly = 1) { service.startActivity(any()) }
        verify(exactly = 0) { BrowserCompatibilityStore.recordRedirectionFailure(any()) }
        assertFalse(guard.isActive(pkg))
    }

    @Test
    fun supersededCurtainPreventsStaleFailClosedHandoff() {
        coordinator.observeWindow(pkg, 11)
        ReflectionHelpers.setField(service, "instantBlockCurtainGeneration", 2L)
        call("failClosedWebsiteTransition", transition)
        call("finishWebsiteTransition", transition)
        verify(exactly = 0) { service.startActivity(any()) }
        verify(exactly = 0) { BrowserCompatibilityStore.recordRedirectionFailure(any()) }
        assertFalse(guard.isActive(pkg))
    }

    @Test
    fun oldFinallyCannotClearReplacementTransitionOrCompatibilityState() {
        coordinator.observeWindow(pkg, 11)
        call("finishWebsiteTransition", transition)
        val newer = guard.tryStart(
            pkg, 2L, WebsiteRedirectionCoordinator.TerminalDestination.REDIRECT,
            expectedWindowId = 11, inspectionGeneration = coordinator.currentGeneration(pkg, 11)!!
        )!!
        clearMocks(BrowserCompatibilityStore, answers = false)
        call("finishWebsiteTransition", transition)
        assertSame(newer, guard.activeTransition(pkg))
        verify(exactly = 0) { BrowserCompatibilityStore.finishRedirection(any()) }
        verify(exactly = 0) { BrowserCompatibilityStore.recordRedirectionFailure(any()) }
    }

    @Test
    fun currentFailureIsStillRecorded() {
        call("finishWebsiteTransition", transition)
        verify(exactly = 1) { BrowserCompatibilityStore.recordRedirectionFailure(pkg) }
        verify(exactly = 1) { BrowserCompatibilityStore.finishRedirection(pkg) }
    }

    @Test
    fun windowChangeAfterSetTextPreventsEverySubmitAndLearning() = runTest {
        mockkObject(AddressBarRedirectionActions, BrowserSurfaceInspector, WebsiteIdentificationEngine)
        val window = mockk<AccessibilityWindowInfo> {
            every { id } returns 10
            every { isActive } returns true
            every { root } answers {
                mockk<AccessibilityNodeInfo>(relaxed = true) {
                    every { packageName } returns pkg
                    every { windowId } returns 10
                }
            }
        }
        every { service.windows } returns listOf(window)
        every { BrowserSurfaceInspector.inspect(any(), pkg) } returns BrowserSurfaceInspector.Surface.WEB_CONTENT
        every { WebsiteIdentificationEngine.identifyFromRoot(any(), pkg, 10, any(), any()) } returns
            WebsiteIdentificationResult(WebsiteIdentificationStatus.IDENTIFIED, urlCandidate = "https://blocked.example")
        every { BrowserCompatibilityStore.preferredWriteMethod(pkg) } returns null
        every { AddressBarRedirectionActions.activate(any(), pkg, 10, any(), any(), any()) } returns
            AddressBarRedirectionActions.Result(AddressBarRedirectionActions.Status.ACCEPTED, "$pkg:id/url_bar")
        every { AddressBarRedirectionActions.hasFocusedAddressEditor(any(), pkg, 10, any(), any(), any()) } returns true
        every { AddressBarRedirectionActions.setText(any(), pkg, 10, any(), any(), any()) } answers {
            coordinator.observeWindow(pkg, 11)
            AddressBarRedirectionActions.Result(AddressBarRedirectionActions.Status.ACCEPTED)
        }

        val result = callSuspend(
            "requestSafeRedirectInCurrentTab", pkg, 10,
            WebsiteTabNeutralizationPolicy(pkg, 10), transition
        )

        assertEquals(false, result)
        verify(exactly = 1) { AddressBarRedirectionActions.setText(any(), pkg, 10, any(), any(), any()) }
        verify(exactly = 0) { AddressBarRedirectionActions.submitImeEnter(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { AddressBarRedirectionActions.submitAnnouncedEditorAction(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { AddressBarRedirectionActions.clickCertifiedGoButton(any(), any(), any(), any()) }
        verify(exactly = 0) { BrowserCompatibilityStore.recordWriteSuccess(any(), any(), any(), any()) }
    }

    @Test
    fun staleNativePanelDoesNotStopTrackingOrPromoteBrowser() {
        val old = coordinator.offer(pkg, 10, 32, 2L, 2L, "", emptyList(), null).snapshot
        val outcome = BrowserInspectionOutcome.from(
            old, WebsiteIdentificationResult(WebsiteIdentificationStatus.NATIVE_BROWSER_UI),
            BrowserSurfaceInspector.Surface.NATIVE_PANEL, false, true
        )
        coordinator.offer(pkg, 11, 32, 3L, 3L, "", emptyList(), null)
        ReflectionHelpers.setField(service, "trackedDomain", "current.example")
        ReflectionHelpers.setField(service, "browserPackages", emptySet<String>())
        call("applyBrowserInspectionOutcome", outcome)
        assertEquals("current.example", ReflectionHelpers.getField<String>(service, "trackedDomain"))
        assertTrue(ReflectionHelpers.getField<Set<String>>(service, "browserPackages").isEmpty())
    }
}
