package com.focusguard.accessibility.website.compatibility

import com.focusguard.accessibility.website.identification.WebsiteIdentificationStatus
import com.focusguard.utils.BrowserSurfaceInspector
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BrowserRecognitionPolicyTest {
    @Test
    fun `explicit non browser classification overrides legacy single handler`() {
        assertThat(
            BrowserRecognitionPolicy.isRecognizedBrowser(
                classification = BrowserClassification.NOT_BROWSER,
                legacyRecognizedBrowser = true,
                addressBarObservable = false
            )
        ).isFalse()
    }

    @Test
    fun `observable address bar remains strong browser evidence`() {
        assertThat(
            BrowserRecognitionPolicy.isRecognizedBrowser(
                classification = BrowserClassification.NOT_BROWSER,
                legacyRecognizedBrowser = false,
                addressBarObservable = true
            )
        ).isTrue()
    }

    @Test
    fun `confirmed opaque browser enters bounded recovery`() {
        val surface = BrowserRecognitionPolicy.recoverySurface(
            classification = BrowserClassification.CONFIRMED_BROWSER,
            legacyRecognizedBrowser = false,
            addressBarObservable = false,
            identificationStatus = WebsiteIdentificationStatus.UNOBSERVABLE,
            observedSurface = BrowserSurfaceInspector.Surface.UNKNOWN
        )

        assertThat(surface).isEqualTo(BrowserSurfaceInspector.Surface.WEB_CONTENT)
    }

    @Test
    fun `native browser ui is never promoted to web content`() {
        val surface = BrowserRecognitionPolicy.recoverySurface(
            classification = BrowserClassification.CONFIRMED_BROWSER,
            legacyRecognizedBrowser = true,
            addressBarObservable = false,
            identificationStatus = WebsiteIdentificationStatus.NATIVE_BROWSER_UI,
            observedSurface = BrowserSurfaceInspector.Surface.NATIVE_BROWSER_UI
        )

        assertThat(surface).isEqualTo(BrowserSurfaceInspector.Surface.NATIVE_BROWSER_UI)
    }

    @Test
    fun `confirmed browser fails closed only after recovery remains unobservable`() {
        assertThat(
            BrowserRecognitionPolicy.shouldFailClosedAfterRecovery(
                classification = BrowserClassification.CONFIRMED_BROWSER,
                legacyHttpsHandlerRecognized = false,
                identificationStatus = WebsiteIdentificationStatus.UNOBSERVABLE,
                addressBarObservable = false,
                urlCandidatePresent = false
            )
        ).isTrue()
    }

    @Test
    fun `non browser never fails closed from legacy handler`() {
        assertThat(
            BrowserRecognitionPolicy.shouldFailClosedAfterRecovery(
                classification = BrowserClassification.NOT_BROWSER,
                legacyHttpsHandlerRecognized = true,
                identificationStatus = WebsiteIdentificationStatus.UNOBSERVABLE,
                addressBarObservable = false,
                urlCandidatePresent = false
            )
        ).isFalse()
    }

    @Test
    fun `temporary opaque state with recovered url does not fail closed`() {
        assertThat(
            BrowserRecognitionPolicy.shouldFailClosedAfterRecovery(
                classification = BrowserClassification.CONFIRMED_BROWSER,
                legacyHttpsHandlerRecognized = true,
                identificationStatus = WebsiteIdentificationStatus.UNOBSERVABLE,
                addressBarObservable = false,
                urlCandidatePresent = true
            )
        ).isFalse()
    }
}
