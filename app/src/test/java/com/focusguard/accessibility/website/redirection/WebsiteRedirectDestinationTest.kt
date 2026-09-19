package com.focusguard.accessibility.website.redirection

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebsiteRedirectDestinationTest {
    @Test
    fun `initial destination is Google root`() {
        assertThat(WebsiteRedirectDestination.current.url).isEqualTo("https://www.google.com")
        assertThat(WebsiteRedirectDestination.current.matchesSurface("https://www.google.com/"))
            .isTrue()
        assertThat(WebsiteRedirectDestination.current.matchesSurface("https://www.google.com.br/?hl=pt-BR"))
            .isTrue()
    }

    @Test
    fun `destination rejects search paths and lookalikes`() {
        assertThat(WebsiteRedirectDestination.current.matchesSurface("https://www.google.com/search?q=x"))
            .isFalse()
        assertThat(WebsiteRedirectDestination.current.matchesSurface("https://google.com.evil.example/"))
            .isFalse()
    }

    @Test
    fun `custom destination contract is independent from service`() {
        val destination = WebsiteRedirectDestination(
            url = "https://example.org",
            acceptedRootHosts = setOf("example.org")
        )
        assertThat(destination.matchesSurface("https://example.org/" )).isTrue()
        assertThat(destination.matchesSurface("https://www.google.com/" )).isFalse()
    }
}
