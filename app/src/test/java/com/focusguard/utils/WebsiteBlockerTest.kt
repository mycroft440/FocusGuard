package com.focusguard.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Testes unitários para [WebsiteBlocker] — foco em `extractDomain` e
 * `isUrlBlocked` (funções puras que não dependem de Android Context).
 *
 * Estas funções são críticas para o bloqueio correto de sites — um falso
 * negativo permite que o usuário acesse conteúdo bloqueado; um falso positivo
 * bloqueia sites legítimos. Antes da Fase 3, este código não tinha testes.
 */
class WebsiteBlockerTest {

    // ─────────────────────────────────────────────────────────────────────
    // extractDomain
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `extractDomain strips https protocol`() {
        assertThat(WebsiteBlocker.extractDomain("https://www.example.com/path?query=1"))
            .isEqualTo("example.com")
    }

    @Test
    fun `extractDomain strips http protocol`() {
        assertThat(WebsiteBlocker.extractDomain("http://www.example.com/path"))
            .isEqualTo("example.com")
    }

    @Test
    fun `extractDomain strips www prefix`() {
        assertThat(WebsiteBlocker.extractDomain("www.example.com"))
            .isEqualTo("example.com")
    }

    @Test
    fun `extractDomain lowercases domain`() {
        assertThat(WebsiteBlocker.extractDomain("HTTPS://WWW.EXAMPLE.COM"))
            .isEqualTo("example.com")
    }

    @Test
    fun `extractDomain strips path and query`() {
        assertThat(WebsiteBlocker.extractDomain("https://example.com/path/to/page?foo=bar"))
            .isEqualTo("example.com")
    }

    @Test
    fun `extractDomain strips port number`() {
        assertThat(WebsiteBlocker.extractDomain("https://example.com:8080/path"))
            .isEqualTo("example.com")
    }

    @Test
    fun `extractDomain handles bare domain (no protocol)`() {
        assertThat(WebsiteBlocker.extractDomain("example.com/path"))
            .isEqualTo("example.com")
    }

    @Test
    fun `extractDomain handles subdomain`() {
        assertThat(WebsiteBlocker.extractDomain("https://blog.example.com/post"))
            .isEqualTo("blog.example.com")
    }

    @Test
    fun `extractDomain handles youtube short URL youtu be`() {
        val domain = WebsiteBlocker.extractDomain("https://youtu.be/abc123")
        assertThat(domain).isEqualTo("youtu.be")
    }

    @Test
    fun `extractDomain returns lowercase for malformed input`() {
        // Não lança exceção; retorna algo em lowercase
        val result = WebsiteBlocker.extractDomain("NOT_A_URL_AT_ALL")
        assertThat(result).isEqualTo("not_a_url_at_all")
    }

    @Test
    fun `extractDomain handles empty input`() {
        val result = WebsiteBlocker.extractDomain("")
        assertThat(result).isEmpty()
    }

    // ─────────────────────────────────────────────────────────────────────
    // isUrlBlocked
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `isUrlBlocked returns true for exact match`() {
        val blocked = listOf("facebook.com")
        assertThat(WebsiteBlocker.isUrlBlocked("facebook.com", blocked)).isTrue()
    }

    @Test
    fun `isUrlBlocked returns true for subdomain of blocked domain`() {
        val blocked = listOf("facebook.com")
        assertThat(WebsiteBlocker.isUrlBlocked("m.facebook.com", blocked)).isTrue()
        assertThat(WebsiteBlocker.isUrlBlocked("www.facebook.com", blocked)).isTrue()
        assertThat(WebsiteBlocker.isUrlBlocked("blog.facebook.com", blocked)).isTrue()
    }

    @Test
    fun `isUrlBlocked returns true for full URL with blocked domain`() {
        val blocked = listOf("instagram.com")
        assertThat(WebsiteBlocker.isUrlBlocked("https://www.instagram.com/explore", blocked)).isTrue()
    }

    @Test
    fun `isUrlBlocked returns false for unrelated domain`() {
        val blocked = listOf("facebook.com")
        assertThat(WebsiteBlocker.isUrlBlocked("google.com", blocked)).isFalse()
    }

    @Test
    fun `isUrlBlocked returns false for domain that ends with blocked but is not subdomain`() {
        // Verifica anti-falso-positivo: "notfacebook.com" não deve ser bloqueado
        // por "facebook.com"
        val blocked = listOf("facebook.com")
        assertThat(WebsiteBlocker.isUrlBlocked("notfacebook.com", blocked)).isFalse()
        assertThat(WebsiteBlocker.isUrlBlocked("antifacebook.com", blocked)).isFalse()
    }

    @Test
    fun `isUrlBlocked returns false for empty blocked list`() {
        assertThat(WebsiteBlocker.isUrlBlocked("facebook.com", emptyList())).isFalse()
    }

    @Test
    fun `isUrlBlocked matches against multiple blocked domains`() {
        val blocked = listOf("facebook.com", "instagram.com", "twitter.com")
        assertThat(WebsiteBlocker.isUrlBlocked("https://twitter.com/home", blocked)).isTrue()
        assertThat(WebsiteBlocker.isUrlBlocked("https://tiktok.com", blocked)).isFalse()
    }

    @Test
    fun `isUrlBlocked is case insensitive for blocked domains`() {
        val blocked = listOf("FACEBOOK.COM")
        assertThat(WebsiteBlocker.isUrlBlocked("facebook.com", blocked)).isTrue()
    }

    @Test
    fun `isUrlBlocked handles youtube to youtu be special case`() {
        // YouTube short URL deve ser bloqueado quando youtube.com está bloqueado
        val blocked = listOf("youtube.com")
        assertThat(WebsiteBlocker.isUrlBlocked("https://youtu.be/abc123", blocked)).isTrue()
    }

    @Test
    fun `isUrlBlocked returns false for too-short domain`() {
        // Domínio < 4 chars não deve ser bloqueado (evita falsos positivos)
        val blocked = listOf("facebook.com")
        assertThat(WebsiteBlocker.isUrlBlocked("ab", blocked)).isFalse()
        assertThat(WebsiteBlocker.isUrlBlocked("abc", blocked)).isFalse()
    }

    @Test
    fun `isUrlBlocked handles multiple subdomain levels`() {
        val blocked = listOf("example.com")
        assertThat(WebsiteBlocker.isUrlBlocked("a.b.c.example.com", blocked)).isTrue()
    }
}
