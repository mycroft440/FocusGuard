package com.focusguard.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PasswordTargetAccessGrantHierarchyTest {

    @Test
    fun `same website rule overlaps`() {
        assertThat(
            PasswordTargetAccessGrant.websiteRulesOverlap(
                "youtube.com",
                "https://www.youtube.com/watch?v=1"
            )
        ).isTrue()
    }

    @Test
    fun `parent and child website rules overlap in either direction`() {
        assertThat(
            PasswordTargetAccessGrant.websiteRulesOverlap(
                "example.com",
                "news.example.com"
            )
        ).isTrue()
        assertThat(
            PasswordTargetAccessGrant.websiteRulesOverlap(
                "news.example.com",
                "example.com"
            )
        ).isTrue()
    }

    @Test
    fun `unrelated website rules do not overlap`() {
        assertThat(
            PasswordTargetAccessGrant.websiteRulesOverlap(
                "youtube.com",
                "reddit.com"
            )
        ).isFalse()
    }
}
