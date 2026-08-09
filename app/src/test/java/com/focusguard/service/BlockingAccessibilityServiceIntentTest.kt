package com.focusguard.service

import android.content.Intent
import android.provider.Settings
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Este teste valida apenas a composicao das flags do Intent. O Robolectric
// 4.13 usado pelo projeto fornece o ambiente ate o SDK 34; forcar o SDK 35
// fazia o runner falhar antes de executar qualquer uma das assercoes.
@Config(sdk = [34])
class BlockingAccessibilityServiceIntentTest {

    @Test
    fun `settings reset intent discards old screens confirmations and history`() {
        val intent = BlockingAccessibilityService.createSettingsTaskResetIntent()

        assertThat(intent.action).isEqualTo(Settings.ACTION_SETTINGS)
        assertThat(intent.hasFlag(Intent.FLAG_ACTIVITY_NEW_TASK)).isTrue()
        assertThat(intent.hasFlag(Intent.FLAG_ACTIVITY_CLEAR_TASK)).isTrue()
        assertThat(intent.hasFlag(Intent.FLAG_ACTIVITY_CLEAR_TOP)).isTrue()
        assertThat(intent.hasFlag(Intent.FLAG_ACTIVITY_NO_HISTORY)).isTrue()
        assertThat(intent.hasFlag(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)).isTrue()
        assertThat(intent.hasFlag(Intent.FLAG_ACTIVITY_NO_ANIMATION)).isTrue()
    }

    private fun Intent.hasFlag(flag: Int): Boolean = (flags and flag) == flag
}
