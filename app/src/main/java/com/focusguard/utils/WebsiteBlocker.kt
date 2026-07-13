package com.focusguard.utils

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.net.IDN
import java.net.URI
import java.util.Collections

/**
 * Utilitários para localizar a barra de endereço e comparar URLs com a lista de
 * domínios bloqueados.
 *
 * O cache inclui a assinatura da blocklist. Dessa forma, uma URL não mantém um
 * resultado antigo quando o usuário altera os sites bloqueados.
 */
object WebsiteBlocker {

    private const val TAG = "WebsiteBlocker"
    private const val MAX_CACHE_SIZE = 256
    private const val MAX_DEPTH = 6

    private data class CacheKey(
        val normalizedUrl: String,
        val blocklistSignature: Int
    )

    private val urlBlockedCache: MutableMap<CacheKey, Boolean> =
        Collections.synchronizedMap(
            object : LinkedHashMap<CacheKey, Boolean>(64, 0.75f, true) {
                override fun removeEldestEntry(eldest: Map.Entry<CacheKey, Boolean>): Boolean {
                    return size > MAX_CACHE_SIZE
                }
            }
        )

    fun clearCache() {
        synchronized(urlBlockedCache) { urlBlockedCache.clear() }
    }

    private val addressBarIds: Set<String> = setOf(
        "com.android.chrome:id/line_1",
        "com.android.chrome:id/url_bar",
        "com.android.chrome:id/search_box_text",
        "com.android.chrome:id/url_bar_edit_text",
        "com.android.chrome:id/location_bar_edit_text",
        "com.android.chrome:id/url_text",
        "com.microsoft.emmx:id/url_bar",
        "com.microsoft.emmx:id/search_box_text",
        "com.brave.browser:id/url_bar",
        "com.brave.browser_secure:id/url_bar",
        "com.sec.android.app.sbrowser:id/url_bar",
        "com.sec.android.app.sbrowser:id/location_bar_edit_text",
        "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
        "org.mozilla.firefox:id/url_bar_title",
        "org.mozilla.firefox.beta:id/mozac_browser_toolbar_url_view",
        "org.mozilla.fennec_aurora:id/mozac_browser_toolbar_url_view",
        "org.mozilla.focus:id/mozac_browser_toolbar_url_view",
        "org.mozilla.klar:id/mozac_browser_toolbar_url_view",
        "com.opera.browser:id/url_field",
        "com.opera.mini.native:id/url_field",
        "com.opera.gx:id/url_field",
        "com.vivaldi.browser:id/url_bar",
        "com.kiwibrowser.browser:id/url_bar",
        "com.kiwibrowser.browser.dev:id/url_bar",
        "com.duckduckgo.mobile.android:id/omnibarTextInput",
        "com.duckduckgo.mobile.android:id/url_edit_text",
        "com.UCMobile.intl:id/url_bar",
        "com.yandex.browser:id/url_bar",
        "com.ecosia.android:id/url_bar"
    )

    private val commonDescriptions = setOf(
        "address and search bar",
        "endereço e barra de pesquisa",
        "search or type web address",
        "pesquisar ou digitar endereço web",
        "url bar",
        "barra de url",
        "address bar",
        "barra de endereços"
    )

    fun isValidUrl(text: String): Boolean {
        val domain = extractDomain(text)
        if (domain.length < 4 || !domain.contains('.')) return false
        if (domain.any { it.isWhitespace() }) return false
        return domain.split('.').all { label ->
            label.isNotBlank() && label.length <= 63 &&
                label.first() != '-' && label.last() != '-' &&
                label.all { it.isLetterOrDigit() || it == '-' }
        }
    }

    /**
     * Normaliza URL ou domínio para um host ASCII em minúsculas, sem `www.`,
     * porta, ponto final, caminho, query ou fragmento.
     */
    fun extractDomain(url: String): String {
        val raw = url.trim()
        if (raw.isEmpty()) return ""

        val candidate = if (SCHEME_REGEX.containsMatchIn(raw)) raw else "https://$raw"
        val host = runCatching { URI(candidate).host }
            .getOrNull()
            ?: fallbackHost(raw)

        return normalizeHost(host)
    }

    fun isUrlBlocked(url: String, blockedDomains: Collection<String>): Boolean {
        val normalizedUrl = url.trim().lowercase()
        if (normalizedUrl.length < 4) return false

        val normalizedBlocklist = blockedDomains.asSequence()
            .map(::extractDomain)
            .filter { it.length >= 4 }
            .distinct()
            .sorted()
            .toList()

        if (normalizedBlocklist.isEmpty()) return false

        val cacheKey = CacheKey(normalizedUrl, normalizedBlocklist.hashCode())
        synchronized(urlBlockedCache) {
            urlBlockedCache[cacheKey]?.let { return it }
        }

        val domain = extractDomain(normalizedUrl)
        val result = domain.length >= 4 && normalizedBlocklist.any { blocked ->
            matchesDomain(domain, blocked)
        }

        synchronized(urlBlockedCache) { urlBlockedCache[cacheKey] = result }
        return result
    }

    private fun matchesDomain(domain: String, blocked: String): Boolean {
        if (domain == blocked || domain.endsWith(".$blocked")) return true

        return when (blocked) {
            "youtube.com" -> domain == "youtu.be" || domain.endsWith(".youtu.be")
            "twitter.com" -> domain == "x.com" || domain.endsWith(".x.com") ||
                domain == "t.co" || domain.endsWith(".t.co")
            else -> false
        }
    }

    /**
     * Caminho rápido: aceita texto somente quando o evento veio de uma barra de
     * endereço conhecida ou de um campo editável. Textos comuns da página não
     * são tratados como URL, evitando falsos positivos em notícias e buscas.
     */
    fun extractUrlFromEvent(event: AccessibilityEvent): String? {
        val source = event.source ?: return null
        return try {
            if (!looksLikeAddressBar(source)) return null

            val text = source.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty() && isValidUrl(text)) return text

            event.text.orEmpty().forEach { value ->
                val candidate = value?.toString()?.trim().orEmpty()
                if (candidate.isNotEmpty() && isValidUrl(candidate)) return candidate
            }

            val description = source.contentDescription?.toString()?.trim().orEmpty()
            description.takeIf { it.isNotEmpty() && isValidUrl(it) }
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError(TAG, "Falha ao ler URL do evento", error)
            null
        } finally {
            recycleSafely(source)
        }
    }

    fun findAddressBarNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null

        for (id in addressBarIds) {
            val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(id) }
                .getOrNull()
                .orEmpty()
            if (nodes.isNotEmpty()) {
                nodes.drop(1).forEach(::recycleSafely)
                return nodes.first()
            }
        }

        findAddressBarByDescription(root)?.let { return it }

        val startedAt = System.currentTimeMillis()
        val result = findEditTextWithUrl(root, 0)
        val elapsed = System.currentTimeMillis() - startedAt
        if (elapsed > 50L) {
            FocusGuardLogger.log(TAG, "Busca da barra de URL demorou ${elapsed}ms")
        }
        return result
    }

    private fun looksLikeAddressBar(node: AccessibilityNodeInfo): Boolean {
        val viewId = node.viewIdResourceName
        if (viewId != null && viewId in addressBarIds) return true
        if (node.isEditable || node.className == "android.widget.EditText") return true
        val description = node.contentDescription?.toString()
            ?.trim()
            ?.lowercase()
            .orEmpty()
        return description in commonDescriptions
    }

    private fun findAddressBarByDescription(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (description in commonDescriptions) {
            val nodes = runCatching { root.findAccessibilityNodeInfosByText(description) }
                .getOrNull()
                .orEmpty()
            for (node in nodes) {
                if (looksLikeAddressBar(node)) {
                    nodes.filter { it !== node }.forEach(::recycleSafely)
                    return node
                }
                recycleSafely(node)
            }
        }
        return null
    }

    private fun findEditTextWithUrl(
        node: AccessibilityNodeInfo?,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (node == null || depth > MAX_DEPTH) return null

        return try {
            if (node.className == "android.widget.EditText") {
                val text = node.text?.toString().orEmpty()
                if (isValidUrl(text)) return node
            }

            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                val found = findEditTextWithUrl(child, depth + 1)
                if (found != null) {
                    if (found !== child) recycleSafely(child)
                    return found
                }
                recycleSafely(child)
            }
            null
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError(TAG, "Falha ao percorrer árvore de acessibilidade", error)
            null
        }
    }

    private fun fallbackHost(raw: String): String {
        return raw
            .replace(SCHEME_PREFIX_REGEX, "")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringBefore(':')
    }

    private fun normalizeHost(host: String): String {
        val lowered = host.trim().trimEnd('.').lowercase().removePrefix("www.")
        if (lowered.isEmpty()) return ""
        return runCatching { IDN.toASCII(lowered) }.getOrDefault(lowered)
    }

    private fun recycleSafely(node: AccessibilityNodeInfo) {
        runCatching { node.recycle() }
    }

    private val SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    private val SCHEME_PREFIX_REGEX = Regex(
        "^[a-zA-Z][a-zA-Z0-9+.-]*://",
        RegexOption.IGNORE_CASE
    )
}
