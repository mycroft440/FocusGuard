package com.focusguard.ui.compose.navigation

/**
 * Para onde o Voltar leva dentro da tela inicial (as abas da barra de baixo).
 *
 * A Proteção é a aba de partida. Nas outras (Métricas, Pomodoro, Modo Foco parado
 * e Fórum) o Voltar fechava o app inteiro; agora ele volta para a Proteção, como
 * a pessoa espera de uma barra de abas, e só nela o Voltar sai do app. Com o Modo
 * Foco ativo quem decide é o próprio modo, que sempre devolve à aba dele.
 */
object MainTabBackPolicy {
    const val METRICS_TAB = 0
    const val PROTECTION_TAB = 1
    const val POMODORO_TAB = 2

    /** A aba de destino do Voltar, ou null quando ele não é desta política. */
    fun backTarget(selectedTab: Int, focusModeActive: Boolean): Int? =
        if (focusModeActive || selectedTab == PROTECTION_TAB) null else PROTECTION_TAB
}
