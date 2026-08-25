package com.focusguard.focusmode

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FocusModeIdleReturnPolicyTest {

    @Test
    fun `idle timeout is exactly thirty seconds`() {
        assertThat(FocusModeIdleReturnPolicy.IDLE_TIMEOUT_MILLIS).isEqualTo(30_000L)
    }

    @Test
    fun `active focus mode arms return outside focus mode home`() {
        assertThat(
            FocusModeIdleReturnPolicy.shouldArm(
                focusModeActive = true,
                onFocusModeHome = false,
                antiPornCourseActive = false
            )
        ).isTrue()
    }

    @Test
    fun `focus mode home never arms idle return`() {
        assertThat(
            FocusModeIdleReturnPolicy.shouldArm(
                focusModeActive = true,
                onFocusModeHome = true,
                antiPornCourseActive = false
            )
        ).isFalse()
    }

    @Test
    fun `inactive focus mode never arms idle return`() {
        assertThat(
            FocusModeIdleReturnPolicy.shouldArm(
                focusModeActive = false,
                onFocusModeHome = false,
                antiPornCourseActive = false
            )
        ).isFalse()
    }

    @Test
    fun `antiporn course is exempt while user is studying`() {
        assertThat(
            FocusModeIdleReturnPolicy.shouldArm(
                focusModeActive = true,
                onFocusModeHome = false,
                antiPornCourseActive = true
            )
        ).isFalse()
    }

    @Test
    fun `navigation tab constants match main screen tabs`() {
        assertThat(FocusModeIdleReturnPolicy.RECOVERY_TAB).isEqualTo(3)
        assertThat(FocusModeIdleReturnPolicy.FOCUS_MODE_TAB).isEqualTo(4)
    }
}
