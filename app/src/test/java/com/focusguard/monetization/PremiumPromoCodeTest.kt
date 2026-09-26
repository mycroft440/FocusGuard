package com.focusguard.monetization

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PremiumPromoCodeTest {

    @Test
    fun `promo code ignores accents spaces and case`() {
        listOf("josegustavo34", "José Gustavo 34", "JOSEGUSTAVO34", " josé gustavo34 ")
            .forEach { assertThat(PremiumManager.normalizeCode(it)).isEqualTo("josegustavo34") }
    }

    @Test
    fun `other codes do not normalize to the promo code`() {
        assertThat(PremiumManager.normalizeCode("josegustavo35")).isNotEqualTo("josegustavo34")
        assertThat(PremiumManager.normalizeCode("gustavo34")).isNotEqualTo("josegustavo34")
    }
}
