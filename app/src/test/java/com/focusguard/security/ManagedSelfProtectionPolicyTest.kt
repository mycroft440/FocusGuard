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
            ManagedSelfProtectionPolicy.classTargetsAppDetails(
                "com.android.settings.spa.SpaActivity"
            )
        ).isTrue()
        assertThat(
            ManagedSelfProtectionPolicy.classTargetsAppDetails(
                "com.android.settings.spa.SpaAppBridgeActivity"
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
    fun `does not classify the general apps list as FocusGuard details`() {
        assertThat(
            ManagedSelfProtectionPolicy.classTargetsAppDetails(
                "com.android.settings.Settings\$ManageApplicationsActivity"
            )
        ).isFalse()
        assertThat(
            ManagedSelfProtectionPolicy.classTargetsAppDetails(
                "com.android.settings.applications.manageapplications.ManageApplications"
            )
        ).isFalse()
        assertThat(
            ManagedSelfProtectionPolicy.classTargetsAppDetails(
                "com.android.settings.applications.ApplicationsSettings"
            )
        ).isFalse()
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

    @Test
    fun `recognizes essential usage and battery protection settings`() {
        assertThat(
            ManagedSelfProtectionPolicy.classTargetsEssentialSpecialAccess(
                "com.android.settings.Settings\$UsageAccessSettingsActivity"
            )
        ).isTrue()
        assertThat(
            ManagedSelfProtectionPolicy.textTargetsEssentialSpecialAccess(
                listOf("Uso irrestrito da bateria")
            )
        ).isTrue()
        assertThat(
            ManagedSelfProtectionPolicy.textTargetsEssentialSpecialAccess(
                listOf("Instalar apps desconhecidos")
            )
        ).isFalse()
    }

    @Test
    fun `abbreviated device admin labels are recognised`() {
        // A One UI corta o rótulo para caber na barra de título, e nenhum termo
        // por extenso casava com o corte — era por aí que a tela passava.
        assertThat(
            ManagedSelfProtectionPolicy.textTargetsDeviceAdmin(
                listOf("Apps do administr. do aparel...")
            )
        ).isTrue()
        assertThat(
            ManagedSelfProtectionPolicy.textTargetsDeviceAdmin(
                listOf("Apps do administr. do aparelho")
            )
        ).isTrue()
        assertThat(
            ManagedSelfProtectionPolicy.textTargetsDeviceAdmin(listOf("Admin apps do device"))
        ).isTrue()
    }

    @Test
    fun `batch classifier preserves all self protection signals`() {
        val signals = ManagedSelfProtectionPolicy.classifyText(
            listOf(
                "FocusGuard",
                "Apps do administr. do aparelho",
                "Desinstalar",
                "Uso irrestrito da bateria"
            )
        )

        assertThat(signals.focusGuard).isTrue()
        assertThat(signals.deviceAdmin).isTrue()
        assertThat(signals.destructiveControl).isTrue()
        assertThat(signals.essentialSpecialAccess).isTrue()
    }

    @Test
    fun `administration wording alone does not target device admin`() {
        // Sem palavra de aparelho na frase, "administração" é contexto inocente
        // demais para derrubar o usuário da tela.
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
    fun `Samsung system administrator label targets device admin`() {
        assertThat(
            ManagedSelfProtectionPolicy.textTargetsDeviceAdmin(
                listOf("Apps administradores do sistema")
            )
        ).isTrue()
    }

    @Test
    fun `app information gateway labels are recognized`() {
        assertThat(
            ManagedSelfProtectionPolicy.textTargetsAppInfoGateway(
                listOf("Informações do aplicativo")
            )
        ).isTrue()
        assertThat(ManagedSelfProtectionPolicy.textTargetsAppInfoGateway(listOf("App info")))
            .isTrue()
    }

}
