package com.focusguard.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeviceAdminActivationWindowTest {
    @Test
    fun authorizationRequiresSameBootActiveDeadlineAndInactiveAdministrator() {
        assertThat(
            DeviceAdminActivationWindow.evaluate(
                nowElapsedMillis = 1_000L,
                deadlineElapsedMillis = 2_000L,
                storedBootCount = 7,
                currentBootCount = 7,
                deviceAdminActive = false
            )
        ).isTrue()

        assertThat(
            DeviceAdminActivationWindow.evaluate(
                nowElapsedMillis = 2_000L,
                deadlineElapsedMillis = 2_000L,
                storedBootCount = 7,
                currentBootCount = 7,
                deviceAdminActive = false
            )
        ).isFalse()

        assertThat(
            DeviceAdminActivationWindow.evaluate(
                nowElapsedMillis = 1_000L,
                deadlineElapsedMillis = 2_000L,
                storedBootCount = 7,
                currentBootCount = 8,
                deviceAdminActive = false
            )
        ).isFalse()

        assertThat(
            DeviceAdminActivationWindow.evaluate(
                nowElapsedMillis = 1_000L,
                deadlineElapsedMillis = 2_000L,
                storedBootCount = 7,
                currentBootCount = 7,
                deviceAdminActive = true
            )
        ).isFalse()
    }

    @Test
    fun unknownBootIdentityNeverAuthorizesPersistedEnrollmentWindow() {
        assertThat(
            DeviceAdminActivationWindow.evaluate(
                nowElapsedMillis = 1_000L,
                deadlineElapsedMillis = 2_000L,
                storedBootCount = -1,
                currentBootCount = -1,
                deviceAdminActive = false
            )
        ).isFalse()
        assertThat(
            DeviceAdminActivationWindow.evaluate(
                nowElapsedMillis = 1_000L,
                deadlineElapsedMillis = 2_000L,
                storedBootCount = Int.MIN_VALUE,
                currentBootCount = Int.MIN_VALUE,
                deviceAdminActive = false
            )
        ).isFalse()
    }

    @Test
    fun onlyKnownBootIdentityMayPersistEnrollmentAuthorization() {
        assertThat(DeviceAdminActivationWindow.canPersistAcrossProcess(0)).isTrue()
        assertThat(DeviceAdminActivationWindow.canPersistAcrossProcess(12)).isTrue()
        assertThat(DeviceAdminActivationWindow.canPersistAcrossProcess(-1)).isFalse()
        assertThat(DeviceAdminActivationWindow.canPersistAcrossProcess(Int.MIN_VALUE)).isFalse()
    }
}
