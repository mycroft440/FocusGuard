package com.focusguard.ui.compose.screens.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.focusguard.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Estado do PasswordPromptDialog.
 *
 * Antes da migração, o dialog declarava 2 `mutableStateOf` no topo da
 * composable (`password` e `error`) e chamava `authManager.verifyPassword`
 * dentro de `scope.launch`. Tudo isso vive agora neste ViewModel.
 */
data class AuthPromptUiState(
    val password: String = "",
    val error: String? = null,
    val isVerifying: Boolean = false,
    val isAuthenticated: Boolean = false
)

/**
 * ViewModel para o [com.focusguard.ui.compose.screens.PasswordPromptDialog].
 *
 * Expõe:
 *  - `uiState`: StateFlow com password digitada, erro, estado de verificação
 *  - `updatePassword(text)`: atualiza o campo e limpa erro
 *  - `verifyPassword()`: chama AuthRepository e atualiza estado
 *  - `onAuthenticatedHandled()`: reseta flag após UI processar sucesso
 *  - `setError(message)`: para erros vindos da biometria
 *
 * A operação `showBiometricPrompt` (que depende de FragmentActivity)
 * permanece na UI — é responsabilidade de view, não de domínio.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthPromptUiState())
    val uiState: StateFlow<AuthPromptUiState> = _uiState.asStateFlow()

    /**
     * Atualiza a senha digitada e limpa qualquer erro anterior.
     */
    fun updatePassword(text: String) {
        _uiState.value = _uiState.value.copy(password = text, error = null)
    }

    /**
     * Verifica a senha atual contra as senhas armazenadas.
     * Em caso de sucesso, seta `isAuthenticated = true` (UI observa e
     * chama onAuthenticated callback). Em caso de falha, seta erro.
     *
     * @param errorMessage Error message to display if password is wrong.
     */
    fun verifyPassword(errorMessage: String) {
        val password = _uiState.value.password
        if (password.isEmpty()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isVerifying = true)
            try {
                val success = authRepository.verifyPassword(password)
                if (success) {
                    _uiState.value = _uiState.value.copy(
                        isVerifying = false,
                        isAuthenticated = true
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isVerifying = false,
                        error = errorMessage
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isVerifying = false,
                    error = errorMessage
                )
            }
        }
    }

    /**
     * Define uma mensagem de erro (ex: vinda do callback onError da biometria).
     */
    fun setError(message: String) {
        _uiState.value = _uiState.value.copy(error = message)
    }

    /**
     * Reseta a flag `isAuthenticated` após a UI processar o callback.
     */
    fun onAuthenticatedHandled() {
        _uiState.value = _uiState.value.copy(isAuthenticated = false)
    }

    /**
     * Reseta todo o estado (chamar quando o dialog fecha).
     */
    fun reset() {
        _uiState.value = AuthPromptUiState()
    }
}
