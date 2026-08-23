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
    fun `target snapshot survives service recreation without waiting for room`() {
        assertThat(
            SelfProtectionStateStore.setSnapshot(
                context = context,
                armed = true,
                blockedApps = setOf("com.example.one", "com.example.two"),
                blockedSites = setOf("example.com"),
                strictPomodoro = true
            )
        ).isTrue()

        val snapshot = SelfProtectionStateStore.read(context)
        assertThat(snapshot.armed).isTrue()
        assertThat(snapshot.blockedApps)
            .containsExactly("com.example.one", "com.example.two")
        assertThat(snapshot.blockedSites).containsExactly("example.com")
        assertThat(snapshot.strictPomodoro).isTrue()
    }

    @Test
    fun `disarming removes stale targets from the synchronous snapshot`() {
        SelfProtectionStateStore.setSnapshot(
            context = context,
            armed = true,
            blockedApps = setOf("com.example.blocked"),
            blockedSites = setOf("blocked.example"),
            strictPomodoro = true
        )

        assertThat(SelfProtectionStateStore.setArmed(context, false)).isTrue()

        val snapshot = SelfProtectionStateStore.read(context)
        assertThat(snapshot.armed).isFalse()
        assertThat(snapshot.blockedApps).isEmpty()
        assertThat(snapshot.blockedSites).isEmpty()
        assertThat(snapshot.strictPomodoro).isFalse()
    }

    @Test
    fun `inactive snapshot never exposes targets even if caller supplies them`() {
        assertThat(
            SelfProtectionStateStore.setSnapshot(
                context = context,
                armed = false,
                blockedApps = setOf("com.example.stale"),
                blockedSites = setOf("stale.example"),
                strictPomodoro = true
            )
        ).isTrue()

        val snapshot = SelfProtectionStateStore.read(context)
        assertThat(snapshot.armed).isFalse()
        assertThat(snapshot.blockedApps).isEmpty()
        assertThat(snapshot.blockedSites).isEmpty()
        assertThat(snapshot.strictPomodoro).isFalse()
    }

    @Test
    fun `snapshot is available from device protected storage before unlock`() {
        assertThat(SelfProtectionStateStore.usesDeviceProtectedStorage(context)).isTrue()
    }
}
