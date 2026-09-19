package com.focusguard.monetization

/**
 * Pure formatting helpers for monetization diagnostics.
 *
 * Keeping the formatter free from Android/GMA types lets unit tests verify that
 * the production logs preserve the fields exposed by GMA Next-Gen for no-fill,
 * onboarding/configuration and mediation failures.
 */
internal object AdsDiagnostics {
    private const val MAX_DETAIL_LENGTH = 8_000

    fun formatLoadFailure(
        format: String,
        code: String,
        message: String,
        errorDump: String,
        responseInfo: String?
    ): String = buildString {
        append(format)
        append(" falhou: code=")
        append(compact(code, "desconhecido"))
        append(", message=")
        append(compact(message, "sem mensagem"))
        append(", error=")
        append(compact(errorDump, "indisponível"))
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
