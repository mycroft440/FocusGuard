package com.focusguard.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AssociatedBlockTargetsTest {
    @Test
    fun `companion blocking is disabled by default`() {
        assertThat(AssociatedBlockTargets.DEFAULT_BLOCK_COMPANION).isFalse()
    }

    @Test
    fun `youtube app maps to youtube website`() {
        assertThat(
            AssociatedBlockTargets.domainForAppPackage("com.google.android.youtube")
        ).isEqualTo("youtube.com")
    }

    @Test
    fun `youtube website maps to youtube app`() {
        assertThat(
            AssociatedBlockTargets.appForWebsiteRule(
                "https://www.youtube.com/watch?v=123"
            )?.packageName
        ).isEqualTo("com.google.android.youtube")
    }

    @Test
    fun `unknown targets do not invent companions`() {
        assertThat(
            AssociatedBlockTargets.domainForAppPackage("com.example.unknown")
        ).isNull()
        assertThat(AssociatedBlockTargets.appForWebsiteRule("example.invalid")).isNull()
    }
}
