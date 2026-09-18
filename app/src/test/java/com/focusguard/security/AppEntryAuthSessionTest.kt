package com.focusguard.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppEntryAuthSessionTest {

    @Test
    fun `passive app entry unlock does not count as credential authentication`() {
        AppEntryAuthSession.beginForeground()
        val generation = AppEntryAuthSession.foregroundGeneration.value

        AppEntryAuthSession.markAuthenticated(generation)

        assertTrue(AppEntryAuthSession.isAuthenticated(generation))
        assertFalse(AppEntryAuthSession.isCredentialAuthenticated(generation))
    }

    @Test
    fun `verified app entry credential marks both authentication states`() {
        AppEntryAuthSession.beginForeground()
        val generation = AppEntryAuthSession.foregroundGeneration.value

        AppEntryAuthSession.markCredentialAuthenticated(generation)

        assertTrue(AppEntryAuthSession.isAuthenticated(generation))
        assertTrue(AppEntryAuthSession.isCredentialAuthenticated(generation))
    }

    @Test
    fun `new foreground generation invalidates credential authentication`() {
        AppEntryAuthSession.beginForeground()
        val authenticatedGeneration = AppEntryAuthSession.foregroundGeneration.value
        AppEntryAuthSession.markCredentialAuthenticated(authenticatedGeneration)

        AppEntryAuthSession.beginForeground()
        val newGeneration = AppEntryAuthSession.foregroundGeneration.value

        assertFalse(AppEntryAuthSession.isAuthenticated(authenticatedGeneration))
        assertFalse(AppEntryAuthSession.isCredentialAuthenticated(authenticatedGeneration))
        assertFalse(AppEntryAuthSession.isCredentialAuthenticated(newGeneration))
    }
}
