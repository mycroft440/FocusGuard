package com.focusguard.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppEntryLockPolicyTest {

    @Test
    fun `requires password when password session is active and credential exists`() {
        assertTrue(
            AppEntryLockPolicy.requiresPassword(
                hasMasterCredential = true,
                activeSessionTypes = listOf("PASSWORD")
            )
        )
    }

    @Test
    fun `does not lock app when password exists without active password session`() {
        assertFalse(
            AppEntryLockPolicy.requiresPassword(
                hasMasterCredential = true,
                activeSessionTypes = emptyList()
            )
        )
    }

    @Test
    fun `does not lock app for other active session types`() {
        assertFalse(
            AppEntryLockPolicy.requiresPassword(
                hasMasterCredential = true,
                activeSessionTypes = listOf("TIME", "POMODORO")
            )
        )
    }

    @Test
    fun `does not lock app without a stored credential`() {
        assertFalse(
            AppEntryLockPolicy.requiresPassword(
                hasMasterCredential = false,
                activeSessionTypes = listOf("PASSWORD")
            )
        )
    }

    @Test
    fun `matches password session type without case sensitivity`() {
        assertTrue(
            AppEntryLockPolicy.requiresPassword(
                hasMasterCredential = true,
                activeSessionTypes = listOf("password")
            )
        )
    }

    @Test
    fun `locks FocusGuard for a password-enabled app limit`() {
        assertTrue(
            AppEntryLockPolicy.requiresPassword(
                hasMasterCredential = true,
                activeSessionTypes = emptyList(),
                hasPasswordProtectedAppLimit = true
            )
        )
    }

    @Test
    fun `locks FocusGuard for a password-enabled website limit`() {
        assertTrue(
            AppEntryLockPolicy.requiresPassword(
                hasMasterCredential = true,
                activeSessionTypes = emptyList(),
                hasPasswordProtectedWebsiteLimit = true
            )
        )
    }

    @Test
    fun `passwordless limits do not lock FocusGuard entry`() {
        assertFalse(
            AppEntryLockPolicy.requiresPassword(
                hasMasterCredential = true,
                activeSessionTypes = emptyList()
            )
        )
    }
}
