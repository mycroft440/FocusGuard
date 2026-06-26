package com.focusguard.repository

import com.focusguard.security.AuthManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository para operações de autenticação.
 *
 * Encapsula o [AuthManager] para que ViewModels possam verificar senhas
 * sem instanciar `AuthManager(context)` diretamente (que era o pattern
 * legacy em cada composable).
 *
 * A operação `showBiometricPrompt` (que depende de `FragmentActivity`)
 * não vive aqui — é responsabilidade da UI, não de domínio. Apenas a
 * lógica de verificação de senha (testável, sem Android dependencies
 * além de Context) é exposta pelo Repository.
 */
@Singleton
class AuthRepository @Inject constructor(
    private val authManager: AuthManager
) {
    /**
     * Verifica se a senha digitada corresponde a alguma senha armazenada.
     * Delega para [AuthManager.verifyPassword] — mesma lógica, mesma
     * segurança (SHA-256 + salt + 5000 iterações).
     *
     * @return true se a senha está correta, false caso contrário.
     */
    suspend fun verifyPassword(password: String): Boolean {
        return authManager.verifyPassword(password)
    }

    /**
     * Verifica se o usuário tem alguma senha configurada.
     */
    suspend fun hasPasswordSet(): Boolean {
        return authManager.hasPasswordSet()
    }
}
