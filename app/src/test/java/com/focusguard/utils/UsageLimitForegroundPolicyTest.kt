package com.focusguard.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UsageLimitForegroundPolicyTest {

    @Test
    fun `app limit uses only reported foreground milliseconds`() {
        assertThat(UsageLimitForegroundPolicy.usedMinutes(-1L)).isEqualTo(0L)
        assertThat(UsageLimitForegroundPolicy.usedMinutes(59_999L)).isEqualTo(0L)
        assertThat(UsageLimitForegroundPolicy.usedMinutes(60_000L)).isEqualTo(1L)
        assertThat(UsageLimitForegroundPolicy.usedMinutes(150_000L)).isEqualTo(2L)
    }

    @Test
    fun `website usage counts only while tracked browser is foreground and screen is on`() {
        assertThat(
            UsageLimitForegroundPolicy.shouldCountWebsiteUsage(
                trackedPackageName = "com.android.chrome",
                foregroundPackageName = "com.android.chrome",
                isDeviceInteractive = true
            )
        ).isTrue()

        assertThat(
            UsageLimitForegroundPolicy.shouldCountWebsiteUsage(
                trackedPackageName = "com.android.chrome",
                foregroundPackageName = "com.example.other",
                isDeviceInteractive = true
            )
        ).isFalse()
        assertThat(
            UsageLimitForegroundPolicy.shouldCountWebsiteUsage(
                trackedPackageName = "com.android.chrome",
                foregroundPackageName = "com.android.chrome",
                isDeviceInteractive = false
            )
        ).isFalse()
        assertThat(
            UsageLimitForegroundPolicy.shouldCountWebsiteUsage(
                trackedPackageName = null,
                foregroundPackageName = "com.android.chrome",
                isDeviceInteractive = true
            )
        ).isFalse()
    }
}
