package com.focusguard.sitesblocker

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RedirectDestinationStoreTest {

    @Test
    fun `bare domain becomes an https root`() {
        assertThat(RedirectDestinationStore.normalize("Wikipedia.org"))
            .isEqualTo("https://wikipedia.org")
        assertThat(RedirectDestinationStore.normalize("  https://www.duckduckgo.com/  "))
            .isEqualTo("https://duckduckgo.com")
    }

    @Test
    fun `http is kept when typed`() {
        assertThat(RedirectDestinationStore.normalize("http://example.com"))
            .isEqualTo("http://example.com")
    }

    @Test
    fun `anything beyond the site root is rejected`() {
        listOf(
            "",
            "   ",
            "example.com/path",
            "example.com?q=1",
            "example.com#top",
            "https://user@example.com",
            "https://example.com:8443",
            "ftp://example.com",
            "javascript:alert(1)",
            "not a site"
        ).forEach { raw ->
            assertThat(RedirectDestinationStore.normalize(raw)).isNull()
        }
    }

    @Test
    fun `default destination is already normalized`() {
        assertThat(RedirectDestinationStore.normalize(RedirectDestinationStore.DEFAULT_URL))
            .isEqualTo(RedirectDestinationStore.DEFAULT_URL)
    }
}
