package com.focusguard.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PredefinedWebsitesTest {
    @Test
    fun `full site selection catalogue matches time block shortcuts`() {
        assertThat(PredefinedWebsites.SITE_SELECTION_RULES.first())
            .isEqualTo(PredefinedWebsites.PORNOGRAPHY_RULE)
        assertThat(PredefinedWebsites.SITE_SELECTION_RULES.drop(1))
            .containsExactlyElementsIn(PredefinedWebsites.ALL_PRESETS.map { it.domain })
            .inOrder()
    }

    @Test
    fun `full site selection catalogue has no duplicate rules`() {
        assertThat(PredefinedWebsites.SITE_SELECTION_RULES)
            .containsNoDuplicates()
    }
}
