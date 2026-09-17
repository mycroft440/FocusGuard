package com.focusguard.accessibility.website.compatibility

import com.focusguard.accessibility.website.identification.WebsiteIdentificationStatus
import com.focusguard.utils.BrowserSurfaceInspector

/** Bridges package-level browser evidence with one accessibility inspection. */
internal object BrowserRecognitionPolicy {
    fun isRecognizedBrowser(
        classification: BrowserClassification,
        legacyRecognizedBrowser: Boolean,
        addressBarObservable: Boolean
    ): Boolean = when {
        addressBarObservable -> true
        classification.isBrowserLike -> true
        classification == BrowserClassification.NOT_BROWSER -> false
        else -> legacyRecognizedBrowser
    }

    /**
     * Only strong package-level browser evidence may promote an entirely opaque tree into the
     * bounded URL-recovery path. The legacy HTTPS-handler signal still preserves existing browser
     * recognition for observable surfaces, but cannot by itself turn an UNKNOWN tree into web
     * content because App Links can produce that same signal.
     */
    fun recoverySurface(
        classification: BrowserClassification,
        identificationStatus: WebsiteIdentificationStatus,
        observedSurface: BrowserSurfaceInspector.Surface
    ): BrowserSurfaceInspector.Surface =
        if (classification.isBrowserLike &&
            identificationStatus == WebsiteIdentificationStatus.UNOBSERVABLE &&
            observedSurface == BrowserSurfaceInspector.Surface.UNKNOWN
        ) {
            BrowserSurfaceInspector.Surface.WEB_CONTENT
        } else {
            observedSurface
        }

    /**
     * After bounded recovery, only a confirmed/probable browser may add new fail-closed web
     * evidence. Legacy HTTPS-handler recognition is intentionally excluded here: it remains useful
     * for the pre-existing observable-browser flow, but a single App Link must never be sufficient
     * to create opaque-browser blocking.
     */
    fun shouldFailClosedAfterRecovery(
        classification: BrowserClassification,
        identificationStatus: WebsiteIdentificationStatus,
        addressBarObservable: Boolean,
        urlCandidatePresent: Boolean
    ): Boolean {
        if (!classification.isBrowserLike ||
            identificationStatus != WebsiteIdentificationStatus.UNOBSERVABLE ||
            addressBarObservable || urlCandidatePresent
        ) return false

        return true
    }
}
