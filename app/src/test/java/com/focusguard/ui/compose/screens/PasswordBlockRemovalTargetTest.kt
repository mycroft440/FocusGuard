package com.focusguard.ui.compose.screens

import com.focusguard.manager.BlockingSessionManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PasswordBlockRemovalTargetTest {

    @Test
    fun `app removal keeps the app package route`() {
        val target = passwordRemovalTargetFor(
            BlockingSessionManager.BlockOverview.Entry(
                identifier = "com.example.app",
                isWebsite = false
            )
        )

        assertThat(target.targetId).isEqualTo("com.example.app")
        assertThat(target.blockedPackage).isEqualTo("com.example.app")
        assertThat(target.blockedDomain).isNull()
    }

    @Test
    fun `website removal uses the website credential and domain route`() {
        val target = passwordRemovalTargetFor(
            BlockingSessionManager.BlockOverview.Entry(
                identifier = "youtube.com",
                isWebsite = true
            )
        )

        assertThat(target.targetId).isEqualTo("site:youtube.com")
        assertThat(target.blockedPackage).isNull()
        assertThat(target.blockedDomain).isEqualTo("youtube.com")
    }
}
