package com.focusguard.domain.port

/** Native kiosk/window policy boundary for Focus Mode. */
interface FocusModeSystemPort {
    fun reconcileSystemRestrictions(): Boolean
    fun launchFocusGuardHome(): Boolean
}

