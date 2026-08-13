package com.focusguard.security

import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SelfProtectionStateStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication().applicationContext
        SelfProtectionStateStore.setArmed(context, false)
    }

    @Test
    fun `snapshot defaults to disarmed and persists both transitions synchronously`() {
        assertThat(SelfProtectionStateStore.isArmed(context)).isFalse()

        assertThat(SelfProtectionStateStore.setArmed(context, true)).isTrue()
        assertThat(SelfProtectionStateStore.isArmed(context)).isTrue()

        assertThat(SelfProtectionStateStore.setArmed(context, false)).isTrue()
        assertThat(SelfProtectionStateStore.isArmed(context)).isFalse()
    }

    @Test
    fun `snapshot is available from device protected storage before unlock`() {
        assertThat(SelfProtectionStateStore.usesDeviceProtectedStorage(context)).isTrue()
    }
}
