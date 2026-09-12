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
    fun `unknown boot identity never restores persisted maintenance`() {
        val remaining = DeviceOwnerMaintenanceGate.evaluateRemainingMillis(
            automaticDateTimeEnabled = true,
            nowElapsedMillis = 1_000L,
            deadlineElapsedMillis = 601_000L,
            storedBootCount = -1,
            currentBootCount = -1
        )

        assertThat(remaining).isEqualTo(0L)
    }

    @Test
    fun `unknown boot persists only non authorizing interruption marker`() {
        assertThat(
            DeviceOwnerMaintenanceGate.persistedAuthorizationDeadline(
                bootCount = -1,
                deadlineElapsedMillis = 601_000L
            )
        ).isEqualTo(0L)
        assertThat(
            DeviceOwnerMaintenanceGate.persistedAuthorizationDeadline(
                bootCount = 9,
                deadlineElapsedMillis = 601_000L
            )
        ).isEqualTo(601_000L)
    }

    @Test
    fun `direct boot consumes interruption marker before preload`() {
        assertThat(
            DeviceOwnerMaintenanceGate.shouldPreloadBeforeDirectBoot(userUnlocked = false)
        ).isFalse()
        assertThat(
            DeviceOwnerMaintenanceGate.shouldPreloadBeforeDirectBoot(userUnlocked = true)
        ).isTrue()
    }

    @Test
    fun `only known boot identity may persist maintenance authorization`() {
        assertThat(DeviceOwnerMaintenanceGate.canPersistAcrossProcess(0)).isTrue()
        assertThat(DeviceOwnerMaintenanceGate.canPersistAcrossProcess(9)).isTrue()
        assertThat(DeviceOwnerMaintenanceGate.canPersistAcrossProcess(-1)).isFalse()
        assertThat(DeviceOwnerMaintenanceGate.canPersistAcrossProcess(Int.MIN_VALUE)).isFalse()
    }

    @Test
    fun `android before 12 can always schedule exact maintenance expiry`() {
        assertThat(
            DeviceOwnerMaintenanceGate.canGuaranteeExactExpiry(
                sdkInt = 30,
                exactAlarmCapability = false
            )
        ).isTrue()
    }

    @Test
    fun `android 12 plus refuses maintenance without exact alarm capability`() {
        assertThat(
            DeviceOwnerMaintenanceGate.canGuaranteeExactExpiry(
                sdkInt = 31,
                exactAlarmCapability = false
            )
        ).isFalse()
        assertThat(
            DeviceOwnerMaintenanceGate.canGuaranteeExactExpiry(
                sdkInt = 35,
                exactAlarmCapability = false
            )
        ).isFalse()
    }

    @Test
    fun `android 12 plus accepts maintenance with exact alarm capability`() {
        assertThat(
            DeviceOwnerMaintenanceGate.canGuaranteeExactExpiry(
                sdkInt = 31,
                exactAlarmCapability = true
            )
        ).isTrue()
    }

    @Test
    fun `restored maintenance requires remaining time and exact expiry`() {
        assertThat(
            DeviceOwnerMaintenanceGate.shouldRestorePersistedMaintenance(
                remainingMillis = 60_000L,
                exactExpiryAvailable = true
            )
        ).isTrue()
        assertThat(
            DeviceOwnerMaintenanceGate.shouldRestorePersistedMaintenance(
                remainingMillis = 60_000L,
                exactExpiryAvailable = false
            )
        ).isFalse()
        assertThat(
            DeviceOwnerMaintenanceGate.shouldRestorePersistedMaintenance(
                remainingMillis = 0L,
                exactExpiryAvailable = true
            )
        ).isFalse()
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
