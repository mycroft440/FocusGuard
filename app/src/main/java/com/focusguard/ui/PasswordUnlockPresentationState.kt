package com.focusguard.ui

/**
 * Keeps one authentication surface per access attempt, independently of the
 * accessibility curtain requests received while that surface is already visible.
 */
internal class PasswordUnlockPresentationState {
    var attemptId = 0L
        private set
    var curtainRequestId = 0L
        private set
    var pendingCurtainGeneration = 0L
        private set
    var authenticationReady = false
        private set

    /** Returns true only when the credential UI needs a new composition. */
    fun present(accessAttemptId: Long, curtainGeneration: Long): Boolean {
        val newAttempt = attemptId == 0L || attemptId != accessAttemptId
        if (newAttempt) {
            attemptId = accessAttemptId
            authenticationReady = curtainGeneration <= 0L
        }

        // A duplicate intent without a curtain cannot release an earlier pending
        // curtain or invalidate its settle callback. Once ready, the same attempt
        // must never remove/recreate the password panel or its biometric prompt.
        if (newAttempt || curtainGeneration > 0L) {
            curtainRequestId++
            pendingCurtainGeneration = curtainGeneration
        }
        return newAttempt
    }

    fun acknowledgeCurtain(): Long {
        val generation = pendingCurtainGeneration
        pendingCurtainGeneration = 0L
        return generation
    }

    fun finishCurtainSettle(requestId: Long): Boolean {
        if (requestId != curtainRequestId || pendingCurtainGeneration > 0L) return false
        authenticationReady = true
        return true
    }
}
