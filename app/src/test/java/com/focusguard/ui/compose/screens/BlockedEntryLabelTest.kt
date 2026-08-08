package com.focusguard.ui.compose.screens

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BlockedEntryLabelTest {

    @Test
    fun `installed app uses the label resolved by the package manager`() {
        val label = blockedEntryLabel(
            identifier = "com.instagram.android",
            isWebsite = false,
            installedLabel = "Instagram"
        )

        assertThat(label).isEqualTo("Instagram")
    }

    @Test
    fun `preventive target falls back to the catalogue name instead of the package id`() {
        val label = blockedEntryLabel(
            identifier = "com.instagram.android",
            isWebsite = false,
            installedLabel = null
        )

        assertThat(label).isEqualTo("Instagram")
    }

    @Test
    fun `unknown package keeps its identifier`() {
        val label = blockedEntryLabel(
            identifier = "com.example.unknown",
            isWebsite = false,
            installedLabel = null
        )

        assertThat(label).isEqualTo("com.example.unknown")
    }

    @Test
    fun `website entries show their rule`() {
        val label = blockedEntryLabel(
            identifier = "instagram.com",
            isWebsite = true,
            installedLabel = null
        )

        assertThat(label).isEqualTo("instagram.com")
    }
}
