package com.focusguard.utils

import android.view.accessibility.AccessibilityNodeInfo
import java.net.URL

/**
 * Utility object for website blocking via Accessibility Service.
 * Provides URL extraction, domain matching, and browser address bar detection.
 */
object WebsiteBlocker {

    // Não estático customizado para evitar vulnerabilidade ReDoS. Usaremos Patterns nativo.

    /**
     * Checks if a string looks like a valid URL or domain.
     * More strict than the original: requires proper domain structure.
     */
    fun isValidUrl(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length < 4 || trimmed.contains(" ")) return false
        return android.util.Patterns.WEB_URL.matcher(trimmed).matches()
    }

    /**
     * Extracts domain from URL, removing protocol, www prefix, path, and query.
     */
    fun extractDomain(url: String): String {
        return try {
            val cleanUrl = url.trim()
            val urlObj = URL(if (cleanUrl.startsWith("http")) cleanUrl else "https://$cleanUrl")
            urlObj.host?.removePrefix("www.")?.lowercase() ?: cleanUrl.lowercase()
        } catch (_: Exception) {
            // Fallback: simple string extraction
            try {
                url.trim()
                    .removePrefix("http://")
                    .removePrefix("https://")
                    .removePrefix("www.")
                    .split("/")[0]
                    .split("?")[0]
                    .split(":")[0] // Remove port number
                    .lowercase()
            } catch (_: Exception) {
                url.trim().lowercase()
            }
        }
    }

    /**
     * Checks if a URL matches any blocked domain using proper suffix matching.
     * Avoids false positives like "com" matching "facebook.com".
     */
    fun isUrlBlocked(url: String, blockedDomains: List<String>): Boolean {
        val domain = extractDomain(url)
        if (domain.length < 4) return false

        return blockedDomains.any { blockedDomain ->
            val blocked = blockedDomain.lowercase()
            domain == blocked || domain.endsWith(".$blocked") || blocked.endsWith(".$domain")
        }
    }

    /**
     * Finds the address bar node in a browser.
     * Searches by known resource IDs first, then falls back to EditText heuristic.
     */
    fun findAddressBarNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null

        val addressBarIds = listOf(
            "com.android.chrome:id/url_bar",
            "com.android.chrome:id/search_box_text",
            "com.android.chrome:id/url_bar_edit_text",
            "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "org.mozilla.firefox:id/url_bar_title",
            "com.opera.browser:id/url_field",
            "com.microsoft.emmx:id/url_bar",
            "com.sec.android.app.sbrowser:id/url_bar",
            "com.brave.browser:id/url_bar",
            "com.kiwibrowser.browser:id/url_bar",
            "com.vivaldi.browser:id/url_bar",
            "com.duckduckgo.mobile.android:id/omnibarTextInput"
        )

        for (id in addressBarIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) {
                // Recycle all but the first match
                for (i in 1 until nodes.size) {
                    nodes[i].recycle()
                }
                return nodes[0]
            }
        }

        // Fallback: look for EditText with URL-like content
        return findEditTextWithUrl(root, 0)
    }

    /**
     * Finds an EditText node that contains URL-like content.
     * Limited to MAX_DEPTH to prevent StackOverflowError.
     */
    private const val MAX_DEPTH = 10

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
        } catch (_: Exception) {
            // Handle exceptions silently
        }

        return null
    }
}
