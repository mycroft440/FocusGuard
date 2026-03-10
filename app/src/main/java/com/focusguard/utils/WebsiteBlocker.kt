package com.focusguard.utils

import android.view.accessibility.AccessibilityNodeInfo
import java.net.URL

object WebsiteBlocker {

    /**
     * Extracts URLs from accessibility node tree
     */
    fun extractUrlsFromNode(node: AccessibilityNodeInfo?): List<String> {
        val urls = mutableListOf<String>()
        if (node == null) return urls

        // Check the node's text
        if (node.text != null) {
            val text = node.text.toString()
            if (isValidUrl(text)) {
                urls.add(text)
            }
        }

        // Check content description
        if (node.contentDescription != null) {
            val desc = node.contentDescription.toString()
            if (isValidUrl(desc)) {
                urls.add(desc)
            }
        }

        // Recursively check children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                urls.addAll(extractUrlsFromNode(child))
                child.recycle()
            }
        }

        return urls
    }

    /**
     * Checks if a string is a valid URL
     */
    fun isValidUrl(text: String): Boolean {
        return try {
            text.trim().let { trimmedText ->
                (trimmedText.startsWith("http://") || 
                 trimmedText.startsWith("https://") || 
                 trimmedText.startsWith("www.") ||
                 (trimmedText.contains(".") && !trimmedText.contains(" ")))
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Extracts domain from URL
     */
    fun extractDomain(url: String): String {
        return try {
            val cleanUrl = url.trim()
            val urlObj = URL(if (cleanUrl.startsWith("http")) cleanUrl else "https://$cleanUrl")
            urlObj.host?.removePrefix("www.") ?: cleanUrl
        } catch (e: Exception) {
            // If URL parsing fails, try simple string extraction
            try {
                val domain = url.trim()
                    .removePrefix("http://")
                    .removePrefix("https://")
                    .removePrefix("www.")
                    .split("/")[0]
                    .split("?")[0]
                domain
            } catch (e: Exception) {
                url.trim()
            }
        }
    }

    /**
     * Checks if a URL matches any blocked domain
     */
    fun isUrlBlocked(url: String, blockedDomains: List<String>): Boolean {
        val domain = extractDomain(url)
        return blockedDomains.any { blockedDomain ->
            domain.contains(blockedDomain, ignoreCase = true) || 
            blockedDomain.contains(domain, ignoreCase = true)
        }
    }

    /**
     * Finds the address bar node in a browser
     */
    fun findAddressBarNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null

        // Common resource IDs for address bars
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
            "com.kiwibrowser.browser:id/url_bar"
        )

        for (id in addressBarIds) {
            val node = findNodeById(root, id)
            if (node != null) return node
        }

        // Fallback: look for EditText with URL-like content
        return findEditTextWithUrl(root)
    }

    /**
     * Finds a node by resource ID
     */
    private fun findNodeById(root: AccessibilityNodeInfo?, id: String): AccessibilityNodeInfo? {
        if (root == null) return null

        try {
            if (root.viewIdResourceName == id) {
                return root
            }

            for (i in 0 until root.childCount) {
                val child = root.getChild(i)
                if (child != null) {
                    val found = findNodeById(child, id)
                    if (found != null) {
                        return found
                    }
                    child.recycle()
                }
            }
        } catch (e: Exception) {
            // Handle exceptions silently
        }

        return null
    }

    /**
     * Finds an EditText node that contains URL-like content
     */
    private fun findEditTextWithUrl(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null

        try {
            if (root.className == "android.widget.EditText" && root.text != null) {
                val text = root.text.toString()
                if (isValidUrl(text)) {
                    return root
                }
            }

            for (i in 0 until root.childCount) {
                val child = root.getChild(i)
                if (child != null) {
                    val found = findEditTextWithUrl(child)
                    if (found != null) {
                        return found
                    }
                    child.recycle()
                }
            }
        } catch (e: Exception) {
            // Handle exceptions silently
        }

        return null
    }
}
