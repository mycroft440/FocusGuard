package com.focusguard.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeviceOwnerMaintenanceGateTest {

    @Test
    fun `monthly window opens exactly at 02 50 on day 15`() {
        assertThat(
            DeviceOwnerMaintenanceGate.isWithinMonthlyWindow(
                dayOfMonth = 15,
                hourOfDay = 2,
                minute = 50
            )
        ).isTrue()
    }

    @Test
    fun `monthly window remains open until 02 59`() {
        assertThat(
            DeviceOwnerMaintenanceGate.isWithinMonthlyWindow(
                dayOfMonth = 15,
                hourOfDay = 2,
                minute = 59
            )
        ).isTrue()
    }

    @Test
    fun `monthly window closes at 03 00`() {
        assertThat(
            DeviceOwnerMaintenanceGate.isWithinMonthlyWindow(
                dayOfMonth = 15,
                hourOfDay = 3,
                minute = 0
            )
        ).isFalse()
    }

    @Test
    fun `monthly window is unavailable on another day`() {
        assertThat(
            DeviceOwnerMaintenanceGate.isWithinMonthlyWindow(
                dayOfMonth = 14,
                hourOfDay = 2,
                minute = 55
            )
        ).isFalse()
    }

    @Test
    fun `remaining duration uses monotonic deadline`() {
        val remaining = DeviceOwnerMaintenanceGate.evaluateRemainingMillis(
            automaticDateTimeEnabled = true,
            nowElapsedMillis = 1_000L,
            deadlineElapsedMillis = 601_000L,
            storedBootCount = 9,
            currentBootCount = 9
        )

        assertThat(remaining).isEqualTo(600_000L)
    }

    @Test
    fun `manual date time invalidates maintenance`() {
        val remaining = DeviceOwnerMaintenanceGate.evaluateRemainingMillis(
            automaticDateTimeEnabled = false,
            nowElapsedMillis = 1_000L,
            deadlineElapsedMillis = 601_000L,
            storedBootCount = 9,
            currentBootCount = 9
        )

        assertThat(remaining).isEqualTo(0L)
    }

    @Test
    fun `reboot invalidates maintenance`() {
        val remaining = DeviceOwnerMaintenanceGate.evaluateRemainingMillis(
            automaticDateTimeEnabled = true,
            nowElapsedMillis = 1_000L,
            deadlineElapsedMillis = 601_000L,
            storedBootCount = 9,
            currentBootCount = 10
        )

        assertThat(remaining).isEqualTo(0L)
    }

    @Test
    fun `expired deadline returns zero`() {
        val remaining = DeviceOwnerMaintenanceGate.evaluateRemainingMillis(
            automaticDateTimeEnabled = true,
            nowElapsedMillis = 700_000L,
            deadlineElapsedMillis = 601_000L,
            storedBootCount = 9,
            currentBootCount = 9
        )

        assertThat(remaining).isEqualTo(0L)
    }
}
