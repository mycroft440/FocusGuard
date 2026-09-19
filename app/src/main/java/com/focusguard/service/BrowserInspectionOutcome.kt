package com.focusguard.service

import android.view.accessibility.AccessibilityEvent
import com.focusguard.accessibility.website.compatibility.BrowserDetector
import com.focusguard.accessibility.website.compatibility.BrowserRecognitionPolicy
import com.focusguard.accessibility.website.identification.WebsiteIdentificationLayer
import com.focusguard.accessibility.website.identification.WebsiteIdentificationResult
import com.focusguard.accessibility.website.identification.WebsiteIdentificationStatus
import com.focusguard.utils.BrowserSurfaceInspector
import com.focusguard.utils.WebsiteBlocker

/**
 * Immutable data returned by one worker-owned browser inspection.
 *
 * Accessibility objects never appear in this contract. Generation and sequence
 * make every state mutation rejectable once a newer browser observation exists.
 */
internal data class BrowserInspectionOutcome(
    val packageName: String,
    val windowId: Int,
    val generation: Long,
    val sequence: Long,
    val eventType: Int,
    val eventUptimeMillis: Long,
    val surface: BrowserSurfaceInspector.Surface,
    val identificationStatus: WebsiteIdentificationStatus,
    val urlCandidate: String?,
    val rawAddressText: String?,
    val addressBarPresent: Boolean,
    val focusedAddressEditor: Boolean,
    val webContentObserved: Boolean,
    val evidence: Set<WebsiteIdentificationLayer>,
    val recognizedBrowser: Boolean,
    val directEventText: List<String>,
    val eventContentDescription: String?,
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

    val bestCandidate: String?
        get() = urlCandidate?.takeIf(String::isNotBlank)
            ?: rawAddressText?.takeIf(String::isNotBlank)

    /** Direct text is only search evidence on a freshly verified Google document. */
    fun hasBlockedGoogleSearch(): Boolean =
        surface == BrowserSurfaceInspector.Surface.WEB_CONTENT &&
            eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
            eventClassName.endsWith("EditText") &&
            urlCandidate?.let(WebsiteBlocker::isGoogleUrl) == true &&
            directEventText.any(WebsiteBlocker::containsPornographySearchTerm)

    companion object {
        fun from(
            snapshot: BrowserInspectionCoordinator.Snapshot,
            identification: WebsiteIdentificationResult,
            surface: BrowserSurfaceInspector.Surface,
            focusedAddressEditor: Boolean,
            recognizedBrowser: Boolean
        ): BrowserInspectionOutcome {
            val token = snapshot.token
            val classification = BrowserDetector.classify(token.packageName)
            val effectiveRecognizedBrowser = BrowserRecognitionPolicy.isRecognizedBrowser(
                classification = classification,
                legacyRecognizedBrowser = recognizedBrowser,
                addressBarObservable = identification.addressBarObservable
            )
            val effectiveSurface = BrowserRecognitionPolicy.recoverySurface(
                classification = classification,
                identificationStatus = identification.status,
                observedSurface = surface
            )
            return BrowserInspectionOutcome(
                packageName = token.packageName,
                windowId = token.windowId,
                generation = token.generation,
                sequence = token.sequence,
                eventType = snapshot.eventType,
                eventUptimeMillis = snapshot.eventUptimeMillis,
                surface = effectiveSurface,
                identificationStatus = identification.status,
                urlCandidate = identification.urlCandidate,
                rawAddressText = identification.rawAddressText,
                addressBarPresent = identification.addressBarObservable,
                focusedAddressEditor = focusedAddressEditor,
                webContentObserved = identification.webContentObserved ||
                    effectiveSurface == BrowserSurfaceInspector.Surface.WEB_CONTENT,
                evidence = identification.evidence.toSet(),
                recognizedBrowser = effectiveRecognizedBrowser,
                directEventText = snapshot.directText.toList(),
                eventContentDescription = snapshot.contentDescription,
                eventClassName = snapshot.className,
                sourceToken = token
            )
        }
    }
}
