package com.focusguard.monetization

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdsDiagnosticsTest {
    @Test
    fun loadFailureKeepsAllNextGenDiagnosticFields() {
        val diagnostic = AdsDiagnostics.formatLoadFailure(
            format = "Banner",
            code = "NO_FILL",
            message = "Account not approved yet",
            errorDump = "LoadAdError(code=NO_FILL, message=Account not approved yet)",
            responseInfo = "response-id=abc\nadapter=AdMob"
        )

        assertTrue(diagnostic.contains("Banner falhou"))
        assertTrue(diagnostic.contains("code=NO_FILL"))
        assertTrue(diagnostic.contains("Account not approved yet"))
        assertTrue(diagnostic.contains("LoadAdError(code=NO_FILL"))
        assertTrue(diagnostic.contains("response-id=abc adapter=AdMob"))
        assertFalse(diagnostic.contains('\n'))
    }

    @Test
    fun loadFailureUsesExplicitFallbacksForMissingDetails() {
        val diagnostic = AdsDiagnostics.formatLoadFailure(
            format = "Rewarded",
            code = "",
            message = "",
            errorDump = "",
            responseInfo = null
        )

        assertTrue(diagnostic.contains("code=desconhecido"))
        assertTrue(diagnostic.contains("message=sem mensagem"))
        assertTrue(diagnostic.contains("error=indisponível"))
        assertTrue(diagnostic.contains("responseInfo=indisponível"))
    }
}
