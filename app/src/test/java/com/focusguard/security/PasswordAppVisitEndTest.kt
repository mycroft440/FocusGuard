package com.focusguard.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * O que encerra a visita a um app liberado por senha: outro app na frente, a tela
 * inicial ou a tela apagada. Telas de sistema por cima (permissão, compartilhar,
 * login do Google, cortina de notificações) não encerram.
 */
class PasswordAppVisitEndTest {
    private val instagram = "com.instagram.android"
    private val whatsapp = "com.whatsapp"

    @Test
    fun `another app in front ends the visit`() {
        assertThat(PasswordTargetAccessGrant.endsVisit(whatsapp)).isTrue()
    }

    @Test
    fun `system screens on top do not end the visit`() {
        listOf(
            "com.android.systemui",
            "android",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.intentresolver",
            "com.google.android.gms"
        ).forEach { overlay ->
            assertThat(PasswordTargetAccessGrant.endsVisit(overlay)).isFalse()
        }
    }

    @Test
    fun `a blank package never ends the visit`() {
        assertThat(PasswordTargetAccessGrant.endsVisit("")).isFalse()
        assertThat(PasswordTargetAccessGrant.endsVisit(null)).isFalse()
    }

    @Test
    fun `the home screen or the screen turning off ends every started visit`() {
        assertThat(
            PasswordTargetAccessGrant.visitsEndedBy(listOf(instagram, whatsapp), null)
        ).containsExactly(instagram, whatsapp)
    }

    @Test
    fun `another app ends every visit except its own`() {
        assertThat(
            PasswordTargetAccessGrant.visitsEndedBy(listOf(instagram, whatsapp), whatsapp)
        ).containsExactly(instagram)
    }

    @Test
    fun `the protected app itself in front keeps its visit`() {
        assertThat(
            PasswordTargetAccessGrant.visitsEndedBy(listOf(instagram), instagram)
        ).isEmpty()
    }
}
