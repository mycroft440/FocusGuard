package com.focusguard.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

class WebsiteBlockerTest {

    @Before
    fun setUp() {
        WebsiteBlocker.clearCache()
    }

    @Test
    fun `extractDomain normalizes protocol www port path query and fragment`() {
        assertThat(
            WebsiteBlocker.extractDomain("HTTPS://WWW.Example.COM:8443/path?q=1#part")
        ).isEqualTo("example.com")
    }

    @Test
    fun `extractDomain normalizes bare domain`() {
        assertThat(WebsiteBlocker.extractDomain("example.com/path"))
            .isEqualTo("example.com")
    }

    @Test
    fun `extractDomain keeps subdomain`() {
        assertThat(WebsiteBlocker.extractDomain("https://news.example.com/article"))
            .isEqualTo("news.example.com")
    }

    @Test
    fun `extractDomain trims trailing dot`() {
        assertThat(WebsiteBlocker.extractDomain("https://example.com./"))
            .isEqualTo("example.com")
    }

    @Test
    fun `extractDomain handles uppercase scheme`() {
        assertThat(WebsiteBlocker.extractDomain("HTTPS://EXAMPLE.COM"))
            .isEqualTo("example.com")
    }

    @Test
    fun `extractDomain returns empty for empty input`() {
        assertThat(WebsiteBlocker.extractDomain("   ")).isEmpty()
    }

    @Test
    fun `isValidUrl accepts domains and full urls`() {
        assertThat(WebsiteBlocker.isValidUrl("example.com")).isTrue()
        assertThat(WebsiteBlocker.isValidUrl("https://sub.example.com/path")).isTrue()
    }

    @Test
    fun `isValidUrl rejects search phrases and malformed hosts`() {
        assertThat(WebsiteBlocker.isValidUrl("pesquisa no google")).isFalse()
        assertThat(WebsiteBlocker.isValidUrl("-example.com")).isFalse()
        assertThat(WebsiteBlocker.isValidUrl("example-.com")).isFalse()
        assertThat(WebsiteBlocker.isValidUrl("localhost")).isFalse()
    }

    @Test
    fun `exact domain is blocked`() {
        assertThat(WebsiteBlocker.isUrlBlocked("facebook.com", listOf("facebook.com")))
            .isTrue()
    }

    @Test
    fun `subdomains are blocked`() {
        val blocked = listOf("facebook.com")
        assertThat(WebsiteBlocker.isUrlBlocked("m.facebook.com", blocked)).isTrue()
        assertThat(WebsiteBlocker.isUrlBlocked("https://a.b.facebook.com/x", blocked)).isTrue()
    }

    @Test
    fun `lookalike domains are not blocked`() {
        val blocked = listOf("facebook.com")
        assertThat(WebsiteBlocker.isUrlBlocked("notfacebook.com", blocked)).isFalse()
        assertThat(WebsiteBlocker.isUrlBlocked("facebook.com.evil.test", blocked)).isFalse()
    }

    @Test
    fun `blocked entries are normalized before matching`() {
        assertThat(
            WebsiteBlocker.isUrlBlocked(
                "https://news.example.com",
                listOf(" HTTPS://WWW.EXAMPLE.COM/path ")
            )
        ).isTrue()
    }

    @Test
    fun `youtube short links are blocked by youtube domain`() {
        assertThat(
            WebsiteBlocker.isUrlBlocked("https://youtu.be/abc123", listOf("youtube.com"))
        ).isTrue()
    }

    @Test
    fun `twitter aliases are blocked`() {
        val blocked = listOf("twitter.com")
        assertThat(WebsiteBlocker.isUrlBlocked("https://x.com/user", blocked)).isTrue()
        assertThat(WebsiteBlocker.isUrlBlocked("https://t.co/abc", blocked)).isTrue()
    }

    @Test
    fun `matching is case insensitive`() {
        assertThat(
            WebsiteBlocker.isUrlBlocked("HTTPS://FACEBOOK.COM", listOf("FACEBOOK.COM"))
        ).isTrue()
    }

    @Test
    fun `empty blocklist never blocks`() {
        assertThat(WebsiteBlocker.isUrlBlocked("facebook.com", emptyList())).isFalse()
    }

    @Test
    fun `cache is isolated by blocklist contents`() {
        val url = "https://facebook.com/home"
        assertThat(WebsiteBlocker.isUrlBlocked(url, listOf("facebook.com"))).isTrue()
        assertThat(WebsiteBlocker.isUrlBlocked(url, emptyList())).isFalse()
        assertThat(WebsiteBlocker.isUrlBlocked(url, listOf("instagram.com"))).isFalse()
    }

    @Test
    fun `cache does not depend on blocklist order`() {
        val url = "https://instagram.com/explore"
        val first = listOf("facebook.com", "instagram.com")
        val second = listOf("instagram.com", "facebook.com")
        assertThat(WebsiteBlocker.isUrlBlocked(url, first)).isTrue()
        assertThat(WebsiteBlocker.isUrlBlocked(url, second)).isTrue()
    }

    @Test
    fun `clearCache preserves correct recomputation`() {
        val url = "https://facebook.com"
        assertThat(WebsiteBlocker.isUrlBlocked(url, listOf("facebook.com"))).isTrue()
        WebsiteBlocker.clearCache()
        assertThat(WebsiteBlocker.isUrlBlocked(url, listOf("instagram.com"))).isFalse()
    }
}
