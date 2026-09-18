package com.focusguard.security

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoveAllBlocksAuthorizationPolicyTest {

    @Test
    fun `allows removal without extra credential when time and usage protections are absent`() {
        assertEquals(
            RemoveAllBlocksAuthorizationPolicy.Gate.ALLOW,
            RemoveAllBlocksAuthorizationPolicy.evaluate(
                hasActiveTimeProtection = false,
                hasActiveUsageLimit = false,
                appEntryCredentialAuthenticated = false
            )
        )
    }

    @Test
    fun `requires master credential when time protection is active`() {
        assertEquals(
            RemoveAllBlocksAuthorizationPolicy.Gate.REQUIRE_MASTER_CREDENTIAL,
            RemoveAllBlocksAuthorizationPolicy.evaluate(
                hasActiveTimeProtection = true,
                hasActiveUsageLimit = false,
                appEntryCredentialAuthenticated = false
            )
        )
    }

    @Test
    fun `requires master credential when usage limit is active`() {
        assertEquals(
            RemoveAllBlocksAuthorizationPolicy.Gate.REQUIRE_MASTER_CREDENTIAL,
            RemoveAllBlocksAuthorizationPolicy.evaluate(
                hasActiveTimeProtection = false,
                hasActiveUsageLimit = true,
                appEntryCredentialAuthenticated = false
            )
        )
    }

    @Test
    fun `verified app entry credential authorizes active time protection`() {
        assertEquals(
            RemoveAllBlocksAuthorizationPolicy.Gate.ALLOW,
            RemoveAllBlocksAuthorizationPolicy.evaluate(
                hasActiveTimeProtection = true,
                hasActiveUsageLimit = false,
                appEntryCredentialAuthenticated = true
            )
        )
    }

    @Test
    fun `verified app entry credential authorizes active usage limit`() {
        assertEquals(
            RemoveAllBlocksAuthorizationPolicy.Gate.ALLOW,
            RemoveAllBlocksAuthorizationPolicy.evaluate(
                hasActiveTimeProtection = false,
                hasActiveUsageLimit = true,
                appEntryCredentialAuthenticated = true
            )
        )
    }

    @Test
    fun `verified app entry credential authorizes combined time and usage protections`() {
        assertEquals(
            RemoveAllBlocksAuthorizationPolicy.Gate.ALLOW,
            RemoveAllBlocksAuthorizationPolicy.evaluate(
                hasActiveTimeProtection = true,
                hasActiveUsageLimit = true,
                appEntryCredentialAuthenticated = true
            )
        )
    }
}
