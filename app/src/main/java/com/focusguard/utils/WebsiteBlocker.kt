package com.focusguard.utils

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.focusguard.utils.FocusGuardLogger
import java.net.URL
import java.util.Collections

/**
 * Utility object for website blocking via Accessibility Service.
 *
 * HÍBRIDO O(1) — combina 3 otimizações para bloqueio de sites:
 *
 * 1. **Cache LRU de URLs verificadas** — evita re-calcular `extractDomain`
 *    + `endsWith` para a mesma URL (Chrome dispara muitos eventos para a
 *    mesma página). Tamanho fixo (256 entradas) com eviction automática.
 *
 * 2. **Fallback via event.text/contentDescription** — método rápido para
 *    extrair URL do evento ANTES de varrer a árvore de nodes (que é
 *    10-50x mais caro). Firefox Focus e DuckDuckGo frequentemente só
 *    expõem URL dessa forma.
 *
 * 3. **Lista estendida de browsers** — IDs de barra de endereço para
 *    Chrome, Edge, Firefox, Brave, Samsung Internet, Kiwi, Vivaldi,
 *    DuckDuckGo, Opera, UC, Yandex, Ecosia, Firefox Focus.
 */
object WebsiteBlocker {

    private const val TAG = "WebsiteBlocker"
    private const val MAX_CACHE_SIZE = 256
    private const val MAX_DEPTH = 6

    // ────────────────────────────────────────────────────────────────────
    // LRU Cache O(1) — URL -> isBlocked
    // Synchronized para thread-safety (AccessibilityService events são
    // delivered no main thread, mas pode haver concorrência com refreshData).
    // ────────────────────────────────────────────────────────────────────
    private val urlBlockedCache: MutableMap<String, Boolean> =
        Collections.synchronizedMap(object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Boolean>): Boolean {
                return size > MAX_CACHE_SIZE
            }
        })

    /**
     * Limpa o cache — chamar quando a blocklist muda (refreshData do A11y).
     */
    fun clearCache() {
        synchronized(urlBlockedCache) {
            urlBlockedCache.clear()
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Lista de IDs de barra de endereço por navegador.
    // Atualizada 2026 — inclui todos os browsers Chromium-based que herdam
    // o ID `url_bar` do upstream, + browsers com IDs próprios.
    // ────────────────────────────────────────────────────────────────────
    private val addressBarIds: List<String> = listOf(
        // Chrome (estável há muitos releases)
        "com.android.chrome:id/line_1",
        "com.android.chrome:id/url_bar",
        "com.android.chrome:id/search_box_text",
        "com.android.chrome:id/url_bar_edit_text",
        "com.android.chrome:id/location_bar_edit_text",
        "com.android.chrome:id/url_text",

        // Microsoft Edge (Chromium-based, mesmo ID que Chrome)
        "com.microsoft.emmx:id/url_bar",
        "com.microsoft.emmx:id/search_box_text",

        // Brave (Chromium-based, herda url_bar)
        "com.brave.browser:id/url_bar",
        "com.brave.browser_secure:id/url_bar",

        // Samsung Internet
        "com.sec.android.app.sbrowser:id/url_bar",
        "com.sec.android.app.sbrowser:id/location_bar_edit_text",

        // Mozilla Firefox
        "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
        "org.mozilla.firefox:id/url_bar_title",
        "org.mozilla.firefox.beta:id/mozac_browser_toolbar_url_view",
        "org.mozilla.fennec_aurora:id/mozac_browser_toolbar_url_view",

        // Firefox Focus / Klar
        "org.mozilla.focus:id/mozac_browser_toolbar_url_view",
        "org.mozilla.klar:id/mozac_browser_toolbar_url_view",

        // Opera / Opera Mini / Opera GX
        "com.opera.browser:id/url_field",
        "com.opera.mini.native:id/url_field",
        "com.opera.gx:id/url_field",

        // Brave Search, Vivaldi, Kiwi (Chromium-based)
        "com.vivaldi.browser:id/url_bar",
        "com.kiwibrowser.browser:id/url_bar",
        "com.kiwibrowser.browser.dev:id/url_bar",

        // DuckDuckGo
        "com.duckduckgo.mobile.android:id/omnibarTextInput",
        "com.duckduckgo.mobile.android:id/url_edit_text",

        // UC Browser
        "com.UCMobile.intl:id/url_bar",

        // Yandex Browser
        "com.yandex.browser:id/url_bar",

        // Ecosia (Chromium-based)
        "com.ecosia.android:id/url_bar"
    )

    private val commonDescriptions = listOf(
        "Address and search bar",
        "Endereço e barra de pesquisa",
        "Search or type web address",
        "Pesquisar ou digitar endereço Web",
        "URL bar",
        "Barra de URL",
        "Address bar",
        "Barra de endereços"
    )

    // ────────────────────────────────────────────────────────────────────
    // Public API
    // ────────────────────────────────────────────────────────────────────

    /**
     * Checks if a string looks like a valid URL or domain.
     */
    fun isValidUrl(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length < 4 || trimmed.contains(" ")) return false
        return android.util.Patterns.WEB_URL.matcher(trimmed).matches()
    }

    /**
     * Extracts domain from URL, removing protocol, www prefix, path, and query.
     * Result is always lowercase for case-insensitive matching.
     */
    fun extractDomain(url: String): String {
        return try {
            val cleanUrl = url.trim()
            // Case-insensitive check para "http" — antes era case-sensitive
            // e falhava com "HTTPS://..." (adicionava "https://" na frente).
            val withProtocol = if (cleanUrl.lowercase().startsWith("http")) {
                cleanUrl
            } else {
                "https://$cleanUrl"
            }
            val urlObj = URL(withProtocol)
            // lowercase ANTES do removePrefix para case-insensitive matching
            // — antes era removePrefix("www.") que não funcionava com "WWW."
            urlObj.host?.lowercase()?.removePrefix("www.") ?: cleanUrl.lowercase()
        } catch (_: Throwable) {
            // Fallback: simple string extraction
            try {
                url.trim()
                    .removePrefix("http://")
                    .removePrefix("https://")
                    .removePrefix("www.")
                    .split("/")[0]
                    .split("?")[0]
                    .split(":")[0]
                    .lowercase()
            } catch (_: Throwable) {
                url.trim().lowercase()
            }
        }
    }

    /**
     * Checks if a URL matches any blocked domain using proper suffix matching.
     *
     * O(1) via cache LRU — primeira verificação de uma URL calcula o domain
     * e percorre a blocklist O(n); verificações subsequentes da mesma URL
     * retornam do cache em ~0ms.
     *
     * @param url URL ou domínio a verificar
     * @param blockedDomains coleção de domínios bloqueados (lowercase)
     */
    fun isUrlBlocked(url: String, blockedDomains: Collection<String>): Boolean {
        val trimmedUrl = url.trim().lowercase()
        if (trimmedUrl.length < 4) return false

        // Cache lookup O(1)
        synchronized(urlBlockedCache) {
            urlBlockedCache[trimmedUrl]?.let { return it }
        }

        // Cache miss — computa e armazena
        val domain = extractDomain(trimmedUrl)
        if (domain.length < 4) {
            synchronized(urlBlockedCache) { urlBlockedCache[trimmedUrl] = false }
            return false
        }

        // Suffix matching O(n) na blocklist (normalmente < 50 domínios)
        val isBlocked = blockedDomains.any { blockedDomain ->
            val blocked = blockedDomain.lowercase()
            domain == blocked ||
            domain.endsWith(".$blocked") ||
            // Caso especial: youtube.com deve bloquear youtu.be
            (blocked == "youtube.com" && (domain == "youtu.be" || domain.endsWith(".youtu.be")))
        }

        synchronized(urlBlockedCache) { urlBlockedCache[trimmedUrl] = isBlocked }
        return isBlocked
    }

    // ────────────────────────────────────────────────────────────────────
    // FAST PATH: extrair URL do event (antes de varrer árvore)
    // ────────────────────────────────────────────────────────────────────

    /**
     * Tenta extrair a URL diretamente do [AccessibilityEvent], sem varrer
     * a árvore de nodes. Muito mais rápido (~0ms vs 5-50ms da árvore).
     *
     * Alguns browsers — especialmente Firefox Focus, DuckDuckGo, e browsers
     * com toolbar custom — disparam `TYPE_WINDOW_CONTENT_CHANGED` com a URL
     * em `event.text` ou `event.contentDescription` em vez de no nó `url_bar`.
     *
     * @return URL string se encontrada, null caso contrário.
     */
    fun extractUrlFromEvent(event: AccessibilityEvent): String? {
        // 1. event.text — lista de CharSequences
        val eventTexts = event.text
        if (eventTexts != null && eventTexts.isNotEmpty()) {
            for (text in eventTexts) {
                if (text == null) continue
                val s = text.toString().trim()
                if (s.length >= 4 && isValidUrl(s)) {
                    return s
                }
            }
        }

        // 2. event.contentDescription — alguns browsers usam em vez de text
        val desc = event.contentDescription
        if (desc != null) {
            val s = desc.toString().trim()
            if (s.length >= 4 && isValidUrl(s)) {
                return s
            }
        }

        return null
    }

    // ────────────────────────────────────────────────────────────────────
    // SLOW PATH: varrer árvore de nodes (fallback quando event não tem URL)
    // ────────────────────────────────────────────────────────────────────

    /**
     * Finds the address bar node in a browser by:
     * 1. Looking up known resource IDs (fast — direct map lookup)
     * 2. Looking up by content description heuristics
     * 3. Falling back to EditText with URL-like content
     */
    fun findAddressBarNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null

        // Pass 1: ID lookup — fast path O(k) onde k = número de IDs conhecidos
        for (id in addressBarIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) {
                for (i in 1 until nodes.size) {
                    nodes[i].recycle()
                }
                return nodes[0]
            }
        }

        // Pass 2: Description-based heuristic
        val descResult = findAddressBarByDescription(root)
        if (descResult != null) return descResult

        // Pass 3: EditText with URL content (slowest — full tree walk)
        val startTime = System.currentTimeMillis()
        val result = findEditTextWithUrl(root, 0)
        val duration = System.currentTimeMillis() - startTime

        if (duration > 50) {
            FocusGuardLogger.log(TAG, "findEditTextWithUrl demorou ${duration}ms")
        }

        return result
    }

    /**
     * Heuristic to find address bar based on common content descriptions.
     */
    private fun findAddressBarByDescription(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null

        for (desc in commonDescriptions) {
            val nodes = root.findAccessibilityNodeInfosByText(desc)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    if (node.isEditable || node.className == "android.widget.EditText") {
                        nodes.filter { it !== node }.forEach { it.recycle() }
                        return node
                    }
                    node.recycle()
                }
            }
        }
        return null
    }

    /**
     * Finds an EditText node that contains URL-like content.
     * Limited to MAX_DEPTH to prevent performance issues and StackOverflowError.
     */
    private fun findEditTextWithUrl(root: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (root == null || depth > MAX_DEPTH) return null

        try {
            if (root.className == "android.widget.EditText" && root.text != null) {
                val text = root.text.toString()
                if (isValidUrl(text)) {
                    return root
                }
            }

            for (i in 0 until root.childCount) {
                val child = root.getChild(i) ?: continue
                val found = findEditTextWithUrl(child, depth + 1)
                if (found != null) {
                    if (found !== child) {
                        child.recycle()
                    }
                    return found
                }
                child.recycle()
            }
        } catch (_: Throwable) {
            // Handle exceptions silently
        }

        return null
    }
}
