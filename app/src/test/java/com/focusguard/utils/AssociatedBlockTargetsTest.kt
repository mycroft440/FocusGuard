package com.focusguard.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AssociatedBlockTargetsTest {
    @Test
    fun `companion blocking is disabled by default`() {
        assertFalse(AssociatedBlockTargets.DEFAULT_BLOCK_COMPANION)
    }

    @Test
    fun `youtube app maps to youtube website`() {
        assertEquals(
            "youtube.com",
            AssociatedBlockTargets.domainForAppPackage("com.google.android.youtube")
        )
    }

    @Test
    fun `youtube website maps to youtube app`() {
        assertEquals(
            "com.google.android.youtube",
            AssociatedBlockTargets.appForWebsiteRule("https://www.youtube.com/watch?v=123")?.packageName
        )
    }

    @Test
    fun `unknown targets do not invent companions`() {
        assertNull(AssociatedBlockTargets.domainForAppPackage("com.example.unknown"))
        assertNull(AssociatedBlockTargets.appForWebsiteRule("example.invalid"))
    }
}
