package com.focusguard.ui.compose.screens

import kotlinx.coroutines.CancellationException

/**
 * Versão segura de runCatching para este pacote de telas.
 *
 * A função padrão do Kotlin captura CancellationException, o que pode fazer um
 * LaunchedEffect cancelado parecer uma falha de carregamento e ainda tentar
 * atualizar estado depois que a tela saiu da composição. Declarações do mesmo
 * pacote têm precedência sobre imports padrão, portanto as telas passam a
 * preservar o cancelamento sem alterar o restante do comportamento de Result.
 */
inline fun <R> runCatching(block: () -> R): Result<R> {
    return try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    }
}
