package com.focusguard.sitesblocker

import android.content.Context

/**
 * Lista de sites e opção "Bloquear pornografia" da antiga tela Configurações > Bloquear sites
 * (BlockedSitesStore). A tela saiu do app, mas o motor continuava lendo o que ela gravou: os
 * sites postos ali seguiam bloqueados sem aparecer em nenhum bloqueio e sem como tirá-los, e os
 * navegadores sem suporte seguiam fechados. Agora só os bloqueios do FocusGuard valem, e o
 * arquivo que sobrou é apagado.
 */
object LegacySitesList {
    internal const val PREFS = "blocked_sites"

    fun delete(context: Context): Boolean =
        context.applicationContext.deleteSharedPreferences(PREFS)
}
