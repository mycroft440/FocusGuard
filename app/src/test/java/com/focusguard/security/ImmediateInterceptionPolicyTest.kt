package com.focusguard.security

import com.focusguard.security.ImmediateInterceptionPolicy.DirectDecision
import com.focusguard.security.ImmediateInterceptionPolicy.LauncherLabelEntry
import com.focusguard.security.ImmediateInterceptionPolicy.SettingsSurface
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ImmediateInterceptionPolicyTest {

    @Test
    fun `globally unique launcher label matches a blocked package`() {
        val index = ImmediateInterceptionPolicy.buildLauncherLabelIndex(
            listOf(LauncherLabelEntry("YouTube", "com.google.youtube", "Main"))
        )

        assertThat(
            index.matchBlockedPackage(listOf("YouTube"), setOf("com.google.youtube"))
        ).isEqualTo("com.google.youtube")
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
    fun `punctuation inside launcher labels is retained before suffix matching`() {
        val index = ImmediateInterceptionPolicy.buildLauncherLabelIndex(
            listOf(
                LauncherLabelEntry("Washington, D.C.", "com.example.washington", "Main"),
                LauncherLabelEntry("Banco (Trabalho)", "com.example.bank", "Main"),
                LauncherLabelEntry("Foo • Bar", "com.example.foo", "Main")
            )
        )

        assertThat(
            index.matchBlockedPackage(
                listOf("Washington, D.C., 3 notificações"),
                setOf("com.example.washington")
            )
        ).isEqualTo("com.example.washington")
        assertThat(
            index.matchBlockedPackage(
                listOf("Banco (Trabalho)\n2 notificações"),
                setOf("com.example.bank")
            )
        ).isEqualTo("com.example.bank")
        assertThat(
            index.matchBlockedPackage(
                listOf("Foo • Bar • 1 notificação"),
                setOf("com.example.foo")
            )
        ).isEqualTo("com.example.foo")
    }

    @Test
    fun `known allowed or ambiguous exact label cannot collapse to blocked prefix`() {
        val index = ImmediateInterceptionPolicy.buildLauncherLabelIndex(
            listOf(
                LauncherLabelEntry("Foo", "com.example.blocked", "Main"),
                LauncherLabelEntry("Foo, Bar", "com.example.allowed", "Main"),
                LauncherLabelEntry("Foo (Beta)", "com.example.beta", "Main"),
                LauncherLabelEntry("Fotos", "com.example.photos.one", "Main"),
                LauncherLabelEntry("Fotos", "com.example.photos.two", "Main")
            )
        )

        assertThat(
            index.matchBlockedPackage(listOf("Foo, Bar"), setOf("com.example.blocked"))
        ).isNull()
        assertThat(
            index.matchBlockedPackage(listOf("Foo (Beta)"), setOf("com.example.blocked"))
        ).isNull()
        assertThat(
            index.matchBlockedPackage(
                listOf("Fotos"),
                setOf("com.example.photos.one")
            )
        ).isNull()
    }

    @Test
    fun `folder widget or shortcut cannot enter the app icon fast path`() {
        assertThat(
            ImmediateInterceptionPolicy.isLikelyLauncherAppIconClass(
                "com.android.launcher3.folder.FolderIcon"
            )
        ).isFalse()
        assertThat(
            ImmediateInterceptionPolicy.isLikelyLauncherAppIconClass(
                "com.android.launcher3.widget.WidgetCell"
            )
        ).isFalse()
        assertThat(
            ImmediateInterceptionPolicy.isLikelyLauncherAppIconClass(
                "com.oem.launcher.DeepShortcutIconView"
            )
        ).isFalse()
        assertThat(
            ImmediateInterceptionPolicy.isLikelyLauncherAppIconClass(
                "com.android.launcher3.BubbleTextView"
            )
        ).isTrue()
        assertThat(
            ImmediateInterceptionPolicy.isLikelyLauncherAppIconClass(
                "android.widget.TextView"
            )
        ).isTrue()
        assertThat(
            ImmediateInterceptionPolicy.isLikelyLauncherAppIconClass(
                "android.widget.FrameLayout"
            )
        ).isFalse()
    }

    @Test
    fun `unindexed folder-like suffix cannot collapse to blocked app label`() {
        val index = ImmediateInterceptionPolicy.buildLauncherLabelIndex(
            listOf(
                LauncherLabelEntry("YouTube", "com.google.youtube", "Main"),
                LauncherLabelEntry("Foo", "com.example.foo", "Main")
            )
        )

        assertThat(
            index.matchBlockedPackage(
                listOf("YouTube, Trabalho"),
                setOf("com.google.youtube")
            )
        ).isNull()
        assertThat(
            index.matchBlockedPackage(
                listOf("YouTube, 3 notificações"),
                setOf("com.google.youtube")
            )
        ).isEqualTo("com.google.youtube")
        assertThat(
            index.matchBlockedPackage(
                listOf("Foo, Trabalho 2"),
                setOf("com.example.foo")
            )
        ).isNull()
        assertThat(
            index.matchBlockedPackage(
                listOf("Foo (Beta 2)"),
                setOf("com.example.foo")
            )
        ).isNull()
        assertThat(
            index.matchBlockedPackage(
                listOf("Foo, 3 notificações"),
                setOf("com.example.foo")
            )
        ).isEqualTo("com.example.foo")
        assertThat(
            index.matchBlockedPackage(
                listOf("Foo\n2 notifications"),
                setOf("com.example.foo")
            )
        ).isEqualTo("com.example.foo")
        assertThat(
            index.matchBlockedPackage(
                listOf("Foo (3)"),
                setOf("com.example.foo")
            )
        ).isEqualTo("com.example.foo")
    }

    @Test
    fun `duplicate label between blocked and free apps is never fast matched`() {
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
    fun `multiple launcher activities from one package remain unambiguous`() {
        val index = ImmediateInterceptionPolicy.buildLauncherLabelIndex(
            listOf(
                LauncherLabelEntry("Mapas", "com.example.maps", "Main"),
                LauncherLabelEntry("Mapas", "com.example.maps", "Driving")
            )
        )

        assertThat(index.packageForExactLabel("Mapas")).isEqualTo("com.example.maps")
    }

    @Test
    fun `launcher lookup stays direct with thousands of labels`() {
        val entries = (0 until 5_000).map { index ->
            LauncherLabelEntry(
                label = "Aplicativo $index",
                packageName = "com.example.app$index",
                componentName = "Main$index"
            )
        }
        val index = ImmediateInterceptionPolicy.buildLauncherLabelIndex(entries)

        assertThat(index.size).isEqualTo(5_000)
        assertThat(
            index.matchBlockedPackage(
                values = listOf("Aplicativo 4999, 8 notificações"),
                blockedPackages = setOf("com.example.app4999")
            )
        ).isEqualTo("com.example.app4999")
    }

    @Test
    fun `unknown text and unblocked package are ignored`() {
        val index = ImmediateInterceptionPolicy.buildLauncherLabelIndex(
            listOf(LauncherLabelEntry("Câmera", "com.example.camera", "Main"))
        )

        assertThat(index.matchBlockedPackage(emptyList(), setOf("com.example.camera"))).isNull()
        assertThat(index.matchBlockedPackage(listOf("Galeria"), setOf("com.example.camera")))
            .isNull()
        assertThat(index.matchBlockedPackage(listOf("Câmera"), emptySet())).isNull()
    }

    @Test
    fun `only a click from the current launcher enters the fast path`() {
        assertThat(
            ImmediateInterceptionPolicy.shouldHandleLauncherClick(
                isViewClickedEvent = true,
                eventPackageName = "com.launcher.current",
                defaultLauncherPackage = "com.launcher.current"
            )
        ).isTrue()
        assertThat(
            ImmediateInterceptionPolicy.shouldHandleLauncherClick(
                isViewClickedEvent = true,
                eventPackageName = "com.launcher.old",
                defaultLauncherPackage = "com.launcher.current"
            )
        ).isFalse()
        assertThat(
            ImmediateInterceptionPolicy.shouldHandleLauncherClick(
                isViewClickedEvent = false,
                eventPackageName = "com.launcher.current",
                defaultLauncherPackage = "com.launcher.current"
            )
        ).isFalse()
        assertThat(
            ImmediateInterceptionPolicy.shouldHandleLauncherClick(
                isViewClickedEvent = true,
                eventPackageName = "",
                defaultLauncherPackage = null
            )
        ).isFalse()
    }

    @Test
    fun `target window fallback remains active without a launcher click`() {
        val blocked = setOf("com.example.blocked")

        assertThat(
            ImmediateInterceptionPolicy.isBlockedTargetWindow(
                foregroundPackageName = "com.example.blocked",
                blockedPackages = blocked
            )
        ).isTrue()
        assertThat(
            ImmediateInterceptionPolicy.isBlockedTargetWindow(
                foregroundPackageName = "com.example.allowed",
                blockedPackages = blocked
            )
        ).isFalse()
    }

    @Test
    fun `focus mode only package participates in immediate launcher match`() {
        val index = ImmediateInterceptionPolicy.buildLauncherLabelIndex(
            listOf(LauncherLabelEntry("Redes sociais", "com.example.social", "Main"))
        )

        assertThat(
            index.matchBlockedPackage(
                values = listOf("Redes sociais"),
                blockedPackages = emptySet(),
                additionalBlockedPackages = setOf("com.example.social")
            )
        ).isEqualTo("com.example.social")
    }

    @Test
    fun `direct protected settings gateways avoid tree inspection`() {
        val admin = ImmediateInterceptionPolicy.classifySettingsClick(
            "com.android.settings",
            "android.widget.TextView",
            listOf("Apps do administrador do aparelho")
        )
        val accessibility = ImmediateInterceptionPolicy.classifySettingsClick(
            "com.android.settings",
            "android.widget.TextView",
            listOf("Acessibilidade", "Aplicativos instalados")
        )
        val appInfo = ImmediateInterceptionPolicy.classifySettingsClick(
            "com.android.settings",
            "android.widget.TextView",
            listOf("Informações do app", "HardBlock")
        )

        assertThat(admin.decision).isEqualTo(DirectDecision.PROTECT)
        assertThat(admin.surface).isEqualTo(SettingsSurface.DEVICE_ADMIN)
        val samsungAdmin = ImmediateInterceptionPolicy.classifySettingsClick(
            "com.android.settings",
            "android.widget.TextView",
            listOf("Apps administradores do sistema")
        )
        assertThat(samsungAdmin.decision).isEqualTo(DirectDecision.PROTECT)
        assertThat(samsungAdmin.surface).isEqualTo(SettingsSurface.DEVICE_ADMIN)
        assertThat(accessibility.decision).isEqualTo(DirectDecision.PROTECT)
        assertThat(accessibility.surface).isEqualTo(SettingsSurface.ACCESSIBILITY)
        assertThat(appInfo.decision).isEqualTo(DirectDecision.PROTECT)
        assertThat(appInfo.surface).isEqualTo(SettingsSurface.APP_INFO)
        assertThat(
            ImmediateInterceptionPolicy.classifySettingsClick(
                "com.android.settings",
                "android.widget.TextView",
                listOf("Informações do app")
            ).decision
        ).isEqualTo(DirectDecision.NEED_TREE)
        assertThat(
            ImmediateInterceptionPolicy.classifySettingsClick(
                "com.android.settings",
                "android.widget.TextView",
                listOf("Informações do app", "HardBlocker")
            ).decision
        ).isEqualTo(DirectDecision.NEED_TREE)
    }

    @Test
    fun `textless generic Settings click always falls back to tree`() {
        val result = ImmediateInterceptionPolicy.classifySettingsClick(
            packageName = "com.android.settings",
            className = "android.widget.LinearLayout",
            values = emptyList()
        )

        assertThat(result.decision).isEqualTo(DirectDecision.NEED_TREE)
    }

    @Test
    fun `another apps settings surface needs identity instead of being blocked`() {
        val result = ImmediateInterceptionPolicy.classifySettingsClick(
            "com.android.settings",
            "com.android.settings.applications.InstalledAppDetails",
            listOf("Example Notes")
        )

        assertThat(result.decision).isEqualTo(DirectDecision.NEED_TREE)
    }

    @Test
    fun `ordinary system ui content is ignored`() {
        val result = ImmediateInterceptionPolicy.classifySettingsClick(
            "com.android.systemui",
            "ExpandableNotificationRow",
            listOf("Reunião às 14h")
        )

        assertThat(result.decision).isEqualTo(DirectDecision.IGNORE)
    }

    @Test
    fun `partial or textless System UI disclosure signals require context`() {
        assertThat(
            ImmediateInterceptionPolicy.classifySettingsClick(
                "com.android.systemui",
                "ExpandableNotificationRow",
                listOf("HardBlock")
            ).decision
        ).isEqualTo(DirectDecision.NEED_TREE)
        assertThat(
            ImmediateInterceptionPolicy.classifySettingsClick(
                "com.android.systemui",
                "ExpandableNotificationRow",
                listOf("toque para revisar")
            ).decision
        ).isEqualTo(DirectDecision.NEED_TREE)
        assertThat(
            ImmediateInterceptionPolicy.classifySettingsClick(
                "com.android.systemui",
                "android.widget.LinearLayout",
                emptyList()
            ).decision
        ).isEqualTo(DirectDecision.NEED_TREE)
        assertThat(
            ImmediateInterceptionPolicy.classifySettingsClick(
                "com.android.systemui",
                "ExpandableNotificationRow",
                listOf("HardBlock", "toque para revisar")
            ).decision
        ).isEqualTo(DirectDecision.PROTECT)
    }

    @Test
    fun `System UI disclosure expands the simulated source only when ambiguous`() {
        var sourceReads = 0
        val fromPartial = ImmediateInterceptionPolicy.classifySystemUiClickWithContext(
            className = "ExpandableNotificationRow",
            directValues = listOf("HardBlock"),
            contextualValues = {
                sourceReads += 1
                listOf("toque para revisar")
            }
        )

        assertThat(fromPartial.decision).isEqualTo(DirectDecision.PROTECT)
        assertThat(fromPartial.surface).isEqualTo(SettingsSurface.ACCESSIBILITY)
        assertThat(sourceReads).isEqualTo(1)

        val fromComplete = ImmediateInterceptionPolicy.classifySystemUiClickWithContext(
            className = "ExpandableNotificationRow",
            directValues = listOf("HardBlock", "toque para revisar"),
            contextualValues = {
                sourceReads += 1
                emptyList()
            }
        )

        assertThat(fromComplete.decision).isEqualTo(DirectDecision.PROTECT)
        assertThat(sourceReads).isEqualTo(1)

        val fromTextlessGenericRow =
            ImmediateInterceptionPolicy.classifySystemUiClickWithContext(
                className = "android.widget.LinearLayout",
                directValues = emptyList(),
                contextualValues = {
                    sourceReads += 1
                    listOf("HardBlock", "can see what you're doing")
                }
            )

        assertThat(fromTextlessGenericRow.decision).isEqualTo(DirectDecision.PROTECT)
        assertThat(sourceReads).isEqualTo(2)
    }

    @Test
    fun `launcher app info requires FocusGuard identity`() {
        assertThat(AccessibilitySettingsPolicy.accessibilityDisclosureNodeSearchTerms)
            .contains("HardBlock")
        assertThat(
            ImmediateInterceptionPolicy.classifyLauncherAppInfoClick(
                listOf("App info", "HardBlock")
            )
        ).isEqualTo(DirectDecision.PROTECT)
        assertThat(
            ImmediateInterceptionPolicy.classifyLauncherAppInfoClick(listOf("App info"))
        ).isEqualTo(DirectDecision.NEED_TREE)
    }

    @Test
    fun `authorized device admin list route is delegated to full policy`() {
        assertThat(
            ImmediateInterceptionPolicy.requiresFullPolicyForAuthorizedAdmin(
                deviceAdminActivationAuthorized = true,
                className = "com.android.settings.SubSettings",
                directSurface = SettingsSurface.APP_INFO
            )
        ).isTrue()
        assertThat(
            ImmediateInterceptionPolicy.requiresFullPolicyForAuthorizedAdmin(
                deviceAdminActivationAuthorized = false,
                className = "com.android.settings.SubSettings",
                directSurface = SettingsSurface.DEVICE_ADMIN
            )
        ).isFalse()
    }
}
