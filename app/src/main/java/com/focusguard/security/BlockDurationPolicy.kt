package com.focusguard.security

import com.focusguard.utils.WebsiteBlocker
import java.util.concurrent.TimeUnit

/**
 * Converts "30 dias" into the hours the session layer actually stores.
 *
 * Pure so the conversions and the edge cases are testable: a wrong multiplier
 * here arms an irrevocable fast for the wrong length, and the user has no way to
 * correct it afterwards.
 */
object BlockDurationPolicy {

    /** Months are rendered as 30 days — the app never promises calendar months. */
    private const val DAYS_PER_MONTH = 30

    /** Ceiling for a finite block, matching the 120-day cap used elsewhere. */
    const val MAX_DAYS = 120

    enum class Unit {
        HOURS,
        DAYS,
        MONTHS,

        /**
         * No end date. Stored as an open-ended session rather than a huge number
         * of days, so the UI can say "para sempre" instead of counting down from
         * an arbitrary figure.
         */
        FOREVER
    }

    /** Whether the amount field should be shown at all. */
    fun requiresAmount(unit: Unit): Boolean = unit != Unit.FOREVER

    /**
     * "Para sempre" só existe para pornografia.
     *
     * Um bloqueio sem data final é a única decisão do app que o próprio usuário
     * não consegue desfazer, e ela só se justifica onde a pessoa não quer voltar
     * nunca. Prender Instagram ou um jogo para sempre é sobretudo uma armadilha
     * — o arrependimento é provável e não há saída. Para pornografia é o pedido
     * literal de quem arma o bloqueio.
     *
     * @param rules regras de site do alvo, já normalizadas ou não.
     * @param hasApps se algum aplicativo foi escolhido junto. Um app na lista
     *   basta para tirar o "para sempre": ele não é pornografia por categoria e
     *   ficaria preso pelo mesmo prazo infinito.
     */
    fun allowsForever(rules: Collection<String>, hasApps: Boolean): Boolean {
        if (hasApps) return false
        val normalized = WebsiteBlocker.normalizeRules(rules)
        return normalized.isNotEmpty() && normalized.all(WebsiteBlocker::isPornographyRule)
    }

    /** Unidades oferecidas ao usuário para o alvo escolhido. */
    fun availableUnits(rules: Collection<String>, hasApps: Boolean): List<Unit> =
        if (allowsForever(rules, hasApps)) {
            Unit.entries
        } else {
            Unit.entries.filterNot { it == Unit.FOREVER }
        }

    sealed interface Duration {
        /** Finite block; [totalHours] is what the session layer stores. */
        data class Finite(val totalHours: Int) : Duration

        /** Open-ended: no deadline is written. */
        data object Forever : Duration
    }

    /**
     * @return null when the input cannot arm anything — a blank or zero amount on
     *   a unit that needs one. Callers should keep the confirm button disabled
     *   rather than guessing a default.
     */
    fun resolve(unit: Unit, amount: Int?): Duration? {
        if (unit == Unit.FOREVER) return Duration.Forever

        val value = amount ?: return null
        if (value <= 0) return null

        val hours = when (unit) {
            Unit.HOURS -> value.toLong()
            Unit.DAYS -> TimeUnit.DAYS.toHours(value.toLong())
            Unit.MONTHS -> TimeUnit.DAYS.toHours(value.toLong() * DAYS_PER_MONTH)
            Unit.FOREVER -> return Duration.Forever
        }

        val cappedHours = TimeUnit.DAYS.toHours(MAX_DAYS.toLong())
        return Duration.Finite(hours.coerceAtMost(cappedHours).toInt())
    }

    /** Largest amount that still makes sense for the unit, for input clamping. */
    fun maxAmountFor(unit: Unit): Int = when (unit) {
        Unit.HOURS -> MAX_DAYS * 24
        Unit.DAYS -> MAX_DAYS
        Unit.MONTHS -> MAX_DAYS / DAYS_PER_MONTH
        Unit.FOREVER -> 0
    }
}
