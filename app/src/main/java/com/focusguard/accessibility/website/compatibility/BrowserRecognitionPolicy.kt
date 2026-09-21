package com.focusguard.accessibility.website.compatibility

import com.focusguard.accessibility.website.identification.WebsiteIdentificationStatus
import com.focusguard.utils.BrowserSurfaceInspector

/** Bridges package-level browser evidence with one accessibility inspection. */
internal object BrowserRecognitionPolicy {
    /**
     * V4 generic ownership starts only after package-level structural recognition.
     * A single HTTPS handler, a browser-looking field or a WebView surface is not
     * enough to promote an otherwise unrecognized app into the browser pipeline.
     * Registered profiles are already returned as CONFIRMED_BROWSER by BrowserDetector.
     */
    @Suppress("UNUSED_PARAMETER")
    fun isRecognizedBrowser(
        classification: BrowserClassification,
        legacyRecognizedBrowser: Boolean,
        addressBarObservable: Boolean
    ): Boolean = classification.isBrowserLike

    /**
     * Only a structurally confirmed browser may promote an inconclusive tree into
     * the bounded URL-recovery path. Native browser UI remains separate.
     */
    fun recoverySurface(
        classification: BrowserClassification,
        identificationStatus: WebsiteIdentificationStatus,
        observedSurface: BrowserSurfaceInspector.Surface
    ): BrowserSurfaceInspector.Surface =
        if (classification.isBrowserLike &&
            identificationStatus in setOf(
                WebsiteIdentificationStatus.UNOBSERVABLE,
                WebsiteIdentificationStatus.ADDRESS_BAR_OBSERVABLE
            ) &&
            observedSurface == BrowserSurfaceInspector.Surface.UNKNOWN
        ) {
            BrowserSurfaceInspector.Surface.WEB_CONTENT
        } else {
            observedSurface
        }

    /**
     * After bounded recovery, only a structurally confirmed browser may acquire
     * new opaque-browser fail-closed evidence. PROBABLE/UNKNOWN remain diagnostic
     * states and never authorize automatic generic blocking.
     */
    fun shouldFailClosedAfterRecovery(
        classification: BrowserClassification,
        identificationStatus: WebsiteIdentificationStatus,
        addressBarObservable: Boolean,
        urlCandidatePresent: Boolean
    ): Boolean =
        classification.isBrowserLike &&
            identificationStatus == WebsiteIdentificationStatus.UNOBSERVABLE &&
            !addressBarObservable &&
            !urlCandidatePresent
}
