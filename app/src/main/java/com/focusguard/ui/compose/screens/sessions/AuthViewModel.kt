package com.focusguard.ui.compose.screens.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.focusguard.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthPromptUiState(
    val password: String = "",
    val error: String? = null,
    val isVerifying: Boolean = false,
    val isAuthenticated: Boolean = false
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthPromptUiState())
    val uiState: StateFlow<AuthPromptUiState> = _uiState.asStateFlow()

    fun updatePassword(text: String) {
        _uiState.value = _uiState.value.copy(password = text, error = null)
    }

    fun verifyPassword(errorMessage: String) {
        val password = _uiState.value.password
        if (password.isEmpty() || _uiState.value.isVerifying) return

        // Atualiza antes de lançar a coroutine para que a UI desabilite o botão
        // imediatamente e não permita duas verificações simultâneas.
        _uiState.value = _uiState.value.copy(isVerifying = true, error = null)

        viewModelScope.launch {
            try {
                val success = authRepository.verifyPassword(password)
                _uiState.value = if (success) {
                    _uiState.value.copy(
                        isVerifying = false,
                        isAuthenticated = true,
                        error = null
                    )
                } else {
                    _uiState.value.copy(
                        isVerifying = false,
                        isAuthenticated = false,
                        error = errorMessage
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(
                    isVerifying = false,
                    isAuthenticated = false,
                    error = errorMessage
                )
            }
        }
    }

    fun setError(message: String) {
        _uiState.value = _uiState.value.copy(error = message)
    }

    fun onAuthenticatedHandled() {
        _uiState.value = _uiState.value.copy(isAuthenticated = false)
    }

    fun reset() {
        _uiState.value = AuthPromptUiState()
    }
}
