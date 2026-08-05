package com.focusguard.admin

import android.content.Context
import android.content.Intent
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The legacy Device Admin warning must never claim FocusGuard can stop Android
 * from revoking a consumer administrator — Google Play treats that claim as
 * misuse of the Device Admin API.
 *
 * The locale is pinned per test on purpose. This suite used to assert Portuguese
 * text without pinning it, which only passed because `values-en` had no
 * translation for the string and silently fell back to the default resources.
 * Translating it broke the test without the guarantee itself changing, so the
 * invariant is now checked in every shipped locale.
 */
@RunWith(RobolectricTestRunner::class)
class FocusGuardDeviceAdminReceiverTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication().applicationContext

    private fun disableWarning(): String =
        FocusGuardDeviceAdminReceiver().onDisableRequested(context, Intent()).toString()

    @Test
    @Config(sdk = [34], qualifiers = "pt-rBR")
    fun `Portuguese warning admits Android can disable the legacy admin`() {
        val warning = disableWarning()

        assertThat(warning).contains("pode ser desativado pelo Android")
        assertThat(warning).contains("Device Owner")
        assertThat(warning).doesNotContain("impedir a desativação")
    }

    @Test
    @Config(sdk = [34], qualifiers = "en")
    fun `English warning admits Android can disable the legacy admin`() {
        val warning = disableWarning()

        assertThat(warning).contains("can be disabled by Android")
        assertThat(warning).contains("Device Owner")
        assertThat(warning).doesNotContain("prevent")
    }

    @Test
    @Config(sdk = [34], qualifiers = "pt-rBR")
    fun `Portuguese warning points to Device Owner as the strong path`() {
        assertThat(disableWarning()).contains("proteção forte")
    }

    @Test
    @Config(sdk = [34], qualifiers = "en")
    fun `English warning points to Device Owner as the strong path`() {
        assertThat(disableWarning()).contains("strong protection")
    }
}
