package com.focusguard.security

import com.focusguard.security.PowerMenuProtectionPolicy.Action
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PowerMenuProtectionPolicyTest {
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
