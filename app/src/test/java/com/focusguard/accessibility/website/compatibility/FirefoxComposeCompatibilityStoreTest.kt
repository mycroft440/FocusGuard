package com.focusguard.accessibility.website.compatibility

import com.focusguard.utils.BrowserUiCapabilityPolicy
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FirefoxComposeCompatibilityStoreTest {

    @Test
    fun `URL reads prefer stable Firefox display selector over editor selector`() {
        val ordered = BrowserCompatibilityStore.prioritizeUrlEntryNames(
            packageName = "org.mozilla.firefox",
            defaults = listOf(
                BrowserUiCapabilityPolicy.FIREFOX_COMPOSE_SEARCH_ENTRY,
                BrowserUiCapabilityPolicy.FIREFOX_COMPOSE_URL_ENTRY
            )
        )

        assertThat(ordered).containsExactly(
            BrowserUiCapabilityPolicy.FIREFOX_COMPOSE_URL_ENTRY,
            BrowserUiCapabilityPolicy.FIREFOX_COMPOSE_SEARCH_ENTRY
        ).inOrder()
    }

    @Test
    fun `legacy observation API uses the URL-specific ordering`() {
        val ordered = BrowserCompatibilityStore.prioritizeAddressBarEntryNames(
            packageName = "org.mozilla.firefox",
            defaults = listOf(
                BrowserUiCapabilityPolicy.FIREFOX_COMPOSE_SEARCH_ENTRY,
                BrowserUiCapabilityPolicy.FIREFOX_COMPOSE_URL_ENTRY
            )
        )

        assertThat(ordered.first())
            .isEqualTo(BrowserUiCapabilityPolicy.FIREFOX_COMPOSE_URL_ENTRY)
    }
}
