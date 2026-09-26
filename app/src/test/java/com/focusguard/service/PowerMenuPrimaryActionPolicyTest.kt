package com.focusguard.service

import com.focusguard.security.PowerMenuProtectionPolicy.Action
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PowerMenuPrimaryActionPolicyTest {
    @Test
    fun `first primary action tap starts native action`() {
        assertThat(
  ProtectedPowerMenuController.primaryActionTapDecision(
      pendingAction = null,
      tappedAction = Action.POWER_OFF,
      pendingForMillis = Long.MAX_VALUE
  )
        ).isEqualTo(ProtectedPowerMenuController.PrimaryActionTapDecision.START)
    }

    @Test
    fun `same primary action within confirmation window confirms`() {
        assertThat(
  ProtectedPowerMenuController.primaryActionTapDecision(
      pendingAction = Action.RESTART,
      tappedAction = Action.RESTART,
      pendingForMillis = 500L
  )
        ).isEqualTo(ProtectedPowerMenuController.PrimaryActionTapDecision.CONFIRM)
    }

    @Test
    fun `expired or different action starts a new native action`() {
        assertThat(
  ProtectedPowerMenuController.primaryActionTapDecision(
      pendingAction = Action.POWER_OFF,
      tappedAction = Action.POWER_OFF,
      pendingForMillis = ProtectedPowerMenuController.PRIMARY_ACTION_CONFIRM_WINDOW_MILLIS + 1L
  )
        ).isEqualTo(ProtectedPowerMenuController.PrimaryActionTapDecision.START)
        assertThat(
  ProtectedPowerMenuController.primaryActionTapDecision(
      pendingAction = Action.POWER_OFF,
      tappedAction = Action.RESTART,
      pendingForMillis = 100L
  )
        ).isEqualTo(ProtectedPowerMenuController.PrimaryActionTapDecision.START)
    }
}
