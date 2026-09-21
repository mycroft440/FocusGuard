package com.focusguard.service

import android.view.KeyEvent
import android.view.WindowManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebsiteCurtainInputPolicyTest {
    @Test
    fun `website curtain is touchable and focusable while other curtains stay non focusable`() {
        val initial = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

        val website = BlockingAccessibilityService.visibleOverlayFlags(
            flags = initial,
            focusable = true
        )
        assertThat(website and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).isEqualTo(0)
        assertThat(website and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).isEqualTo(0)

        val regular = BlockingAccessibilityService.visibleOverlayFlags(initial)
        assertThat(regular and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).isEqualTo(0)
        assertThat(regular and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).isNotEqualTo(0)
    }

    @Test
    fun `website curtain consumes navigation and keyboard input but preserves hardware controls`() {
        assertThat(
            BlockingAccessibilityService.shouldConsumeWebsiteCurtainKey(
                curtainVisible = true,
                websiteCurtain = true,
                keyCode = KeyEvent.KEYCODE_BACK
            )
        ).isTrue()
        assertThat(
            BlockingAccessibilityService.shouldConsumeWebsiteCurtainKey(
                curtainVisible = true,
                websiteCurtain = true,
                keyCode = KeyEvent.KEYCODE_A
            )
        ).isTrue()
        assertThat(
            BlockingAccessibilityService.shouldConsumeWebsiteCurtainKey(
                curtainVisible = true,
                websiteCurtain = true,
                keyCode = KeyEvent.KEYCODE_VOLUME_UP
            )
        ).isFalse()
        assertThat(
            BlockingAccessibilityService.shouldConsumeWebsiteCurtainKey(
                curtainVisible = true,
                websiteCurtain = false,
                keyCode = KeyEvent.KEYCODE_BACK
            )
        ).isFalse()
    }
}
