package com.focusguard.security

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DevelopmentUninstallCoordinatorTest {

    @Test
    fun `uninstall is handed to Android for the current package`() {
        val context = RuntimeEnvironment.getApplication()

        val intent = DevelopmentUninstallCoordinator.createUninstallIntent(context)

        assertThat(intent.action).isEqualTo(Intent.ACTION_DELETE)
        assertThat(intent.data?.scheme).isEqualTo("package")
        assertThat(intent.data?.schemeSpecificPart).isEqualTo(context.packageName)
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
    }
}
