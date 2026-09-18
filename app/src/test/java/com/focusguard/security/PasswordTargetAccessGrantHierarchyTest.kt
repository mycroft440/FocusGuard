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
    @Test
    fun `new strong app owner is visible immediately`() {
        PasswordTargetAccessGrant.clear()
        try {
            PasswordTargetAccessGrant.claimStrongerAppProtection("com.example.target")
            assertThat(
                PasswordTargetAccessGrant.isAppStronglyProtected("com.example.target")
            ).isTrue()
        } finally {
            PasswordTargetAccessGrant.clear()
        }
    }

    @Test
    fun `new strong website owner covers the password rule immediately`() {
        PasswordTargetAccessGrant.clear()
        try {
            PasswordTargetAccessGrant.claimStrongerWebsiteProtection("youtube.com")
            assertThat(
                PasswordTargetAccessGrant.isWebsiteStronglyProtected(
                    "https://m.youtube.com/watch?v=1"
                )
            ).isTrue()
        } finally {
            PasswordTargetAccessGrant.clear()
        }
    }

}
