package com.focusguard.focusmode

import android.app.admin.DevicePolicyManager
import android.content.Intent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FocusModeHomeControllerTest {

    @Test
    fun `native home keeps only home and global power actions`() {
        val expected = DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
            DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS

        assertThat(FocusModeHomeController.requiredLockTaskFeatures()).isEqualTo(expected)
        assertThat(FocusModeHomeController.lockTaskFeaturesKeepHomeAndPower(expected)).isTrue()
        assertThat(
            FocusModeHomeController.lockTaskFeaturesKeepHomeAndPower(
                expected or DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW
            )
        ).isFalse()
    }

    @Test
    fun `temporary home filter is a real default home intent`() {
        val filter = FocusModeHomeController.homeIntentFilter()

        assertThat(filter.hasAction(Intent.ACTION_MAIN)).isTrue()
        assertThat(filter.hasCategory(Intent.CATEGORY_HOME)).isTrue()
        assertThat(filter.hasCategory(Intent.CATEGORY_DEFAULT)).isTrue()
    }
}
