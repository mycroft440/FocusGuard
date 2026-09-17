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
     * A confirmed/probable browser with an entirely opaque tree must still enter the bounded URL
     * recovery path. Native browser panels remain untouched because they have their own surface.
     */
    fun recoverySurface(
        classification: BrowserClassification,
        legacyRecognizedBrowser: Boolean,
        addressBarObservable: Boolean,
        identificationStatus: WebsiteIdentificationStatus,
        observedSurface: BrowserSurfaceInspector.Surface
    ): BrowserSurfaceInspector.Surface {
        val recognized = isRecognizedBrowser(
            classification = classification,
            legacyRecognizedBrowser = legacyRecognizedBrowser,
            addressBarObservable = addressBarObservable
        )
        return if (recognized &&
            identificationStatus == WebsiteIdentificationStatus.UNOBSERVABLE &&
            observedSurface == BrowserSurfaceInspector.Surface.UNKNOWN
        ) {
            BrowserSurfaceInspector.Surface.WEB_CONTENT
        } else {
            observedSurface
        }
    }

    /**
     * After bounded recovery, only strong browser evidence may turn a still-unobservable result
     * into fail-closed web content. A single legacy HTTPS match is accepted only when the new
     * detector could not decide, never when it explicitly classified the package as NOT_BROWSER.
     */
    fun shouldFailClosedAfterRecovery(
        classification: BrowserClassification,
        legacyHttpsHandlerRecognized: Boolean,
        identificationStatus: WebsiteIdentificationStatus,
        addressBarObservable: Boolean,
        urlCandidatePresent: Boolean
    ): Boolean {
        if (identificationStatus != WebsiteIdentificationStatus.UNOBSERVABLE ||
            addressBarObservable || urlCandidatePresent
        ) return false

        return classification.isBrowserLike ||
            (classification == BrowserClassification.UNKNOWN && legacyHttpsHandlerRecognized)
    }
}
