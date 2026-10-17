package com.focusguard.security

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.focusguard.database.AppUsageLimit
import com.focusguard.database.BlockSession
import com.focusguard.database.WebsiteUsageLimit
import com.focusguard.utils.UsageLimitBehaviorPolicy
import kotlin.math.abs

/**
 * Bloqueio por tempo à prova de relógio adiantado à mão.
 *
 * Sem Device Owner nada impede a pessoa de desligar a hora automática e adiantar a
 * data: o jejum vencia na hora e a reconciliação o desativava de vez, mesmo com o
 * relógio voltando depois. O relógio desde o boot (elapsedRealtime) não muda com a
 * data; a diferença entre os dois, o "boot no relógio de parede", só muda quando a
 * data é trocada. Um salto dessa diferença no mesmo boot desloca os prazos dos
 * bloqueios por tempo (fim, janela de cancelamento) e dos limites de apps e de sites
 * (fim da regra, trava de edição, ativação, a pausa de 30 minutos e o bloqueio até a
 * meia-noite em curso) pelo mesmo tanto: o tempo real que falta continua o mesmo,
 * para a frente e para trás.
 *
 * O ajuste da rede com a hora automática ligada (antes e agora) é a hora certa e não
 * é compensado; isso também cobre o aparelho que liga com a hora errada e é
 * corrigido pela rede. Voltar à hora automática depois de mudar à mão desfaz a
 * compensação, porque a âncora gravada era de hora manual.
 */
internal object ClockJumpPolicy {
    /** Ajustes menores que isso são deriva do relógio, não troca de data. */
    const val MIN_COMPENSATED_JUMP_MILLIS = 60_000L

    /** Número do boot ou tempo desde o boot que o aparelho não informou. */
    const val UNKNOWN = -1

    data class Anchor(
        /** [UNKNOWN] quando o Android não informa o número do boot. */
        val bootCount: Int,
        val bootWallClockMillis: Long,
        val autoTimeEnabled: Boolean,
        /** A hora de parede em que a âncora foi gravada. */
        val wallClockMillis: Long = Long.MAX_VALUE,
        /** O tempo desde o boot em que a âncora foi gravada; negativo se desconhecido. */
        val elapsedRealtimeMillis: Long = UNKNOWN.toLong()
    )

    /** O salto a compensar desde [anchor]; 0 quando não há o que compensar. */
    fun compensatedJumpMillis(anchor: Anchor?, current: Anchor?): Long {
        if (anchor == null || current == null) return 0L
        // Outro boot: o relógio desde o boot recomeçou e a diferença não diz nada.
        if (!sameBoot(anchor, current)) return 0L
        val jump = current.bootWallClockMillis - anchor.bootWallClockMillis
        if (abs(jump) < MIN_COMPENSATED_JUMP_MILLIS) return 0L
        if (anchor.autoTimeEnabled && current.autoTimeEnabled) return 0L
        return jump
    }

    /**
     * Se as duas âncoras são do mesmo boot. Sem o número do boot (alguns aparelhos
     * não o informam, e então a compensação nunca rodava), vale o tempo desde o boot:
     * ele só volta para trás quando o aparelho reinicia.
     */
    internal fun sameBoot(anchor: Anchor, current: Anchor): Boolean = when {
        anchor.bootCount != UNKNOWN && current.bootCount != UNKNOWN ->
            anchor.bootCount == current.bootCount
        anchor.elapsedRealtimeMillis >= 0L && current.elapsedRealtimeMillis >= 0L ->
            current.elapsedRealtimeMillis >= anchor.elapsedRealtimeMillis
        else -> false
    }

    /**
     * O jejum (ou período com prazo) visto com o mesmo tempo real restante. Só o que
     * já existia na última reconciliação ([anchorWallClockMillis]) está no relógio
     * antigo; um bloqueio criado depois já nasceu com a hora nova.
     */
    fun shiftSession(
        session: BlockSession,
        jumpMillis: Long,
        anchorWallClockMillis: Long = Long.MAX_VALUE
    ): BlockSession? {
        val endTime = session.endTime ?: return null
        if (!session.isActive || session.createdAt > anchorWallClockMillis) return null
        return session.copy(
            startTime = session.startTime.plusClamped(jumpMillis),
            endTime = endTime.plusClamped(jumpMillis),
            cancelableUntil = session.cancelableUntil?.plusClamped(jumpMillis),
            // A criação também anda: com ela na hora antiga, um segundo salto para
            // trás deixava o bloqueio de fora como se tivesse sido criado depois, e
            // a janela de cancelamento (já vencida) voltava a valer.
            createdAt = session.createdAt.plusClamped(jumpMillis)
        )
    }

    /**
     * O limite com os mesmos prazos em tempo real: o fim da regra (o "sem senha por
     * vários dias" e a duração escolhida para os modos diários), o momento a partir
     * do qual ele não pode mais ser editado nem removido (atrasar o relógio à mão o
     * reabria para remoção) e a ativação.
     *
     * Adiantar o relógio além do fim de uma regra diária a dava por encerrada, e uma
     * regra encerrada pode ser removida. A ativação acompanha o histórico de uso, que
     * o Android move pelo mesmo tanto: sem isso, atrasar o relógio para antes dela
     * zerava o uso contado do dia.
     */
    fun shiftLimit(
        limit: AppUsageLimit,
        jumpMillis: Long,
        anchorWallClockMillis: Long = Long.MAX_VALUE
    ): AppUsageLimit? = shiftedLimitDeadlines(
        createdAt = limit.createdAt,
        lockMode = limit.lockMode,
        lockUntilTimestamp = limit.lockUntilTimestamp,
        lockAfter = limit.lockAfter,
        jumpMillis = jumpMillis,
        anchorWallClockMillis = anchorWallClockMillis
    )?.let { (createdAt, lockUntil, lockAfter) ->
        limit.copy(createdAt = createdAt, lockUntilTimestamp = lockUntil, lockAfter = lockAfter)
    }

    /** O mesmo que [shiftLimit] para um limite de site ou palavra. */
    fun shiftWebsiteLimit(
        limit: WebsiteUsageLimit,
        jumpMillis: Long,
        anchorWallClockMillis: Long = Long.MAX_VALUE
    ): WebsiteUsageLimit? = shiftedLimitDeadlines(
        createdAt = limit.createdAt,
        lockMode = limit.lockMode,
        lockUntilTimestamp = limit.lockUntilTimestamp,
        lockAfter = limit.lockAfter,
        jumpMillis = jumpMillis,
        anchorWallClockMillis = anchorWallClockMillis
    )?.let { (createdAt, lockUntil, lockAfter) ->
        limit.copy(createdAt = createdAt, lockUntilTimestamp = lockUntil, lockAfter = lockAfter)
    }

    /** Ativação, fim da regra e trava de edição de um limite deslocados; null: nada a mover. */
    private fun shiftedLimitDeadlines(
        createdAt: Long,
        lockMode: String,
        lockUntilTimestamp: Long?,
        lockAfter: Long?,
        jumpMillis: Long,
        anchorWallClockMillis: Long
    ): Triple<Long, Long?, Long?>? {
        if (jumpMillis == 0L || createdAt > anchorWallClockMillis) return null
        // No modo PASSWORD o horário é uma liberação até a meia-noite, não um prazo.
        val ruleEnd = lockUntilTimestamp?.takeIf {
            lockMode.equals(MasterCredentialPolicy.LOCK_MODE_TIME, ignoreCase = true) ||
                UsageLimitBehaviorPolicy.isDailyBehaviorMode(lockMode)
        }
        return Triple(
            createdAt.plusClamped(jumpMillis),
            ruleEnd?.plusClamped(jumpMillis) ?: lockUntilTimestamp,
            lockAfter?.plusClamped(jumpMillis)
        )
    }

    /** [valueMillis] deslocado pelo salto, saturando nos extremos. */
    fun shifted(valueMillis: Long, jumpMillis: Long): Long = valueMillis.plusClamped(jumpMillis)

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
            bootCount = prefs.getInt(KEY_BOOT_COUNT, ClockJumpPolicy.UNKNOWN),
            bootWallClockMillis = prefs.getLong(KEY_BOOT_WALL_CLOCK, 0L),
            autoTimeEnabled = prefs.getBoolean(KEY_AUTO_TIME, true),
            wallClockMillis = prefs.getLong(KEY_WALL_CLOCK, Long.MAX_VALUE),
            elapsedRealtimeMillis = prefs.getLong(
                KEY_ELAPSED_REALTIME,
                ClockJumpPolicy.UNKNOWN.toLong()
            )
        )
    }

    fun save(anchor: ClockJumpPolicy.Anchor) {
        prefs.edit()
            .putInt(KEY_BOOT_COUNT, anchor.bootCount)
            .putLong(KEY_BOOT_WALL_CLOCK, anchor.bootWallClockMillis)
            .putBoolean(KEY_AUTO_TIME, anchor.autoTimeEnabled)
            .putLong(KEY_WALL_CLOCK, anchor.wallClockMillis)
            .putLong(KEY_ELAPSED_REALTIME, anchor.elapsedRealtimeMillis)
            .apply()
    }

    /** O estado do relógio agora. */
    fun current(): ClockJumpPolicy.Anchor {
        val resolver = appContext.contentResolver
        val bootCount = runCatching {
            Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT)
        }.getOrDefault(ClockJumpPolicy.UNKNOWN)
        val autoTime = runCatching {
            Settings.Global.getInt(resolver, Settings.Global.AUTO_TIME, 1) != 0
        }.getOrDefault(true)
        val wallClock = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        return ClockJumpPolicy.Anchor(
            bootCount = bootCount,
            bootWallClockMillis = wallClock - elapsed,
            autoTimeEnabled = autoTime,
            wallClockMillis = wallClock,
            elapsedRealtimeMillis = elapsed
        )
    }

    private companion object {
        const val PREFS_NAME = "clock_jump_anchor"
        const val KEY_BOOT_COUNT = "boot_count"
        const val KEY_BOOT_WALL_CLOCK = "boot_wall_clock_millis"
        const val KEY_AUTO_TIME = "auto_time_enabled"
        const val KEY_WALL_CLOCK = "wall_clock_millis"
        const val KEY_ELAPSED_REALTIME = "elapsed_realtime_millis"
    }
}
