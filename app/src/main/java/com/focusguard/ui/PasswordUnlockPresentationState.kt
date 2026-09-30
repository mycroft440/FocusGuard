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
    private var acknowledgedGeneration = 0L

    /** Returns true only when the credential UI needs a new composition. */
    fun present(accessAttemptId: Long, curtainGeneration: Long): Boolean {
        val newAttempt = attemptId == 0L || attemptId != accessAttemptId
        if (newAttempt) {
            attemptId = accessAttemptId
            authenticationReady = curtainGeneration <= 0L
            acknowledgedGeneration = 0L
        } else if (curtainGeneration > 0L && curtainGeneration == acknowledgedGeneration) {
            // O serviço reenviou a tela com a mesma cortina já confirmada (evento
            // juntado): nada a refazer. Rearmar invalidaria o aviso de cortina oculta e
            // a digital cairia na reserva de 240 ms.
            return false
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
        if (generation > 0L) acknowledgedGeneration = generation
        return generation
    }

    fun finishCurtainSettle(requestId: Long): Boolean {
        if (requestId != curtainRequestId || pendingCurtainGeneration > 0L) return false
        authenticationReady = true
        return true
    }
}
