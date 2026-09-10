package com.focusguard.service

import android.app.Application
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BlockingAccessibilityInputTest {
    private val ownPackage = "com.focusguard.v2"

    private fun event(type: Int, pkg: String?, id: Int) = mockk<AccessibilityEvent>(relaxed = true) {
        every { eventType } returns type
        every { packageName } returns pkg
        every { windowId } returns id
        every { source } returns null
    }

    private fun window(windowId: Int, windowType: Int) = mockk<AccessibilityWindowInfo> {
        every { id } returns windowId
        every { type } returns windowType
        every { root } returns null
    }

    @Test
    fun `own resize and text storms never request nodes on the service callback`() {
        val service = spyk(BlockingAccessibilityService())
        val ownWindow = window(1, AccessibilityWindowInfo.TYPE_APPLICATION)
        every { service.packageName } returns ownPackage
        every { service.windows } returns listOf(ownWindow)
        every { service.rootInActiveWindow } returns null

        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_VIEW_FOCUSED, ownPackage, 1))
        val resize = event(AccessibilityEvent.TYPE_WINDOWS_CHANGED, null, 1)
        val text = event(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, null, 1)
        service.onAccessibilityEvent(resize)
        repeat(100) { service.onAccessibilityEvent(text) }

        verify(exactly = 1) { service.windows }
        verify(exactly = 0) { service.rootInActiveWindow }
        verify(exactly = 0) { ownWindow.root }
        verify(exactly = 0) { resize.source }
        verify(exactly = 0) { text.source }
        assertThat(ReflectionHelpers.getField<String>(service, "foregroundPackageName"))
            .isEqualTo(ownPackage)
    }

    @Test
    fun `keyboard open type and close preserve the app and do not read either tree`() {
        val service = spyk(BlockingAccessibilityService())
        val ownWindow = window(1, AccessibilityWindowInfo.TYPE_APPLICATION)
        val imeWindow = window(2, AccessibilityWindowInfo.TYPE_INPUT_METHOD)
        var visibleWindows = listOf(ownWindow, imeWindow)
        every { service.packageName } returns ownPackage
        every { service.windows } answers { visibleWindows }
        every { service.rootInActiveWindow } returns null

        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_VIEW_FOCUSED, ownPackage, 1))
        val transition = event(AccessibilityEvent.TYPE_WINDOWS_CHANGED, null, 2)
        val typing = event(
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            "com.samsung.android.honeyboard",
            2
        )
        service.onAccessibilityEvent(transition)
        repeat(100) { service.onAccessibilityEvent(typing) }
        visibleWindows = listOf(ownWindow)
        service.onAccessibilityEvent(transition)

        verify(exactly = 2) { service.windows }
        verify(exactly = 0) { service.rootInActiveWindow }
        verify(exactly = 0) { ownWindow.root }
        verify(exactly = 0) { imeWindow.root }
        verify(exactly = 0) { transition.source }
        verify(exactly = 0) { typing.source }
        assertThat(ReflectionHelpers.getField<String>(service, "foregroundPackageName"))
            .isEqualTo(ownPackage)
    }
}
