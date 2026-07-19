package com.focusguard.manager

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BlockingSessionManagerTargetsTest {

    @Test
    fun `session and daily limit targets are treated as already blocked`() {
        val targets = BlockingSessionManager.combineConfiguredBlockedTargets(
            sessionAppPackages = listOf("com.example.password", "com.example.dopamine"),
            sessionWebsiteRules = listOf("youtube.com", "keyword:porn"),
            limitedAppPackages = listOf("com.example.limit"),
            limitedWebsiteRules = listOf("https://www.reddit.com/r/focus")
        )

        assertThat(targets.appPackageNames).containsExactly(
            "com.example.password",
            "com.example.dopamine",
            "com.example.limit",
            "com.google.android.youtube"
        )
        assertThat(targets.websiteRules).containsExactly(
            "youtube.com",
            "keyword:porn",
            "reddit.com"
        )
    }

    @Test
    fun `blank and duplicate targets are removed`() {
        val targets = BlockingSessionManager.combineConfiguredBlockedTargets(
            sessionAppPackages = listOf("com.example.app", ""),
            sessionWebsiteRules = listOf("youtube.com"),
            limitedAppPackages = listOf("com.example.app", "   "),
            limitedWebsiteRules = listOf("https://www.youtube.com/watch?v=1")
        )

        assertThat(targets.appPackageNames).containsExactly(
            "com.example.app",
            "com.google.android.youtube"
        )
        assertThat(targets.websiteRules).containsExactly("youtube.com")
    }
}
