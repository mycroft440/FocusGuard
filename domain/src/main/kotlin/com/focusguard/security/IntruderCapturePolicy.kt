package com.focusguard.security

/**
 * Decides where a failed password is worth photographing.
 *
 * The selfie exists to catch someone trying to get into an app *you* protected —
 * a phone handed over, a curious relative, a stolen device. It is not a general
 * failed-password alarm, and firing it on FocusGuard's own screens photographs
 * the owner far more often than an intruder: mistyping your own password on the
 * lock screen is routine, and the password manager is only reachable after you
 * already authenticated.
 *
 * Every capture is a photo of a real person's face, so the narrower scope is the
 * privacy-correct default as well as the useful one.
 */
object IntruderCapturePolicy {

    /** Where the wrong password was typed. */
    enum class Surface {
        /** Unlock sheet shown when opening an app protected by a password. */
        BLOCKED_APP_UNLOCK,

        /** FocusGuard's own lock screen. */
        FOCUSGUARD_APP_LOCK,

        /** The password management area, reachable only after authenticating. */
        PASSWORD_MANAGEMENT,

        /** Password prompt guarding a usage limit. */
        USAGE_LIMIT_UNLOCK
    }

    /** True only for the surface that guards someone else's access attempt. */
    fun capturesOn(surface: Surface): Boolean = surface == Surface.BLOCKED_APP_UNLOCK

    /**
     * Full decision for a wrong password.
     *
     * Fires on the *first* wrong attempt rather than after an attempt limit: the
     * useful photo is of whoever is holding the phone right now, and someone who
     * does not know the password often stops after one try — waiting for a third
     * would photograph nobody.
     *
     * The cost is that the owner's own mistyped password also produces a photo.
     * That is the deliberate trade, and the preference is off by default.
     */
    fun shouldCapture(
        surface: Surface,
        photoCaptureEnabled: Boolean
    ): Boolean = photoCaptureEnabled && capturesOn(surface)
}
