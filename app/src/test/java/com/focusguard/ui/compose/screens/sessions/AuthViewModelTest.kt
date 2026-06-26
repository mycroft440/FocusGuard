package com.focusguard.ui.compose.screens.sessions

import com.focusguard.repository.AuthRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Testes unitários para [AuthViewModel].
 *
 * Valida:
 *  - Estado inicial vazio
 *  - updatePassword atualiza o campo e limpa erro
 *  - verifyPassword com senha correta seta isAuthenticated = true
 *  - verifyPassword com senha errada seta erro
 *  - verifyPassword com senha vazia não faz nada
 *  - isVerifying transiciona durante verificação
 *  - onAuthenticatedHandled reseta a flag
 *  - reset limpa todo o estado
 *  - setError define mensagem de erro (ex: biometria falhou)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private val authRepository: AuthRepository = mockk(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: AuthViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel = AuthViewModel(authRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is empty`() {
        val state = viewModel.uiState.value
        assertThat(state.password).isEmpty()
        assertThat(state.error).isNull()
        assertThat(state.isVerifying).isFalse()
        assertThat(state.isAuthenticated).isFalse()
    }

    @Test
    fun `updatePassword updates password field`() {
        viewModel.updatePassword("minhaSenha")
        assertThat(viewModel.uiState.value.password).isEqualTo("minhaSenha")
    }

    @Test
    fun `updatePassword clears error`() {
        viewModel.setError("erro anterior")
        assertThat(viewModel.uiState.value.error).isEqualTo("erro anterior")

        viewModel.updatePassword("novaSenha")
        assertThat(viewModel.uiState.value.error).isNull()
    }

    @Test
    fun `verifyPassword with correct password sets isAuthenticated true`() = runTest(testDispatcher) {
        val password = "senhaCorreta"
        coEvery { authRepository.verifyPassword(password) } returns true

        viewModel.updatePassword(password)
        viewModel.verifyPassword("errorMsg")
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.isAuthenticated).isTrue()
        assertThat(viewModel.uiState.value.isVerifying).isFalse()
        assertThat(viewModel.uiState.value.error).isNull()
    }

    @Test
    fun `verifyPassword with wrong password sets error`() = runTest(testDispatcher) {
        val password = "senhaErrada"
        coEvery { authRepository.verifyPassword(password) } returns false

        viewModel.updatePassword(password)
        viewModel.verifyPassword("Senha incorreta")
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.isAuthenticated).isFalse()
        assertThat(viewModel.uiState.value.error).isEqualTo("Senha incorreta")
        assertThat(viewModel.uiState.value.isVerifying).isFalse()
    }

    @Test
    fun `verifyPassword with empty password does nothing`() = runTest(testDispatcher) {
        viewModel.updatePassword("")
        viewModel.verifyPassword("errorMsg")

        // Não chama o repository
        coVerify(exactly = 0) { authRepository.verifyPassword(any()) }
        assertThat(viewModel.uiState.value.isAuthenticated).isFalse()
        assertThat(viewModel.uiState.value.error).isNull()
    }

    @Test
    fun `verifyPassword delegates to repository with correct password`() = runTest(testDispatcher) {
        val password = "minhaSenha"
        coEvery { authRepository.verifyPassword(password) } returns true

        viewModel.updatePassword(password)
        viewModel.verifyPassword("errorMsg")
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { authRepository.verifyPassword(password) }
    }

    @Test
    fun `verifyPassword sets isVerifying true during verification`() = runTest(testDispatcher) {
        val password = "senha"
        coEvery { authRepository.verifyPassword(password) } returns true

        viewModel.updatePassword(password)
        viewModel.verifyPassword("errorMsg")

        // Antes de advanceUntilIdle, isVerifying deve ser true
        assertThat(viewModel.uiState.value.isVerifying).isTrue()

        testScheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.isVerifying).isFalse()
    }

    @Test
    fun `verifyPassword handles exception from repository`() = runTest(testDispatcher) {
        val password = "senha"
        coEvery { authRepository.verifyPassword(password) } throws RuntimeException("DB error")

        viewModel.updatePassword(password)
        viewModel.verifyPassword("Erro de conexão")
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.isAuthenticated).isFalse()
        assertThat(viewModel.uiState.value.error).isEqualTo("Erro de conexão")
        assertThat(viewModel.uiState.value.isVerifying).isFalse()
    }

    @Test
    fun `onAuthenticatedHandled resets isAuthenticated flag`() = runTest(testDispatcher) {
        // Setup: authenticate first
        coEvery { authRepository.verifyPassword(any()) } returns true
        viewModel.updatePassword("senha")
        viewModel.verifyPassword("errorMsg")
        testScheduler.advanceUntilIdle()
        assertThat(viewModel.uiState.value.isAuthenticated).isTrue()

        // Action
        viewModel.onAuthenticatedHandled()

        // Assert
        assertThat(viewModel.uiState.value.isAuthenticated).isFalse()
    }

    @Test
    fun `setError sets error message`() {
        viewModel.setError("Biometria falhou")
        assertThat(viewModel.uiState.value.error).isEqualTo("Biometria falhou")
    }

    @Test
    fun `reset clears all state`() = runTest(testDispatcher) {
        // Setup: populate state
        coEvery { authRepository.verifyPassword(any()) } returns false
        viewModel.updatePassword("senha")
        viewModel.verifyPassword("erro")
        testScheduler.advanceUntilIdle()
        assertThat(viewModel.uiState.value.password).isNotEmpty()
        assertThat(viewModel.uiState.value.error).isNotNull()

        // Action
        viewModel.reset()

        // Assert
        val state = viewModel.uiState.value
        assertThat(state.password).isEmpty()
        assertThat(state.error).isNull()
        assertThat(state.isVerifying).isFalse()
        assertThat(state.isAuthenticated).isFalse()
    }

    @Test
    fun `updatePassword with various inputs updates correctly`() {
        viewModel.updatePassword("a")
        assertThat(viewModel.uiState.value.password).isEqualTo("a")

        viewModel.updatePassword("abc123")
        assertThat(viewModel.uiState.value.password).isEqualTo("abc123")

        viewModel.updatePassword("")
        assertThat(viewModel.uiState.value.password).isEmpty()
    }

    @Test
    fun `multiple verifyPassword attempts each update error`() = runTest(testDispatcher) {
        coEvery { authRepository.verifyPassword(any()) } returns false

        viewModel.updatePassword("tentativa1")
        viewModel.verifyPassword("Erro 1")
        testScheduler.advanceUntilIdle()
        assertThat(viewModel.uiState.value.error).isEqualTo("Erro 1")

        viewModel.updatePassword("tentativa2")
        viewModel.verifyPassword("Erro 2")
        testScheduler.advanceUntilIdle()
        assertThat(viewModel.uiState.value.error).isEqualTo("Erro 2")
    }
}
