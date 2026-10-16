package com.focusguard.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.focusguard.R
import com.focusguard.database.AppUsageLimit
import com.focusguard.database.WebsiteUsageLimit
import com.focusguard.receiver.BlockingScheduleCalculator
import com.focusguard.receiver.BlockingScheduleReceiver
import java.util.Calendar
import java.util.Locale
import kotlin.math.min

/**
 * Product policy for app daily-usage limits.
 *
 * App limits intentionally have only two user-facing outcomes after the daily allowance is used:
 * a single 30-minute pause for that local day, or a block until the next local-day reset.
 * The overall rule deadline is independent from that daily behavior.
 */
object UsageLimitBehaviorPolicy {
    const val PAUSE_30_PREFIX = "PAUSE_30:"
    const val BLOCK_UNTIL_TOMORROW_PREFIX = "BLOCK_UNTIL_TOMORROW:"
    const val PAUSE_DURATION_MILLIS = 30L * 60L * 1_000L

    /** Um dia não tem mais que isso: acima, o "limite" nunca seria alcançado. */
    const val MAX_DAILY_LIMIT_MINUTES = 24 * 60

    enum class RuleDurationUnit {
        DAYS,
        WEEKS,
        MONTHS
    }

    fun pauseModeFor(identifier: String): String =
        PAUSE_30_PREFIX + identifier.trim()

    fun blockUntilTomorrowModeFor(identifier: String): String =
        BLOCK_UNTIL_TOMORROW_PREFIX + identifier.trim()

    fun isPauseMode(lockMode: String): Boolean =
        lockMode.uppercase(Locale.ROOT).startsWith(PAUSE_30_PREFIX)

    fun isBlockUntilTomorrowMode(lockMode: String): Boolean =
        lockMode.uppercase(Locale.ROOT).startsWith(BLOCK_UNTIL_TOMORROW_PREFIX)

    fun isDailyBehaviorMode(lockMode: String): Boolean =
        isPauseMode(lockMode) || isBlockUntilTomorrowMode(lockMode)

    fun identifierFrom(lockMode: String): String =
        lockMode.substringAfter(':', missingDelimiterValue = "").trim()

    fun isValidDailyLimitMinutes(minutes: Int): Boolean =
        minutes in 1..MAX_DAILY_LIMIT_MINUTES

    fun isRuleActive(ruleEndMillis: Long?, nowMillis: Long): Boolean =
        ruleEndMillis?.let { it > nowMillis } == true

    /**
     * O limite ainda vale: ligado e, nos dois modos diários, antes do fim da regra.
     * Uma regra encerrada nunca mais bloqueia, então não conta como limite ativo
     * (por exemplo, para impedir a criação da senha mestra ou aparecer entre os
     * bloqueios em vigor).
     */
    fun isInForce(
        isEnabled: Boolean,
        lockMode: String,
        ruleEndMillis: Long?,
        nowMillis: Long
    ): Boolean = isEnabled &&
        (!isDailyBehaviorMode(lockMode) || isRuleActive(ruleEndMillis, nowMillis))

    fun isInForce(limit: AppUsageLimit, nowMillis: Long): Boolean =
        isInForce(limit.isEnabled, limit.lockMode, limit.lockUntilTimestamp, nowMillis)

    fun isInForce(limit: WebsiteUsageLimit, nowMillis: Long): Boolean =
        isInForce(limit.isEnabled, limit.lockMode, limit.lockUntilTimestamp, nowMillis)

    /**
     * Keeps the exact persisted deadline when an edit only changes allowance or
     * post-limit behavior. Recomputing from an approximate "days remaining" value
     * would silently extend an existing rule every time the editor was saved.
     */
    internal fun resolveRuleEndForEdit(
        existingRuleEndMillis: Long?,
        durationEdited: Boolean,
        calculatedRuleEndMillis: Long?
    ): Long? = if (!durationEdited && existingRuleEndMillis != null) {
        existingRuleEndMillis
    } else {
        calculatedRuleEndMillis
    }

    /**
     * Uses calendar arithmetic so "2 months" means two calendar months, not a fixed 60-day guess.
     * The rule remains valid through the selected final local day.
     */
    fun calculateRuleEndMillis(
        nowMillis: Long,
        amount: Int,
        unit: RuleDurationUnit
    ): Long? {
        if (amount <= 0) return null
        val calendar = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            when (unit) {
                RuleDurationUnit.DAYS -> add(Calendar.DAY_OF_YEAR, amount)
                RuleDurationUnit.WEEKS -> add(Calendar.WEEK_OF_YEAR, amount)
                RuleDurationUnit.MONTHS -> add(Calendar.MONTH, amount)
            }
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
        return calendar.timeInMillis
    }

    internal fun localDayKey(nowMillis: Long): Long {
        val calendar = Calendar.getInstance().apply { timeInMillis = nowMillis }
        return calendar.get(Calendar.YEAR).toLong() * 1_000L +
            calendar.get(Calendar.DAY_OF_YEAR).toLong()
    }

    internal data class PauseState(
        val ruleEndMillis: Long,
        val dayKey: Long,
        val blockedUntilMillis: Long
    )

    internal data class PauseEvaluation(
        val shouldBlock: Boolean,
        val state: PauseState?,
        val startedPause: Boolean
    )

    internal fun evaluatePause(
        previous: PauseState?,
        ruleEndMillis: Long,
        nowMillis: Long,
        dayKey: Long = localDayKey(nowMillis)
    ): PauseEvaluation {
        if (ruleEndMillis <= nowMillis) {
            return PauseEvaluation(false, null, false)
        }

        val sameRuleAndDay = previous != null &&
            previous.ruleEndMillis == ruleEndMillis &&
            previous.dayKey == dayKey
        if (!sameRuleAndDay) {
            val blockedUntil = min(nowMillis + PAUSE_DURATION_MILLIS, ruleEndMillis)
            val newState = PauseState(
                ruleEndMillis = ruleEndMillis,
                dayKey = dayKey,
                blockedUntilMillis = blockedUntil
            )
            return PauseEvaluation(
                shouldBlock = blockedUntil > nowMillis,
                state = newState,
                startedPause = blockedUntil > nowMillis
            )
        }

        return PauseEvaluation(
            shouldBlock = nowMillis < previous.blockedUntilMillis,
            state = previous,
            startedPause = false
        )
    }

    /**
     * A mesma pausa com o relógio mudado à mão: o fim da regra e o fim da pausa
     * andam juntos com o limite, então a pausa continua sendo da mesma regra e o
     * tempo real que falta dela não muda.
     */
    internal fun shiftedPause(state: PauseState, shift: (Long) -> Long): PauseState =
        state.copy(
            ruleEndMillis = shift(state.ruleEndMillis),
            blockedUntilMillis = shift(state.blockedUntilMillis)
        )

    /**
     * O "bloquear até amanhã" gravado quando a regra se esgota: até a meia-noite
     * seguinte o alvo fica fechado mesmo que a conta do dia zere antes disso.
     */
    internal data class DailyBlockState(
        val ruleEndMillis: Long,
        val dayKey: Long,
        /** Null nos registros de versões antigas, que só guardavam o aviso do dia. */
        val blockedUntilMillis: Long?
    )

    /**
     * O registro a gravar quando a regra está esgotada agora, ou null quando o de
     * hoje já existe. Um bloqueio ainda maior da mesma regra (relógio mudado à mão)
     * nunca é encurtado.
     */
    internal fun dailyBlockToRecord(
        previous: DailyBlockState?,
        ruleEndMillis: Long,
        nowMillis: Long,
        dayKey: Long = localDayKey(nowMillis)
    ): DailyBlockState? {
        val sameRule = previous?.ruleEndMillis == ruleEndMillis
        if (sameRule && previous?.dayKey == dayKey && previous.blockedUntilMillis != null) {
            return null
        }
        val untilMidnight = BlockingScheduleCalculator.nextLocalMidnight(nowMillis)
        val keptUntil = previous?.blockedUntilMillis?.takeIf { sameRule }
        return DailyBlockState(
            ruleEndMillis = ruleEndMillis,
            dayKey = dayKey,
            blockedUntilMillis = maxOf(untilMidnight, keptUntil ?: Long.MIN_VALUE)
        )
    }

    /**
     * Até quando uma regra que já se esgotou continua segurando o alvo, sem depender
     * do uso medido agora: a pausa de 30 minutos em curso ou o bloqueio até a
     * meia-noite. Sem isso, tudo que zera a conta (adiantar o relógio para logo
     * depois da meia-noite, revogar o Acesso de uso) liberava o alvo na hora.
     */
    internal fun heldUntil(
        pause: PauseState?,
        dailyBlock: DailyBlockState?,
        lockMode: String,
        ruleEndMillis: Long?,
        nowMillis: Long
    ): Long? {
        val ruleEnd = ruleEndMillis?.takeIf { it > nowMillis } ?: return null
        val until = when {
            isPauseMode(lockMode) ->
                pause?.takeIf { it.ruleEndMillis == ruleEnd }?.blockedUntilMillis
            isBlockUntilTomorrowMode(lockMode) ->
                dailyBlock?.takeIf { it.ruleEndMillis == ruleEnd }?.blockedUntilMillis
            else -> null
        } ?: return null
        return until.takeIf { it > nowMillis }?.let { minOf(it, ruleEnd) }
    }

    internal fun pauseEndDeadline(
        evaluation: PauseEvaluation,
        nowMillis: Long
    ): Long? = evaluation.state
        ?.blockedUntilMillis
        ?.takeIf { evaluation.shouldBlock && it > nowMillis }
}

/**
 * Persistent per-target state of exhausted daily rules: the once-per-day 30-minute
 * pause and the block until midnight (which also drives the once-per-day notice).
 */
object UsageLimitPauseStateStore {
    private const val PREFS_NAME = "usage_limit_pause_state"
    private const val KEY_RULE_END = "rule_end_"
    private const val KEY_DAY = "day_"
    private const val KEY_BLOCKED_UNTIL = "blocked_until_"
    private const val KEY_DAILY_NOTICE_RULE_END = "daily_notice_rule_end_"
    private const val KEY_DAILY_NOTICE_DAY = "daily_notice_day_"
    private const val KEY_DAILY_BLOCK_UNTIL = "daily_block_until_"

    private val stateLock = Any()
    @Volatile private var storageContext: Context? = null
    private val memoryPauseStates = mutableMapOf<String, UsageLimitBehaviorPolicy.PauseState>()
    private val memoryDailyBlocks = mutableMapOf<String, UsageLimitBehaviorPolicy.DailyBlockState>()

    fun initialize(context: Context) {
        // Keep the supplied context rather than replacing it with applicationContext: during
        // locked boot this can intentionally be a device-protected-storage context.
        storageContext = context
    }

    fun shouldBlockForPause(
        lockMode: String,
        ruleEndMillis: Long?,
        nowMillis: Long
    ): Boolean {
        val end = ruleEndMillis ?: return false
        val identifier = UsageLimitBehaviorPolicy.identifierFrom(lockMode)
            .ifBlank { "rule_$end" }
        val storageKey = safeKey(identifier)

        val evaluation = synchronized(stateLock) {
            val previous = readPauseState(storageKey)
            val decision = UsageLimitBehaviorPolicy.evaluatePause(
                previous = previous,
                ruleEndMillis = end,
                nowMillis = nowMillis
            )
            if (decision.state == null) {
                clearPauseState(storageKey)
            } else if (decision.state != previous) {
                writePauseState(storageKey, decision.state)
            }
            decision
        }

        if (evaluation.startedPause) {
            notifyUser(R.string.limits_pause_notice)
        }
        UsageLimitBehaviorPolicy.pauseEndDeadline(evaluation, nowMillis)?.let { blockedUntil ->
            storageContext?.let { context ->
                BlockingScheduleReceiver.scheduleUsageLimitPauseEnd(
                    context = context,
                    identifier = identifier,
                    atMillis = blockedUntil
                )
            }
        }
        return evaluation.shouldBlock
    }

    /**
     * Até quando a regra, já esgotada, segura o alvo independentemente do uso medido
     * agora (ver [UsageLimitBehaviorPolicy.heldUntil]), sem alterar o estado.
     */
    fun heldUntil(lockMode: String, ruleEndMillis: Long?, nowMillis: Long): Long? {
        if (!UsageLimitBehaviorPolicy.isDailyBehaviorMode(lockMode)) return null
        val end = ruleEndMillis ?: return null
        val storageKey = safeKey(
            UsageLimitBehaviorPolicy.identifierFrom(lockMode).ifBlank { "rule_$end" }
        )
        return synchronized(stateLock) {
            UsageLimitBehaviorPolicy.heldUntil(
                pause = readPauseState(storageKey),
                dailyBlock = readDailyBlock(storageKey),
                lockMode = lockMode,
                ruleEndMillis = end,
                nowMillis = nowMillis
            )
        }
    }

    /**
     * Revokes the once-per-day PAUSE_30 release for one configured rule without
     * touching the rule deadline. This is deliberately synchronous when backed by
     * SharedPreferences because package replacement must finish clearing releases
     * before blocking policy is reconciled again.
     */
    fun clearTemporaryReleaseFor(lockMode: String): Boolean {
        if (!UsageLimitBehaviorPolicy.isPauseMode(lockMode)) return false
        val identifier = UsageLimitBehaviorPolicy.identifierFrom(lockMode)
            .takeIf(String::isNotBlank) ?: return false
        val storageKey = safeKey(identifier)
        return synchronized(stateLock) {
            clearPauseState(storageKey, synchronous = true)
        }
    }

    /**
     * Revokes every persisted PAUSE_30 release, including stale entries no longer
     * represented by a database row. Daily-notice bookkeeping is intentionally
     * preserved because it cannot grant access. Returns the number of distinct
     * targets whose pause-release state existed.
     */
    fun clearAllTemporaryReleases(): Int = synchronized(stateLock) {
        val memoryKeys = memoryPauseStates.keys.toSet()
        memoryPauseStates.clear()

        val context = storageContext ?: return@synchronized memoryKeys.size
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val persistedKeys = linkedSetOf<String>()
        prefs.all.keys.forEach { rawKey ->
            when {
                rawKey.startsWith(KEY_RULE_END) ->
                    persistedKeys += rawKey.removePrefix(KEY_RULE_END)
                rawKey.startsWith(KEY_DAY) ->
                    persistedKeys += rawKey.removePrefix(KEY_DAY)
                rawKey.startsWith(KEY_BLOCKED_UNTIL) ->
                    persistedKeys += rawKey.removePrefix(KEY_BLOCKED_UNTIL)
            }
        }

        if (persistedKeys.isNotEmpty()) {
            val editor = prefs.edit()
            persistedKeys.forEach { key ->
                editor.remove(KEY_RULE_END + key)
                    .remove(KEY_DAY + key)
                    .remove(KEY_BLOCKED_UNTIL + key)
            }
            editor.commit()
        }
        (memoryKeys + persistedKeys).size
    }

    /**
     * Leva a pausa em curso e o bloqueio do dia de uma regra junto com o fim dela
     * quando o relógio é mudado à mão. Sem isso, adiantar o relógio encerrava a
     * pausa de 30 minutos (ou o bloqueio até a meia-noite) na hora.
     */
    fun shiftForClockJump(lockMode: String, shift: (Long) -> Long) {
        if (!UsageLimitBehaviorPolicy.isDailyBehaviorMode(lockMode)) return
        val identifier = UsageLimitBehaviorPolicy.identifierFrom(lockMode)
            .takeIf(String::isNotBlank) ?: return
        val storageKey = safeKey(identifier)
        synchronized(stateLock) {
            readPauseState(storageKey)?.let { state ->
                writePauseState(storageKey, UsageLimitBehaviorPolicy.shiftedPause(state, shift))
            }
            readDailyBlock(storageKey)?.let { state ->
                writeDailyBlock(
                    storageKey,
                    state.copy(
                        ruleEndMillis = shift(state.ruleEndMillis),
                        blockedUntilMillis = state.blockedUntilMillis?.let(shift)
                    )
                )
            }
        }
    }

    /**
     * Esquece a pausa e o bloqueio do dia de um alvo. Para quando o limite dele é
     * salvo ou removido: um limite com mais minutos, ou criado de novo no mesmo dia,
     * não pode herdar o "esgotado" do anterior.
     */
    fun clearDailyStateFor(identifier: String) {
        val storageKey = safeKey(identifier.trim().takeIf(String::isNotEmpty) ?: return)
        synchronized(stateLock) {
            clearPauseState(storageKey)
            clearDailyBlock(storageKey)
        }
    }

    /** O mesmo que [clearDailyStateFor] para todos os alvos (todos os limites removidos). */
    fun clearAllDailyStates() = synchronized(stateLock) {
        memoryPauseStates.clear()
        memoryDailyBlocks.clear()
        val context = storageContext ?: return@synchronized
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    /**
     * Grava o bloqueio até a meia-noite da regra esgotada agora e avisa uma vez por
     * dia. Chamado a cada vez que a regra é vista esgotada.
     */
    fun recordDailyBlock(
        lockMode: String,
        ruleEndMillis: Long?,
        nowMillis: Long
    ) {
        val end = ruleEndMillis ?: return
        if (end <= nowMillis) return
        val identifier = UsageLimitBehaviorPolicy.identifierFrom(lockMode)
            .ifBlank { "rule_$end" }
        val storageKey = safeKey(identifier)
        val dayKey = UsageLimitBehaviorPolicy.localDayKey(nowMillis)

        val shouldNotify = synchronized(stateLock) {
            val previous = readDailyBlock(storageKey)
            UsageLimitBehaviorPolicy.dailyBlockToRecord(
                previous = previous,
                ruleEndMillis = end,
                nowMillis = nowMillis,
                dayKey = dayKey
            )?.let { writeDailyBlock(storageKey, it) }
            previous?.ruleEndMillis != end || previous.dayKey != dayKey
        }
        if (shouldNotify) notifyUser(R.string.limits_daily_lock_notice)
    }

    private fun readPauseState(key: String): UsageLimitBehaviorPolicy.PauseState? {
        val context = storageContext ?: return memoryPauseStates[key]
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val end = prefs.getLong(KEY_RULE_END + key, Long.MIN_VALUE)
        val day = prefs.getLong(KEY_DAY + key, Long.MIN_VALUE)
        val until = prefs.getLong(KEY_BLOCKED_UNTIL + key, Long.MIN_VALUE)
        if (end == Long.MIN_VALUE || day == Long.MIN_VALUE || until == Long.MIN_VALUE) return null
        return UsageLimitBehaviorPolicy.PauseState(end, day, until)
    }

    private fun writePauseState(key: String, state: UsageLimitBehaviorPolicy.PauseState) {
        val context = storageContext
        if (context == null) {
            memoryPauseStates[key] = state
            return
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong(KEY_RULE_END + key, state.ruleEndMillis)
            .putLong(KEY_DAY + key, state.dayKey)
            .putLong(KEY_BLOCKED_UNTIL + key, state.blockedUntilMillis)
            .apply()
    }

    private fun clearPauseState(key: String, synchronous: Boolean = false): Boolean {
        val hadMemoryState = memoryPauseStates.remove(key) != null
        val context = storageContext ?: return hadMemoryState
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hadPersistedState = prefs.contains(KEY_RULE_END + key) ||
            prefs.contains(KEY_DAY + key) ||
            prefs.contains(KEY_BLOCKED_UNTIL + key)
        if (hadPersistedState) {
            val editor = prefs.edit()
                .remove(KEY_RULE_END + key)
                .remove(KEY_DAY + key)
                .remove(KEY_BLOCKED_UNTIL + key)
            if (synchronous) editor.commit() else editor.apply()
        }
        return hadMemoryState || hadPersistedState
    }

    private fun readDailyBlock(key: String): UsageLimitBehaviorPolicy.DailyBlockState? {
        val context = storageContext ?: return memoryDailyBlocks[key]
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val end = prefs.getLong(KEY_DAILY_NOTICE_RULE_END + key, Long.MIN_VALUE)
        val day = prefs.getLong(KEY_DAILY_NOTICE_DAY + key, Long.MIN_VALUE)
        if (end == Long.MIN_VALUE || day == Long.MIN_VALUE) return null
        val until = prefs.getLong(KEY_DAILY_BLOCK_UNTIL + key, Long.MIN_VALUE)
            .takeIf { it != Long.MIN_VALUE }
        return UsageLimitBehaviorPolicy.DailyBlockState(end, day, until)
    }

    private fun writeDailyBlock(key: String, state: UsageLimitBehaviorPolicy.DailyBlockState) {
        val context = storageContext
        if (context == null) {
            memoryDailyBlocks[key] = state
            return
        }
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong(KEY_DAILY_NOTICE_RULE_END + key, state.ruleEndMillis)
            .putLong(KEY_DAILY_NOTICE_DAY + key, state.dayKey)
        val until = state.blockedUntilMillis
        if (until == null) {
            editor.remove(KEY_DAILY_BLOCK_UNTIL + key)
        } else {
            editor.putLong(KEY_DAILY_BLOCK_UNTIL + key, until)
        }
        editor.apply()
    }

    private fun clearDailyBlock(key: String) {
        memoryDailyBlocks.remove(key)
        val context = storageContext ?: return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_DAILY_NOTICE_RULE_END + key)
            .remove(KEY_DAILY_NOTICE_DAY + key)
            .remove(KEY_DAILY_BLOCK_UNTIL + key)
            .apply()
    }

    private fun notifyUser(messageRes: Int) {
        val context = storageContext ?: return
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_LONG).show()
        }
    }

    private fun safeKey(identifier: String): String =
        identifier.replace(Regex("[^A-Za-z0-9_.-]"), "_")
}
