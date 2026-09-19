from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


outcome_path = Path("app/src/main/java/com/focusguard/service/BrowserInspectionOutcome.kt")
outcome = outcome_path.read_text()
outcome = replace_once(
    outcome,
    '''    val eventContentDescription: String?,
    val eventClassName: String
) {
    val token: BrowserInspectionCoordinator.Token
        get() = BrowserInspectionCoordinator.Token(
            packageName = packageName,
            windowId = windowId,
            generation = generation,
            sequence = sequence
        )
''',
    '''    val eventContentDescription: String?,
    val eventClassName: String,
    private val sourceToken: BrowserInspectionCoordinator.Token? = null
) {
    private val fallbackToken: BrowserInspectionCoordinator.Token =
        BrowserInspectionCoordinator.Token(
            packageName = packageName,
            windowId = windowId,
            generation = generation,
            sequence = sequence
        )

    /**
     * Preserve the exact inspection token used by the snapshot. Recovery uses
     * referential identity and finishPass() mutates this token to allow newer
     * observations from the same browser document.
     */
    val token: BrowserInspectionCoordinator.Token
        get() = sourceToken ?: fallbackToken
''',
    "BrowserInspectionOutcome stable token",
)
outcome = replace_once(
    outcome,
    '''                eventContentDescription = snapshot.contentDescription,
                eventClassName = snapshot.className
            )
''',
    '''                eventContentDescription = snapshot.contentDescription,
                eventClassName = snapshot.className,
                sourceToken = token
            )
''',
    "BrowserInspectionOutcome source token",
)
outcome_path.write_text(outcome)


service_path = Path("app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt")
service = service_path.read_text()

service = replace_once(
    service,
    '''            val observedWindowPackage = transitionPackage
            if ((event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) &&
                observedWindowPackage.isNotBlank()
            ) {
                browserInspectionCoordinator.observeWindow(observedWindowPackage, event.windowId)
                retireStaleWebsiteTransitions()
            }

''',
    '',
    "defer browser window observation until package resolution",
)

service = replace_once(
    service,
    '''            if (!inspectWindowEarly && consumeInputUiEvent(event, directPackage, true)) return

            // Address-bar text changes are the strongest low-latency signal Chromium
''',
    '''            if (!inspectWindowEarly && consumeInputUiEvent(event, directPackage, true)) return

            // Browser window events can arrive without packageName, and
            // TYPE_WINDOWS_CHANGED can carry the package of a different window. Resolve
            // the package from the changed window only after the self-protection fast
            // paths above have had a chance to return.
            val inspectionPackage = if (browserInspectionEvent &&
                (directPackage.isBlank() || event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED)
            ) {
                resolveEventPackageName(event)
            } else {
                directPackage
            }

            if ((event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) &&
                inspectionPackage.isNotBlank()
            ) {
                browserInspectionCoordinator.observeWindow(inspectionPackage, event.windowId)
                retireStaleWebsiteTransitions()
            }

            // Address-bar text changes are the strongest low-latency signal Chromium
''',
    "resolved browser event package",
)

service = replace_once(
    service,
    '''            if (browserInspectionEvent && directPackage.isNotBlank() &&
                websiteSurfaceInspectionNeeded() &&
                handleImmediateBrowserAddressEvent(event, directPackage)
''',
    '''            if (browserInspectionEvent && inspectionPackage.isNotBlank() &&
                websiteSurfaceInspectionNeeded() &&
                handleImmediateBrowserAddressEvent(event, inspectionPackage)
''',
    "immediate browser inspection package",
)
service = replace_once(
    service,
    '''            if (browserInspectionEvent && directPackage.isNotBlank() &&
                websiteSurfaceInspectionNeeded()
            ) {
                scheduleBrowserInspection(event, directPackage)
            }

            val packageName = directPackage.ifBlank { foregroundPackageName.orEmpty() }
''',
    '''            if (browserInspectionEvent && inspectionPackage.isNotBlank() &&
                websiteSurfaceInspectionNeeded()
            ) {
                scheduleBrowserInspection(event, inspectionPackage)
            }

            val packageName = inspectionPackage.ifBlank { foregroundPackageName.orEmpty() }
''',
    "async browser inspection package",
)

service = replace_once(
    service,
    '''                val outcome = inspectBrowserSnapshot(snapshot)
''',
    '''                val outcome = inspectBrowserSnapshotWithRetry(snapshot)
''',
    "retry transient browser root",
)

service = replace_once(
    service,
    '''    private fun inspectBrowserSnapshot(
        snapshot: BrowserInspectionCoordinator.Snapshot
    ): BrowserInspectionOutcome? {
''',
    '''    /**
     * A missing Accessibility root during a window transition is inconclusive, not a
     * proof that the page is safe. Retry briefly while the exact package/window/
     * generation/sequence is still current; a newer event cancels these retries and
     * becomes the next queued inspection.
     */
    private suspend fun inspectBrowserSnapshotWithRetry(
        snapshot: BrowserInspectionCoordinator.Snapshot
    ): BrowserInspectionOutcome? {
        val token = snapshot.token
        var attempt = 0
        while (attempt < 3) {
            if (!browserInspectionCoordinator.isCurrent(token, requireLatestSequence = true)) return null
            inspectBrowserSnapshot(snapshot)?.let { return it }
            if (!browserInspectionCoordinator.isCurrent(token, requireLatestSequence = true)) return null
            attempt += 1
            if (attempt < 3) delay(40L * attempt)
        }
        return null
    }

    private fun inspectBrowserSnapshot(
        snapshot: BrowserInspectionCoordinator.Snapshot
    ): BrowserInspectionOutcome? {
''',
    "browser root retry helper",
)

external_start = service.find(
    '''                if (!redirectRequested &&\n                    supportsCapabilityBasedIntentRedirectFallback('''
)
if external_start < 0:
    raise SystemExit("external ACTION_VIEW fallback: start marker not found")
external_end = service.find(
    '''\n                if (!curtainReadyForTransition(transition)) return@launch''',
    external_start,
)
if external_end < 0:
    raise SystemExit("external ACTION_VIEW fallback: end marker not found")
external_replacement = '''                // HardBlock must not treat ACTION_VIEW as neutralization. It can open
                // Google in another tab/window while the blocked tab remains reachable.
                // If both certified same-tab rewrites fail, keep the curtain and fall
                // through to fail-closed protection below instead of opening a second
                // browser surface that cannot satisfy the transition guarantee.
'''
service = service[:external_start] + external_replacement + service[external_end:]
service_path.write_text(service)


test_path = Path("app/src/test/java/com/focusguard/service/BrowserInspectionOutcomeTest.kt")
test_path.write_text('''package com.focusguard.service

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
''')
