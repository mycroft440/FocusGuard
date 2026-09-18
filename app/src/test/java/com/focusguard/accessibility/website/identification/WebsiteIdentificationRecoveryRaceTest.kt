package com.focusguard.accessibility.website.identification

import android.app.Application
import android.view.accessibility.AccessibilityNodeInfo
import com.focusguard.accessibility.website.compatibility.BrowserCompatibilityStore
import com.focusguard.accessibility.website.compatibility.BrowserUrlRecoveryMethod
import com.focusguard.utils.BrowserSurfaceInspector
import com.focusguard.utils.WebsiteBlocker
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebsiteIdentificationRecoveryRaceTest {
    private val pkg = "com.android.chrome"
    private var current = true
    private val roots = mutableListOf<AccessibilityNodeInfo>()
    private val unresolved = WebsiteIdentificationResult(
        WebsiteIdentificationStatus.UNOBSERVABLE, webContentObserved = true
    )

    @Before
    fun setUp() {
        BrowserObservationSignal.clearForTest(pkg, 10)
        mockkObject(WebsiteIdentificationEngine, WebsiteBlocker, BrowserSurfaceInspector, BrowserCompatibilityStore)
        every { BrowserCompatibilityStore.preferredUrlRecoveryMethod(pkg) } returns BrowserUrlRecoveryMethod.FOCUS
        every { BrowserCompatibilityStore.recordUrlRecoverySuccess(any(), any()) } just Runs
        every { BrowserSurfaceInspector.inspect(any(), pkg) } returns BrowserSurfaceInspector.Surface.WEB_CONTENT
        every { WebsiteIdentificationEngine.identifyFromRoot(any(), pkg, 10, true, any()) } returns unresolved
        every {
            WebsiteBlocker.performUniqueAddressBarAction(any(), pkg, 10, any(), any(), any(), true, false, any())
        } returns WebsiteBlocker.AddressBarActionResult(WebsiteBlocker.AddressBarActionStatus.ACCEPTED)
    }

    @After
    fun tearDown() {
        BrowserObservationSignal.clearForTest(pkg, 10)
        unmockkAll()
    }

    private fun recovery() = WebsiteIdentificationRecovery(pkg, 10, true, rootProvider = {
        mockk<AccessibilityNodeInfo>(relaxed = true) {
            every { packageName } returns pkg
            every { windowId } returns 10
        }.also(roots::add)
    }, isCurrent = { current }, readDispatcher = Dispatchers.Unconfined)

    @Test
    fun windowChangeWhileAwaitingObservationStopsEveryFollowingPhase() = runTest {
        val result = async { recovery().recover() }
        runCurrent()
        assertEquals(3, roots.size)
        current = false
        advanceUntilIdle()
        assertEquals(WebsiteIdentificationStatus.REJECTED_CONTEXT, result.await().status)
        assertEquals(3, roots.size)
        verify(exactly = 1) {
            WebsiteBlocker.performUniqueAddressBarAction(any(), pkg, 10, any(), any(), any(), true, false, any())
        }
        verify(exactly = 0) { BrowserCompatibilityStore.recordUrlRecoverySuccess(any(), any()) }
        roots.forEach { verify(exactly = 1) { it.recycle() } }
    }

    @Test
    fun urlFoundAfterInvalidationNeverTeachesCompatibilityCache() = runTest {
        var reads = 0
        every { WebsiteIdentificationEngine.identifyFromRoot(any(), pkg, 10, true, any()) } answers {
            reads++
            if (reads < 3) unresolved else {
                current = false
                WebsiteIdentificationResult(WebsiteIdentificationStatus.IDENTIFIED, urlCandidate = "https://example.org")
            }
        }
        assertEquals(WebsiteIdentificationStatus.REJECTED_CONTEXT, recovery().recover().status)
        verify(exactly = 0) { BrowserCompatibilityStore.recordUrlRecoverySuccess(any(), any()) }
    }

    @Test
    fun currentRecoveryLearnsPreferredMethodAndReacquiresAfterFocus() = runTest {
        var reads = 0
        every { WebsiteIdentificationEngine.identifyFromRoot(any(), pkg, 10, true, any()) } answers {
            if (++reads < 3) unresolved else WebsiteIdentificationResult(
                WebsiteIdentificationStatus.IDENTIFIED, urlCandidate = "https://example.org"
            )
        }
        assertEquals("https://example.org", recovery().recover().urlCandidate)
        assertEquals(4, roots.size)
        assertEquals(4, roots.toSet().size)
        verify(exactly = 1) { BrowserCompatibilityStore.recordUrlRecoverySuccess(pkg, BrowserUrlRecoveryMethod.FOCUS) }
        roots.forEach { verify(exactly = 1) { it.recycle() } }
    }

    @Test
    fun browserEventAfterAcceptedActionAvoidsFixedRecoveryDelay() = runTest {
        var reads = 0
        every { WebsiteIdentificationEngine.identifyFromRoot(any(), pkg, 10, true, any()) } answers {
            if (++reads < 3) unresolved else WebsiteIdentificationResult(
                WebsiteIdentificationStatus.IDENTIFIED,
                urlCandidate = "https://example.org"
            )
        }
        every {
            WebsiteBlocker.performUniqueAddressBarAction(any(), pkg, 10, any(), any(), any(), true, false, any())
        } answers {
            BrowserObservationSignal.markObserved(pkg, 10)
            WebsiteBlocker.AddressBarActionResult(WebsiteBlocker.AddressBarActionStatus.ACCEPTED)
        }

        val result = recovery().recover()

        assertEquals("https://example.org", result.urlCandidate)
        assertEquals(0L, currentTime)
        verify(exactly = 1) {
            BrowserCompatibilityStore.recordUrlRecoverySuccess(pkg, BrowserUrlRecoveryMethod.FOCUS)
        }
    }
}
