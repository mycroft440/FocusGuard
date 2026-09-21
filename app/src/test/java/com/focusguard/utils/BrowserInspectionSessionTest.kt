package com.focusguard.utils

import android.app.Application
import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BrowserInspectionSessionTest {
    @Test
    fun readersShareOneBudgetButNextPassCannotReuseOldFacts() {
        val pkg = "com.android.chrome"
        val root = mockk<AccessibilityNodeInfo> { every { windowId } returns 10 }
        val first = BrowserInspectionSessionStore.withInspection(root, pkg, { true }) {
            val session = BrowserInspectionSessionStore.sessionFor(root, pkg)
            session.url = "https://old.example"
            session.budget.tryVisitNode(0, 1)
            BrowserInspectionSessionStore.withInspection(root, pkg, { true }) {
                assertSame(session, BrowserInspectionSessionStore.sessionFor(root, pkg))
                assertEquals(1, BrowserInspectionSessionStore.sessionFor(root, pkg).budget.nodesVisited)
            }
            session
        }
        BrowserInspectionSessionStore.withInspection(root, pkg, { true }) {
            val next = BrowserInspectionSessionStore.sessionFor(root, pkg)
            assertNotSame(first, next)
            assertNull(next.url)
            assertEquals(0, next.budget.nodesVisited)
        }
    }

    @Test
    fun completedScopedReadRetainsObservedFocusForImmediateConfirmationOnly() {
        val pkg = "com.brave.browser"
        val root = mockk<AccessibilityNodeInfo> { every { windowId } returns 12 }

        BrowserInspectionSessionStore.withInspection(root, pkg, { true }) {
            BrowserInspectionSessionStore.sessionFor(root, pkg).apply {
                url = "https://www.google.com"
                surface = BrowserSurfaceInspector.Surface.WEB_CONTENT
                focusedAddressEditor = true
                addressBarObservable = true
                addressComplete = true
            }
        }

        val immediate = BrowserInspectionSessionStore.sessionFor(root, pkg)
        assertEquals("https://www.google.com", immediate.url)
        assertEquals(BrowserSurfaceInspector.Surface.WEB_CONTENT, immediate.surface)
        assertTrue(immediate.focusedAddressEditor)
        assertTrue(immediate.addressBarObservable)
        assertFalse("detached observation must not escape as a scoped session", immediate.scoped)

        BrowserInspectionSessionStore.withInspection(root, pkg, { true }) {
            val nextPass = BrowserInspectionSessionStore.sessionFor(root, pkg)
            assertFalse(nextPass.focusedAddressEditor)
            assertNull(nextPass.url)
            assertNull(nextPass.surface)
        }
    }

    @Test
    fun defaultBudgetAllowsFullAddressSelectorCatalogBeforeSemanticFallback() {
        val budget = BrowserInspectionBudget(timeoutMillis = 5_000L)
        val selectorCount = (
            BrowserUiCapabilityPolicy.strongAddressBarEntryNames +
                BrowserUiCapabilityPolicy.weakReadOnlyAddressBarEntryNames
            ).size

        repeat(selectorCount) {
            assertTrue("address id probe ${it + 1} should remain within budget", budget.tryIdQuery())
        }

        assertEquals(selectorCount, budget.idQueries)
        assertFalse(budget.isExhausted)
        assertTrue("semantic traversal must still be eligible", budget.tryVisitNode(0, 24))
    }

    @Test
    fun unknownPackageUsesStrictGenericTreeBudget() {
        val session = BrowserInspectionSession(
            rootIdentity = 1,
            windowId = 7,
            browserPackage = "test.unknown.browser"
        )

        assertEquals(BrowserInspectionBudget.GENERIC_MAX_NODES, session.budget.configuredMaxNodes)
        assertEquals(BrowserInspectionBudget.GENERIC_MAX_DEPTH, session.budget.configuredMaxDepth)
        assertEquals(
            BrowserInspectionBudget.GENERIC_TIMEOUT_MILLIS,
            session.budget.configuredTimeoutMillis
        )
    }

    @Test
    fun knownProfileKeepsCompatibilityBudgetAndGenericDepthDoesNotLeakIntoIt() {
        val session = BrowserInspectionSession(
            rootIdentity = 2,
            windowId = 8,
            browserPackage = "com.android.chrome"
        )

        assertEquals(512, session.budget.configuredMaxNodes)
        assertEquals(Int.MAX_VALUE, session.budget.configuredMaxDepth)
        assertEquals(80L, session.budget.configuredTimeoutMillis)
    }

    @Test
    fun genericBudgetEnforcesTwoHundredNodesAndTwentyLevels() {
        val budget = BrowserInspectionBudget(
            maxNodes = BrowserInspectionBudget.GENERIC_MAX_NODES,
            absoluteMaxDepth = BrowserInspectionBudget.GENERIC_MAX_DEPTH,
            timeoutMillis = 5_000L
        )

        repeat(BrowserInspectionBudget.GENERIC_MAX_NODES) { index ->
            assertTrue(budget.tryVisitNode(index % 20, 24))
        }
        assertFalse(budget.tryVisitNode(0, 24))

        val depthBudget = BrowserInspectionBudget(
            maxNodes = BrowserInspectionBudget.GENERIC_MAX_NODES,
            absoluteMaxDepth = BrowserInspectionBudget.GENERIC_MAX_DEPTH,
            timeoutMillis = 5_000L
        )
        assertTrue(depthBudget.tryVisitNode(20, 24))
        assertFalse(depthBudget.tryVisitNode(21, 24))
        assertEquals(1, depthBudget.depthStops)
    }
}
