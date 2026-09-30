package com.focusguard.security

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

/**
 * Volta ao app logo depois de sair de uma visita liberada por senha: o histórico de
 * uso do Android pode ainda não ter registrado a volta, e a entrada real nunca pode
 * ser tomada pelo eco da saída.
 */
class PasswordTargetReentryTest {
    private val target = "com.instagram.android"

    @After
    fun tearDown() = PasswordTargetAccessGrant.clear()

    @Test
    fun `a new window of the app always counts as an entry, even right after an exit`() {
        PasswordTargetAccessGrant.recordExitForTest(target, "com.android.launcher3")

        assertThat(
            PasswordTargetAccessGrant.shouldSuppressPostExitWindow(target, windowStateChanged = true)
        ).isFalse()
    }

    @Test
    fun `the entry also clears the exit marker for the following events`() {
        PasswordTargetAccessGrant.recordExitForTest(target, "com.android.launcher3")
        PasswordTargetAccessGrant.shouldSuppressPostExitWindow(target, windowStateChanged = true)

        assertThat(
            PasswordTargetAccessGrant.shouldSuppressPostExitWindow(target, windowStateChanged = false)
        ).isFalse()
    }
}
