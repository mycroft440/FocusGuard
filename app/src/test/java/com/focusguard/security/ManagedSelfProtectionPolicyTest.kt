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
    fun `device admin class is context only and never target proof`() {
        val className = "com.android.settings.Settings\$DeviceAdminSettingsActivity"

        assertThat(ManagedSelfProtectionPolicy.classLooksLikeDeviceAdminSurface(className))
            .isTrue()
        assertThat(ManagedSelfProtectionPolicy.classTargetsDeviceAdmin(className))
            .isFalse()

        SelfProtectionTargetScope.confirmFocusGuardTarget()

        assertThat(ManagedSelfProtectionPolicy.classTargetsDeviceAdmin(className))
            .isFalse()
    }

    @Test
    fun `generic device admin subtree fast search is disabled`() {
        assertThat(ManagedSelfProtectionPolicy.deviceAdminNodeSearchTerms).isEmpty()
    }

    @Test
    fun `device admin wording alone is context not target`() {
        val generic = listOf("Apps do administrador do aparelho")

        assertThat(ManagedSelfProtectionPolicy.textLooksLikeDeviceAdminContext(generic))
            .isTrue()
        assertThat(ManagedSelfProtectionPolicy.textTargetsDeviceAdmin(generic))
            .isFalse()
    }

    @Test
    fun `device admin target requires FocusGuard identity in same values`() {
        val focusGuardAdmin = listOf("Apps do administrador do aparelho", "HardBlock")

        assertThat(ManagedSelfProtectionPolicy.textLooksLikeDeviceAdminContext(focusGuardAdmin))
            .isTrue()
        assertThat(ManagedSelfProtectionPolicy.textTargetsDeviceAdmin(focusGuardAdmin))
            .isTrue()
    }

    @Test
    fun `abbreviated and Samsung device admin labels remain recognizable as context`() {
        assertThat(
            ManagedSelfProtectionPolicy.textLooksLikeDeviceAdminContext(
                listOf("Apps do administr. do aparel...")
            )
        ).isTrue()
        assertThat(
            ManagedSelfProtectionPolicy.textLooksLikeDeviceAdminContext(
                listOf("Apps administradores do sistema")
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
    fun `administration wording alone does not look like device admin context`() {
        assertThat(
            ManagedSelfProtectionPolicy.textLooksLikeDeviceAdminContext(
                listOf("Configurações de administração da conta")
            )
        ).isFalse()
        assertThat(
            ManagedSelfProtectionPolicy.textLooksLikeDeviceAdminContext(listOf("Meu aparelho"))
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
