package com.focusguard.security

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tracks whether the current system-settings interaction was explicitly confirmed
 * to target FocusGuard itself.
 *
 * This is intentionally a very small, process-local latch. It is never armed from
 * a Settings class name, a generic Device Admin/Accessibility label, or from a
 * whole-window tree search. A new click/window transition that does not directly
 * identify FocusGuard clears it, so protection cannot leak to another app that is
 * managed immediately afterwards.
 */
object SelfProtectionTargetScope {
    private val focusGuardTargetConfirmed = AtomicBoolean(false)

    fun confirmFocusGuardTarget() {
        focusGuardTargetConfirmed.set(true)
    }

    fun clear() {
        focusGuardTargetConfirmed.set(false)
    }

    fun isFocusGuardTargetConfirmed(): Boolean = focusGuardTargetConfirmed.get()
}
