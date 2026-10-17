package com.focusguard.ui.compose.theme

import android.view.WindowManager.LayoutParams
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SystemBarsFrameTest {

    @Test
    fun `screens that asked for adjustResize keep clear of the keyboard`() {
        assertThat(followsKeyboardResize(LayoutParams.SOFT_INPUT_ADJUST_RESIZE)).isTrue()
        assertThat(
            followsKeyboardResize(
                LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    LayoutParams.SOFT_INPUT_STATE_HIDDEN
            )
        ).isTrue()
    }

    @Test
    fun `pan and unspecified windows keep the system keyboard behavior`() {
        assertThat(followsKeyboardResize(LayoutParams.SOFT_INPUT_ADJUST_PAN)).isFalse()
        assertThat(followsKeyboardResize(LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED)).isFalse()
        assertThat(followsKeyboardResize(LayoutParams.SOFT_INPUT_ADJUST_NOTHING)).isFalse()
    }
}
