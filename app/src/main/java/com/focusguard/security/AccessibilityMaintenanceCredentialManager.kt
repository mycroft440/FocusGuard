package com.focusguard.security

import android.content.Context
import com.focusguard.utils.SecurePrefsManager
import java.security.SecureRandom

/**
 * Stores a credential dedicated to Accessibility maintenance.
 *
 * Only salted PBKDF2 verifiers are persisted. The generated recovery code is
 * returned once to the user and its verifier is stored separately. Using the
 * recovery code consumes it and clears the maintenance password, preventing a
 * forgotten password from becoming a permanent lockout.
 */
class AccessibilityMaintenanceCredentialManager(context: Context) {

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

    /**
     * Replaces the maintenance password and creates a new one-time recovery code.
     * The plaintext password and recovery code are never persisted.
     */
    fun configure(password: String): String {
        require(isPasswordValid(password)) {
            "A senha de manutenção deve ter pelo menos $MIN_PASSWORD_LENGTH caracteres"
        }

        val recoveryCode = generateRecoveryCode()
        val passwordVerifier = serializeSecret(password)
        val recoveryVerifier = serializeSecret(normalizeRecoveryCode(recoveryCode))

        val saved = preferences.edit()
            .putString(PASSWORD_VERIFIER_KEY, passwordVerifier)
            .putString(RECOVERY_VERIFIER_KEY, recoveryVerifier)
            .commit()
        check(saved) { "Não foi possível salvar a credencial de manutenção" }

        return recoveryCode
    }

    /**
     * Accepts either the maintenance password or the one-time recovery code.
     * A successful recovery consumes both stored verifiers so a new password
     * must be configured after the temporary maintenance window.
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
            clear()
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
        const val MIN_PASSWORD_LENGTH = 8

        private const val PASSWORD_VERIFIER_KEY =
            "accessibility_maintenance_password_verifier"
        private const val RECOVERY_VERIFIER_KEY =
            "accessibility_maintenance_recovery_verifier"
        private const val RECOVERY_CODE_LENGTH = 16
        private const val RECOVERY_GROUP_SIZE = 4
        private const val RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        private val secureRandom = SecureRandom()

        internal fun isPasswordValid(password: String): Boolean {
            return password.length >= MIN_PASSWORD_LENGTH
        }

        internal fun normalizeRecoveryCode(value: String): String {
            return value
                .uppercase()
                .filter { it.isLetterOrDigit() }
        }

        internal fun serializeSecret(secret: String, salt: String = AuthManager.generateSalt()): String {
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
