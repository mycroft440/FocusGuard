package com.focusguard.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordAppUnlockStoreValidationTest {

    @Test
    fun passwordMinimumLengthIsFourCharacters() {
        assertEquals(4, PasswordAppUnlockStore.MIN_PASSWORD_LENGTH)
        assertTrue(PasswordAppUnlockStore.isPasswordValid("a1b2"))
        assertFalse(PasswordAppUnlockStore.isPasswordValid("a1b"))
    }

    @Test
    fun passwordStillRequiresLetterAndNumber() {
        assertFalse(PasswordAppUnlockStore.isPasswordValid("1234"))
        assertFalse(PasswordAppUnlockStore.isPasswordValid("abcd"))
        assertTrue(PasswordAppUnlockStore.isPasswordValid("ab12"))
    }
}
