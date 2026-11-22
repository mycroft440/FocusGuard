package com.focusguard.utils

import java.time.LocalDate

/**
 * Quanto tempo o uso diário de sites (tabela daily_usage_stats) fica guardado.
 *
 * Os limites só leem o dia de hoje; os dias antigos ficam pelo mesmo prazo do
 * registro de uso por app e depois saem, para a tabela não crescer para sempre.
 */
internal object WebsiteUsageRetention {
    const val RETENTION_DAYS = 62L

    /**
     * Primeiro dia (yyyy-MM-dd) que continua guardado; os anteriores podem sair.
     * Null quando [today] não é uma data válida: nesse caso nada é apagado.
     */
    fun oldestDateToKeep(today: String): String? = runCatching {
        LocalDate.parse(today).minusDays(RETENTION_DAYS).toString()
    }.getOrNull()
}
