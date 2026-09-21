package com.focusguard.accessibility.website.compatibility

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BrowserDetectorPolicyTest {
    @Test
    fun `G B and browser category confirm browser without http`() {
        val result = BrowserClassificationPolicy.decide(
            BrowserCapabilityEvidence(
                genericHttps = BrowserProbeResult.HANDLED,
                broadHttpsFilter = BrowserProbeResult.HANDLED,
                browserCategory = BrowserProbeResult.HANDLED
            )
        )

        assertThat(result.classification).isEqualTo(BrowserClassification.CONFIRMED_BROWSER)
        assertThat(result.reason).isEqualTo(BrowserDetectionReason.STRUCTURAL_BROWSER_CONFIRMED)
    }

    @Test
    fun `G B H and custom tabs confirm browser without category`() {
        val result = BrowserClassificationPolicy.decide(
            BrowserCapabilityEvidence(
                genericHttps = BrowserProbeResult.HANDLED,
                broadHttpsFilter = BrowserProbeResult.HANDLED,
                genericHttp = BrowserProbeResult.HANDLED,
                customTabsService = BrowserProbeResult.HANDLED
            )
        )

        assertThat(result.classification).isEqualTo(BrowserClassification.CONFIRMED_BROWSER)
        assertThat(result.reason).isEqualTo(BrowserDetectionReason.STRUCTURAL_BROWSER_CONFIRMED)
    }

    @Test
    fun `G without broad filter remains probable and cannot enter generic route`() {
        val result = BrowserClassificationPolicy.decide(
            BrowserCapabilityEvidence(
                genericHttps = BrowserProbeResult.HANDLED,
                broadHttpsFilter = BrowserProbeResult.NOT_HANDLED,
                browserCategory = BrowserProbeResult.HANDLED,
                genericHttp = BrowserProbeResult.HANDLED,
                customTabsService = BrowserProbeResult.HANDLED
            ),
            strongCurrentVersionHistory = true
        )

        assertThat(result.classification).isEqualTo(BrowserClassification.PROBABLE_BROWSER)
        assertThat(result.classification.isBrowserLike).isFalse()
        assertThat(result.reason).isEqualTo(BrowserDetectionReason.STRUCTURAL_BROWSER_INCOMPLETE)
    }

    @Test
    fun `G and B without C or H T remains probable`() {
        val result = BrowserClassificationPolicy.decide(
            BrowserCapabilityEvidence(
                genericHttps = BrowserProbeResult.HANDLED,
                broadHttpsFilter = BrowserProbeResult.HANDLED
            )
        )

        assertThat(result.classification).isEqualTo(BrowserClassification.PROBABLE_BROWSER)
        assertThat(result.classification.isBrowserLike).isFalse()
    }

    @Test
    fun `central package query failure is inconclusive even with history`() {
        val result = BrowserClassificationPolicy.decide(
            BrowserCapabilityEvidence(
                genericHttps = BrowserProbeResult.UNKNOWN,
                broadHttpsFilter = BrowserProbeResult.UNKNOWN,
                genericHttp = BrowserProbeResult.HANDLED
            ),
            strongCurrentVersionHistory = true
        )

        assertThat(result.classification).isEqualTo(BrowserClassification.UNKNOWN)
        assertThat(result.reason).isEqualTo(BrowserDetectionReason.PACKAGE_QUERY_UNKNOWN)
    }

    @Test
    fun `no G with complete evidence is not browser`() {
        val result = BrowserClassificationPolicy.decide(BrowserCapabilityEvidence())

        assertThat(result.classification).isEqualTo(BrowserClassification.NOT_BROWSER)
        assertThat(result.reason).isEqualTo(BrowserDetectionReason.NO_GENERIC_HANDLER)
    }

    @Test
    fun `registered owner bypasses generic structural evidence`() {
        val result = BrowserClassificationPolicy.decide(
            evidence = BrowserCapabilityEvidence(),
            strongCurrentVersionHistory = false,
            knownBrowserProfile = true
        )

        assertThat(result.classification).isEqualTo(BrowserClassification.CONFIRMED_BROWSER)
        assertThat(result.reason).isEqualTo(BrowserDetectionReason.KNOWN_BROWSER_PROFILE)
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
