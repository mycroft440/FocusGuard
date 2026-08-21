package com.focusguard.admin

import android.Manifest
import android.app.admin.DeviceAdminInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.os.UserManager
import com.focusguard.receiver.BootReceiver
import com.focusguard.receiver.DirectBootReceiver
import com.focusguard.security.DeactivationCredentialManager
import com.focusguard.service.BlockingAccessibilityService
import com.google.common.truth.Truth.assertThat
import dagger.Lazy
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceOwnerManagerTest {

    private val context: Context = RuntimeEnvironment.getApplication().applicationContext
    private val access = DeviceOwnerPolicyAccess(context)
    private val appController = DeviceOwnerAppController(context, access)
    private val webController = DeviceOwnerWebPolicyController(context, access)
    private val manager = DeviceOwnerManager(
        context,
        Lazy { DeactivationCredentialManager(context) },
        access,
        appController,
        webController,
        DeviceOwnerShieldController(context, access, appController, webController)
    )

    @Test
    fun `activation intent targets the declared admin without starting a detached task`() {
        val intent = manager.createDeviceAdminActivationIntent()

        @Suppress("DEPRECATION")
        val requestedAdmin = intent.getParcelableExtra<ComponentName>(
            DevicePolicyManager.EXTRA_DEVICE_ADMIN
        )

        assertThat(intent.action).isEqualTo(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        assertThat(requestedAdmin)
            .isEqualTo(FocusGuardDeviceAdminReceiver.getComponentName(context))
        assertThat(intent.getStringExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION)).isNotEmpty()
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isEqualTo(0)
    }

    @Test
    fun `manifest exposes a parseable admin with only the policy the app uses`() {
        val component = FocusGuardDeviceAdminReceiver.getComponentName(context)
        @Suppress("DEPRECATION")
        val receiverInfo = context.packageManager.getReceiverInfo(
            component,
            PackageManager.GET_META_DATA
        )
        val adminInfo = DeviceAdminInfo(
            context,
            ResolveInfo().apply { activityInfo = receiverInfo }
        )

        assertThat(receiverInfo.exported).isTrue()
        assertThat(receiverInfo.permission).isEqualTo(Manifest.permission.BIND_DEVICE_ADMIN)
        assertThat(adminInfo.usesPolicy(DeviceAdminInfo.USES_POLICY_FORCE_LOCK)).isTrue()
        assertThat(adminInfo.usesPolicy(DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD)).isFalse()
        assertThat(adminInfo.usesPolicy(DeviceAdminInfo.USES_POLICY_DISABLE_CAMERA)).isFalse()
        assertThat(adminInfo.usesPolicy(DeviceAdminInfo.USES_POLICY_DISABLE_KEYGUARD_FEATURES))
            .isFalse()
    }

    @Test
    fun `android 13 legacy cleanup knows every old global app restriction`() {
        val restrictions = DeviceOwnerManager.legacyGlobalAppControlRestrictionsForSdk(33)

        assertThat(restrictions).contains(UserManager.DISALLOW_UNINSTALL_APPS)
        assertThat(restrictions).contains(UserManager.DISALLOW_APPS_CONTROL)
        assertThat(restrictions).doesNotContain(UserManager.DISALLOW_GRANT_ADMIN)
    }

    @Test
    fun `android 14 legacy cleanup also removes grant admin restriction`() {
        val restrictions = DeviceOwnerManager.legacyGlobalAppControlRestrictionsForSdk(34)

        assertThat(restrictions).contains(UserManager.DISALLOW_GRANT_ADMIN)
    }

    @Test
    fun `android 10 adult filter policy prevents private dns changes`() {
        val restrictions = DeviceOwnerManager.adultContentRestrictionsForSdk(29)

        assertThat(restrictions).containsExactly(
            UserManager.DISALLOW_CONFIG_VPN,
            UserManager.DISALLOW_CONFIG_PRIVATE_DNS
        ).inOrder()
    }

    @Test
    fun `android 9 blocks vpn but not unsupported private dns settings`() {
        val restrictions = DeviceOwnerManager.adultContentRestrictionsForSdk(28)

        assertThat(restrictions).containsExactly(UserManager.DISALLOW_CONFIG_VPN)
    }

    @Test
    fun `full shield keeps critical restrictions`() {
        val restrictions = DeviceOwnerManager.allShieldRestrictionsForSdk(34)

        assertThat(restrictions).contains(UserManager.DISALLOW_FACTORY_RESET)
        assertThat(restrictions).contains(UserManager.DISALLOW_SAFE_BOOT)
        assertThat(restrictions).contains(UserManager.DISALLOW_CONFIG_DATE_TIME)
        assertThat(restrictions).doesNotContain(UserManager.DISALLOW_UNINSTALL_APPS)
        assertThat(restrictions).doesNotContain(UserManager.DISALLOW_APPS_CONTROL)
        assertThat(restrictions).doesNotContain(UserManager.DISALLOW_GRANT_ADMIN)
        assertThat(restrictions).contains(UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
        assertThat(restrictions).doesNotContain(UserManager.DISALLOW_DEBUGGING_FEATURES)
        assertThat(restrictions).doesNotContain(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
        assertThat(restrictions).doesNotContain(UserManager.DISALLOW_INSTALL_APPS)
        assertThat(restrictions).doesNotContain(UserManager.DISALLOW_USB_FILE_TRANSFER)
    }

    @Test
    fun `revocation cleanup still includes restrictions left by older builds`() {
        val restrictions = DeviceOwnerManager.allRestrictionsForCleanupForSdk(34)

        assertThat(restrictions).contains(UserManager.DISALLOW_UNINSTALL_APPS)
        assertThat(restrictions).contains(UserManager.DISALLOW_APPS_CONTROL)
        assertThat(restrictions).contains(UserManager.DISALLOW_GRANT_ADMIN)
    }

    @Test
    fun `android 8 active block isolates additional users without restricted features`() {
        assertThat(DeviceOwnerManager.activeBlockRestrictionsForSdk(27)).containsExactly(
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_REMOVE_USER
        ).inOrder()
    }

    @Test
    fun `android 9 active block also prevents switching users`() {
        assertThat(DeviceOwnerManager.activeBlockRestrictionsForSdk(28)).containsExactly(
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_REMOVE_USER,
            UserManager.DISALLOW_USER_SWITCH
        ).inOrder()
    }

    @Test
    fun `strict focus lockdown requires android 9 and keeps only global actions`() {
        assertThat(DeviceOwnerManager.supportsStrictFocusModeLockdown(27)).isFalse()
        assertThat(DeviceOwnerManager.supportsStrictFocusModeLockdown(28)).isTrue()
        assertThat(
            DeviceOwnerManager.lockTaskFeaturesKeepOnlyGlobalActions(
                DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
            )
        ).isTrue()
        assertThat(
            DeviceOwnerManager.lockTaskFeaturesKeepOnlyGlobalActions(
                DevicePolicyManager.LOCK_TASK_FEATURE_NONE
            )
        ).isFalse()
        assertThat(
            DeviceOwnerManager.lockTaskFeaturesKeepOnlyGlobalActions(
                DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or
                    DevicePolicyManager.LOCK_TASK_FEATURE_HOME
            )
        ).isFalse()
    }

    @Test
    fun `android 15 active block also prevents private profiles`() {
        assertThat(DeviceOwnerManager.activeBlockRestrictionsForSdk(35)).containsExactly(
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_REMOVE_USER,
            UserManager.DISALLOW_USER_SWITCH,
            UserManager.DISALLOW_ADD_PRIVATE_PROFILE
        ).inOrder()
    }

    @Test
    fun `policy state is available before the first unlock`() {
        assertThat(manager.usesDeviceProtectedPolicyState()).isTrue()
    }

    @Test
    fun `direct boot fails closed after interrupted maintenance`() {
        assertThat(
            DeviceOwnerManager.shouldRestoreActiveBlockAtDirectBoot(
                blockingProtectionArmed = false,
                interruptedMaintenance = true
            )
        ).isTrue()
        assertThat(
            DeviceOwnerManager.shouldRestoreActiveBlockAtDirectBoot(
                blockingProtectionArmed = false,
                interruptedMaintenance = false
            )
        ).isFalse()
    }

    @Test
    fun `direct boot restores adult dns from either persisted source`() {
        assertThat(
            DeviceOwnerManager.shouldRestoreAdultDnsAtDirectBoot(
                adultContentProtectionArmed = true,
                pornographyCategoryActive = false
            )
        ).isTrue()
        assertThat(
            DeviceOwnerManager.shouldRestoreAdultDnsAtDirectBoot(
                adultContentProtectionArmed = false,
                pornographyCategoryActive = true
            )
        ).isTrue()
        assertThat(
            DeviceOwnerManager.shouldRestoreAdultDnsAtDirectBoot(
                adultContentProtectionArmed = false,
                pornographyCategoryActive = false
            )
        ).isFalse()
    }

    @Test
    fun `only the native boot receiver runs before credential storage unlocks`() {
        val bootReceiver = context.packageManager.getReceiverInfo(
            ComponentName(context, BootReceiver::class.java),
            0
        )
        val directBootReceiver = context.packageManager.getReceiverInfo(
            ComponentName(context, DirectBootReceiver::class.java),
            0
        )
        val accessibilityService = context.packageManager.getServiceInfo(
            ComponentName(context, BlockingAccessibilityService::class.java),
            0
        )

        assertThat(bootReceiver.directBootAware).isFalse()
        assertThat(directBootReceiver.directBootAware).isTrue()
        assertThat(accessibilityService.directBootAware).isFalse()
    }

    @Test
    fun `adult filter forces managed browser to use system dns`() {
        val restrictions = DeviceOwnerManager.buildManagedBrowserRestrictions(
            existing = Bundle().apply {
                putString("DnsOverHttpsMode", "secure")
                putString("DnsOverHttpsTemplates", "https://bypass.example/dns-query")
            },
            managedFilters = emptyList(),
            privateModePolicy = "IncognitoModeAvailability",
            requireSystemDns = true
        )

        assertThat(restrictions.getString("DnsOverHttpsMode")).isEqualTo("off")
        assertThat(restrictions.containsKey("DnsOverHttpsTemplates")).isFalse()
    }

    @Test
    fun `pornography category requires the same dns protection as global filter`() {
        assertThat(DeviceOwnerManager.requiresAdultDns(false, false)).isFalse()
        assertThat(DeviceOwnerManager.requiresAdultDns(true, false)).isTrue()
        assertThat(DeviceOwnerManager.requiresAdultDns(false, true)).isTrue()
        assertThat(DeviceOwnerManager.ADULT_DNS_HOST)
            .isEqualTo("family-filter-dns.cleanbrowsing.org")
    }

    @Test
    fun `disabling adult filter removes only focusguard dns browser policy`() {
        val restrictions = DeviceOwnerManager.buildManagedBrowserRestrictions(
            existing = Bundle().apply {
                putString("UnrelatedPolicy", "preserved")
                putString("DnsOverHttpsMode", "off")
            },
            managedFilters = emptyList(),
            privateModePolicy = "IncognitoModeAvailability",
            requireSystemDns = false
        )

        assertThat(restrictions.containsKey("DnsOverHttpsMode")).isFalse()
        assertThat(restrictions.getString("UnrelatedPolicy")).isEqualTo("preserved")
    }
}
