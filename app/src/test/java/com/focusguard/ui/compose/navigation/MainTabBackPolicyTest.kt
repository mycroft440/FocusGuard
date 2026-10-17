package com.focusguard.ui.compose.navigation

import com.focusguard.focusmode.FocusModeIdleReturnPolicy
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MainTabBackPolicyTest {

    @Test
    fun `back on any other tab returns to protection instead of closing the app`() {
        listOf(
            MainTabBackPolicy.METRICS_TAB,
            MainTabBackPolicy.POMODORO_TAB,
            FocusModeIdleReturnPolicy.FOCUS_MODE_TAB,
            5
        ).forEach { tab ->
            assertThat(MainTabBackPolicy.backTarget(tab, focusModeActive = false))
                .isEqualTo(MainTabBackPolicy.PROTECTION_TAB)
        }
    }

    @Test
    fun `back on the protection tab keeps the default and leaves the app`() {
        assertThat(
            MainTabBackPolicy.backTarget(MainTabBackPolicy.PROTECTION_TAB, focusModeActive = false)
        ).isNull()
    }

    @Test
    fun `active focus mode keeps its own back handling`() {
        listOf(0, 1, 2, 4).forEach { tab ->
            assertThat(MainTabBackPolicy.backTarget(tab, focusModeActive = true)).isNull()
        }
    }
}
