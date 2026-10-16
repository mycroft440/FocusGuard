package com.focusguard.analytics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BlockSurfaceUsageTest {
    private val own = "com.focusguard.v2"

    @Test
    fun `block screens are not counted as the app itself`() {
        assertThat(
            BlockSurfaceUsage.attributedPackage(own, "com.focusguard.ui.UsageImpactActivity", own)
        ).isEqualTo(BlockSurfaceUsage.SURFACE_PACKAGE)
        assertThat(
            BlockSurfaceUsage.attributedPackage(own, "com.focusguard.ui.BlockNoticeActivity", own)
        ).isEqualTo(BlockSurfaceUsage.SURFACE_PACKAGE)
    }

    @Test
    fun `the main screen and other apps keep their own package`() {
        assertThat(
            BlockSurfaceUsage.attributedPackage(own, "com.focusguard.MainActivity", own)
        ).isEqualTo(own)
        assertThat(
            BlockSurfaceUsage.attributedPackage(
                "com.whatsapp",
                "com.focusguard.ui.UsageImpactActivity",
                own
            )
        ).isEqualTo("com.whatsapp")
    }
}
