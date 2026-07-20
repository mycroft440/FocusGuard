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
        assertThat(WebsiteBlocker.extractDomain("example.com:8443/path"))
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
    fun `extractDomain handles international domains and unicode separators`() {
        assertThat(WebsiteBlocker.extractDomain("https://BÜCHER.de/path"))
            .isEqualTo("xn--bcher-kva.de")
        assertThat(WebsiteBlocker.extractDomain("example。com"))
            .isEqualTo("example.com")
        assertThat(WebsiteBlocker.extractDomain("https://example%2Ecom/path"))
            .isEqualTo("example.com")
    }

    @Test
    fun `extractDomain unwraps browser origin schemes`() {
        assertThat(WebsiteBlocker.extractDomain("view-source:https://www.example.com/page"))
            .isEqualTo("example.com")
        assertThat(WebsiteBlocker.extractDomain("blob:https://sub.example.com/id"))
            .isEqualTo("sub.example.com")
    }

    @Test
    fun `extractDomain honors the real host after user info`() {
        assertThat(WebsiteBlocker.extractDomain("https://example.com@evil.test/path"))
            .isEqualTo("evil.test")
        assertThat(WebsiteBlocker.extractDomain("https://example.com\\@evil.test/path"))
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
        assertThat(WebsiteBlocker.isValidUrl("999.999.999.999")).isFalse()
        assertThat(WebsiteBlocker.isValidUrl("javascript:example.com")).isFalse()
        assertThat(WebsiteBlocker.isValidUrl("mailto:user@example.com")).isFalse()
        assertThat(WebsiteBlocker.isValidUrl("user@example.com")).isFalse()
        assertThat(WebsiteBlocker.extractDomain("https:example.com/path"))
            .isEqualTo("example.com")
        assertThat(WebsiteBlocker.extractDomain("https:\\\\example.com/path"))
            .isEqualTo("example.com")
    }

    @Test
    fun `isValidUrl accepts literal IPv4 addresses`() {
        assertThat(WebsiteBlocker.isValidUrl("https://192.168.1.10:8443/path")).isTrue()
        assertThat(WebsiteBlocker.extractDomain("https://192.168.1.10:8443/path"))
            .isEqualTo("192.168.1.10")
        assertThat(WebsiteBlocker.extractDomain("https://010.0.0.1/path"))
            .isEqualTo("8.0.0.1")
        assertThat(WebsiteBlocker.extractDomain("https://0x7f.1/path"))
            .isEqualTo("127.0.0.1")
        assertThat(WebsiteBlocker.extractDomain("https://2130706433/path"))
            .isEqualTo("127.0.0.1")

        val compressedIpv6 = WebsiteBlocker.extractDomain("https://[2001:db8::1]/path")
        val expandedIpv6 = WebsiteBlocker.extractDomain(
            "https://[2001:0db8:0:0:0:0:0:1]/path"
        )
        assertThat(compressedIpv6).isNotEmpty()
        assertThat(expandedIpv6).isEqualTo(compressedIpv6)
        assertThat(WebsiteBlocker.extractDomain(compressedIpv6)).isEqualTo(compressedIpv6)
    }

    @Test
    fun `normalizeRule distinguishes domains from keywords`() {
        assertThat(WebsiteBlocker.normalizeRule("HTTPS://WWW.Example.COM/path"))
            .isEqualTo("example.com")
        assertThat(WebsiteBlocker.normalizeRule("Porn")).isEqualTo("keyword:porn")
        assertThat(WebsiteBlocker.normalizeRule("*XXX*")).isEqualTo("keyword:xxx")
        assertThat(WebsiteBlocker.displayRule("keyword:porn")).isEqualTo("*porn*")
    }

    @Test
    fun `keyword rules require one safe word with at least three characters`() {
        assertThat(WebsiteBlocker.isValidRule("xxx")).isTrue()
        assertThat(WebsiteBlocker.isValidRule("porn")).isTrue()
        assertThat(WebsiteBlocker.isValidRule("ab")).isFalse()
        assertThat(WebsiteBlocker.isValidRule("duas palavras")).isFalse()
        assertThat(WebsiteBlocker.isValidRule("po*rn")).isFalse()
    }

    @Test
    fun `keyword blocks any domain containing it`() {
        val blocked = listOf("porn", "xxx")

        assertThat(WebsiteBlocker.isUrlBlocked("https://examplepornsite.com", blocked)).isTrue()
        assertThat(WebsiteBlocker.isUrlBlocked("https://safe.xxx-content.org", blocked)).isTrue()
        assertThat(WebsiteBlocker.isUrlBlocked("https://PoRnHub.example", blocked)).isTrue()
        assertThat(WebsiteBlocker.isUrlBlocked("https://safe-example.com", blocked)).isFalse()
    }

    @Test
    fun `keyword matching inspects only the domain`() {
        assertThat(
            WebsiteBlocker.isUrlBlocked(
                "https://safe-example.com/path/porn?query=xxx",
                listOf("porn", "xxx")
            )
        ).isFalse()
    }

    @Test
    fun `domain rules win before keyword rules and longest keyword is deterministic`() {
        val rules = WebsiteBlocker.normalizeRules(
            listOf("exampleporn.com", "porn", "exampleporn")
        )

        assertThat(WebsiteBlocker.findMatchingRule("exampleporn.com", rules))
            .isEqualTo("exampleporn.com")
        assertThat(WebsiteBlocker.findMatchingRule("my-exampleporn-site.com", rules))
            .isEqualTo("keyword:exampleporn")
    }

    @Test
    fun `persisted keyword identifiers resolve to their configured rule`() {
        val rules = WebsiteBlocker.normalizeRules(listOf("porn"))

        assertThat(WebsiteBlocker.findMatchingRule("keyword:porn", rules))
            .isEqualTo("keyword:porn")
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
        assertThat(
            WebsiteBlocker.isUrlBlocked(
                "https://www.youtube-nocookie.com/embed/abc123",
                listOf("youtube.com")
            )
        ).isTrue()
    }

    @Test
    fun `twitter aliases are blocked`() {
        val blocked = listOf("twitter.com")
        assertThat(WebsiteBlocker.isUrlBlocked("https://x.com/user", blocked)).isTrue()
        assertThat(WebsiteBlocker.isUrlBlocked("https://t.co/abc", blocked)).isTrue()
    }

    @Test
    fun `findMatchingRule returns most specific parent rule`() {
        val rules = WebsiteBlocker.normalizeDomains(
            listOf("example.com", "news.example.com")
        )

        assertThat(
            WebsiteBlocker.findMatchingRule("https://a.news.example.com/story", rules)
        ).isEqualTo("news.example.com")
        assertThat(
            WebsiteBlocker.findMatchingRule("https://shop.example.com", rules)
        ).isEqualTo("example.com")
    }

    @Test
    fun `findMatchingRules returns every overlapping rule in precedence order`() {
        val rules = WebsiteBlocker.normalizeRules(
            listOf("example.com", "news.example.com", "keyword:news")
        )

        assertThat(
            WebsiteBlocker.findMatchingRules("https://a.news.example.com/story", rules)
        ).containsExactly(
            "news.example.com",
            "example.com",
            "keyword:news"
        ).inOrder()
    }

    @Test
    fun `equivalent IPv6 spellings match the same rule`() {
        val rules = WebsiteBlocker.normalizeDomains(listOf("[2001:db8::1]"))

        assertThat(
            WebsiteBlocker.findMatchingRule(
                "https://[2001:0db8:0:0:0:0:0:1]/path",
                rules
            )
        ).isEqualTo(rules.single())
    }

    @Test
    fun `findMatchingRule associates aliases with configured canonical domain`() {
        val rules = WebsiteBlocker.normalizeDomains(listOf("youtube.com"))

        assertThat(WebsiteBlocker.findMatchingRule("m.youtube.com", rules))
            .isEqualTo("youtube.com")
        assertThat(WebsiteBlocker.findMatchingRule("youtu.be/abc", rules))
            .isEqualTo("youtube.com")
    }

    @Test
    fun `expandDomainAliases keeps rules first and adds known bypass domains`() {
        val expanded = WebsiteBlocker.expandDomainAliases(
            listOf("youtube.com", "example.com", "twitter.com")
        )

        assertThat(expanded).containsExactly(
            "youtube.com",
            "example.com",
            "twitter.com",
            "youtu.be",
            "youtube-nocookie.com",
            "x.com",
            "t.co"
        ).inOrder()
    }

    @Test
    fun `managed domain aliases and app mappings ignore keyword rules`() {
        assertThat(WebsiteBlocker.expandDomainAliases(listOf("porn", "youtube.com")))
            .containsExactly("youtube.com", "youtu.be", "youtube-nocookie.com")
            .inOrder()
        assertThat(WebsiteBlocker.appPackageDomainsFor(listOf("youtube"))).isEmpty()
    }

    @Test
    fun `youtube website rule also covers the native app bypass`() {
        assertThat(WebsiteBlocker.appPackageDomainsFor(listOf("youtube.com")))
            .containsEntry("com.google.android.youtube", "youtube.com")
        assertThat(WebsiteBlocker.appPackageDomainsFor(listOf("youtu.be")))
            .containsEntry("com.google.android.youtube", "youtube.com")
        assertThat(WebsiteBlocker.appPackageDomainsFor(listOf("example.com"))).isEmpty()
    }

    @Test
    fun `extractUrlCandidate reads compound accessibility descriptions`() {
        assertThat(
            WebsiteBlocker.extractUrlCandidate(
                "Address and search bar, https://www.Example.com/path?q=1"
            )
        ).isEqualTo("https://www.Example.com/path?q=1")
        assertThat(WebsiteBlocker.extractUrlCandidate("Search or type web address")).isNull()
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
