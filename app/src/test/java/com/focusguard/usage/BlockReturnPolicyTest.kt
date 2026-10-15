package com.focusguard.usage

import com.focusguard.usage.BlockReturnPolicy.Destination
import com.focusguard.usage.BlockReturnPolicy.Resumed
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BlockReturnPolicyTest {
    private val own = "com.focusguard.v2"
    private val ownMain = "com.focusguard.MainActivity"
    private val launcher = "com.android.launcher"
    private val blocked = "com.instagram.android"
    private val launchable = setOf("com.whatsapp", "com.android.chrome", blocked)

    @Test
    fun `entry from the home screen returns home`() {
        val origin = origin(
            Resumed(launcher, "Launcher", 1_000L),
            Resumed(blocked, "MainActivity", 2_000L),
            Resumed(own, "com.focusguard.ui.GenericBlockNoticeActivity", 2_100L)
        )

        assertThat(origin).isEqualTo(Destination.Home)
    }

    @Test
    fun `entry from another app returns to that app`() {
        val origin = origin(
            Resumed(launcher, "Launcher", 500L),
            Resumed("com.whatsapp", "HomeActivity", 1_000L),
            Resumed(blocked, "DeepLinkActivity", 2_000L),
            Resumed(blocked, "MainActivity", 2_050L)
        )

        assertThat(origin).isEqualTo(Destination.App("com.whatsapp"))
    }

    @Test
    fun `system screens and block screens are not a place to return to`() {
        val origin = origin(
            Resumed("com.android.chrome", "ChromeTabbedActivity", 1_000L),
            Resumed("com.android.intentresolver", "ChooserActivity", 1_500L),
            Resumed(own, "com.focusguard.ui.UsageImpactActivity", 1_600L),
            Resumed(blocked, "MainActivity", 2_000L)
        )

        assertThat(origin).isEqualTo(Destination.App("com.android.chrome"))
    }

    @Test
    fun `entry from the HardBlock main screen returns to HardBlock`() {
        val origin = origin(
            Resumed(own, ownMain, 1_000L),
            Resumed(blocked, "MainActivity", 2_000L)
        )

        assertThat(origin).isEqualTo(Destination.FocusGuard)
    }

    @Test
    fun `the latest entry decides and later events are ignored`() {
        val origin = origin(
            Resumed("com.whatsapp", "HomeActivity", 1_000L),
            Resumed(blocked, "MainActivity", 1_500L),
            Resumed(launcher, "Launcher", 1_700L),
            Resumed(blocked, "MainActivity", 2_000L),
            // Depois da tentativa (fora da tolerância): já é outra coisa.
            Resumed("com.android.chrome", "ChromeTabbedActivity", 60_000L)
        )

        assertThat(origin).isEqualTo(Destination.Home)
    }

    @Test
    fun `without history it falls back home`() {
        assertThat(origin()).isEqualTo(Destination.Home)
    }

    @Test
    fun `returning to the same app again right away goes home instead`() {
        val app = Destination.App("com.whatsapp")
        val lastReturn = BlockReturnPolicy.LastReturn(blocked, app, atMs = 10_000L)

        assertThat(
            BlockReturnPolicy.applyLoopGuard(app, blocked, lastReturn, nowMs = 14_000L)
        ).isEqualTo(Destination.Home)
        assertThat(
            BlockReturnPolicy.applyLoopGuard(
                app,
                blocked,
                lastReturn,
                nowMs = 10_000L + BlockReturnPolicy.LOOP_GUARD_MILLIS + 1L
            )
        ).isEqualTo(app)
        assertThat(
            BlockReturnPolicy.applyLoopGuard(app, "com.other", lastReturn, nowMs = 14_000L)
        ).isEqualTo(app)
    }

    private fun origin(vararg resumed: Resumed): Destination =
        BlockReturnPolicy.originBefore(
            resumedActivities = resumed.toList(),
            blockedPackage = blocked,
            attemptAtMs = 2_000L,
            ownPackage = own,
            ownMainActivity = ownMain,
            isHomeScreen = { it == launcher },
            isLaunchable = { it in launchable }
        )
}
