package com.focusguard.accessibility.website.identification

import android.app.Application
import android.view.accessibility.AccessibilityNodeInfo
import com.focusguard.accessibility.website.compatibility.BrowserClassification
import com.focusguard.accessibility.website.compatibility.BrowserCompatibilityStore
import com.focusguard.accessibility.website.compatibility.BrowserDetectionDecision
import com.focusguard.accessibility.website.compatibility.BrowserDetectionReason
import com.focusguard.accessibility.website.compatibility.BrowserDetector
import com.focusguard.accessibility.website.diagnostics.WebsiteBlockingDiagnostics
import com.focusguard.utils.BrowserSurfaceInspector
import com.focusguard.utils.BrowserUiCapabilityPolicy
import com.focusguard.utils.WebsiteBlocker
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebsiteIdentificationGenericRecoveryTest {
    private val pkg = "test.structural.browser"
    private val windowId = 41

    @Before
    fun setUp() {
        BrowserObservationSignal.clearForTest(pkg, windowId)
        mockkObject(
            BrowserCompatibilityStore,
            BrowserDetector,
            BrowserSurfaceInspector,
            WebsiteBlockingDiagnostics,
            WebsiteIdentificationEngine,
            WebsiteBlocker
        )
        every { BrowserCompatibilityStore.preferredUrlRecoveryMethod(pkg) } returns null
        every { BrowserCompatibilityStore.recordUrlRecoverySuccess(any(), any()) } just Runs
        every { BrowserDetector.detect(pkg) } returns BrowserDetectionDecision(
            BrowserClassification.CONFIRMED_BROWSER,
            BrowserDetectionReason.HTTP_HTTPS_CONFIRMED
        )
        every { BrowserSurfaceInspector.inspect(any(), pkg) } returns
            BrowserSurfaceInspector.Surface.WEB_CONTENT
        every { BrowserSurfaceInspector.isWebContainer(any()) } returns false
        every {
            WebsiteIdentificationEngine.identifyFromRoot(any(), pkg, windowId, true, any())
        } returns WebsiteIdentificationResult(
            status = WebsiteIdentificationStatus.UNOBSERVABLE,
            browserPackageName = pkg,
            windowId = windowId,
            webContentObserved = true
        )
        every {
            WebsiteBlocker.performUniqueAddressBarAction(
                any(), pkg, windowId, any(), any(), any(), true, false, any()
            )
        } returns WebsiteBlocker.AddressBarActionResult(
            WebsiteBlocker.AddressBarActionStatus.ACCEPTED
        )
        every {
            WebsiteBlockingDiagnostics.recordIdentificationFailure(
                browserPackageName = any(),
                windowId = any(),
                status = any(),
                addressBarObservable = any(),
                webContentObserved = any(),
                evidence = any()
            )
        } just Runs
    }

    @After
    fun tearDown() {
        BrowserObservationSignal.clearForTest(pkg, windowId)
        unmockkAll()
    }

    @Test
    fun `generic recovery is bounded and fails closed when confirmed browser stays opaque`() = runTest {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        val recovery = WebsiteIdentificationRecovery(
            browserPackage = pkg,
            windowId = windowId,
            httpsHandlerRecognized = true,
            rootProvider = {
                mockk<AccessibilityNodeInfo>(relaxed = true) {
                    every { packageName } returns pkg
                    every { this@mockk.windowId } returns this@WebsiteIdentificationGenericRecoveryTest.windowId
                    every { isVisibleToUser } returns true
                    every { childCount } returns 0
                }.also(roots::add)
            },
            isCurrent = { true },
            readDispatcher = Dispatchers.Unconfined
        )

        val result = recovery.recover()

        assertThat(currentTime)
            .isAtMost(WebsiteIdentificationRecovery.GENERIC_RECOVERY_TOTAL_TIMEOUT_MILLIS)
        assertThat(result.status).isEqualTo(WebsiteIdentificationStatus.UNOBSERVABLE)
        assertThat(result.webContentObserved).isTrue()
        assertThat(result.evidence).contains(WebsiteIdentificationLayer.FAIL_CLOSED)
        verify(atMost = WebsiteIdentificationRecovery.GENERIC_MAX_RECOVERY_TECHNIQUES) {
            WebsiteBlocker.performUniqueAddressBarAction(
                any(), pkg, windowId, any<BrowserUiCapabilityPolicy.NodeAction>(),
                any(), any(), true, false, any()
            )
        }
        roots.forEach { verify(exactly = 1) { it.recycle() } }
    }

    @Test
    fun `generic recovery contract keeps three techniques and strict reveal limits`() {
        assertThat(WebsiteIdentificationRecovery.GENERIC_MAX_RECOVERY_TECHNIQUES).isEqualTo(3)
        assertThat(WebsiteIdentificationRecovery.GENERIC_RECOVERY_TOTAL_TIMEOUT_MILLIS).isEqualTo(500L)
        assertThat(WebsiteIdentificationRecovery.GENERIC_REVEAL_MAX_NODES).isEqualTo(200)
        assertThat(WebsiteIdentificationRecovery.GENERIC_REVEAL_MAX_DEPTH).isEqualTo(20)
    }
}
