package com.focusguard.accessibility.website.compatibility

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BrowserDetectorPolicyTest {
    @Test
    fun `generic http and https handling confirms browser`() {
        val result = BrowserClassificationPolicy.decide(
            BrowserCapabilityEvidence(
                genericHttp = BrowserProbeResult.HANDLED,
                genericHttps = BrowserProbeResult.HANDLED
            ),
            strongCurrentVersionHistory = false
        )

        assertThat(result.classification).isEqualTo(BrowserClassification.CONFIRMED_BROWSER)
        assertThat(result.reason).isEqualTo(BrowserDetectionReason.HTTP_HTTPS_CONFIRMED)
    }

    @Test
    fun `https only handler is not enough to classify as browser`() {
        val result = BrowserClassificationPolicy.decide(
            BrowserCapabilityEvidence(genericHttps = BrowserProbeResult.HANDLED),
            strongCurrentVersionHistory = true
        )

        assertThat(result.classification).isEqualTo(BrowserClassification.UNKNOWN)
        assertThat(result.reason).isEqualTo(BrowserDetectionReason.PARTIAL_GENERIC_HANDLER)
    }

    @Test
    fun `http only handler is not enough to classify as browser`() {
        val result = BrowserClassificationPolicy.decide(
            BrowserCapabilityEvidence(genericHttp = BrowserProbeResult.HANDLED),
            strongCurrentVersionHistory = true
        )

        assertThat(result.classification).isEqualTo(BrowserClassification.UNKNOWN)
        assertThat(result.reason).isEqualTo(BrowserDetectionReason.PARTIAL_GENERIC_HANDLER)
    }

    @Test
    fun `app without generic web handlers is not browser`() {
        val result = BrowserClassificationPolicy.decide(
            BrowserCapabilityEvidence(),
            strongCurrentVersionHistory = true,
            knownBrowserProfile = true
        )

        assertThat(result.classification).isEqualTo(BrowserClassification.NOT_BROWSER)
        assertThat(result.reason).isEqualTo(BrowserDetectionReason.NO_GENERIC_HANDLER)
    }

    @Test
    fun `query failure preserves unknown without strong evidence`() {
        val result = BrowserClassificationPolicy.decide(
            BrowserCapabilityEvidence(genericHttp = BrowserProbeResult.UNKNOWN),
            strongCurrentVersionHistory = false,
            knownBrowserProfile = false
        )

        assertThat(result.classification).isEqualTo(BrowserClassification.UNKNOWN)
        assertThat(result.reason).isEqualTo(BrowserDetectionReason.PACKAGE_QUERY_UNKNOWN)
    }

    @Test
    fun `query failure may use strong evidence from exact installed version`() {
        val result = BrowserClassificationPolicy.decide(
            BrowserCapabilityEvidence(
                genericHttp = BrowserProbeResult.HANDLED,
                genericHttps = BrowserProbeResult.UNKNOWN
            ),
            strongCurrentVersionHistory = true
        )

        assertThat(result.classification).isEqualTo(BrowserClassification.PROBABLE_BROWSER)
        assertThat(result.reason).isEqualTo(BrowserDetectionReason.CURRENT_VERSION_HISTORY)
    }

    @Test
    fun `query failure may use exact shipped browser profile`() {
        val result = BrowserClassificationPolicy.decide(
            BrowserCapabilityEvidence(
                genericHttp = BrowserProbeResult.HANDLED,
                genericHttps = BrowserProbeResult.UNKNOWN
            ),
            strongCurrentVersionHistory = false,
            knownBrowserProfile = true
        )

        assertThat(result.classification).isEqualTo(BrowserClassification.PROBABLE_BROWSER)
        assertThat(result.reason).isEqualTo(BrowserDetectionReason.KNOWN_BROWSER_PROFILE)
    }

    @Test
    fun `known browser profile never overrides deterministic no handler result`() {
        val result = BrowserClassificationPolicy.decide(
            BrowserCapabilityEvidence(
                genericHttp = BrowserProbeResult.NOT_HANDLED,
                genericHttps = BrowserProbeResult.NOT_HANDLED
            ),
            strongCurrentVersionHistory = false,
            knownBrowserProfile = true
        )

        assertThat(result.classification).isEqualTo(BrowserClassification.NOT_BROWSER)
        assertThat(result.reason).isEqualTo(BrowserDetectionReason.NO_GENERIC_HANDLER)
    }

    @Test
    fun `partial deterministic handler never becomes probable from history alone`() {
        val result = BrowserClassificationPolicy.decide(
            BrowserCapabilityEvidence(
                genericHttp = BrowserProbeResult.HANDLED,
                genericHttps = BrowserProbeResult.NOT_HANDLED
            ),
            strongCurrentVersionHistory = true,
            knownBrowserProfile = true
        )

        assertThat(result.classification).isEqualTo(BrowserClassification.UNKNOWN)
        assertThat(result.reason).isEqualTo(BrowserDetectionReason.PARTIAL_GENERIC_HANDLER)
    }

    @Test
    fun `unknown retry backoff grows quickly and stays bounded`() {
        assertThat(BrowserUnknownRetryPolicy.retryDelayMillis(1)).isEqualTo(250L)
        assertThat(BrowserUnknownRetryPolicy.retryDelayMillis(2)).isEqualTo(500L)
        assertThat(BrowserUnknownRetryPolicy.retryDelayMillis(3)).isEqualTo(1_000L)
        assertThat(BrowserUnknownRetryPolicy.retryDelayMillis(4)).isEqualTo(2_000L)
        assertThat(BrowserUnknownRetryPolicy.retryDelayMillis(5)).isEqualTo(4_000L)
        assertThat(BrowserUnknownRetryPolicy.retryDelayMillis(20)).isEqualTo(4_000L)
    }
}
