package com.focusguard.accessibility.website.redirection

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

class WebsiteRedirectDestinationTest {
    @After
    fun tearDown() {
        WebsiteRedirectDestination.resetToDefault()
    }

    @Test
    fun `initial destination is Google root`() {
        assertThat(WebsiteRedirectDestination.current.url).isEqualTo("https://google.com/")
        assertThat(WebsiteRedirectDestination.current.matchesSurface("https://google.com/"))
            .isTrue()
        assertThat(
            WebsiteRedirectDestination.current.matchesSurface(
                "https://www.google.com.br/?hl=pt-BR"
            )
        ).isTrue()
    }

    @Test
    fun `destination rejects search paths and lookalikes`() {
        assertThat(
            WebsiteRedirectDestination.current.matchesSurface(
                "https://www.google.com/search?q=x"
            )
        ).isFalse()
        assertThat(
            WebsiteRedirectDestination.current.matchesSurface(
                "https://google.com.evil.example/"
            )
        ).isFalse()
    }

    @Test
    fun `custom destination contract is independent from service`() {
        val destination = WebsiteRedirectDestination(
            url = "https://example.org/",
            acceptedRootHosts = setOf("example.org")
        )
        WebsiteRedirectDestination.install(destination)

        assertThat(WebsiteRedirectDestination.current.url).isEqualTo("https://example.org/")
        assertThat(WebsiteRedirectDestination.current.matchesSurface("https://example.org/"))
            .isTrue()
        assertThat(WebsiteRedirectDestination.current.matchesSurface("https://www.google.com/"))
            .isFalse()
    }

    @Test
    fun `http can be an explicitly configured web destination`() {
        val destination = WebsiteRedirectDestination(
            url = "http://example.org/",
            acceptedRootHosts = setOf("example.org"),
            acceptedSchemes = setOf("http")
        )

        assertThat(destination.matchesSurface("http://example.org/")).isTrue()
        assertThat(destination.matchesSurface("https://example.org/")).isFalse()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `configured host must belong to validation policy`() {
        WebsiteRedirectDestination(
            url = "https://example.org/",
            acceptedRootHosts = setOf("example.com")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `configured destination must target certified root surface`() {
        WebsiteRedirectDestination(
            url = "https://example.org/path",
            acceptedRootHosts = setOf("example.org")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `configured query parameters must be explicitly accepted`() {
        WebsiteRedirectDestination(
            url = "https://example.org/?source=focusguard",
            acceptedRootHosts = setOf("example.org")
        )
    }

    @Test
    fun `www alias must be explicitly accepted for custom destinations`() {
        val destination = WebsiteRedirectDestination(
            url = "https://www.example.org/",
            acceptedRootHosts = setOf("www.example.org")
        )
        assertThat(destination.matchesSurface("https://www.example.org/")).isTrue()
        assertThat(destination.matchesSurface("https://example.org/")).isFalse()
    }

    @Test
    fun `destination rejects wrong scheme and user info surfaces`() {
        assertThat(WebsiteRedirectDestination.current.matchesSurface("http://google.com/"))
            .isFalse()
        assertThat(WebsiteRedirectDestination.current.matchesSurface("https://user@google.com/"))
            .isFalse()
    }
}
