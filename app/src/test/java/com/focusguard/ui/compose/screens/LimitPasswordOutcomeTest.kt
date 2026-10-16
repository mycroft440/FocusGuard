package com.focusguard.ui.compose.screens

import com.focusguard.manager.BlockingSessionManager.LimitUnlockResult
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** O que a tela de bloqueio faz depois de pedir a liberação do limite com senha. */
class LimitPasswordOutcomeTest {

    @Test
    fun `an accepted password opens the app`() {
        assertThat(limitPasswordOutcome(LimitUnlockResult.UNLOCKED))
            .isEqualTo(LimitPasswordOutcome.OPEN_APP)
    }

    @Test
    fun `no password limit holding the app anymore opens it too`() {
        // Zerou à meia-noite ou já foi liberado: se outra proteção ainda segurar o app,
        // o serviço bloqueia de novo com a tela certa.
        assertThat(limitPasswordOutcome(LimitUnlockResult.NOT_FOUND))
            .isEqualTo(LimitPasswordOutcome.OPEN_APP)
    }

    @Test
    fun `a wrong password and a failure keep the app closed`() {
        assertThat(limitPasswordOutcome(LimitUnlockResult.WRONG_PASSWORD))
            .isEqualTo(LimitPasswordOutcome.WRONG_PASSWORD)
        assertThat(limitPasswordOutcome(LimitUnlockResult.FAILED))
            .isEqualTo(LimitPasswordOutcome.FAILED)
    }

    @Test
    fun `only a checked and accepted password clears the wrong attempt count`() {
        assertThat(limitPasswordVerified(LimitUnlockResult.UNLOCKED)).isTrue()
        // NOT_FOUND volta antes de conferir a senha: não prova nada sobre ela.
        assertThat(limitPasswordVerified(LimitUnlockResult.NOT_FOUND)).isFalse()
        assertThat(limitPasswordVerified(LimitUnlockResult.WRONG_PASSWORD)).isFalse()
        assertThat(limitPasswordVerified(LimitUnlockResult.FAILED)).isFalse()
    }
}
