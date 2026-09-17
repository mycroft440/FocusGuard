package com.focusguard.accessibility.website.compatibility

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BrowserDetectorPolicyTest {
    @Test
    fun `browser role confirms browser`() {
        val result = BrowserClassificationPolicy.classify(
            BrowserCapabilityEvidence(browserRole = BrowserProbeResult.HANDLED)
        )

        assertThat(result).isEqualTo(BrowserClassification.CONFIRMED_BROWSER)
    }

    @Test
    fun `generic http and https handling confirms browser`() {
        val result = BrowserClassificationPolicy.classify(
            BrowserCapabilityEvidence(
                genericHttp = BrowserProbeResult.HANDLED,
                genericHttps = BrowserProbeResult.HANDLED
            )
        )

        assertThat(result).isEqualTo(BrowserClassification.CONFIRMED_BROWSER)
    }

    @Test
    fun `https only handler is not enough to classify as browser`() {
        val result = BrowserClassificationPolicy.classify(
            BrowserCapabilityEvidence(genericHttps = BrowserProbeResult.HANDLED)
        )

        assertThat(result).isEqualTo(BrowserClassification.UNKNOWN)
    }

    @Test
    fun `http only handler is not enough to classify as browser`() {
        val result = BrowserClassificationPolicy.classify(
            BrowserCapabilityEvidence(genericHttp = BrowserProbeResult.HANDLED)
        )

        assertThat(result).isEqualTo(BrowserClassification.UNKNOWN)
    }

    @Test
    fun `app without generic web handlers is not browser`() {
        val result = BrowserClassificationPolicy.classify(BrowserCapabilityEvidence())

        assertThat(result).isEqualTo(BrowserClassification.NOT_BROWSER)
    }

    @Test
    fun `query failure preserves unknown instead of guessing`() {
        val result = BrowserClassificationPolicy.classify(
            BrowserCapabilityEvidence(genericHttp = BrowserProbeResult.UNKNOWN)
        )

        assertThat(result).isEqualTo(BrowserClassification.UNKNOWN)
    }

    @Test
    fun `one handled scheme plus one failed probe remains unknown`() {
        val result = BrowserClassificationPolicy.classify(
            BrowserCapabilityEvidence(
                genericHttp = BrowserProbeResult.HANDLED,
                genericHttps = BrowserProbeResult.UNKNOWN
            )
        )

        assertThat(result).isEqualTo(BrowserClassification.UNKNOWN)
    }
}
