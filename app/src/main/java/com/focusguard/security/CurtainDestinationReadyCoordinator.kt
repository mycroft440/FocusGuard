package com.focusguard.security

/**
 * Process-local safe-surface handshake.
 *
 * A generation must never travel through an externally sendable broadcast: on
 * Android 8-12 a dynamically registered receiver would let another app spoof a
 * predictable generation and hide the accessibility curtain early.
 */
object CurtainDestinationReadyCoordinator {
    fun interface Listener {
        fun onDestinationReady(generation: Long)
    }

    fun interface CurtainHiddenListener {
        fun onCurtainHidden(generation: Long)
    }

    @Volatile private var listener: Listener? = null
    @Volatile private var curtainHiddenListener: CurtainHiddenListener? = null

    // Última geração cuja tela avisou depois de o quadro ir de fato para a tela
    // (frame commit), e não só antes de desenhar. Nesse caso o serviço pode soltar
    // a cortina com uma espera curta em vez de adivinhar com o tempo cheio.
    @Volatile private var frameCommittedGeneration = 0L

    fun register(value: Listener) {
        listener = value
    }

    fun unregister(value: Listener) {
        if (listener === value) listener = null
    }

    fun notifyReady(generation: Long, frameCommitted: Boolean = false) {
        if (generation <= 0L) return
        if (frameCommitted) frameCommittedGeneration = generation
        listener?.onDestinationReady(generation)
    }

    fun isFrameCommitted(generation: Long): Boolean =
        generation > 0L && frameCommittedGeneration == generation

    /** A tela de destino quer saber quando a cortina saiu, para abrir a digital na hora. */
    fun setCurtainHiddenListener(value: CurtainHiddenListener?) {
        curtainHiddenListener = value
    }

    fun clearCurtainHiddenListener(value: CurtainHiddenListener) {
        if (curtainHiddenListener === value) curtainHiddenListener = null
    }

    fun notifyCurtainHidden(generation: Long) {
        if (generation <= 0L) return
        curtainHiddenListener?.onCurtainHidden(generation)
    }
}
