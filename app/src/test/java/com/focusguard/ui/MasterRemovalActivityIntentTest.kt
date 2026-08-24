package com.focusguard.ui

import android.content.Intent
import android.provider.Settings
import com.focusguard.service.BlockingAccessibilityService
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MasterRemovalActivityIntentTest {

    @Test
    fun `app info accessibility and device admin reset Settings before credential`() {
        assertThat(
            MasterRemovalActivity.shouldResetSettingsTaskBeforeCredential(
                MasterRemovalActivity.Target.APP_INFO
            )
        ).isTrue()
        assertThat(
            MasterRemovalActivity.shouldResetSettingsTaskBeforeCredential(
                MasterRemovalActivity.Target.ACCESSIBILITY
            )
        ).isTrue()
        assertThat(
            MasterRemovalActivity.shouldResetSettingsTaskBeforeCredential(
                MasterRemovalActivity.Target.DEVICE_ADMIN
            )
        ).isTrue()
        assertThat(
            MasterRemovalActivity.shouldResetSettingsTaskBeforeCredential(
                MasterRemovalActivity.Target.UNINSTALL
            )
        ).isFalse()
    }

    @Test
    fun `Settings reset clears the previous task and starts at root`() {
        val intent = MasterRemovalActivity.createSettingsTaskResetIntent()

        assertThat(intent.action).isEqualTo(Settings.ACTION_SETTINGS)
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK).isNotEqualTo(0)
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NO_ANIMATION).isNotEqualTo(0)
    }

    @Test
    fun `cancel returns to home instead of protected Settings`() {
        val intent = MasterRemovalActivity.createHomeIntent()

        assertThat(intent.action).isEqualTo(Intent.ACTION_MAIN)
        assertThat(intent.categories).contains(Intent.CATEGORY_HOME)
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP).isNotEqualTo(0)
    }

    @Test
    fun `master gate intent carries the curtain generation handshake`() {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val intent = MasterRemovalActivity.createIntent(
            context,
            MasterRemovalActivity.Target.ACCESSIBILITY,
            curtainGeneration = 73L
        )

        assertThat(
            intent.getLongExtra(BlockingAccessibilityService.EXTRA_CURTAIN_GENERATION, 0L)
        ).isEqualTo(73L)
    }
}
