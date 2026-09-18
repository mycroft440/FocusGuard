package com.focusguard.accessibility.website.identification

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BrowserSiteOnlyBlockingPolicyTest {
    @Test
    fun `opaque web evidence survives URL recovery failure`() {
        val opaqueWebSurface = WebsiteIdentificationResult(
            status = WebsiteIdentificationStatus.UNOBSERVABLE,
            webContentObserved = true
        )

        val adjusted = BrowserSiteOnlyBlockingPolicy.applyAfterRecovery(opaqueWebSurface)

        assertThat(adjusted.status).isEqualTo(WebsiteIdentificationStatus.UNOBSERVABLE)
        assertThat(adjusted.webContentObserved).isTrue()
        assertThat(adjusted.bestCandidate).isNull()
    }

    @Test
    fun `positive blocked candidate evidence is never downgraded`() {
        val identified = WebsiteIdentificationResult(
            status = WebsiteIdentificationStatus.IDENTIFIED,
            rawAddressText = "https://facebook.com/feed",
            urlCandidate = "https://facebook.com/feed",
            webContentObserved = true
        )

        val adjusted = BrowserSiteOnlyBlockingPolicy.applyAfterRecovery(identified)

        assertThat(adjusted).isEqualTo(identified)
        assertThat(adjusted.bestCandidate).isEqualTo("https://facebook.com/feed")
        assertThat(adjusted.webContentObserved).isTrue()
    }

    @Test
    fun `address bar without valid URL becomes fail closed after recovery`() {
        val observable = WebsiteIdentificationResult(
            status = WebsiteIdentificationStatus.ADDRESS_BAR_OBSERVABLE,
            rawAddressText = "Search or type URL",
            webContentObserved = true
        )

        val adjusted = BrowserSiteOnlyBlockingPolicy.applyAfterRecovery(observable)

        assertThat(adjusted.status).isEqualTo(WebsiteIdentificationStatus.UNOBSERVABLE)
        assertThat(adjusted.addressBarObservable).isFalse()
        assertThat(adjusted.webContentObserved).isTrue()
        assertThat(adjusted.evidence).contains(WebsiteIdentificationLayer.FAIL_CLOSED)
    }

    @Test
    fun `native browser UI is never rewritten as opaque web content`() {
        val native = WebsiteIdentificationResult(
            status = WebsiteIdentificationStatus.NATIVE_BROWSER_UI,
            webContentObserved = false
        )

        assertThat(BrowserSiteOnlyBlockingPolicy.applyAfterRecovery(native))
            .isEqualTo(native)
    }

    @Test
    fun `rejected context remains rejected`() {
        val rejected = WebsiteIdentificationResult(
            status = WebsiteIdentificationStatus.REJECTED_CONTEXT,
            webContentObserved = true
        )

        assertThat(BrowserSiteOnlyBlockingPolicy.applyAfterRecovery(rejected))
            .isEqualTo(rejected)
    }
}
