package com.focusguard.repository

import com.focusguard.security.AuthManager
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Testes unitários para [AuthRepository].
 *
 * Valida que o Repository delega corretamente para [AuthManager] e
 * não adiciona lógica extra (apenas passthrough).
 */
class AuthRepositoryTest {

    private val authManager: AuthManager = mockk(relaxed = true)
    private val repository = AuthRepository(authManager)

    @Test
    fun `verifyPassword delegates to AuthManager`() = runTest {
        val password = "minhaSenha123"
        coEvery { authManager.verifyPassword(password) } returns true

        val result = repository.verifyPassword(password)

        assertThat(result).isTrue()
        coVerify(exactly = 1) { authManager.verifyPassword(password) }
    }

    @Test
    fun `verifyPassword returns false when AuthManager returns false`() = runTest {
        val password = "senhaErrada"
        coEvery { authManager.verifyPassword(password) } returns false

        val result = repository.verifyPassword(password)

        assertThat(result).isFalse()
    }

    @Test
    fun `verifyPassword with empty password delegates to AuthManager`() = runTest {
        coEvery { authManager.verifyPassword("") } returns false

        val result = repository.verifyPassword("")

        assertThat(result).isFalse()
        coVerify(exactly = 1) { authManager.verifyPassword("") }
    }

    @Test
    fun `hasPasswordSet delegates to AuthManager`() = runTest {
        coEvery { authManager.hasPasswordSet() } returns true

        val result = repository.hasPasswordSet()

        assertThat(result).isTrue()
        coVerify(exactly = 1) { authManager.hasPasswordSet() }
    }

    @Test
    fun `hasPasswordSet returns false when AuthManager returns false`() = runTest {
        coEvery { authManager.hasPasswordSet() } returns false

        val result = repository.hasPasswordSet()

        assertThat(result).isFalse()
    }
}
