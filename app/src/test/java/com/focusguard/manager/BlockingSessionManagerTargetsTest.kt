package com.focusguard.manager

import com.focusguard.data.PredefinedWebsites
import com.focusguard.database.BlockSession
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BlockingSessionManagerTargetsTest {

    @Test
    fun `one or two protection layers keep separate target inventories`() {
        val targets = BlockingSessionManager.combineConfiguredBlockedTargets(
            passwordSessionAppPackages = listOf(
                "com.example.password",
                "com.example.passwordLimit"
            ),
            passwordSessionWebsiteRules = listOf("youtube.com", "reddit.com"),
            exclusiveSessionAppPackages = listOf(
                "com.example.time",
                "com.example.passwordTime"
            ),
            exclusiveSessionWebsiteRules = listOf("instagram.com", "reddit.com"),
            limitedAppPackages = listOf(
                "com.example.limit",
                "com.example.passwordLimit"
            ),
            limitedWebsiteRules = listOf("youtube.com", "instagram.com")
        )

        assertThat(targets.passwordAppPackageNames)
            .containsExactly("com.example.password", "com.example.passwordLimit")
        assertThat(targets.limitedAppPackageNames)
            .containsExactly("com.example.limit", "com.example.passwordLimit")
        assertThat(targets.exclusiveWebsiteRules)
            .containsExactly("instagram.com", "reddit.com")
    }

    @Test
    fun `target with all three layers keeps independent ownership`() {
        val targets = BlockingSessionManager.combineConfiguredBlockedTargets(
            passwordSessionAppPackages = listOf("com.example.all", "com.example.password"),
            passwordSessionWebsiteRules = listOf("youtube.com", "reddit.com"),
            exclusiveSessionAppPackages = listOf("com.example.all", "com.example.time"),
            exclusiveSessionWebsiteRules = listOf("youtube.com", "instagram.com"),
            limitedAppPackages = listOf("com.example.all", "com.example.limit"),
            limitedWebsiteRules = listOf("https://www.youtube.com/watch?v=1", "tiktok.com")
        )

        assertThat(targets.passwordAppPackageNames).contains("com.example.all")
        assertThat(targets.limitedAppPackageNames).contains("com.example.all")
        assertThat(targets.exclusiveAppPackageNames).contains("com.example.all")
        assertThat(targets.passwordWebsiteRules).contains("youtube.com")
        assertThat(targets.limitedWebsiteRules).contains("youtube.com")
        assertThat(targets.exclusiveWebsiteRules).contains("youtube.com")
    }

    @Test
    fun `existing keyword covers site in its own protection mode`() {
        assertThat(
            BlockingSessionManager.isWebsiteRuleCoveredBy(
                "example-porn-site.com", listOf("keyword:porn")
            )
        ).isTrue()
    }

    @Test
    fun `pornography category semantically covers its internal rules`() {
        assertThat(
            BlockingSessionManager.isWebsiteRuleCoveredBy(
                "keyword:xvideo",
                listOf(PredefinedWebsites.PORNOGRAPHY_RULE)
            )
        ).isTrue()
        assertThat(
            BlockingSessionManager.isWebsiteRuleCoveredBy(
                "onlyfans.com",
                listOf(PredefinedWebsites.PORNOGRAPHY_RULE)
            )
        ).isTrue()
    }

    @Test
    fun `blank and duplicate targets are removed`() {
        val targets = BlockingSessionManager.combineConfiguredBlockedTargets(
            passwordSessionAppPackages = listOf("com.example.app", ""),
            passwordSessionWebsiteRules = listOf("youtube.com"),
            exclusiveSessionAppPackages = emptyList(),
            exclusiveSessionWebsiteRules = emptyList(),
            limitedAppPackages = listOf("com.example.app", "   "),
            limitedWebsiteRules = listOf("https://www.youtube.com/watch?v=1")
        )

        assertThat(targets.allAppPackageNames).containsExactly("com.example.app")
        assertThat(targets.allWebsiteRules).containsExactly("youtube.com")
    }

    @Test
    fun `YouTube app does not reserve the YouTube website surface`() {
        val appOnly = BlockingSessionManager.combineConfiguredBlockedTargets(
            passwordSessionAppPackages = listOf("com.google.android.youtube"),
            passwordSessionWebsiteRules = emptyList(),
            exclusiveSessionAppPackages = emptyList(),
            exclusiveSessionWebsiteRules = emptyList(),
            limitedAppPackages = emptyList(),
            limitedWebsiteRules = emptyList()
        )

        assertThat(appOnly.allAppPackageNames)
            .containsExactly("com.google.android.youtube")
        assertThat(appOnly.allWebsiteRules).isEmpty()

        val siteOnly = BlockingSessionManager.combineConfiguredBlockedTargets(
            passwordSessionAppPackages = emptyList(),
            passwordSessionWebsiteRules = listOf("youtube.com"),
            exclusiveSessionAppPackages = emptyList(),
            exclusiveSessionWebsiteRules = emptyList(),
            limitedAppPackages = emptyList(),
            limitedWebsiteRules = emptyList()
        )

        assertThat(siteOnly.allWebsiteRules).containsExactly("youtube.com")
        assertThat(siteOnly.allAppPackageNames).isEmpty()
    }

    @Test
    fun `leftover sessions of the removed strict pomodoro never block`() {
        assertThat(
            BlockingSessionManager.participatesInBlocking(
                BlockSession(sessionType = "POMODORO", isBlockingEnabled = false)
            )
        ).isFalse()
        assertThat(
            BlockingSessionManager.participatesInBlocking(
                BlockSession(sessionType = "POMODORO", isBlockingEnabled = true)
            )
        ).isFalse()
        assertThat(
            BlockingSessionManager.participatesInBlocking(
                BlockSession(sessionType = "PASSWORD", isBlockingEnabled = false)
            )
        ).isTrue()
    }

    @Test
    fun `password session must match the blocked target`() {
        assertThat(
            BlockingSessionManager.matchesBlockedTarget(
                blockedPackage = "com.example.blocked",
                blockedDomain = null,
                sessionApps = setOf("com.example.other"),
                sessionSites = emptySet()
            )
        ).isFalse()
        assertThat(
            BlockingSessionManager.matchesBlockedTarget(
                blockedPackage = null,
                blockedDomain = "news.example.com",
                sessionApps = emptySet(),
                sessionSites = setOf("example.com")
            )
        ).isTrue()
    }

    @Test
    fun `adult filter alone arms self protection`() {
        assertThat(
            BlockingSessionManager.shouldArmSelfProtection(
                hasEnforcingSessions = false,
                hasBlockedApps = false,
                hasBlockedSites = false,
                adultFilterEnabled = true
            )
        ).isTrue()
    }

    @Test
    fun `active focus mode arms self protection even when its blocked list is empty`() {
        assertThat(
            BlockingSessionManager.shouldArmSelfProtection(
                hasEnforcingSessions = false,
                hasBlockedApps = false,
                hasBlockedSites = false,
                adultFilterEnabled = false,
                focusModeActive = true
            )
        ).isTrue()
    }

    @Test
    fun `self protection disarms only when no target is being blocked`() {
        assertThat(
            BlockingSessionManager.shouldArmSelfProtection(
                hasEnforcingSessions = false,
                hasBlockedApps = false,
                hasBlockedSites = false,
                adultFilterEnabled = false
            )
        ).isFalse()
    }

    @Test
    fun `device owner keeps password only target launchable for accessibility auth`() {
        assertThat(
            BlockingSessionManager.packagesForDeviceOwnerSuspension(
                enforcedPackages = listOf("com.example.password"),
                passwordSessionPackages = listOf("com.example.password"),
                strongerProtectionPackages = emptyList()
            )
        ).isEmpty()
    }

    @Test
    fun `device owner suspends password target once daily limit becomes stronger`() {
        assertThat(
            BlockingSessionManager.packagesForDeviceOwnerSuspension(
                enforcedPackages = listOf("com.example.password"),
                passwordSessionPackages = listOf("com.example.password"),
                strongerProtectionPackages = listOf("com.example.password")
            )
        ).containsExactly("com.example.password")
    }

    @Test
    fun `device owner keeps unrelated non password targets suspended`() {
        assertThat(
            BlockingSessionManager.packagesForDeviceOwnerSuspension(
                enforcedPackages = listOf("com.example.password", "com.example.time"),
                passwordSessionPackages = listOf("com.example.password"),
                strongerProtectionPackages = listOf("com.example.time")
            )
        ).containsExactly("com.example.time")
    }
}
