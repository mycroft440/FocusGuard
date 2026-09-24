package com.focusguard.monetization

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FocusGuardAdsBannerWidthTest {

    @Test
    fun `rounding differences reuse the screen width preload`() {
        assertThat(FocusGuardAds.normalizedBannerWidthDp(412, 411)).isEqualTo(411)
        assertThat(FocusGuardAds.normalizedBannerWidthDp(409, 411)).isEqualTo(411)
    }

    @Test
    fun `clearly narrower slots keep their own width`() {
        assertThat(FocusGuardAds.normalizedBannerWidthDp(371, 411)).isEqualTo(371)
    }

    @Test
    fun `widths never go below the adaptive banner minimum`() {
        assertThat(FocusGuardAds.normalizedBannerWidthDp(200, 280)).isEqualTo(300)
    }
}
