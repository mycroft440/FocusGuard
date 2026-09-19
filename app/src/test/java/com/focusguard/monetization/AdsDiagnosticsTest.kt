package com.focusguard.monetization

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdsDiagnosticsTest {
    @Test
    fun loadFailureKeepsAllActionableFields() {
        val diagnostic = AdsDiagnostics.formatLoadFailure(
            format = "Banner",
            code = 3,
            domain = "com.google.android.gms.ads",
            message = "Account not approved yet",
            cause = "network cause",
            responseInfo = "response-id=abc\nadapter=AdMob"
        )

        assertTrue(diagnostic.contains("Banner falhou"))
        assertTrue(diagnostic.contains("domain=com.google.android.gms.ads"))
        assertTrue(diagnostic.contains("code=3"))
        assertTrue(diagnostic.contains("Account not approved yet"))
        assertTrue(diagnostic.contains("cause=network cause"))
        assertTrue(diagnostic.contains("response-id=abc adapter=AdMob"))
        assertFalse(diagnostic.contains('\n'))
    }

    @Test
    fun loadFailureUsesExplicitFallbacksForMissingDetails() {
        val diagnostic = AdsDiagnostics.formatLoadFailure(
            format = "Rewarded",
            code = 0,
            domain = "",
            message = "",
            cause = null,
            responseInfo = null
        )

        assertTrue(diagnostic.contains("domain=desconhecido"))
        assertTrue(diagnostic.contains("message=sem mensagem"))
        assertTrue(diagnostic.contains("cause=sem causa"))
        assertTrue(diagnostic.contains("responseInfo=indisponível"))
    }
}
