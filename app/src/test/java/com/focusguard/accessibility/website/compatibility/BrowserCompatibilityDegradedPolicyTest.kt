package com.focusguard.accessibility.website.compatibility

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BrowserCompatibilityDegradedPolicyTest {
    @Test
    fun `two redirect failures degrade instead of permanently disabling browser`() {
        assertThat(
            BrowserCompatibilityStore.statusAfterRedirectionFailure(
                previousStatus = BrowserCompatibilityStatus.SUPPORTED,
                failures = 2
            )
        ).isEqualTo(BrowserCompatibilityStatus.DEGRADED)
    }

    @Test
    fun `continued redirect failures eventually mark browser unsupported`() {
        assertThat(
            BrowserCompatibilityStore.statusAfterRedirectionFailure(
                previousStatus = BrowserCompatibilityStatus.DEGRADED,
                failures = 5
            )
        ).isEqualTo(BrowserCompatibilityStatus.UNSUPPORTED)
    }

    @Test
    fun `single transient redirect failure preserves previous status`() {
        assertThat(
            BrowserCompatibilityStore.statusAfterRedirectionFailure(
                previousStatus = BrowserCompatibilityStatus.SUPPORTED,
                failures = 1
            )
        ).isEqualTo(BrowserCompatibilityStatus.SUPPORTED)
    }

    @Test
    fun `degraded exact version remains strong browser evidence`() {
        val record = BrowserCompatibilityRecord(
            packageName = "example.browser",
            status = BrowserCompatibilityStatus.DEGRADED,
            packageVersionCode = 42L
        )

        assertThat(
            BrowserCompatibilityStore.hasStrongBrowserEvidenceForVersion(record, 42L)
        ).isTrue()
    }

    @Test
    fun `firefox discovery order starts with gecko entries`() {
        val entries = BrowserCompatibilityStore.prioritizeUrlEntryNames(
            packageName = "org.mozilla.firefox",
            defaults = listOf("url_bar", "mozac_browser_toolbar_url_view", "custom_url")
        )

        assertThat(entries.first()).isEqualTo("mozac_browser_toolbar_url_view")
        assertThat(entries).contains("url_bar")
        assertThat(entries).contains("custom_url")
    }

    @Test
    fun `brave discovery order starts with chromium entries`() {
        val entries = BrowserCompatibilityStore.prioritizeUrlEntryNames(
            packageName = "com.brave.browser",
            defaults = listOf("toolbar_url", "url_bar", "custom_url")
        )

        assertThat(entries.first()).isEqualTo("url_bar")
        assertThat(entries).contains("toolbar_url")
        assertThat(entries).contains("custom_url")
    }
}
