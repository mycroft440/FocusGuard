package com.focusguard.monetization

/**
 * Pure formatting helpers for monetization diagnostics.
 *
 * Keeping the formatter free from Android/GMA types lets unit tests verify that
 * the production logs preserve the fields needed to diagnose no-fill, consent,
 * account/configuration and mediation failures.
 */
internal object AdsDiagnostics {
    private const val MAX_DETAIL_LENGTH = 8_000

    fun formatLoadFailure(
        format: String,
        code: Int,
        domain: String,
        message: String,
        cause: String?,
        responseInfo: String?
    ): String = buildString {
        append(format)
        append(" falhou: domain=")
        append(compact(domain, "desconhecido"))
        append(", code=")
        append(code)
        append(", message=")
        append(compact(message, "sem mensagem"))
        append(", cause=")
        append(compact(cause, "sem causa"))
        append(", responseInfo=")
        append(compact(responseInfo, "indisponível"))
    }

    private fun compact(value: String?, fallback: String): String {
        val compacted = value
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.joinToString(" ")
            ?.trim()
            .orEmpty()

        if (compacted.isBlank()) return fallback
        if (compacted.length <= MAX_DETAIL_LENGTH) return compacted
        return compacted.take(MAX_DETAIL_LENGTH) + "…[truncado]"
    }
}
