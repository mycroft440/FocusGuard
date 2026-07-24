package com.focusguard.admin

import android.Manifest
import android.app.admin.DeviceAdminInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.UserManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceOwnerManagerTest {

    private val context: Context = RuntimeEnvironment.getApplication().applicationContext
    private val manager = DeviceOwnerManager.getInstance(context)

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
    fun `android 13 app control policies exclude grant admin restriction`() {
        val restrictions = DeviceOwnerManager.appControlRestrictionsForSdk(33)

        assertThat(restrictions).contains(UserManager.DISALLOW_UNINSTALL_APPS)
        assertThat(restrictions).contains(UserManager.DISALLOW_APPS_CONTROL)
        assertThat(restrictions).doesNotContain(UserManager.DISALLOW_GRANT_ADMIN)
    }

    @Test
    fun `android 14 app control policies include grant admin restriction`() {
        val restrictions = DeviceOwnerManager.appControlRestrictionsForSdk(34)

        assertThat(restrictions).contains(UserManager.DISALLOW_GRANT_ADMIN)
    }

    @Test
    fun `full shield keeps critical restrictions`() {
        val restrictions = DeviceOwnerManager.allShieldRestrictionsForSdk(34)

        assertThat(restrictions).contains(UserManager.DISALLOW_FACTORY_RESET)
        assertThat(restrictions).contains(UserManager.DISALLOW_SAFE_BOOT)
        assertThat(restrictions).contains(UserManager.DISALLOW_CONFIG_DATE_TIME)
        assertThat(restrictions).contains(UserManager.DISALLOW_UNINSTALL_APPS)
        assertThat(restrictions).contains(UserManager.DISALLOW_APPS_CONTROL)
    }
}
