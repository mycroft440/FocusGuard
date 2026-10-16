package com.focusguard.security

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.focusguard.database.AppUsageLimit
import com.focusguard.database.BlockSession
import kotlin.math.abs

/**
 * Bloqueio por tempo à prova de relógio adiantado à mão.
 *
 * Sem Device Owner nada impede a pessoa de desligar a hora automática e adiantar a
 * data: o jejum vencia na hora e a reconciliação o desativava de vez, mesmo com o
 * relógio voltando depois. O relógio desde o boot (elapsedRealtime) não muda com a
 * data; a diferença entre os dois, o "boot no relógio de parede", só muda quando a
 * data é trocada. Um salto dessa diferença no mesmo boot desloca os prazos dos
 * bloqueios por tempo (fim, janela de cancelamento) e dos limites (fim do "sem senha
 * por vários dias", trava de edição) pelo mesmo tanto: o tempo real que falta
 * continua o mesmo, para a frente e para trás.
 *
 * O ajuste da rede com a hora automática ligada (antes e agora) é a hora certa e não
 * é compensado; isso também cobre o aparelho que liga com a hora errada e é
 * corrigido pela rede. Voltar à hora automática depois de mudar à mão desfaz a
 * compensação, porque a âncora gravada era de hora manual.
 */
internal object ClockJumpPolicy {
    /** Ajustes menores que isso são deriva do relógio, não troca de data. */
    const val MIN_COMPENSATED_JUMP_MILLIS = 60_000L

    data class Anchor(
        val bootCount: Int,
        val bootWallClockMillis: Long,
        val autoTimeEnabled: Boolean
    )

    /** O salto a compensar desde [anchor]; 0 quando não há o que compensar. */
    fun compensatedJumpMillis(anchor: Anchor?, current: Anchor?): Long {
        if (anchor == null || current == null) return 0L
        // Outro boot: o relógio desde o boot recomeçou e a diferença não diz nada.
        if (anchor.bootCount != current.bootCount) return 0L
        val jump = current.bootWallClockMillis - anchor.bootWallClockMillis
        if (abs(jump) < MIN_COMPENSATED_JUMP_MILLIS) return 0L
        if (anchor.autoTimeEnabled && current.autoTimeEnabled) return 0L
        return jump
    }

    /** O jejum (ou período com prazo) visto com o mesmo tempo real restante. */
    fun shiftSession(session: BlockSession, jumpMillis: Long): BlockSession? {
        val endTime = session.endTime ?: return null
        if (!session.isActive) return null
        return session.copy(
            startTime = session.startTime.plusClamped(jumpMillis),
            endTime = endTime.plusClamped(jumpMillis),
            cancelableUntil = session.cancelableUntil?.plusClamped(jumpMillis)
        )
    }

    /**
     * O limite com os mesmos prazos em tempo real: o fim do "sem senha por vários
     * dias" e o momento a partir do qual ele não pode mais ser editado nem removido
     * (atrasar o relógio à mão o reabria para remoção).
     */
    fun shiftLimit(limit: AppUsageLimit, jumpMillis: Long): AppUsageLimit? {
        val hardenedUntil = limit.lockUntilTimestamp?.takeIf {
            limit.lockMode.equals(MasterCredentialPolicy.LOCK_MODE_TIME, ignoreCase = true)
        }
        val lockAfter = limit.lockAfter
        if (hardenedUntil == null && lockAfter == null) return null
        return limit.copy(
            lockUntilTimestamp = hardenedUntil?.plusClamped(jumpMillis)
                ?: limit.lockUntilTimestamp,
            lockAfter = lockAfter?.plusClamped(jumpMillis)
        )
    }

    private fun Long.plusClamped(delta: Long): Long {
        val result = this + delta
        // Estouro: mantém o extremo em vez de virar o sinal.
        return when {
            delta > 0 && result < this -> Long.MAX_VALUE
            delta < 0 && result > this -> Long.MIN_VALUE
            else -> result
        }
    }
}

/** A âncora do relógio gravada a cada reconciliação. */
internal class ClockJumpAnchorStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(): ClockJumpPolicy.Anchor? {
        if (!prefs.contains(KEY_BOOT_COUNT) || !prefs.contains(KEY_BOOT_WALL_CLOCK)) return null
        return ClockJumpPolicy.Anchor(
            bootCount = prefs.getInt(KEY_BOOT_COUNT, -1),
            bootWallClockMillis = prefs.getLong(KEY_BOOT_WALL_CLOCK, 0L),
            autoTimeEnabled = prefs.getBoolean(KEY_AUTO_TIME, true)
        )
    }

    fun save(anchor: ClockJumpPolicy.Anchor) {
        prefs.edit()
            .putInt(KEY_BOOT_COUNT, anchor.bootCount)
            .putLong(KEY_BOOT_WALL_CLOCK, anchor.bootWallClockMillis)
            .putBoolean(KEY_AUTO_TIME, anchor.autoTimeEnabled)
            .apply()
    }

    /** O estado do relógio agora; null quando o Android não diz o número do boot. */
    fun current(): ClockJumpPolicy.Anchor? {
        val resolver = appContext.contentResolver
        val bootCount = runCatching {
            Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT)
        }.getOrNull() ?: return null
        val autoTime = runCatching {
            Settings.Global.getInt(resolver, Settings.Global.AUTO_TIME, 1) != 0
        }.getOrDefault(true)
        return ClockJumpPolicy.Anchor(
            bootCount = bootCount,
            bootWallClockMillis = System.currentTimeMillis() - SystemClock.elapsedRealtime(),
            autoTimeEnabled = autoTime
        )
    }

    private companion object {
        const val PREFS_NAME = "clock_jump_anchor"
        const val KEY_BOOT_COUNT = "boot_count"
        const val KEY_BOOT_WALL_CLOCK = "boot_wall_clock_millis"
        const val KEY_AUTO_TIME = "auto_time_enabled"
    }
}
