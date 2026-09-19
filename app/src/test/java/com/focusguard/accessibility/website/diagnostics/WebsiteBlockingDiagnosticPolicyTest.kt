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
    fun successfulRedirect_hasNoFailureDiagnosis() {
        val result = WebsiteBlockingDiagnosticPolicy.diagnoseTransition(
            WebsiteBlockingTransitionEvidence(
                curtainShown = true,
                submitAccepted = true,
                safeRedirectConfirmed = true,
                strictDestination = false,
                destinationRequested = false,
                destinationConfirmed = false
            )
        )

        assertNull(result)
    }

    @Test
    fun acceptedSubmitWithoutNavigation_isRedirectConfirmationFailure() {
        val result = WebsiteBlockingDiagnosticPolicy.diagnoseTransition(
            WebsiteBlockingTransitionEvidence(
                curtainShown = true,
                submitAccepted = true,
                safeRedirectConfirmed = false,
                strictDestination = false,
                destinationRequested = false,
                destinationConfirmed = false
            )
        )

        assertEquals(WebsiteBlockingFailureStage.REDIRECT_CONFIRMATION, result?.stage)
        assertTrue(result?.summary.orEmpty().contains("não foi confirmada"))
        assertTrue(result?.conclusion.orEmpty().contains("não pôde ser confirmada"))
    }

    @Test
    fun curtainWithoutAcceptedSubmit_isPreparationFailure() {
        val result = WebsiteBlockingDiagnosticPolicy.diagnoseTransition(
            WebsiteBlockingTransitionEvidence(
                curtainShown = true,
                submitAccepted = false,
                safeRedirectConfirmed = false,
                strictDestination = false,
                destinationRequested = false,
                destinationConfirmed = false
            )
        )

        assertEquals(WebsiteBlockingFailureStage.SAME_TAB_PREPARATION, result?.stage)
    }

    @Test
    fun strictDestinationWithoutFinalConfirmation_isStrictDestinationFailure() {
        val result = WebsiteBlockingDiagnosticPolicy.diagnoseTransition(
            WebsiteBlockingTransitionEvidence(
                curtainShown = true,
                submitAccepted = true,
                safeRedirectConfirmed = true,
                strictDestination = true,
                destinationRequested = true,
                destinationConfirmed = false
            )
        )

        assertEquals(WebsiteBlockingFailureStage.STRICT_DESTINATION, result?.stage)
    }
}
