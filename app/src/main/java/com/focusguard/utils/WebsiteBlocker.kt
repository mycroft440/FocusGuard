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
    private fun isValidUrl(text: String): Boolean {
        return try {
            text.startsWith("http://") || text.startsWith("https://") || text.contains(".")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Extracts domain from URL
     */
    fun extractDomain(url: String): String {
        return try {
            val urlObj = URL(url)
            urlObj.host ?: url
        } catch (e: Exception) {
            // If URL parsing fails, try simple string extraction
            val domain = url.removePrefix("http://").removePrefix("https://").split("/")[0]
            domain.removePrefix("www.")
        }
    }

    /**
     * Checks if a URL matches any blocked domain
     */
    fun isUrlBlocked(url: String, blockedDomains: List<String>): Boolean {
        val domain = extractDomain(url)
        return blockedDomains.any { blockedDomain ->
            domain.contains(blockedDomain) || blockedDomain.contains(domain)
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
            "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "com.opera.browser:id/url_field"
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

        return null
    }

    /**
     * Finds an EditText node that contains URL-like content
     */
    private fun findEditTextWithUrl(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null

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

        return null
    }
}
