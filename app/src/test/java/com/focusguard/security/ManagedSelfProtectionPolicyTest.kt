package com.focusguard.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ManagedSelfProtectionPolicyTest {

    @Test
    fun `recognizes Samsung device administrator list shown to the user`() {
        assertThat(
            ManagedSelfProtectionPolicy.classTargetsDeviceAdmin(
                "com.android.settings.Settings\$DeviceAdminSettingsActivity"
            )
        ).isTrue()
        assertThat(
            ManagedSelfProtectionPolicy.textTargetsDeviceAdmin(
                listOf("Apps do administrador do aparelho")
            )
        ).isTrue()
    }

    @Test
    fun `recognizes FocusGuard app details and uninstall surfaces`() {
        assertThat(
            ManagedSelfProtectionPolicy.classTargetsAppDetails(
                "com.android.settings.applications.InstalledAppDetails"
            )
        ).isTrue()
        assertThat(
            ManagedSelfProtectionPolicy.classTargetsUninstall(
                "com.android.packageinstaller.UninstallerActivity"
            )
        ).isTrue()
        assertThat(
            ManagedSelfProtectionPolicy.textTargetsFocusGuard(
                listOf("FocusGuard", "com.focusguard.v2")
            )
        ).isTrue()
        assertThat(
            ManagedSelfProtectionPolicy.textTargetsDestructiveControl(
                listOf("Desinstalar", "Forçar parada")
            )
        ).isTrue()
    }

    @Test
    fun `does not classify unrelated settings or app details`() {
        assertThat(
            ManagedSelfProtectionPolicy.classTargetsDeviceAdmin(
                "com.android.settings.Settings\$WifiSettingsActivity"
            )
        ).isFalse()
        assertThat(
            ManagedSelfProtectionPolicy.textTargetsFocusGuard(
                listOf("Google Chrome", "com.android.chrome")
            )
        ).isFalse()
        assertThat(
            ManagedSelfProtectionPolicy.textTargetsDestructiveControl(
                listOf("Bateria", "Notificações")
            )
        ).isFalse()
    }
}
