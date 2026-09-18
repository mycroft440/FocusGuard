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
}
