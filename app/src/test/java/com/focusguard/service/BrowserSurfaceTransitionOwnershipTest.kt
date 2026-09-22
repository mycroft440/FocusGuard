package com.focusguard.service

import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.view.accessibility.AccessibilityEvent
import com.focusguard.accessibility.website.BrowserSurfaceIdentityRegistry
import com.focusguard.accessibility.website.redirection.WebsiteBlockTransitionGuard
import com.focusguard.accessibility.website.redirection.WebsiteBlockTransitionHandle
import com.focusguard.accessibility.website.redirection.WebsiteRedirectionCoordinator
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BrowserSurfaceTransitionOwnershipTest {
    private val packageName = "com.android.chrome"
    private lateinit var service: BlockingAccessibilityService
    private lateinit var coordinator: BrowserInspectionCoordinator
    private lateinit var guard: WebsiteBlockTransitionGuard
    private lateinit var transition: WebsiteBlockTransitionHandle

    @Before
    fun setUp() {
        BrowserSurfaceIdentityRegistry.clearForTest()
        service = spyk(BlockingAccessibilityService())
        every { service.packageName } returns "com.focusguard.v2"
        every { service.performGlobalAction(any()) } returns true
        every { service.startActivity(any()) } just Runs

        coordinator = ReflectionHelpers.getField(service, "browserInspectionCoordinator")
        guard = ReflectionHelpers.getField(service, "websiteBlockTransitionGuard")
        val token = coordinator.offer(
            packageName,
            10,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            1L,
            1L,
            "",
            emptyList(),
            null
        ).snapshot.token
        transition = guard.tryStart(
            browserPackageName = packageName,
            transitionId = 1L,
            destination = WebsiteRedirectionCoordinator.TerminalDestination.REDIRECT,
            expectedWindowId = 10,
            inspectionGeneration = token.generation,
            blockedCandidate = "https://blocked.example",
            blockedRules = setOf("blocked.example"),
            detectionEventUptimeMillis = 1L
        )!!
        assertEquals(token.surfaceEpoch, transition.inspectionSurfaceEpoch)

        guard.markCurtainGeneration(packageName, 1L, 1L)
        ReflectionHelpers.setField(service, "instantBlockCurtainAttached", true)
        ReflectionHelpers.setField(service, "instantBlockCurtainVisible", true)
        ReflectionHelpers.setField(service, "instantBlockCurtainGeneration", 1L)
    }

    @After
    fun tearDown() {
        BrowserSurfaceIdentityRegistry.clearForTest()
        unmockkAll()
    }

    private fun call(name: String, vararg args: Any?): Any? {
        val method = BlockingAccessibilityService::class.java.declaredMethods.single {
            it.name == name && it.parameterCount == args.size
        }
        method.isAccessible = true
        return method.invoke(service, *args)
    }

    @Test
    fun sameWindowNewSurfaceCannotReuseOldTransitionForBack() {
        coordinator.offer(
            packageName,
            10,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            2L,
            2L,
            "BrowserActivity",
            emptyList(),
            null
        )

        assertEquals(false, call("performTransitionBack", transition))
        assertEquals(-1, transition.expectedWindowId)
        verify(exactly = 0) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }

    @Test
    fun verifiedDestinationMayRebindWhenAndroidReusesSameWindowId() {
        assertTrue(
            guard.markSanitizationRequested(
                browserPackageName = packageName,
                transitionId = transition.id,
                requestedAtUptimeMillis = 2L
            )
        )
        val newSurface = coordinator.offer(
            packageName,
            10,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            3L,
            3L,
            "BrowserActivity",
            emptyList(),
            null
        )

        val candidate = guard.transitionForDestinationCandidate(
            browserPackageName = packageName,
            eventUptimeMillis = 3L,
            eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        )
        assertTrue(candidate === transition)
        assertEquals(-1, transition.expectedWindowId)

        assertTrue(
            guard.rebindVerifiedDestinationWindow(
                browserPackageName = packageName,
                transitionId = transition.id,
                windowId = 10,
                inspectionGeneration = newSurface.snapshot.token.generation,
                eventUptimeMillis = 3L,
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            )
        )
        assertEquals(10, transition.expectedWindowId)
        assertEquals(newSurface.snapshot.token.surfaceEpoch, transition.inspectionSurfaceEpoch)
        assertTrue(call("transitionWindowIsCurrent", transition) as Boolean)
        assertFalse(transition.safeRedirectConfirmed.isCompleted)
    }
}
