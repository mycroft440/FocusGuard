package com.focusguard.ui.compose.screens

import com.focusguard.manager.BlockingSessionManager.BlockOverview
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BlockedTargetCountTest {

    @Test
    fun `one app held by two period blocks counts once`() {
        val entries = listOf(
            BlockOverview.Entry("com.instagram.android", isWebsite = false, sessionId = 1),
            BlockOverview.Entry("com.instagram.android", isWebsite = false, sessionId = 2),
            BlockOverview.Entry("youtube.com", isWebsite = true, sessionId = 2)
        )

        assertThat(blockedTargetCount(entries)).isEqualTo(2)
    }

    @Test
    fun `an app and a site never merge even with the same text`() {
        val entries = listOf(
            BlockOverview.Entry("example", isWebsite = false),
            BlockOverview.Entry("example", isWebsite = true)
        )

        assertThat(blockedTargetCount(entries)).isEqualTo(2)
    }
}
