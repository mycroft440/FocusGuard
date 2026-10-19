package com.focusguard.utils

import android.content.Context
import android.content.pm.PackageInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class XiaomiBackgroundSettingsTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun `xiaomi redmi and poco devices are recognized`() {
        assertThat(XiaomiBackgroundSettings.isXiaomiDevice("Xiaomi", "Xiaomi")).isTrue()
        assertThat(XiaomiBackgroundSettings.isXiaomiDevice("Xiaomi", "Redmi")).isTrue()
        assertThat(XiaomiBackgroundSettings.isXiaomiDevice("Xiaomi", "POCO")).isTrue()
        assertThat(XiaomiBackgroundSettings.isXiaomiDevice("XIAOMI", "")).isTrue()
        assertThat(XiaomiBackgroundSettings.isXiaomiDevice(null, "redmi")).isTrue()
        assertThat(XiaomiBackgroundSettings.isXiaomiDevice("unknown", "poco")).isTrue()
    }

    @Test
    fun `other manufacturers are not xiaomi`() {
        assertThat(XiaomiBackgroundSettings.isXiaomiDevice("samsung", "samsung")).isFalse()
        assertThat(XiaomiBackgroundSettings.isXiaomiDevice("Google", "google")).isFalse()
        assertThat(XiaomiBackgroundSettings.isXiaomiDevice("motorola", "motorola")).isFalse()
        assertThat(XiaomiBackgroundSettings.isXiaomiDevice(null, null)).isFalse()
    }

    @Test
    fun `without xiaomi brand or system apps the shortcuts stay hidden`() {
        assertThat(XiaomiBackgroundSettings.isXiaomiSystem(context)).isFalse()
    }

    @Test
    fun `miui or hyperos system apps reveal the shortcuts on any brand`() {
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply { packageName = "com.miui.securitycenter" }
        )

        assertThat(XiaomiBackgroundSettings.isXiaomiSystem(context)).isTrue()
    }

    @Test
    fun `the battery keeper alone also reveals the shortcuts`() {
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply { packageName = "com.miui.powerkeeper" }
        )

        assertThat(XiaomiBackgroundSettings.isXiaomiSystem(context)).isTrue()
    }
}
