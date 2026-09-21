package com.focusguard.accessibility.website.compatibility

import com.focusguard.accessibility.website.identification.WebsiteIdentificationStatus
import com.focusguard.utils.BrowserSurfaceInspector

/** Bridges package-level browser evidence with one accessibility inspection. */
internal object BrowserRecognitionPolicy {
    /**
     * A shipped profile owns its package independently of generic detection. For an
     * unowned package, opaque-browser protection requires strong generic evidence;
     * PROBABLE_BROWSER and legacy handler history are not sufficient by themselves.
     */
    fun isRecognizedBrowser(
        classification: BrowserClassification,
        legacyRecognizedBrowser: Boolean,
        addressBarObservable: Boolean,
        hasSpecificOwner: Boolean = false
    ): Boolean = when {
        hasSpecificOwner -> true
        classification == BrowserClassification.CONFIRMED_BROWSER -> true
        addressBarObservable && classification != BrowserClassification.NOT_BROWSER -> true
        classification == BrowserClassification.NOT_BROWSER -> false
        else -> false
    }

    /**
     * Promote an inconclusive tree into bounded URL recovery only for a package that
     * already has a specific owner or an unowned package strongly confirmed as a
     * browser. A probable/legacy-only package is never converted into opaque browser
     * fail-closed state.
     */
    fun recoverySurface(
        classification: BrowserClassification,
        identificationStatus: WebsiteIdentificationStatus,
        observedSurface: BrowserSurfaceInspector.Surface,
        hasSpecificOwner: Boolean = false
    ): BrowserSurfaceInspector.Surface =
        if ((hasSpecificOwner || classification == BrowserClassification.CONFIRMED_BROWSER) &&
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
     * After bounded recovery, fail closed only for a specifically owned browser or
     * for an unowned package with strong generic browser evidence.
     */
    fun shouldFailClosedAfterRecovery(
        classification: BrowserClassification,
        identificationStatus: WebsiteIdentificationStatus,
        addressBarObservable: Boolean,
        urlCandidatePresent: Boolean,
        hasSpecificOwner: Boolean = false
    ): Boolean =
        (hasSpecificOwner || classification == BrowserClassification.CONFIRMED_BROWSER) &&
            identificationStatus == WebsiteIdentificationStatus.UNOBSERVABLE &&
            !addressBarObservable &&
            !urlCandidatePresent
}
