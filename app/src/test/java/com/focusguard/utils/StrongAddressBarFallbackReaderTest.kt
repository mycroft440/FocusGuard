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
            pkg = pkg,
            win = 7,
            viewId = "$pkg:id/url_bar",
            nodeText = "https://blocked.example/path",
            editable = true,
            focused = true
        )
        val webContainer = node(
            pkg = pkg,
            win = 7,
            nodeClassName = "android.webkit.WebView",
            children = listOf(strong)
        )
        val root = node(pkg = pkg, win = 7, children = listOf(webContainer))

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
            pkg = pkg,
            win = 8,
            viewId = "$pkg:id/page_search_field",
            nodeText = "https://blocked.example"
        )
        val webContainer = node(
            pkg = pkg,
            win = 8,
            nodeClassName = "android.webkit.WebView",
            children = listOf(htmlField)
        )
        val root = node(pkg = pkg, win = 8, children = listOf(webContainer))

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
            pkg = pkg,
            win = 9,
            viewId = "$pkg:id/location_bar",
            nodeText = "Search or type URL"
        )
        val actualAddress = node(
            pkg = pkg,
            win = 9,
            viewId = "$pkg:id/url_bar",
            nodeText = "https://blocked.example/page"
        )
        val root = node(
            pkg = pkg,
            win = 9,
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
        pkg: String,
        win: Int,
        viewId: String? = null,
        nodeText: String? = null,
        nodeClassName: String = "android.view.View",
        editable: Boolean = false,
        focused: Boolean = false,
        children: List<AccessibilityNodeInfo> = emptyList()
    ): AccessibilityNodeInfo = mockk(relaxed = true) {
        every { packageName } returns pkg
        every { windowId } returns win
        every { isVisibleToUser } returns true
        every { viewIdResourceName } returns viewId
        every { text } returns nodeText
        every { contentDescription } returns null
        every { hintText } returns null
        every { className } returns nodeClassName
        every { isEditable } returns editable
        every { isFocused } returns focused
        every { childCount } returns children.size
        children.forEachIndexed { index, child ->
            every { getChild(index) } returns child
        }
    }
}
