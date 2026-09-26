package com.focusguard.admin

import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnknownSourcesSecurityManagerActivationFlowTest {

    private lateinit var context: Context
    private lateinit var manager: UnknownSourcesSecurityManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("extra_security", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        manager = UnknownSourcesSecurityManager.getInstance(context)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("extra_security", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `activation flow survives settings round trip until it is cleared`() {
        assertThat(manager.isActivationPending()).isFalse()
        assertThat(manager.isReadyToEnable()).isFalse()

        assertThat(manager.markSettingsReviewStarted()).isTrue()
        assertThat(manager.isActivationPending()).isTrue()
        assertThat(manager.isReadyToEnable()).isFalse()

        assertThat(manager.markReadyToEnable()).isTrue()
        assertThat(manager.isActivationPending()).isTrue()
        assertThat(manager.isReadyToEnable()).isTrue()

        assertThat(manager.clearActivationFlow()).isTrue()
        assertThat(manager.isActivationPending()).isFalse()
        assertThat(manager.isReadyToEnable()).isFalse()
    }
}
