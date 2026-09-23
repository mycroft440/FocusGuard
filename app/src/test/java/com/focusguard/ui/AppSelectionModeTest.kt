package com.focusguard.ui

import com.focusguard.manager.BlockingSessionManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppSelectionModeTest {

    private val configured = BlockingSessionManager.ConfiguredBlockedTargets(
        passwordAppPackageNames = setOf("com.example.app"),
        limitedAppPackageNames = setOf("com.example.app"),
        exclusiveAppPackageNames = setOf("com.example.app"),
        passwordWebsiteRules = setOf("example.com"),
        limitedWebsiteRules = setOf("example.com"),
        exclusiveWebsiteRules = setOf("example.com")
    )

    @Test
    fun `usage limit alone does not exclude app or site from time and password`() {
        val limitOnly = BlockingSessionManager.ConfiguredBlockedTargets(
            limitedAppPackageNames = setOf("com.example.app"),
            limitedWebsiteRules = setOf("example.com")
        )

        listOf("TIME", "PASSWORD").forEach { sessionType ->
            val excluded = selectionExclusions(
                configured = limitOnly,
                allowCompatibleProtection = true,
                sessionType = sessionType
            )
            assertThat(excluded.appPackages).isEmpty()
            assertThat(excluded.websiteRules).isEmpty()
        }
    }

    @Test
    fun `daily period picker accepts app and site even with every existing layer`() {
        val excluded = selectionExclusions(
            configured = configured,
            allowCompatibleProtection = true,
            sessionType = "TIME"
        )

        assertThat(excluded.appPackages).isEmpty()
        assertThat(excluded.websiteRules).isEmpty()
    }

    @Test
    fun `unified picker waits for mode selection before rejecting a duplicate`() {
        val excluded = selectionExclusions(
            configured = configured,
            allowCompatibleProtection = true,
            sessionType = null
        )

        assertThat(excluded.appPackages).isEmpty()
        assertThat(excluded.websiteRules).isEmpty()
    }

    @Test
    fun `password picker still rejects another password rule for the same target`() {
        val excluded = selectionExclusions(
            configured = configured,
            allowCompatibleProtection = true,
            sessionType = "PASSWORD"
        )

        assertThat(excluded.appPackages).containsExactly("com.example.app")
        assertThat(excluded.websiteRules).containsExactly("example.com")
    }

    @Test
    fun `legacy exclusive picker still rejects any configured layer`() {
        val excluded = selectionExclusions(
            configured = configured,
            allowCompatibleProtection = false,
            sessionType = "TIME"
        )

        assertThat(excluded.appPackages).containsExactly("com.example.app")
        assertThat(excluded.websiteRules).containsExactly("example.com")
    }
}
