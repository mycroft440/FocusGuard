package com.focusguard.accessibility.website.diagnostics

import java.net.URI
import java.util.Locale

internal enum class WebsiteBlockingFailureStage {
    PRESENTATION,
    SAME_TAB_PREPARATION,
    NAVIGATION_EVIDENCE,
    REDIRECT_CONFIRMATION,
    STRICT_DESTINATION,
    IDENTIFICATION
}

internal data class WebsiteBlockingTransitionEvidence(
    val curtainShown: Boolean,
    val submitAccepted: Boolean,
    val navigationEvidenceObserved: Boolean,
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

            !evidence.navigationEvidenceObserved && !evidence.safeRedirectConfirmed ->
                WebsiteBlockingFailureDiagnosis(
                    stage = WebsiteBlockingFailureStage.NAVIGATION_EVIDENCE,
                    summary = "O envio foi aceito, mas nenhum evento de navegação posterior foi observado.",
                    conclusion = inconclusive(
                        "O FocusGuard aceitou a ação de envio, porém não observou uma mutação de conteúdo/janela elegível depois dessa submissão."
                    )
                )

            !evidence.safeRedirectConfirmed -> WebsiteBlockingFailureDiagnosis(
                stage = WebsiteBlockingFailureStage.REDIRECT_CONFIRMATION,
                summary = "Houve evidência de navegação, mas o destino seguro não foi confirmado.",
                conclusion = inconclusive(
                    "O FocusGuard observou atividade de navegação após o envio, porém não comprovou uma superfície estável do destino seguro."
                )
            )

            evidence.strictDestination && !evidence.destinationRequested ->
                WebsiteBlockingFailureDiagnosis(
                    stage = WebsiteBlockingFailureStage.STRICT_DESTINATION,
                    summary = "O redirecionamento foi confirmado, mas o destino rigoroso não chegou a ser solicitado.",
                    conclusion = inconclusive(
                        "A sanitização da mesma aba foi confirmada, porém a etapa terminal do modo rigoroso não foi iniciada de forma comprovada."
                    )
                )

            evidence.strictDestination && !evidence.destinationConfirmed ->
                WebsiteBlockingFailureDiagnosis(
                    stage = WebsiteBlockingFailureStage.STRICT_DESTINATION,
                    summary = "O destino rigoroso foi solicitado, mas sua superfície final não foi confirmada.",
                    conclusion = inconclusive(
                        "A solicitação do destino rigoroso foi registrada, porém sua superfície terminal não foi comprovada."
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
            val scheme = uri.scheme
                ?.takeIf { it.isNotBlank() }
                ?.lowercase(Locale.ROOT)
                ?: "https"
            "$scheme://$host$port"
        }.getOrNull()

        // Never persist arbitrary fallback text when URL parsing cannot prove an authority.
        // Losing an unparseable target is preferable to accidentally storing a path or secret.
        return authorityOnly
            ?.take(MAX_TARGET_CHARS)
            ?.takeIf(String::isNotBlank)
    }

    fun stageLabel(stage: WebsiteBlockingFailureStage): String = when (stage) {
        WebsiteBlockingFailureStage.PRESENTATION -> "apresentação do bloqueio"
        WebsiteBlockingFailureStage.SAME_TAB_PREPARATION -> "preparação/envio na mesma aba"
        WebsiteBlockingFailureStage.NAVIGATION_EVIDENCE -> "evidência de navegação após o envio"
        WebsiteBlockingFailureStage.REDIRECT_CONFIRMATION -> "confirmação do redirecionamento"
        WebsiteBlockingFailureStage.STRICT_DESTINATION -> "confirmação do destino rigoroso"
        WebsiteBlockingFailureStage.IDENTIFICATION -> "identificação do site"
    }

    private fun inconclusive(observation: String): String =
        "$observation A causa exata não pôde ser confirmada com as evidências disponíveis."
}
