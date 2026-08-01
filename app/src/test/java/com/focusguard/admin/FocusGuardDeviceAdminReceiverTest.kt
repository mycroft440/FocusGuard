package com.focusguard.admin

import android.content.Context
import android.content.Intent
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FocusGuardDeviceAdminReceiverTest {

    private val context: Context = RuntimeEnvironment.getApplication().applicationContext

    @Test
    fun `legacy admin warning never claims Android deactivation can be prevented`() {
        val warning = FocusGuardDeviceAdminReceiver()
            .onDisableRequested(context, Intent())
            .toString()

        assertThat(warning).contains("pode ser desativado pelo Android")
        assertThat(warning).contains("Device Owner")
        assertThat(warning).doesNotContain("impedir a desativação")
    }
}
