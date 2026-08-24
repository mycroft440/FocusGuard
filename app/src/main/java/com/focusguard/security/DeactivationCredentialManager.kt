package com.focusguard.security

import android.content.Context
import com.focusguard.utils.SecurePrefsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores the credential used to deactivate password-based blocking sessions.
 *
 * The credential must be configured before a blocking window begins. Only
 * salted PBKDF2 verifiers are persisted. A one-time recovery code is returned
 * to the user and can be used if the password is forgotten.
 */
@Singleton
class DeactivationCredentialManager @Inject constructor(
    @ApplicationContext context: Context
) {

    enum class VerificationResult {
        PASSWORD_ACCEPTED,
        RECOVERY_ACCEPTED,
        REJECTED,
        NOT_CONFIGURED
    }

    private val preferences = SecurePrefsManager(context.applicationContext).prefs

    fun hasCredential(): Boolean {
        return !preferences.getString(PASSWORD_VERIFIER_KEY, null).isNullOrBlank()
    }

    /** Replaces the password and creates a new one-time recovery code. */
    fun configure(password: String): String {
        require(isPasswordValid(password)) {
            "A senha de desativação deve ter pelo menos $MIN_PASSWORD_LENGTH caracteres"
        }

        val recoveryCode = generateRecoveryCode()
        val saved = preferences.edit()
            .putString(PASSWORD_VERIFIER_KEY, serializeSecret(password))
            .putString(
                RECOVERY_VERIFIER_KEY,
                serializeSecret(normalizeRecoveryCode(recoveryCode))
            )
            .commit()
        check(saved) { "Não foi possível salvar a senha de desativação" }
        return recoveryCode
    }

    /**
     * Accepts the configured password or the one-time recovery code.
     * Recovery is consumed after use, but the password remains configured.
     */
    fun verify(credential: String): VerificationResult {
        val passwordVerifier = preferences.getString(PASSWORD_VERIFIER_KEY, null)
        if (passwordVerifier.isNullOrBlank()) return VerificationResult.NOT_CONFIGURED

        if (AuthManager.verifySerializedPassword(credential, passwordVerifier)) {
            return VerificationResult.PASSWORD_ACCEPTED
        }

        val recoveryVerifier = preferences.getString(RECOVERY_VERIFIER_KEY, null)
        if (!recoveryVerifier.isNullOrBlank() &&
            AuthManager.verifySerializedPassword(
                normalizeRecoveryCode(credential),
                recoveryVerifier
            )
        ) {
            preferences.edit().remove(RECOVERY_VERIFIER_KEY).commit()
            return VerificationResult.RECOVERY_ACCEPTED
        }

        return VerificationResult.REJECTED
    }

    fun clear() {
        preferences.edit()
            .remove(PASSWORD_VERIFIER_KEY)
            .remove(RECOVERY_VERIFIER_KEY)
            .commit()
    }

    companion object {
        const val MIN_PASSWORD_LENGTH = 4

        private const val PASSWORD_VERIFIER_KEY = "deactivation_password_verifier"
        private const val RECOVERY_VERIFIER_KEY = "deactivation_recovery_verifier"
        private const val RECOVERY_CODE_LENGTH = 16
        private const val RECOVERY_GROUP_SIZE = 4
        private const val RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        private val secureRandom = SecureRandom()

        internal fun isPasswordValid(password: String): Boolean {
            return password.length >= MIN_PASSWORD_LENGTH
        }

        internal fun normalizeRecoveryCode(value: String): String {
            return value.uppercase().filter { it.isLetterOrDigit() }
        }

        internal fun serializeSecret(
            secret: String,
            salt: String = AuthManager.generateSalt()
        ): String {
            return "$salt:${AuthManager.hashPasswordWithSalt(secret, salt)}"
        }

        internal fun classifyStoredCredential(
            credential: String,
            passwordVerifier: String?,
            recoveryVerifier: String?
        ): VerificationResult {
            if (passwordVerifier.isNullOrBlank()) return VerificationResult.NOT_CONFIGURED
            if (AuthManager.verifySerializedPassword(credential, passwordVerifier)) {
                return VerificationResult.PASSWORD_ACCEPTED
            }
            if (!recoveryVerifier.isNullOrBlank() &&
                AuthManager.verifySerializedPassword(
                    normalizeRecoveryCode(credential),
                    recoveryVerifier
                )
            ) {
                return VerificationResult.RECOVERY_ACCEPTED
            }
            return VerificationResult.REJECTED
        }

        private fun generateRecoveryCode(): String {
            val raw = buildString(RECOVERY_CODE_LENGTH) {
                repeat(RECOVERY_CODE_LENGTH) {
                    append(RECOVERY_ALPHABET[secureRandom.nextInt(RECOVERY_ALPHABET.length)])
                }
            }
            return raw.chunked(RECOVERY_GROUP_SIZE).joinToString("-")
        }
    }
}
