package com.focusguard.ui.compose.theme

import android.os.Build
import android.view.WindowManager.LayoutParams
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SystemBarsFrameTest {

    private val android15 = Build.VERSION_CODES.VANILLA_ICE_CREAM

    @Test
    fun `screens that asked for adjustResize keep clear of the keyboard on Android 15+`() {
        assertThat(followsKeyboardResize(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, android15))
            .isTrue()
        assertThat(
            followsKeyboardResize(
                LayoutParams.SOFT_INPUT_ADJUST_RESIZE or LayoutParams.SOFT_INPUT_STATE_HIDDEN,
                android15 + 1
            )
        ).isTrue()
    }

    @Test
    fun `up to Android 14 the system itself resizes the window`() {
        val android14 = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        assertThat(followsKeyboardResize(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, android14))
            .isFalse()
    }

    @Test
    fun `pan and unspecified windows keep the system keyboard behavior`() {
        listOf(
            LayoutParams.SOFT_INPUT_ADJUST_PAN,
            LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED,
            LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        ).forEach { mode ->
            assertThat(followsKeyboardResize(mode, android15)).isFalse()
        }
    }
}
