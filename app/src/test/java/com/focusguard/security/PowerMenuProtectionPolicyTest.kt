package com.focusguard.security

import com.focusguard.security.PowerMenuProtectionPolicy.Action
import com.focusguard.security.PowerMenuProtectionPolicy.DirectDecision
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PowerMenuProtectionPolicyTest {
    @Test
    fun `known global actions class matches before its text tree exists`() {
        assertThat(
            PowerMenuProtectionPolicy.classifyDirect(
                packageName = "com.android.systemui",
                className = "com.android.systemui.globalactions.GlobalActionsDialogLite",
                values = emptyList()
            )
        ).isEqualTo(DirectDecision.MATCH)
        assertThat(
            PowerMenuProtectionPolicy.classifyDirect(
                packageName = "com.android.systemui",
                className = "com.oem.keyguard.KeyguardGlobalActionsDialog",
                values = emptyList()
            )
        ).isEqualTo(DirectDecision.MATCH)
    }

    @Test
    fun `aosp global actions class with power off is recognised`() {
        assertThat(
            PowerMenuProtectionPolicy.isPowerMenu(
                packageName = "com.android.systemui",
                className = "com.android.systemui.globalactions.GlobalActionsDialogLite",
                values = listOf("Power off", "Emergency")
            )
        ).isTrue()
    }

    @Test
    fun `text signature recognises samsung style power menu in portuguese`() {
        assertThat(
            PowerMenuProtectionPolicy.isPowerMenu(
                packageName = "com.android.systemui",
                className = "android.widget.FrameLayout",
                values = listOf("Desligar", "Reiniciar", "Chamada de emergência")
            )
        ).isTrue()
        assertThat(
            PowerMenuProtectionPolicy.classifyDirect(
                packageName = "com.samsung.android.systemui",
                className = "android.widget.FrameLayout",
                values = listOf("Desligar", "Reiniciar", "Chamada de emergência")
            )
        ).isEqualTo(DirectDecision.MATCH)
    }

    @Test
    fun `samsung quick settings power shortcut plus emergency text is not power menu`() {
        val portugueseShade = listOf(
            "Desligar",
            "Chamadas de emergência",
            "Wi-Fi",
            "Bluetooth",
            "Saída de mídia"
        )
        assertThat(
            PowerMenuProtectionPolicy.isPowerMenu(
                packageName = "com.samsung.android.systemui",
                className = "android.widget.FrameLayout",
                values = portugueseShade
            )
        ).isFalse()
        assertThat(
            PowerMenuProtectionPolicy.classifyDirect(
                packageName = "com.samsung.android.systemui",
                className = "android.widget.FrameLayout",
                values = portugueseShade
            )
        ).isEqualTo(DirectDecision.UNKNOWN)

        assertThat(
            PowerMenuProtectionPolicy.isPowerMenu(
                packageName = "com.android.systemui",
                className = "android.widget.FrameLayout",
                values = listOf("Power off", "Emergency calls only", "Wi-Fi", "Bluetooth")
            )
        ).isFalse()

        // Some OEMs expose the QS power shortcut with one combined accessibility
        // description. A single node must not count as two independent actions.
        assertThat(
            PowerMenuProtectionPolicy.isPowerMenu(
                packageName = "com.samsung.android.systemui",
                className = "android.widget.FrameLayout",
                values = listOf("Desligar ou reiniciar", "Chamadas de emergência")
            )
        ).isFalse()
    }

    @Test
    fun `generic power menu requires both primary power actions`() {
        assertThat(
            PowerMenuProtectionPolicy.isPowerMenu(
                packageName = "com.android.systemui",
                className = "android.widget.FrameLayout",
                values = listOf("Desligar", "Reiniciar")
            )
        ).isTrue()
        assertThat(
            PowerMenuProtectionPolicy.isPowerMenu(
                packageName = "com.android.systemui",
                className = "android.widget.FrameLayout",
                values = listOf("Desligar", "Chamada de emergência")
            )
        ).isFalse()
        assertThat(
            PowerMenuProtectionPolicy.isPowerMenu(
                packageName = "com.android.systemui",
                className = "android.widget.FrameLayout",
                values = listOf("Reiniciar", "Chamada de emergência")
            )
        ).isFalse()
    }

    @Test
    fun `ordinary system ui notification is not a power menu`() {
        assertThat(
            PowerMenuProtectionPolicy.isPowerMenu(
                packageName = "com.android.systemui",
                className = "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow",
                values = listOf("FocusGuard ativo", "12 minutos restantes")
            )
        ).isFalse()
        assertThat(
            PowerMenuProtectionPolicy.classifyDirect(
                packageName = "com.android.systemui",
                className = "ExpandableNotificationRow",
                values = listOf("Reiniciar o download")
            )
        ).isEqualTo(DirectDecision.NOT_MATCH)
        assertThat(
            PowerMenuProtectionPolicy.classifyDirect(
                packageName = "com.android.systemui",
                className = "com.android.systemui.NotificationActionsDialog",
                values = listOf("Reiniciar")
            )
        ).isEqualTo(DirectDecision.NOT_MATCH)
    }

    @Test
    fun `ambiguous actions dialog needs actual primary power actions`() {
        assertThat(
            PowerMenuProtectionPolicy.classifyDirect(
                packageName = "com.android.systemui",
                className = "com.android.systemui.ActionsDialog",
                values = listOf("Wi-Fi", "Bluetooth")
            )
        ).isEqualTo(DirectDecision.UNKNOWN)
        assertThat(
            PowerMenuProtectionPolicy.classifyDirect(
                packageName = "com.android.systemui",
                className = "com.android.systemui.ActionsDialog",
                values = listOf("Reiniciar")
            )
        ).isEqualTo(DirectDecision.UNKNOWN)
        assertThat(
            PowerMenuProtectionPolicy.classifyDirect(
                packageName = "com.android.systemui",
                className = "com.android.systemui.ActionsDialog",
                values = listOf("Desligar", "Chamada de emergência")
            )
        ).isEqualTo(DirectDecision.UNKNOWN)
        assertThat(
            PowerMenuProtectionPolicy.classifyDirect(
                packageName = "com.android.systemui",
                className = "com.android.systemui.ActionsDialog",
                values = listOf("Desligar", "Reiniciar")
            )
        ).isEqualTo(DirectDecision.MATCH)
    }

    @Test
    fun `generic system ui window falls back to its tree`() {
        assertThat(
            PowerMenuProtectionPolicy.classifyDirect(
                packageName = "com.android.systemui",
                className = "android.widget.FrameLayout",
                values = emptyList()
            )
        ).isEqualTo(DirectDecision.UNKNOWN)
    }

    @Test
    fun `same labels outside system ui are ignored`() {
        assertThat(
            PowerMenuProtectionPolicy.isPowerMenu(
                packageName = "com.example.app",
                className = "GlobalActionsDialog",
                values = listOf("Desligar", "Reiniciar")
            )
        ).isFalse()
    }

    @Test
    fun `safe actions recognise portuguese and english labels`() {
        assertThat(
            PowerMenuProtectionPolicy.matchesAction(Action.POWER_OFF, listOf("Desligar"))
        ).isTrue()
        assertThat(
            PowerMenuProtectionPolicy.matchesAction(Action.RESTART, listOf("Restart"))
        ).isTrue()
        assertThat(
            PowerMenuProtectionPolicy.matchesAction(
                Action.EMERGENCY,
                listOf("Chamada de emergência")
            )
        ).isTrue()
        assertThat(
            PowerMenuProtectionPolicy.matchesAction(
                Action.MEDICAL_INFO,
                listOf("Medical information")
            )
        ).isTrue()
    }

    @Test
    fun `exact label is the action name and not a sentence that mentions it`() {
        assertThat(
            PowerMenuProtectionPolicy.isExactActionLabel(Action.POWER_OFF, listOf(null, " Desligar "))
        ).isTrue()
        assertThat(
            PowerMenuProtectionPolicy.isExactActionLabel(Action.POWER_OFF, listOf("POWER OFF"))
        ).isTrue()
        assertThat(
            PowerMenuProtectionPolicy.isExactActionLabel(
                Action.POWER_OFF,
                listOf("Para desligar, mantenha as teclas pressionadas", "", null)
            )
        ).isFalse()
        assertThat(
            PowerMenuProtectionPolicy.isExactActionLabel(Action.RESTART, listOf("Desligar"))
        ).isFalse()
    }

    @Test
    fun `notifications that mention the actions never make a power menu`() {
        // Cortina ou tela de bloqueio com notificações de outros apps: frases que
        // citam as ações não são os botões do menu.
        assertThat(
            PowerMenuProtectionPolicy.classifyDirect(
                packageName = "com.android.systemui",
                className = "android.widget.FrameLayout",
                values = listOf(
                    "Atualização do sistema pronta",
                    "Reiniciar agora",
                    "Ponto de acesso ativo",
                    "Toque para desligar"
                )
            )
        ).isEqualTo(DirectDecision.UNKNOWN)
        assertThat(
            PowerMenuProtectionPolicy.isPowerMenu(
                packageName = "com.android.systemui",
                className = "android.widget.TextView",
                values = listOf("Restart to update", "Turn off battery saver")
            )
        ).isFalse()
    }

    @Test
    fun `notification shade with exact action buttons is still not a power menu`() {
        // Dois botões de notificação com o nome exato das ações, dentro da cortina.
        val shade = listOf(
            "com.android.systemui:id/notification_stack_scroller",
            "Desligar",
            "Reiniciar"
        )
        assertThat(
            PowerMenuProtectionPolicy.isPowerMenu(
                packageName = "com.android.systemui",
                className = "android.widget.FrameLayout",
                values = shade
            )
        ).isFalse()
        listOf("status_bar_container", "keyguard_bottom_area", "qs_frame").forEach { id ->
            assertThat(
                PowerMenuProtectionPolicy.isPowerMenu(
                    packageName = "com.android.systemui",
                    className = "android.widget.FrameLayout",
                    values = listOf("Desligar", "Reiniciar", "com.android.systemui:id/$id")
                )
            ).isFalse()
        }
    }

    @Test
    fun `a real menu without its class is still recognised by its buttons`() {
        assertThat(
            PowerMenuProtectionPolicy.isPowerMenu(
                packageName = "com.android.systemui",
                className = "android.widget.FrameLayout",
                values = listOf(
                    "com.android.systemui:id/global_actions_view",
                    " Desligar ",
                    "REINICIAR",
                    "Chamada de emergência"
                )
            )
        ).isTrue()
        // A classe do menu continua bastando, com qualquer id na árvore.
        assertThat(
            PowerMenuProtectionPolicy.classifyDirect(
                packageName = "com.android.systemui",
                className = "com.android.systemui.globalactions.GlobalActionsDialogLite",
                values = listOf("com.android.systemui:id/keyguard_message_area")
            )
        ).isEqualTo(DirectDecision.MATCH)
    }
}
