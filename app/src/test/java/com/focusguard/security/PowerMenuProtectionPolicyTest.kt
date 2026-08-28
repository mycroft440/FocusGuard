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
    fun `ambiguous actions dialog needs actual power actions`() {
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
}
