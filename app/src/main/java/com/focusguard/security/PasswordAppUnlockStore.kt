package com.focusguard.security

import android.content.Context
import com.focusguard.utils.SecurePrefsManager

enum class PasswordAppUnlockMode {
    PASSWORD,
    PATTERN,
    BIOMETRIC_ONLY
}

data class PasswordAppUnlockConfig(
    val mode: PasswordAppUnlockMode,
    val biometricEnabled: Boolean,
    val hidePatternTrace: Boolean,
    val biometricOfferShown: Boolean
) {
    val hasTypedCredential: Boolean
        get() = mode != PasswordAppUnlockMode.BIOMETRIC_ONLY
}

/**
 * Configuração de como um app protegido por uma sessão PASSWORD pode ser aberto.
 *
 * A senha/padrão nunca é persistida em texto puro: somente um verificador PBKDF2
 * fica dentro das preferências criptografadas do FocusGuard. A configuração é
 * indexada pelo packageName para que grupos diferentes de apps possam usar
 * métodos de desbloqueio diferentes sem alterar o schema Room das sessões.
 */
class PasswordAppUnlockStore(context: Context) {
    private val preferences = SecurePrefsManager(context.applicationContext).prefs

    fun saveForPackages(
        packageNames: Collection<String>,
        mode: PasswordAppUnlockMode,
        credential: String?,
        biometricEnabled: Boolean,
        hidePatternTrace: Boolean
    ): Boolean {
        val targets = packageNames.filter(String::isNotBlank).distinct()
        require(targets.isNotEmpty()) { "É necessário selecionar ao menos um aplicativo" }
        require(
            mode == PasswordAppUnlockMode.BIOMETRIC_ONLY || !credential.isNullOrBlank()
        ) { "A credencial não pode ficar vazia" }

        val verifier = credential
            ?.takeIf { mode != PasswordAppUnlockMode.BIOMETRIC_ONLY }
            ?.let(::serializeSecret)

        val editor = preferences.edit()
        targets.forEach { packageName ->
            val prefix = prefix(packageName)
            editor.putString(prefix + KEY_MODE, mode.name)
            if (verifier == null) {
                editor.remove(prefix + KEY_VERIFIER)
            } else {
                editor.putString(prefix + KEY_VERIFIER, verifier)
            }
            editor.putBoolean(
                prefix + KEY_BIOMETRIC_ENABLED,
                biometricEnabled || mode == PasswordAppUnlockMode.BIOMETRIC_ONLY
            )
            editor.putBoolean(prefix + KEY_HIDE_PATTERN, hidePatternTrace)
            editor.putBoolean(
                prefix + KEY_BIOMETRIC_OFFER_SHOWN,
                biometricEnabled || mode == PasswordAppUnlockMode.BIOMETRIC_ONLY
            )
        }
        return editor.commit()
    }

    fun get(packageName: String?): PasswordAppUnlockConfig? {
        val target = packageName?.takeIf(String::isNotBlank) ?: return null
        val prefix = prefix(target)
        val modeName = preferences.getString(prefix + KEY_MODE, null) ?: return null
        val mode = runCatching { PasswordAppUnlockMode.valueOf(modeName) }.getOrNull()
            ?: return null
        return PasswordAppUnlockConfig(
            mode = mode,
            biometricEnabled = preferences.getBoolean(
                prefix + KEY_BIOMETRIC_ENABLED,
                mode == PasswordAppUnlockMode.BIOMETRIC_ONLY
            ),
            hidePatternTrace = preferences.getBoolean(prefix + KEY_HIDE_PATTERN, false),
            biometricOfferShown = preferences.getBoolean(
                prefix + KEY_BIOMETRIC_OFFER_SHOWN,
                false
            )
        )
    }

    fun verify(packageName: String?, credential: String): Boolean {
        val target = packageName?.takeIf(String::isNotBlank) ?: return false
        val prefix = prefix(target)
        val config = get(target) ?: return false
        if (!config.hasTypedCredential) return false
        val verifier = preferences.getString(prefix + KEY_VERIFIER, null) ?: return false
        return AuthManager.verifySerializedPassword(credential, verifier)
    }

    fun setBiometricEnabled(packageName: String?, enabled: Boolean): Boolean {
        val target = packageName?.takeIf(String::isNotBlank) ?: return false
        val config = get(target) ?: return false
        if (config.mode == PasswordAppUnlockMode.BIOMETRIC_ONLY && !enabled) return false
        return preferences.edit()
            .putBoolean(prefix(target) + KEY_BIOMETRIC_ENABLED, enabled)
            .putBoolean(prefix(target) + KEY_BIOMETRIC_OFFER_SHOWN, true)
            .commit()
    }

    fun markBiometricOfferShown(packageName: String?): Boolean {
        val target = packageName?.takeIf(String::isNotBlank) ?: return false
        if (get(target) == null) return false
        return preferences.edit()
            .putBoolean(prefix(target) + KEY_BIOMETRIC_OFFER_SHOWN, true)
            .commit()
    }

    fun clearPackages(packageNames: Collection<String>) {
        val editor = preferences.edit()
        packageNames.filter(String::isNotBlank).distinct().forEach { packageName ->
            val prefix = prefix(packageName)
            editor.remove(prefix + KEY_MODE)
            editor.remove(prefix + KEY_VERIFIER)
            editor.remove(prefix + KEY_BIOMETRIC_ENABLED)
            editor.remove(prefix + KEY_HIDE_PATTERN)
            editor.remove(prefix + KEY_BIOMETRIC_OFFER_SHOWN)
        }
        editor.commit()
    }

    companion object {
        const val MIN_PASSWORD_LENGTH = 6
        const val MIN_PATTERN_POINTS = 4

        private const val KEY_NAMESPACE = "password_app_unlock."
        private const val KEY_MODE = ".mode"
        private const val KEY_VERIFIER = ".verifier"
        private const val KEY_BIOMETRIC_ENABLED = ".biometric_enabled"
        private const val KEY_HIDE_PATTERN = ".hide_pattern"
        private const val KEY_BIOMETRIC_OFFER_SHOWN = ".biometric_offer_shown"

        fun isPasswordValid(value: String): Boolean =
            value.length >= MIN_PASSWORD_LENGTH &&
                value.any(Char::isLetter) &&
                value.any(Char::isDigit)

        fun isPatternValid(value: String): Boolean =
            decodePattern(value).distinct().size >= MIN_PATTERN_POINTS

        fun encodePattern(points: List<Int>): String =
            points.filter { it in 0..8 }.distinct().joinToString("-")

        fun decodePattern(value: String): List<Int> =
            value.split('-').mapNotNull(String::toIntOrNull).filter { it in 0..8 }

        private fun prefix(packageName: String): String = KEY_NAMESPACE + packageName

        private fun serializeSecret(secret: String): String {
            val salt = AuthManager.generateSalt()
            return "$salt:${AuthManager.hashPasswordWithSalt(secret, salt)}"
        }
    }
}
