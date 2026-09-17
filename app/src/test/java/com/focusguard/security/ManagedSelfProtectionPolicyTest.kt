package com.focusguard.security

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

class ManagedSelfProtectionPolicyTest {

    @Before
    fun resetTargetScope() {
        SelfProtectionTargetScope.clear()
    }

    @Test
    fun `device admin class is context only until FocusGuard target is confirmed`() {
        val className = "com.android.settings.Settings\$DeviceAdminSettingsActivity"

        assertThat(ManagedSelfProtectionPolicy.classLooksLikeDeviceAdminSurface(className))
            .isTrue()
        assertThat(ManagedSelfProtectionPolicy.classTargetsDeviceAdmin(className))
            .isFalse()

        SelfProtectionTargetScope.confirmFocusGuardTarget()

        assertThat(ManagedSelfProtectionPolicy.classTargetsDeviceAdmin(className))
            .isTrue()
    }

    @Test
    fun `generic device admin subtree fast search is disabled`() {
        assertThat(ManagedSelfProtectionPolicy.deviceAdminNodeSearchTerms).isEmpty()
    }

    @Test
    fun `device admin labels remain available as context`() {
        assertThat(
            ManagedSelfProtectionPolicy.textTargetsDeviceAdmin(
                listOf("Apps do administrador do aparelho")
            )
        ).isTrue()
        assertThat(
            ManagedSelfProtectionPolicy.textTargetsDeviceAdmin(
                listOf("Apps administradores do sistema")
            )
        ).isTrue()
        assertThat(
            ManagedSelfProtectionPolicy.textTargetsDeviceAdmin(
                listOf("Apps do administr. do aparel...")
            )
        ).isTrue()
    }

    @Test
    fun `recognizes FocusGuard identity exactly`() {
        assertThat(
            ManagedSelfProtectionPolicy.textTargetsFocusGuard(
                listOf("HardBlock", "com.focusguard.v2")
            )
        ).isTrue()
        assertThat(
            ManagedSelfProtectionPolicy.textTargetsFocusGuard(
                listOf("HardBlocker", "com.focusguard.v20")
            )
        ).isFalse()
    }

    @Test
    fun `recognizes app details uninstall and destructive controls`() {
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
            ManagedSelfProtectionPolicy.textTargetsDestructiveControl(
                listOf("Desinstalar", "Forçar parada")
            )
        ).isTrue()
    }

    @Test
    fun `general apps list is not app details`() {
        assertThat(
            ManagedSelfProtectionPolicy.classTargetsAppDetails(
                "com.android.settings.Settings\$ManageApplicationsActivity"
            )
        ).isFalse()
    }

    @Test
    fun `administration wording alone does not target device admin`() {
        assertThat(
            ManagedSelfProtectionPolicy.textTargetsDeviceAdmin(
                listOf("Configurações de administração da conta")
            )
        ).isFalse()
        assertThat(
            ManagedSelfProtectionPolicy.textTargetsDeviceAdmin(listOf("Meu aparelho"))
        ).isFalse()
    }

    @Test
    fun `batch classifier preserves contextual signals and FocusGuard identity`() {
        val signals = ManagedSelfProtectionPolicy.classifyText(
            listOf(
                "FocusGuard",
                "Apps do administrador do aparelho",
                "Desinstalar",
                "Uso irrestrito da bateria"
            )
        )

        assertThat(signals.focusGuard).isTrue()
        assertThat(signals.deviceAdmin).isTrue()
        assertThat(signals.destructiveControl).isTrue()
        assertThat(signals.essentialSpecialAccess).isTrue()
    }
}
