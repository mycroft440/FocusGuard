package com.focusguard.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SensitivePermissionsConsentTest {

    @Test
    fun `current disclosure version is accepted`() {
        assertThat(
            SensitivePermissionsConsent.isAcceptedVersion(
                SensitivePermissionsConsent.CURRENT_VERSION
            )
        ).isTrue()
    }

    @Test
    fun `older disclosure version requires consent again`() {
        assertThat(
            SensitivePermissionsConsent.isAcceptedVersion(
                SensitivePermissionsConsent.CURRENT_VERSION - 1
            )
        ).isFalse()
    }

    @Test
    fun `future stored version remains accepted`() {
        assertThat(
            SensitivePermissionsConsent.isAcceptedVersion(
                SensitivePermissionsConsent.CURRENT_VERSION + 1
            )
        ).isTrue()
    }
}
