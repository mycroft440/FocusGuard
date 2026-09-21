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
    fun `browser looking field alone cannot promote non browser app`() {
        assertThat(
            BrowserRecognitionPolicy.isRecognizedBrowser(
                classification = BrowserClassification.NOT_BROWSER,
                legacyRecognizedBrowser = false,
                addressBarObservable = true
            )
        ).isFalse()
    }

    @Test
    fun `legacy https handler cannot promote probable browser`() {
        assertThat(
            BrowserRecognitionPolicy.isRecognizedBrowser(
                classification = BrowserClassification.PROBABLE_BROWSER,
                legacyRecognizedBrowser = true,
                addressBarObservable = true
            )
        ).isFalse()
    }

    @Test
    fun `structurally confirmed browser is recognized without legacy evidence`() {
        assertThat(
            BrowserRecognitionPolicy.isRecognizedBrowser(
                classification = BrowserClassification.CONFIRMED_BROWSER,
                legacyRecognizedBrowser = false,
                addressBarObservable = false
            )
        ).isTrue()
    }

    @Test
    fun `confirmed opaque browser enters bounded recovery`() {
        val surface = BrowserRecognitionPolicy.recoverySurface(
            classification = BrowserClassification.CONFIRMED_BROWSER,
            identificationStatus = WebsiteIdentificationStatus.UNOBSERVABLE,
            observedSurface = BrowserSurfaceInspector.Surface.UNKNOWN
        )

        assertThat(surface).isEqualTo(BrowserSurfaceInspector.Surface.WEB_CONTENT)
    }

    @Test
    fun `confirmed browser with address bar but no url enters bounded recovery`() {
        val surface = BrowserRecognitionPolicy.recoverySurface(
            classification = BrowserClassification.CONFIRMED_BROWSER,
            identificationStatus = WebsiteIdentificationStatus.ADDRESS_BAR_OBSERVABLE,
            observedSurface = BrowserSurfaceInspector.Surface.UNKNOWN
        )

        assertThat(surface).isEqualTo(BrowserSurfaceInspector.Surface.WEB_CONTENT)
    }

    @Test
    fun `probable browser never enters automatic generic recovery`() {
        val surface = BrowserRecognitionPolicy.recoverySurface(
            classification = BrowserClassification.PROBABLE_BROWSER,
            identificationStatus = WebsiteIdentificationStatus.UNOBSERVABLE,
            observedSurface = BrowserSurfaceInspector.Surface.UNKNOWN
        )

        assertThat(surface).isEqualTo(BrowserSurfaceInspector.Surface.UNKNOWN)
    }

    @Test
    fun `unknown legacy handler does not promote opaque tree`() {
        val surface = BrowserRecognitionPolicy.recoverySurface(
            classification = BrowserClassification.UNKNOWN,
            identificationStatus = WebsiteIdentificationStatus.UNOBSERVABLE,
            observedSurface = BrowserSurfaceInspector.Surface.UNKNOWN
        )

        assertThat(surface).isEqualTo(BrowserSurfaceInspector.Surface.UNKNOWN)
    }

    @Test
    fun `native browser ui is never promoted to web content`() {
        val surface = BrowserRecognitionPolicy.recoverySurface(
            classification = BrowserClassification.CONFIRMED_BROWSER,
            identificationStatus = WebsiteIdentificationStatus.NATIVE_BROWSER_UI,
            observedSurface = BrowserSurfaceInspector.Surface.NATIVE_UI
        )

        assertThat(surface).isEqualTo(BrowserSurfaceInspector.Surface.NATIVE_UI)
    }

    @Test
    fun `confirmed browser fails closed only after recovery remains unobservable`() {
        assertThat(
            BrowserRecognitionPolicy.shouldFailClosedAfterRecovery(
                classification = BrowserClassification.CONFIRMED_BROWSER,
                identificationStatus = WebsiteIdentificationStatus.UNOBSERVABLE,
                addressBarObservable = false,
                urlCandidatePresent = false
            )
        ).isTrue()
    }

    @Test
    fun `probable browser never gains automatic opaque fail closed ownership`() {
        assertThat(
            BrowserRecognitionPolicy.shouldFailClosedAfterRecovery(
                classification = BrowserClassification.PROBABLE_BROWSER,
                identificationStatus = WebsiteIdentificationStatus.UNOBSERVABLE,
                addressBarObservable = false,
                urlCandidatePresent = false
            )
        ).isFalse()
    }

    @Test
    fun `unknown package never gains new opaque fail closed evidence`() {
        assertThat(
            BrowserRecognitionPolicy.shouldFailClosedAfterRecovery(
                classification = BrowserClassification.UNKNOWN,
                identificationStatus = WebsiteIdentificationStatus.UNOBSERVABLE,
                addressBarObservable = false,
                urlCandidatePresent = false
            )
        ).isFalse()
    }

    @Test
    fun `non browser never fails closed`() {
        assertThat(
            BrowserRecognitionPolicy.shouldFailClosedAfterRecovery(
                classification = BrowserClassification.NOT_BROWSER,
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
                identificationStatus = WebsiteIdentificationStatus.UNOBSERVABLE,
                addressBarObservable = false,
                urlCandidatePresent = true
            )
        ).isFalse()
    }
}
