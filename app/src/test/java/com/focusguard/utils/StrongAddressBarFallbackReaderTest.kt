package com.focusguard.utils

import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrongAddressBarFallbackReaderTest {
    @Test
    fun nestedExactChromeAddressResourceIsReadBelowWebContainer() {
        val pkg = "com.android.chrome"
        val strong = node(
            packageName = pkg,
            windowId = 7,
            viewId = "$pkg:id/url_bar",
            text = "https://blocked.example/path",
            editable = true,
            focused = true
        )
        val webContainer = node(
            packageName = pkg,
            windowId = 7,
            className = "android.webkit.WebView",
            children = listOf(strong)
        )
        val root = node(packageName = pkg, windowId = 7, children = listOf(webContainer))

        val evidence = StrongAddressBarFallbackReader.read(
            root = root,
            browserPackageName = pkg,
            expectedWindowId = 7,
            isCurrent = { true }
        )

        assertEquals("https://blocked.example/path", evidence?.url)
        assertEquals("$pkg:id/url_bar", evidence?.viewIdResourceName)
        assertTrue(evidence?.focusedAddressEditor == true)
    }

    @Test
    fun arbitraryHtmlTextInsideWebContainerIsNeverAcceptedAsAddress() {
        val pkg = "com.android.chrome"
        val htmlField = node(
            packageName = pkg,
            windowId = 8,
            viewId = "$pkg:id/page_search_field",
            text = "https://blocked.example"
        )
        val webContainer = node(
            packageName = pkg,
            windowId = 8,
            className = "android.webkit.WebView",
            children = listOf(htmlField)
        )
        val root = node(packageName = pkg, windowId = 8, children = listOf(webContainer))

        val evidence = StrongAddressBarFallbackReader.read(
            root = root,
            browserPackageName = pkg,
            expectedWindowId = 8,
            isCurrent = { true }
        )

        assertNull(evidence)
    }

    @Test
    fun nonUrlTextOnOneStrongNodeDoesNotHideLaterStrongUrl() {
        val pkg = "com.android.chrome"
        val labelOnly = node(
            packageName = pkg,
            windowId = 9,
            viewId = "$pkg:id/location_bar",
            text = "Search or type URL"
        )
        val actualAddress = node(
            packageName = pkg,
            windowId = 9,
            viewId = "$pkg:id/url_bar",
            text = "https://blocked.example/page"
        )
        val root = node(
            packageName = pkg,
            windowId = 9,
            children = listOf(labelOnly, actualAddress)
        )

        val evidence = StrongAddressBarFallbackReader.read(
            root = root,
            browserPackageName = pkg,
            expectedWindowId = 9,
            isCurrent = { true }
        )

        assertEquals("https://blocked.example/page", evidence?.url)
        assertEquals("$pkg:id/url_bar", evidence?.viewIdResourceName)
    }

    private fun node(
        packageName: String,
        windowId: Int,
        viewId: String? = null,
        text: String? = null,
        className: String = "android.view.View",
        editable: Boolean = false,
        focused: Boolean = false,
        children: List<AccessibilityNodeInfo> = emptyList()
    ): AccessibilityNodeInfo = mockk(relaxed = true) {
        every { this@mockk.packageName } returns packageName
        every { this@mockk.windowId } returns windowId
        every { isVisibleToUser } returns true
        every { viewIdResourceName } returns viewId
        every { this@mockk.text } returns text
        every { contentDescription } returns null
        every { hintText } returns null
        every { this@mockk.className } returns className
        every { isEditable } returns editable
        every { isFocused } returns focused
        every { childCount } returns children.size
        children.forEachIndexed { index, child ->
            every { getChild(index) } returns child
        }
    }
}
