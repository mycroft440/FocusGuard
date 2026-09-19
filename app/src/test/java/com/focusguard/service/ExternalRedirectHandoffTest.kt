package com.focusguard.service

import android.app.Application
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ExternalRedirectHandoffTest {
    private val browserPackage = "com.android.chrome"
    private val ownPackage = "com.focusguard.v2"

    private fun event(type: Int, pkg: String?, id: Int, time: Long) =
        mockk<AccessibilityEvent>(relaxed = true) {
            every { eventType } returns type
            every { packageName } returns pkg
            every { windowId } returns id
            every { eventTime } returns time
        }

    @Test
    fun `transient new browser window does not inherit redirect before safe Google inspection`() = runTest {
        val service = spyk(BlockingAccessibilityService())
        every { service.packageName } returns ownPackage
        val newWindow = mockk<AccessibilityWindowInfo> {
            every { id } returns 11
            every { type } returns AccessibilityWindowInfo.TYPE_APPLICATION
            every { isActive } returns true
            every { root } returns null
        }
        every { service.windows } returns listOf(newWindow)
        ReflectionHelpers.setField(service, "scope", this)
        ReflectionHelpers.setField(service, "foregroundPackageName", browserPackage)

        val coordinator = ReflectionHelpers.getField<BrowserInspectionCoordinator>(
            service, "browserInspectionCoordinator"
        )
        val guard = ReflectionHelpers.getField<BlockingAccessibilityService.WebsiteBlockTransitionGuard>(
            service, "websiteBlockTransitionGuard"
        )
        val token = coordinator.offer(
            browserPackage, 10, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            1L, 1L, "", emptyList(), null
        ).snapshot.token
        val transition = guard.tryStart(
            browserPackageName = browserPackage,
            transitionId = 1L,
            destination = BlockingAccessibilityService.WebsiteTransitionDestination.GOOGLE,
            expectedWindowId = 10,
            inspectionGeneration = token.generation,
            blockedCandidate = "https://blocked.example",
            blockedRules = setOf("blocked.example"),
            detectionEventUptimeMillis = 1L
        )!!
        guard.markCurtainGeneration(browserPackage, 1L, 1L)
        assertTrue(guard.markExternalRedirectRequested(browserPackage, 1L, 2L))
        ReflectionHelpers.setField(service, "instantBlockCurtainAttached", true)
        ReflectionHelpers.setField(service, "instantBlockCurtainVisible", true)
        ReflectionHelpers.setField(service, "instantBlockCurtainGeneration", 1L)

        service.onAccessibilityEvent(
            event(AccessibilityEvent.TYPE_WINDOWS_CHANGED, null, 11, 3L)
        )
        runCurrent()

        assertSame(transition, guard.activeTransition(browserPackage))
        assertEquals(10, transition.expectedWindowId)
        assertFalse(transition.externalRedirectWindowRebound)
    }

    @Test
    fun `second different window cannot inherit the same external redirect`() = runTest {
        val service = spyk(BlockingAccessibilityService())
        every { service.packageName } returns ownPackage
        ReflectionHelpers.setField(service, "scope", this)
        val coordinator = ReflectionHelpers.getField<BrowserInspectionCoordinator>(
            service, "browserInspectionCoordinator"
        )
        val guard = ReflectionHelpers.getField<BlockingAccessibilityService.WebsiteBlockTransitionGuard>(
            service, "websiteBlockTransitionGuard"
        )
        val token = coordinator.offer(
            browserPackage, 10, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            1L, 1L, "", emptyList(), null
        ).snapshot.token
        val transition = guard.tryStart(
            browserPackage, 1L,
            BlockingAccessibilityService.WebsiteTransitionDestination.GOOGLE,
            expectedWindowId = 10,
            inspectionGeneration = token.generation,
            detectionEventUptimeMillis = 1L
        )!!
        assertTrue(guard.markExternalRedirectRequested(browserPackage, 1L, 2L))
        val generation11 = coordinator.observeWindow(browserPackage, 11)
        assertTrue(
            guard.rebindExternalRedirectWindow(
                browserPackage, 1L, 11, generation11, 3L
            )
        )
        assertFalse(
            guard.rebindExternalRedirectWindow(
                browserPackage, 1L, 12,
                coordinator.observeWindow(browserPackage, 12), 4L
            )
        )
        assertEquals(11, transition.expectedWindowId)
    }
}