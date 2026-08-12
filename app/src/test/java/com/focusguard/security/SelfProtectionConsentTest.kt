package com.focusguard.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SelfProtectionConsentTest {

    @Test
    fun `current term version is accepted`() {
        assertThat(
            SelfProtectionConsent.isAcceptedVersion(SelfProtectionConsent.CURRENT_VERSION)
        ).isTrue()
    }

    @Test
    fun `old term version requires a new affirmative action`() {
        assertThat(
            SelfProtectionConsent.isAcceptedVersion(SelfProtectionConsent.CURRENT_VERSION - 1)
        ).isFalse()
    }

    @Test
    fun `a future stored version remains accepted after downgrade`() {
        assertThat(
            SelfProtectionConsent.isAcceptedVersion(SelfProtectionConsent.CURRENT_VERSION + 1)
        ).isTrue()
    }
}
