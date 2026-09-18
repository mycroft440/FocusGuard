package com.focusguard.accessibility.website.identification

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebsiteIdentificationStatusPolicyTest {
    @Test
    fun `address chrome text without URL is observable but not identified`() {
        assertThat(
            WebsiteIdentificationEngine.classifyStatus(
                urlCandidate = null,
                addressBarObservable = true
            )
        ).isEqualTo(WebsiteIdentificationStatus.ADDRESS_BAR_OBSERVABLE)
    }

    @Test
    fun `valid URL candidate is the only positive website identification`() {
        assertThat(
            WebsiteIdentificationEngine.classifyStatus(
                urlCandidate = "https://example.com/path",
                addressBarObservable = true
            )
        ).isEqualTo(WebsiteIdentificationStatus.IDENTIFIED)
    }

    @Test
    fun `native browser UI stays distinct from an unidentified web surface`() {
        assertThat(
            WebsiteIdentificationEngine.classifyStatus(
                urlCandidate = null,
                addressBarObservable = false,
                nativeBrowserUiObserved = true
            )
        ).isEqualTo(WebsiteIdentificationStatus.NATIVE_BROWSER_UI)
        assertThat(
            WebsiteIdentificationEngine.classifyStatus(
                urlCandidate = null,
                addressBarObservable = false,
                nativeBrowserUiObserved = false
            )
        ).isEqualTo(WebsiteIdentificationStatus.UNOBSERVABLE)
    }
}
