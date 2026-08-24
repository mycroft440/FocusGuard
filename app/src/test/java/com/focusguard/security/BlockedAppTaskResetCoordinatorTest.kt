package com.focusguard.security

import android.content.Intent
import com.focusguard.ui.BlockNoticeActivity
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BlockedAppTaskResetCoordinatorTest {

    @Test
    fun `blocked third party package is reset exactly once`() {
        assertThat(
            BlockedAppTaskResetCoordinator.shouldResetBlockedPackage(
                blockedPackage = "com.example.blocked",
                focusGuardPackage = "com.focusguard.v2",
                alreadyReset = false
            )
        ).isTrue()
        assertThat(
            BlockedAppTaskResetCoordinator.shouldResetBlockedPackage(
                blockedPackage = "com.example.blocked",
                focusGuardPackage = "com.focusguard.v2",
                alreadyReset = true
            )
        ).isFalse()
    }

    @Test
    fun `FocusGuard and empty package are never reset`() {
        assertThat(
            BlockedAppTaskResetCoordinator.shouldResetBlockedPackage(
                blockedPackage = "com.focusguard.v2",
                focusGuardPackage = "com.focusguard.v2",
                alreadyReset = false
            )
        ).isFalse()
        assertThat(
            BlockedAppTaskResetCoordinator.shouldResetBlockedPackage(
                blockedPackage = "",
                focusGuardPackage = "com.focusguard.v2",
                alreadyReset = false
            )
        ).isFalse()
        assertThat(
            BlockedAppTaskResetCoordinator.shouldResetBlockedPackage(
                blockedPackage = null,
                focusGuardPackage = "com.focusguard.v2",
                alreadyReset = false
            )
        ).isFalse()
    }

    @Test
    fun `reset intent clears target task before relaunching root`() {
        val resetIntent = BlockedAppTaskResetCoordinator.prepareResetIntent(
            Intent(Intent.ACTION_MAIN).setPackage("com.example.blocked")
        )

        assertThat(resetIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
        assertThat(resetIntent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK).isNotEqualTo(0)
        assertThat(resetIntent.flags and Intent.FLAG_ACTIVITY_NO_ANIMATION).isNotEqualTo(0)
    }

    @Test
    fun `restore intent returns protected notice without another task reset`() {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val source = Intent().putExtra("payload", "kept")

        val restored = BlockedAppTaskResetCoordinator.prepareRestoreIntent(source, context)

        assertThat(restored.component?.className).isEqualTo(BlockNoticeActivity::class.java.name)
        assertThat(restored.getStringExtra("payload")).isEqualTo("kept")
        assertThat(
            restored.getBooleanExtra(
                BlockedAppTaskResetCoordinator.EXTRA_BLOCKED_TASK_RESET_DONE,
                false
            )
        ).isTrue()
        assertThat(restored.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
        assertThat(restored.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP).isNotEqualTo(0)
        assertThat(restored.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP).isNotEqualTo(0)
        assertThat(restored.flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS).isNotEqualTo(0)
    }

    @Test
    fun `fallback home intent removes blocked app from foreground`() {
        val home = BlockedAppTaskResetCoordinator.createHomeIntent()

        assertThat(home.action).isEqualTo(Intent.ACTION_MAIN)
        assertThat(home.categories).contains(Intent.CATEGORY_HOME)
        assertThat(home.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
        assertThat(home.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP).isNotEqualTo(0)
    }
}
