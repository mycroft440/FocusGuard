package com.focusguard.service

import android.view.accessibility.AccessibilityWindowInfo
import com.focusguard.service.AccessibilityInputEventFilter.Decision
import com.focusguard.service.AccessibilityInputEventFilter.Window
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AccessibilityInputEventFilterTest {
    private val filter = AccessibilityInputEventFilter()
    private var windows = listOf(Window(1, AccessibilityWindowInfo.TYPE_APPLICATION))
    private var windowReads = 0

    private fun classify(
        pkg: String,
        id: Int,
        changed: Boolean = false,
        allowLookup: Boolean = true
    ): Decision = filter.classify(
        ownPackageName = "com.focusguard.v2",
        eventPackageName = pkg,
        windowId = id,
        windowsChanged = changed,
        allowWindowLookup = allowLookup,
        readWindows = {
            windowReads++
            windows
        }
    )

    @Test
    fun `own typing and anonymous text events require no window reads`() {
        repeat(1_000) {
            assertThat(classify("com.focusguard.v2", 1)).isEqualTo(Decision.OWN_UI)
            assertThat(classify("", 1)).isEqualTo(Decision.OWN_UI)
        }
        assertThat(windowReads).isEqualTo(0)
    }

    @Test
    fun `anonymous own window resize is recognized through metadata`() {
        classify("com.focusguard.v2", 1)
        assertThat(classify("", 1, changed = true)).isEqualTo(Decision.OWN_UI)
        assertThat(windowReads).isEqualTo(1)
    }

    @Test
    fun `keyboard opening typing and closing never become a foreground app`() {
        windows = windows + Window(2, AccessibilityWindowInfo.TYPE_INPUT_METHOD)
        assertThat(classify("", 2, changed = true)).isEqualTo(Decision.INPUT_METHOD)
        repeat(1_000) {
            assertThat(classify("com.samsung.android.honeyboard", 2))
                .isEqualTo(Decision.INPUT_METHOD)
            assertThat(classify("", 2)).isEqualTo(Decision.INPUT_METHOD)
        }
        assertThat(windowReads).isEqualTo(1)

        windows = windows.filterNot { it.id == 2 }
        assertThat(classify("", 2, changed = true)).isEqualTo(Decision.INPUT_METHOD)
        assertThat(windowReads).isEqualTo(2)
    }

    @Test
    fun `keyboard settings and protected apps remain inspectable with an IME visible`() {
        windows = listOf(
            Window(2, AccessibilityWindowInfo.TYPE_INPUT_METHOD),
            Window(3, AccessibilityWindowInfo.TYPE_APPLICATION),
            Window(4, AccessibilityWindowInfo.TYPE_APPLICATION)
        )
        classify("com.samsung.android.honeyboard", 2, changed = true)
        assertThat(classify("com.samsung.android.honeyboard", 3))
            .isEqualTo(Decision.INSPECT)
        assertThat(classify("com.example.blocked", 4, changed = true))
            .isEqualTo(Decision.INSPECT)
    }

    @Test
    fun `named interception fast paths do not perform metadata lookups`() {
        listOf("com.android.settings", "com.android.systemui", "com.android.chrome")
            .forEach { pkg ->
                assertThat(classify(pkg, 9, allowLookup = false))
                    .isEqualTo(Decision.INSPECT)
            }
        assertThat(windowReads).isEqualTo(0)
    }

    @Test
    fun `anonymous system ui and settings windows retain normal inspection`() {
        windows = listOf(
            Window(1, AccessibilityWindowInfo.TYPE_APPLICATION),
            Window(2, AccessibilityWindowInfo.TYPE_INPUT_METHOD),
            Window(3, AccessibilityWindowInfo.TYPE_SYSTEM)
        )
        assertThat(classify("", 3, changed = true)).isEqualTo(Decision.INSPECT)
        assertThat(classify("com.android.settings", 1)).isEqualTo(Decision.INSPECT)
    }

    @Test
    fun `removed own window cannot exempt an app that later uses its id`() {
        classify("com.focusguard.v2", 1)
        windows = emptyList()
        assertThat(classify("", 1, changed = true)).isEqualTo(Decision.INSPECT)
        windows = listOf(Window(1, AccessibilityWindowInfo.TYPE_APPLICATION))
        assertThat(classify("", 1, changed = true)).isEqualTo(Decision.INSPECT)
    }

    @Test
    fun `removed keyboard id cannot exempt a later application window`() {
        windows = listOf(Window(2, AccessibilityWindowInfo.TYPE_INPUT_METHOD))
        classify("", 2, changed = true)
        windows = listOf(Window(2, AccessibilityWindowInfo.TYPE_APPLICATION))
        assertThat(classify("", 2, changed = true)).isEqualTo(Decision.INSPECT)
    }

    @Test
    fun `package hint on windows changed is not trusted as a window owner`() {
        assertThat(classify("com.focusguard.v2", 1, changed = true))
            .isEqualTo(Decision.INSPECT)
        assertThat(classify("", 1)).isEqualTo(Decision.INSPECT)
    }

    @Test
    fun `unknown window ids and missing metadata fail through to normal inspection`() {
        windows = emptyList()
        assertThat(classify("", -1, changed = true)).isEqualTo(Decision.INSPECT)
        assertThat(classify("", 99, changed = true)).isEqualTo(Decision.INSPECT)
    }
}
