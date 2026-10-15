package com.focusguard.service

/**
 * Aviso, dentro do processo, de que uma tentativa interceptada não tem dono.
 *
 * O serviço decide pela lista rápida de apps bloqueados, que pode estar velha (um
 * período agendado que terminou, um limite que zerou à meia-noite, um bloqueio
 * removido com o serviço parado). O roteador confere o dono de verdade no banco; sem
 * nenhum, a tentativa é liberada: o serviço tira o app da lista, solta a cortina e
 * refaz o estado. Fica no processo, como CurtainDestinationReadyCoordinator, porque
 * um broadcast poderia ser forjado por outro app para soltar a cortina.
 */
internal object UnownedBlockRelease {
    fun interface Listener {
        fun onUnownedAttempt(packageName: String, curtainGeneration: Long)
    }

    @Volatile private var listener: Listener? = null

    fun register(value: Listener) {
        listener = value
    }

    fun unregister(value: Listener) {
        if (listener === value) listener = null
    }

    fun notifyUnowned(packageName: String, curtainGeneration: Long) {
        if (packageName.isBlank()) return
        listener?.onUnownedAttempt(packageName, curtainGeneration)
    }
}
