package com.focusguard.utils

import android.os.Build
import android.os.SystemClock

/**
 * O Android só grava DEVICE_SHUTDOWN e DEVICE_STARTUP a partir do 10. No 9, desligar
 * não deixa evento nenhum e o trecho de uso aberto antes de desligar seguiria até o
 * primeiro evento depois de religar. A hora do último boot é o que se sabe dele.
 */
internal object LegacyBootClock {
    /** Hora de parede do último boot, só nas versões sem evento de desligar; senão null. */
    fun bootMillisWithoutShutdownEvents(): Long? =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            System.currentTimeMillis() - SystemClock.elapsedRealtime()
        } else {
            null
        }
}
