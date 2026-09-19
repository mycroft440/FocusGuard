package com.focusguard.accessibility.website.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebsiteBlockingDiagnosticPolicyTest {

    @Test
    fun sanitizeTarget_removesPathQueryAndFragment() {
        val sanitized = WebsiteBlockingDiagnosticPolicy.sanitizeTarget(
            "https://example.com/private/path?token=secret#fragment"
        )

        assertEquals("https://example.com", sanitized)
        assertFalse(sanitized.orEmpty().contains("token"))
        assertFalse(sanitized.orEmpty().contains("private"))
    }

    @Test
    fun sanitizeTarget_unparseableText_isNotPersisted() {
        val sanitized = WebsiteBlockingDiagnosticPolicy.sanitizeTarget(
            "not a valid host/private-secret"
        )

        assertNull(sanitized)
    }

    @Test
    fun successfulRedirect_hasNoFailureDiagnosis() {
        val result = WebsiteBlockingDiagnosticPolicy.diagnoseTransition(
            WebsiteBlockingTransitionEvidence(
                curtainShown = true,
                submitAccepted = true,
                navigationEvidenceObserved = true,
                safeRedirectConfirmed = true,
                strictDestination = false,
                destinationRequested = false,
                destinationConfirmed = false
            )
        )

        assertNull(result)
    }

    @Test
    fun acceptedSubmitWithoutNavigation_isNavigationEvidenceFailure() {
        val result = WebsiteBlockingDiagnosticPolicy.diagnoseTransition(
            WebsiteBlockingTransitionEvidence(
                curtainShown = true,
                submitAccepted = true,
                navigationEvidenceObserved = false,
                safeRedirectConfirmed = false,
                strictDestination = false,
                destinationRequested = false,
                destinationConfirmed = false
            )
        )

        assertEquals(WebsiteBlockingFailureStage.NAVIGATION_EVIDENCE, result?.stage)
        assertTrue(result?.summary.orEmpty().contains("nenhum evento de navegação"))
        assertTrue(result?.conclusion.orEmpty().contains("não pôde ser confirmada"))
    }

    @Test
    fun navigationEvidenceWithoutStableDestination_isRedirectConfirmationFailure() {
        val result = WebsiteBlockingDiagnosticPolicy.diagnoseTransition(
            WebsiteBlockingTransitionEvidence(
                curtainShown = true,
                submitAccepted = true,
                navigationEvidenceObserved = true,
                safeRedirectConfirmed = false,
                strictDestination = false,
                destinationRequested = false,
                destinationConfirmed = false
            )
        )

        assertEquals(WebsiteBlockingFailureStage.REDIRECT_CONFIRMATION, result?.stage)
        assertTrue(result?.summary.orEmpty().contains("destino seguro não foi confirmado"))
    }

    @Test
    fun curtainWithoutAcceptedSubmit_isPreparationFailure() {
        val result = WebsiteBlockingDiagnosticPolicy.diagnoseTransition(
            WebsiteBlockingTransitionEvidence(
                curtainShown = true,
                submitAccepted = false,
                navigationEvidenceObserved = false,
                safeRedirectConfirmed = false,
                strictDestination = false,
                destinationRequested = false,
                destinationConfirmed = false
            )
        )

        assertEquals(WebsiteBlockingFailureStage.SAME_TAB_PREPARATION, result?.stage)
    }

    @Test
    fun strictDestinationWithoutRequest_isStrictDestinationFailure() {
        val result = WebsiteBlockingDiagnosticPolicy.diagnoseTransition(
            WebsiteBlockingTransitionEvidence(
                curtainShown = true,
                submitAccepted = true,
                navigationEvidenceObserved = true,
                safeRedirectConfirmed = true,
                strictDestination = true,
                destinationRequested = false,
                destinationConfirmed = false
            )
        )

        assertEquals(WebsiteBlockingFailureStage.STRICT_DESTINATION, result?.stage)
        assertTrue(result?.summary.orEmpty().contains("não chegou a ser solicitado"))
    }

    @Test
    fun strictDestinationWithoutFinalConfirmation_isStrictDestinationFailure() {
        val result = WebsiteBlockingDiagnosticPolicy.diagnoseTransition(
            WebsiteBlockingTransitionEvidence(
                curtainShown = true,
                submitAccepted = true,
                navigationEvidenceObserved = true,
                safeRedirectConfirmed = true,
                strictDestination = true,
                destinationRequested = true,
                destinationConfirmed = false
            )
        )

        assertEquals(WebsiteBlockingFailureStage.STRICT_DESTINATION, result?.stage)
        assertTrue(result?.summary.orEmpty().contains("foi solicitado"))
    }
}
