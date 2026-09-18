package com.focusguard.security

import com.focusguard.security.ImmediateInterceptionPolicy.DirectDecision
import com.focusguard.security.ImmediateInterceptionPolicy.LauncherLabelEntry
import com.focusguard.security.ImmediateInterceptionPolicy.SettingsSurface
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

class ImmediateInterceptionPolicyTest {

    @Before
    fun resetTargetScope() {
        SelfProtectionTargetScope.clear()
    }

    @Test
    fun `globally unique launcher label matches blocked package`() {
        val index = ImmediateInterceptionPolicy.buildLauncherLabelIndex(
            listOf(LauncherLabelEntry("YouTube", "com.google.youtube", "Main"))
        )

        assertThat(
            index.matchBlockedPackage(listOf("YouTube"), setOf("com.google.youtube"))
        ).isEqualTo("com.google.youtube")
    }

    @Test
    fun `ambiguous launcher label is never fast matched`() {
        val index = ImmediateInterceptionPolicy.buildLauncherLabelIndex(
            listOf(
                LauncherLabelEntry("Fotos", "com.blocked.photos", "Main"),
                LauncherLabelEntry("Fotos", "com.free.photos", "Main")
            )
        )

        assertThat(
            index.matchBlockedPackage(listOf("Fotos"), setOf("com.blocked.photos"))
        ).isNull()
    }

    @Test
    fun `notification badge suffix keeps exact launcher identity`() {
        val index = ImmediateInterceptionPolicy.buildLauncherLabelIndex(
            listOf(LauncherLabelEntry("Mensagens", "com.example.messages", "Main"))
        )

        assertThat(
            index.matchBlockedPackage(
                listOf("Mensagens, 3 notificações"),
                setOf("com.example.messages")
            )
        ).isEqualTo("com.example.messages")
    }

    @Test
    fun `folder widget or shortcut cannot enter app icon fast path`() {
        assertThat(
            ImmediateInterceptionPolicy.isLikelyLauncherAppIconClass(
                "com.android.launcher3.folder.FolderIcon"
            )
        ).isFalse()
        assertThat(
            ImmediateInterceptionPolicy.isLikelyLauncherAppIconClass(
                "com.android.launcher3.BubbleTextView"
            )
        ).isTrue()
    }

    @Test
    fun `blocked target window behavior is unchanged`() {
        assertThat(
            ImmediateInterceptionPolicy.isBlockedTargetWindow(
                foregroundPackageName = "com.example.blocked",
                blockedPackages = setOf("com.example.blocked")
            )
        ).isTrue()
        assertThat(
            ImmediateInterceptionPolicy.isBlockedTargetWindow(
                foregroundPackageName = "com.example.free",
                blockedPackages = setOf("com.example.blocked")
            )
        ).isFalse()
    }

    @Test
    fun `generic device admin gateway never immediately protects`() {
        val result = ImmediateInterceptionPolicy.classifySettingsClick(
            packageName = "com.android.settings",
            className = "android.widget.TextView",
            values = listOf("Apps do administrador do aparelho")
        )

        assertThat(result.decision).isEqualTo(DirectDecision.NEED_TREE)
        assertThat(result.surface).isNull()
    }

    @Test
    fun `device admin class alone never immediately protects`() {
        val result = ImmediateInterceptionPolicy.classifySettingsClick(
            packageName = "com.android.settings",
            className = "com.android.settings.Settings\$DeviceAdminSettingsActivity",
            values = emptyList()
        )

        assertThat(result.decision).isEqualTo(DirectDecision.NEED_TREE)
    }

    @Test
    fun `FocusGuard app info is delegated to full policy instead of arming guard`() {
        val result = ImmediateInterceptionPolicy.classifySettingsClick(
            packageName = "com.android.settings",
            className = "com.android.settings.applications.InstalledAppDetails",
            values = listOf("Informações do app", "HardBlock")
        )

        assertThat(result.decision).isEqualTo(DirectDecision.NEED_TREE)
        assertThat(result.surface).isEqualTo(SettingsSurface.APP_INFO)
    }

    @Test
    fun `FocusGuard uninstall is delegated to full policy instead of arming guard`() {
        val result = ImmediateInterceptionPolicy.classifySettingsClick(
            packageName = "com.android.packageinstaller",
            className = "com.android.packageinstaller.UninstallerActivity",
            values = listOf("HardBlock", "Desinstalar")
        )

        assertThat(result.decision).isEqualTo(DirectDecision.NEED_TREE)
        assertThat(result.surface).isEqualTo(SettingsSurface.UNINSTALL)
    }

    @Test
    fun `generic accessibility gateway never immediately protects`() {
        val result = ImmediateInterceptionPolicy.classifySettingsClick(
            packageName = "com.android.settings",
            className = "android.widget.TextView",
            values = listOf("Acessibilidade", "Aplicativos instalados")
        )

        assertThat(result.decision).isEqualTo(DirectDecision.NEED_TREE)
    }

    @Test
    fun `ordinary system ui content is ignored`() {
        val result = ImmediateInterceptionPolicy.classifySettingsClick(
            packageName = "com.android.systemui",
            className = "ExpandableNotificationRow",
            values = listOf("Reunião às 14h")
        )

        assertThat(result.decision).isEqualTo(DirectDecision.IGNORE)
    }

    @Test
    fun `FocusGuard system ui disclosure is delegated to target aware full policy`() {
        val result = ImmediateInterceptionPolicy.classifySystemUiClickWithContext(
            className = "ExpandableNotificationRow",
            directValues = listOf("HardBlock"),
            contextualValues = { listOf("toque para revisar") }
        )

        assertThat(result.decision).isEqualTo(DirectDecision.NEED_TREE)
    }

    @Test
    fun `launcher app info no longer arms settings guard`() {
        assertThat(
            ImmediateInterceptionPolicy.classifyLauncherAppInfoClick(
                listOf("App info", "HardBlock")
            )
        ).isEqualTo(DirectDecision.NEED_TREE)
        assertThat(
            ImmediateInterceptionPolicy.classifyLauncherAppInfoClick(listOf("App info"))
        ).isEqualTo(DirectDecision.NEED_TREE)
    }

    @Test
    fun `authorized device admin surfaces are delegated to full policy`() {
        assertThat(
            ImmediateInterceptionPolicy.requiresFullPolicyForAuthorizedAdmin(
                deviceAdminActivationAuthorized = true,
                className = "com.android.settings.Settings\$DeviceAdminAddActivity",
                directSurface = null
            )
        ).isTrue()
        assertThat(
            ImmediateInterceptionPolicy.requiresFullPolicyForAuthorizedAdmin(
                deviceAdminActivationAuthorized = false,
                className = "com.android.settings.Settings\$DeviceAdminAddActivity",
                directSurface = SettingsSurface.DEVICE_ADMIN
            )
        ).isFalse()
    }
}
