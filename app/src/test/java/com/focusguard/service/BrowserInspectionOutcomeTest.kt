package com.focusguard.service

import android.view.accessibility.AccessibilityEvent
import com.focusguard.accessibility.website.identification.WebsiteIdentificationResult
import com.focusguard.accessibility.website.identification.WebsiteIdentificationStatus
import com.focusguard.utils.BrowserSurfaceInspector
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserInspectionOutcomeTest {
    @Test
    fun outcomeKeepsSnapshotTokenIdentityAcrossRecoveryHandoff() {
        val token = BrowserInspectionCoordinator.Token(
            packageName = "com.android.chrome",
            windowId = 7,
            generation = 3L,
            sequence = 11L
        )
        val snapshot = BrowserInspectionCoordinator.Snapshot(
            token = token,
            eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            eventUptimeMillis = 100L,
            receivedUptimeMillis = 101L,
            className = "android.view.View",
            directText = emptyList(),
            contentDescription = null
        )
        val outcome = BrowserInspectionOutcome.from(
            snapshot = snapshot,
            identification = WebsiteIdentificationResult(
                status = WebsiteIdentificationStatus.UNOBSERVABLE,
                browserPackageName = token.packageName,
                windowId = token.windowId,
                webContentObserved = true
            ),
            surface = BrowserSurfaceInspector.Surface.WEB_CONTENT,
            focusedAddressEditor = false,
            recognizedBrowser = true
        )

        assertSame(token, outcome.token)
        token.permitSequenceAdvance()
        assertTrue(outcome.token.allowSequenceAdvance)

        val recovery = BrowserRecoveryCoordinator()
        assertTrue(recovery.offer(outcome.token))
        assertTrue(recovery.isCurrent(outcome.token))
        assertNull(recovery.finish(outcome.token))
    }
}
