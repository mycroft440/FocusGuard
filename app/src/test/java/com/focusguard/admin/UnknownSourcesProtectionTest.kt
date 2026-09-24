package com.focusguard.admin

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.UserManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnknownSourcesProtectionTest {
    private class FakeBackend : UnknownSourcesProtection.Backend {
        var owner = true
        var rejectWrites = false
        var failReads = false
        var ignoreWrites = false
        val policies = mutableSetOf<String>()
        val writes = mutableListOf<String>()
        override fun isDeviceOwner() = owner
        override fun restrictions(): Set<String> {
            if (failReads) throw SecurityException("Unavailable")
            return policies.toSet()
        }
        override fun add(restriction: String) {
            if (rejectWrites) throw SecurityException("Denied")
            writes.add(restriction)
            if (!ignoreWrites) policies.add(restriction)
        }
        override fun clear(restriction: String) {
            if (rejectWrites) throw SecurityException("Denied")
            writes.add(restriction)
            if (!ignoreWrites) policies.remove(restriction)
        }
    }

    @Test
    fun ordinaryAdminCannotClaimOrApplyProtection() {
        val backend = FakeBackend().apply { owner = false }
        val protection = UnknownSourcesProtection(backend, 34)
        assertFalse(protection.inspect().enabled)
        assertEquals(UnknownSourcesProtection.Result.OWNER_REQUIRED, protection.setEnabled(true))
        assertTrue(backend.writes.isEmpty())
    }

    @Test
    fun androidEightAndNineUseOnlyTheCurrentUserRestriction() {
        listOf(26, 28).forEach { sdk ->
            val backend = FakeBackend()
            val protection = UnknownSourcesProtection(backend, sdk)
            assertEquals(UnknownSourcesProtection.Result.APPLIED, protection.setEnabled(true))
            assertEquals(setOf(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES), backend.policies)
            assertEquals(UnknownSourcesProtection.Scope.CURRENT_USER, protection.inspect().scope)
        }
    }

    @Test
    fun androidTenAndLaterUseTheDeviceWideRestriction() {
        listOf(29, 34, 36).forEach { sdk ->
            val backend = FakeBackend()
            val protection = UnknownSourcesProtection(backend, sdk)
            assertEquals(UnknownSourcesProtection.Result.APPLIED, protection.setEnabled(true))
            assertEquals(setOf(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY), backend.policies)
            assertEquals(UnknownSourcesProtection.Scope.ALL_USERS, protection.inspect().scope)
        }
    }

    @Test
    fun failedOrUnconfirmedWritesNeverReportSuccess() {
        listOf(
            FakeBackend().apply { rejectWrites = true },
            FakeBackend().apply { ignoreWrites = true },
            FakeBackend().apply { failReads = true }
        ).forEach { backend ->
            val protection = UnknownSourcesProtection(backend, 34)
            assertEquals(UnknownSourcesProtection.Result.FAILED, protection.setEnabled(true))
            assertFalse(protection.inspect().enabled)
        }
    }

    @Test
    fun disablingClearsLegacyAndGlobalPoliciesButPreservesUnrelatedRestrictions() {
        val backend = FakeBackend().apply {
            policies.addAll(listOf(
                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY,
                UserManager.DISALLOW_SAFE_BOOT
            ))
        }
        val protection = UnknownSourcesProtection(backend, 34)
        assertEquals(UnknownSourcesProtection.Result.APPLIED, protection.setEnabled(false))
        assertEquals(setOf(UserManager.DISALLOW_SAFE_BOOT), backend.policies)
        assertFalse(protection.inspect().enabled)
        assertFalse(backend.writes.contains(UserManager.DISALLOW_INSTALL_APPS))
        assertFalse(backend.writes.contains(UserManager.DISALLOW_DEBUGGING_FEATURES))
    }

    @Test
    fun failedDisableKeepsTheActualBlockedStatus() {
        val backend = FakeBackend()
        val protection = UnknownSourcesProtection(backend, 34)
        protection.setEnabled(true)
        backend.rejectWrites = true
        assertEquals(UnknownSourcesProtection.Result.FAILED, protection.setEnabled(false))
        assertTrue(protection.inspect().enabled)
    }

    @Test
    fun upgradedPerUserPolicyIsNotMisreportedAsDeviceWide() {
        val backend = FakeBackend().apply { policies.add(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES) }
        assertEquals(UnknownSourcesProtection.Scope.CURRENT_USER, UnknownSourcesProtection(backend, 34).inspect().scope)
    }

    @Test
    fun systemPolicySurvivesControllerRecreation() {
        val context: Context = RuntimeEnvironment.getApplication()
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        shadowOf(dpm).setDeviceOwner(FocusGuardDeviceAdminReceiver.getComponentName(context))
        val first = UnknownSourcesProtection(context)
        assertEquals(UnknownSourcesProtection.Result.APPLIED, first.setEnabled(true))
        val recreated = UnknownSourcesProtection(context)
        assertEquals(UnknownSourcesProtection.Scope.ALL_USERS, recreated.inspect().scope)
        assertEquals(UnknownSourcesProtection.Result.APPLIED, recreated.setEnabled(false))
        assertFalse(first.inspect().enabled)
    }

    @Test
    fun optionalRestrictionIsOnlyInRemovalCleanupNotTheAutomaticShield() {
        assertFalse(DeviceOwnerManager.allShieldRestrictionsForSdk(34)
            .contains(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY))
        assertTrue(DeviceOwnerManager.allRestrictionsForCleanupForSdk(34)
            .contains(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY))
        assertEquals(listOf(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES),
            UnknownSourcesProtection.restrictionsForCleanup(26))
    }
}
