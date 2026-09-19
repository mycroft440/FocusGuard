package com.focusguard.accessibility.website.diagnostics

import java.net.URI

internal enum class WebsiteBlockingFailureStage {
    PRESENTATION,
    SAME_TAB_PREPARATION,
    REDIRECT_CONFIRMATION,
    STRICT_DESTINATION,
    IDENTIFICATION
}

internal data class WebsiteBlockingTransitionEvidence(
    val curtainShown: Boolean,
    val submitAccepted: Boolean,
    val safeRedirectConfirmed: Boolean,
    val strictDestination: Boolean,
    val destinationRequested: Boolean,
    val destinationConfirmed: Boolean
)

internal data class WebsiteBlockingFailureDiagnosis(
    val stage: WebsiteBlockingFailureStage,
    val summary: String,
    val conclusion: String
)

internal object WebsiteBlockingDiagnosticPolicy {
    private const val MAX_TARGET_CHARS = 180

    fun diagnoseTransition(
        evidence: WebsiteBlockingTransitionEvidence
    ): WebsiteBlockingFailureDiagnosis? {
        val success = if (evidence.strictDestination) {
            evidence.destinationConfirmed
        } else {
            evidence.safeRedirectConfirmed
        }
        if (success) return null

        return when {
            !evidence.curtainShown -> WebsiteBlockingFailureDiagnosis(
                stage = WebsiteBlockingFailureStage.PRESENTATION,
                summary = "A apresentação de bloqueio não foi confirmada.",
                conclusion = inconclusive(
                    "A transação terminou antes de haver evidência de uma cortina de bloqueio válida."
                )
            )

            !evidence.submitAccepted -> WebsiteBlockingFailureDiagnosis(
                stage = WebsiteBlockingFailureStage.SAME_TAB_PREPARATION,
                summary = "A preparação/envio do endereço seguro não foi confirmada.",
                conclusion = inconclusive(
                    "A cortina foi criada, mas não há evidência de uma submissão aceita na barra da mesma aba."
                )
            )

            !evidence.safeRedirectConfirmed -> WebsiteBlockingFailureDiagnosis(
                stage = WebsiteBlockingFailureStage.REDIRECT_CONFIRMATION,
                summary = "O envio foi aceito, mas a navegação segura não foi confirmada.",
                conclusion = inconclusive(
                    "O FocusGuard chegou à etapa de envio, porém não comprovou uma superfície estável do destino seguro."
                )
            )

            evidence.strictDestination && !evidence.destinationConfirmed ->
                WebsiteBlockingFailureDiagnosis(
                    stage = WebsiteBlockingFailureStage.STRICT_DESTINATION,
                    summary = if (evidence.destinationRequested) {
                        "O redirecionamento foi confirmado, mas o destino rigoroso não foi confirmado."
                    } else {
                        "O redirecionamento foi confirmado, mas a solicitação do destino rigoroso não foi confirmada."
                    },
                    conclusion = inconclusive(
                        "A etapa de sanitização terminou, porém a superfície terminal do modo rigoroso não foi comprovada."
                    )
                )

            else -> WebsiteBlockingFailureDiagnosis(
                stage = WebsiteBlockingFailureStage.REDIRECT_CONFIRMATION,
                summary = "A transação terminou sem um resultado terminal comprovado.",
                conclusion = inconclusive(
                    "As evidências disponíveis não permitem localizar uma causa mais específica."
                )
            )
        }
    }

    fun sanitizeTarget(raw: String?): String? {
        val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val withoutFragment = value.substringBefore('#').substringBefore('?').trim()
        if (withoutFragment.isBlank()) return null

        val authorityOnly = runCatching {
            val candidate = if ("://" in withoutFragment) {
                withoutFragment
            } else {
                "https://$withoutFragment"
            }
            val uri = URI(candidate)
            val host = uri.host?.trim()?.takeIf(String::isNotEmpty) ?: return@runCatching null
            val port = uri.port.takeIf { it >= 0 }?.let { ":$it" }.orEmpty()
            val scheme = uri.scheme?.takeIf { it.isNotBlank() } ?: "https"
            "$scheme://$host$port"
        }.getOrNull()

        return (authorityOnly ?: withoutFragment.substringBefore('/'))
            .take(MAX_TARGET_CHARS)
            .takeIf(String::isNotBlank)
    }

    fun stageLabel(stage: WebsiteBlockingFailureStage): String = when (stage) {
        WebsiteBlockingFailureStage.PRESENTATION -> "apresentação do bloqueio"
        WebsiteBlockingFailureStage.SAME_TAB_PREPARATION -> "preparação/envio na mesma aba"
        WebsiteBlockingFailureStage.REDIRECT_CONFIRMATION -> "confirmação do redirecionamento"
        WebsiteBlockingFailureStage.STRICT_DESTINATION -> "confirmação do destino rigoroso"
        WebsiteBlockingFailureStage.IDENTIFICATION -> "identificação do site"
    }

    private fun inconclusive(observation: String): String =
        "$observation A causa exata não pôde ser confirmada com as evidências disponíveis."
}
