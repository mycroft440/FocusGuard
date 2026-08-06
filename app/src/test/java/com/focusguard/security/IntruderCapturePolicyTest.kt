package com.focusguard.security

import com.focusguard.security.IntruderCapturePolicy.Surface
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IntruderCapturePolicyTest {

    @Test
    fun `only the blocked app unlock captures`() {
        // Guards against a new surface silently inheriting the camera.
        val capturing = Surface.entries.filter(IntruderCapturePolicy::capturesOn)

        assertThat(capturing).containsExactly(Surface.BLOCKED_APP_UNLOCK)
    }

    @Test
    fun `captures on the first wrong password`() {
        // No attempt threshold: someone who does not know the password often
        // stops after one try, and waiting for a third would photograph nobody.
        assertThat(
            IntruderCapturePolicy.shouldCapture(
                surface = Surface.BLOCKED_APP_UNLOCK,
                photoCaptureEnabled = true
            )
        ).isTrue()
    }

    @Test
    fun `FocusGuard own lock screen never captures`() {
        // Mistyping your own password is routine; this would photograph the owner.
        assertThat(
            IntruderCapturePolicy.shouldCapture(
                surface = Surface.FOCUSGUARD_APP_LOCK,
                photoCaptureEnabled = true
            )
        ).isFalse()
    }

    @Test
    fun `password management never captures`() {
        // Only reachable after authenticating, so whoever is there already passed.
        assertThat(
            IntruderCapturePolicy.shouldCapture(
                surface = Surface.PASSWORD_MANAGEMENT,
                photoCaptureEnabled = true
            )
        ).isFalse()
    }

    @Test
    fun `usage limit prompt never captures`() {
        assertThat(
            IntruderCapturePolicy.shouldCapture(
                surface = Surface.USAGE_LIMIT_UNLOCK,
                photoCaptureEnabled = true
            )
        ).isFalse()
    }

    @Test
    fun `the preference switches the whole feature off`() {
        Surface.entries.forEach { surface ->
            assertThat(
                IntruderCapturePolicy.shouldCapture(
                    surface = surface,
                    photoCaptureEnabled = false
                )
            ).isFalse()
        }
    }
}
