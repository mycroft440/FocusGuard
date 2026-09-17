package com.focusguard.utils

import android.app.Application
import android.view.accessibility.AccessibilityNodeInfo
import com.focusguard.accessibility.website.compatibility.BrowserCompatibilityStore
import com.focusguard.accessibility.website.compatibility.BrowserSubmitMethod
import com.focusguard.accessibility.website.redirection.AddressBarRedirectionActions
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebsiteBlockerActionRaceTest {
    @After
    fun tearDown() = unmockkAll()

    @Test
    fun cachedSubmitThatBecomesStaleDoesNotTeachSuccessOrFailure() {
        val pkg = "com.android.chrome"
        var current = true
        mockkObject(BrowserCompatibilityStore, AddressBarRedirectionActions)
        every { BrowserCompatibilityStore.preferredSubmitMethod(pkg) } returns BrowserSubmitMethod.CERTIFIED_GO_BUTTON
        val root = mockk<AccessibilityNodeInfo>(relaxed = true) {
            every { packageName } returns pkg
            every { windowId } returns 10
            every { childCount } returns 0
            every { findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
        }
        every { AddressBarRedirectionActions.clickCertifiedGoButton(root, pkg, 10, any()) } answers {
            assertTrue(lastArg<() -> Boolean>().invoke())
            current = false
            AddressBarRedirectionActions.Result(AddressBarRedirectionActions.Status.ACCEPTED)
        }

        val result = WebsiteBlocker.performUniqueAddressBarAction(
            root, pkg, 10, BrowserUiCapabilityPolicy.NodeAction.IME_ENTER,
            isCurrent = { current }
        )

        assertFalse(result.accepted)
        verify(exactly = 1) { AddressBarRedirectionActions.clickCertifiedGoButton(root, pkg, 10, any()) }
        verify(exactly = 0) { BrowserCompatibilityStore.recordSubmitAccepted(any(), any(), any()) }
        verify(exactly = 0) { BrowserCompatibilityStore.recordRedirectionFailure(any()) }
        verify(exactly = 0) { AddressBarRedirectionActions.submitAnnouncedEditorAction(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun currentCachedSubmitLearnsExactlyOnceAtWebsiteBlockerBoundary() {
        val pkg = "com.android.chrome"
        mockkObject(BrowserCompatibilityStore, AddressBarRedirectionActions)
        every { BrowserCompatibilityStore.preferredSubmitMethod(pkg) } returns BrowserSubmitMethod.CERTIFIED_GO_BUTTON
        every { BrowserCompatibilityStore.recordSubmitAccepted(any(), any(), any()) } just Runs
        val root = mockk<AccessibilityNodeInfo>(relaxed = true) {
            every { packageName } returns pkg
            every { windowId } returns 10
            every { childCount } returns 0
            every { findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
        }
        every { AddressBarRedirectionActions.clickCertifiedGoButton(root, pkg, 10, any()) } returns
            AddressBarRedirectionActions.Result(AddressBarRedirectionActions.Status.ACCEPTED, "$pkg:id/url_bar_go_button")

        val result = WebsiteBlocker.performUniqueAddressBarAction(
            root, pkg, 10, BrowserUiCapabilityPolicy.NodeAction.IME_ENTER,
            isCurrent = { true }
        )

        assertTrue(result.accepted)
        verify(exactly = 1) {
            BrowserCompatibilityStore.recordSubmitAccepted(
                pkg,
                null,
                BrowserSubmitMethod.CERTIFIED_GO_BUTTON
            )
        }
    }

    @Test
    fun staleAddressActionNeverReadsOrActsOnRoot() {
        val root = mockk<AccessibilityNodeInfo>()
        val result = WebsiteBlocker.performUniqueAddressBarAction(
            root, "com.android.chrome", 10, BrowserUiCapabilityPolicy.NodeAction.SET_TEXT,
            isCurrent = { false }
        )
        assertEquals(WebsiteBlocker.AddressBarActionStatus.REJECTED, result.status)
        verify { root wasNot Called }
    }
}
