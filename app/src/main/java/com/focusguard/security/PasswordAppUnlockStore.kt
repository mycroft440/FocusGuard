package com.focusguard.security

import android.content.Context
import com.focusguard.utils.SecurePrefsManager
import com.focusguard.utils.WebsiteBlocker

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
 * Credential store for targets protected by a PASSWORD session.
 *
 * App package names intentionally keep their historical raw key so existing
 * installations remain compatible. Website keys use a namespaced, normalized
 * identifier. Neither kind ever reads or writes the master credential.
 */
class PasswordAppUnlockStore(context: Context) {
    private val preferences = SecurePrefsManager(context.applicationContext).prefs

    fun saveForPackages(
        packageNames: Collection<String>,
        mode: PasswordAppUnlockMode,
        credential: String?,
        biometricEnabled: Boolean,
        hidePatternTrace: Boolean
    ): Boolean = saveForTargets(
        targetIds = packageNames.mapNotNull(::targetIdForPackage),
        mode = mode,
        credential = credential,
        biometricEnabled = biometricEnabled,
        hidePatternTrace = hidePatternTrace
    )

    fun saveForWebsites(
        websiteRules: Collection<String>,
        mode: PasswordAppUnlockMode,
        credential: String?,
        biometricEnabled: Boolean,
        hidePatternTrace: Boolean
    ): Boolean = saveForTargets(
        targetIds = websiteRules.mapNotNull(::targetIdForWebsite),
        mode = mode,
        credential = credential,
        biometricEnabled = biometricEnabled,
        hidePatternTrace = hidePatternTrace
    )

    fun saveForTargets(
        targetIds: Collection<String>,
        mode: PasswordAppUnlockMode,
        credential: String?,
        biometricEnabled: Boolean,
        hidePatternTrace: Boolean
    ): Boolean {
        val targets = targetIds.filter(String::isNotBlank).distinct()
        require(targets.isNotEmpty()) { "É necessário selecionar ao menos um alvo" }
        require(mode == PasswordAppUnlockMode.BIOMETRIC_ONLY || !credential.isNullOrBlank()) {
            "A credencial não pode ficar vazia"
        }
        val verifier = credential
            ?.takeIf { mode != PasswordAppUnlockMode.BIOMETRIC_ONLY }
            ?.let(::serializeSecret)
        val editor = preferences.edit()
        targets.forEach { targetId ->
            val prefix = prefix(targetId)
            editor.putString(prefix + KEY_MODE, mode.name)
            if (verifier == null) editor.remove(prefix + KEY_VERIFIER)
            else editor.putString(prefix + KEY_VERIFIER, verifier)
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

    fun get(packageName: String?): PasswordAppUnlockConfig? =
        targetIdForPackage(packageName)?.let(::getTarget)

    fun getWebsite(websiteRule: String?): PasswordAppUnlockConfig? =
        targetIdForWebsite(websiteRule)?.let(::getTarget)

    fun getTarget(targetId: String?): PasswordAppUnlockConfig? {
        val target = targetId?.takeIf(String::isNotBlank) ?: return null
        val prefix = prefix(target)
        val modeName = preferences.getString(prefix + KEY_MODE, null) ?: return null
        val mode = runCatching { PasswordAppUnlockMode.valueOf(modeName) }.getOrNull() ?: return null
        return PasswordAppUnlockConfig(
            mode = mode,
            biometricEnabled = preferences.getBoolean(
                prefix + KEY_BIOMETRIC_ENABLED,
                mode == PasswordAppUnlockMode.BIOMETRIC_ONLY
            ),
            hidePatternTrace = preferences.getBoolean(prefix + KEY_HIDE_PATTERN, false),
            biometricOfferShown = preferences.getBoolean(prefix + KEY_BIOMETRIC_OFFER_SHOWN, false)
        )
    }

    fun verify(packageName: String?, credential: String): Boolean =
        targetIdForPackage(packageName)?.let { verifyTarget(it, credential) } == true

    fun verifyWebsite(websiteRule: String?, credential: String): Boolean =
        targetIdForWebsite(websiteRule)?.let { verifyTarget(it, credential) } == true

    fun verifyTarget(targetId: String?, credential: String): Boolean {
        val target = targetId?.takeIf(String::isNotBlank) ?: return false
        val prefix = prefix(target)
        val config = getTarget(target) ?: return false
        if (!config.hasTypedCredential) return false
        val verifier = preferences.getString(prefix + KEY_VERIFIER, null) ?: return false
        return AuthManager.verifySerializedPassword(credential, verifier)
    }

    fun setBiometricEnabled(packageName: String?, enabled: Boolean): Boolean =
        targetIdForPackage(packageName)?.let { setBiometricEnabledForTarget(it, enabled) } == true

    fun setBiometricEnabledForTarget(targetId: String?, enabled: Boolean): Boolean {
        val target = targetId?.takeIf(String::isNotBlank) ?: return false
        val config = getTarget(target) ?: return false
        if (config.mode == PasswordAppUnlockMode.BIOMETRIC_ONLY && !enabled) return false
        return preferences.edit()
            .putBoolean(prefix(target) + KEY_BIOMETRIC_ENABLED, enabled)
            .putBoolean(prefix(target) + KEY_BIOMETRIC_OFFER_SHOWN, true)
            .commit()
    }

    fun markBiometricOfferShown(packageName: String?): Boolean =
        targetIdForPackage(packageName)?.let(::markBiometricOfferShownForTarget) == true

    fun markBiometricOfferShownForTarget(targetId: String?): Boolean {
        val target = targetId?.takeIf(String::isNotBlank) ?: return false
        if (getTarget(target) == null) return false
        return preferences.edit()
            .putBoolean(prefix(target) + KEY_BIOMETRIC_OFFER_SHOWN, true)
            .commit()
    }

    fun clearPackages(packageNames: Collection<String>) {
        clearTargets(packageNames.mapNotNull(::targetIdForPackage))
    }

    fun clearWebsites(websiteRules: Collection<String>) {
        clearTargets(websiteRules.mapNotNull(::targetIdForWebsite))
    }

    fun clearTargets(targetIds: Collection<String>) {
        val editor = preferences.edit()
        targetIds.filter(String::isNotBlank).distinct().forEach { targetId ->
            val prefix = prefix(targetId)
            editor.remove(prefix + KEY_MODE)
            editor.remove(prefix + KEY_VERIFIER)
            editor.remove(prefix + KEY_BIOMETRIC_ENABLED)
            editor.remove(prefix + KEY_HIDE_PATTERN)
            editor.remove(prefix + KEY_BIOMETRIC_OFFER_SHOWN)
        }
        editor.commit()
    }

    fun clearAll() {
        val keys = preferences.all.keys.filter { it.startsWith(KEY_NAMESPACE) }
        if (keys.isEmpty()) return
        val editor = preferences.edit()
        keys.forEach(editor::remove)
        editor.commit()
    }

    companion object {
        const val MIN_PASSWORD_LENGTH = 6
        const val MIN_PATTERN_POINTS = 4
        private const val WEBSITE_TARGET_PREFIX = "site:"
        private const val KEY_NAMESPACE = "password_app_unlock."
        private const val KEY_MODE = ".mode"
        private const val KEY_VERIFIER = ".verifier"
        private const val KEY_BIOMETRIC_ENABLED = ".biometric_enabled"
        private const val KEY_HIDE_PATTERN = ".hide_pattern"
        private const val KEY_BIOMETRIC_OFFER_SHOWN = ".biometric_offer_shown"

        fun targetIdForPackage(packageName: String?): String? =
            packageName?.trim()?.takeIf(String::isNotBlank)

        fun targetIdForWebsite(websiteRule: String?): String? = websiteRule
            ?.let(WebsiteBlocker::normalizeRule)
            ?.takeIf(String::isNotBlank)
            ?.let { WEBSITE_TARGET_PREFIX + it }

        fun websiteRuleFromTargetId(targetId: String?): String? = targetId
            ?.takeIf { it.startsWith(WEBSITE_TARGET_PREFIX) }
            ?.removePrefix(WEBSITE_TARGET_PREFIX)
            ?.takeIf(String::isNotBlank)

        fun isPasswordValid(value: String): Boolean =
            value.length >= MIN_PASSWORD_LENGTH && value.any(Char::isLetter) && value.any(Char::isDigit)

        fun isPatternValid(value: String): Boolean =
            decodePattern(value).distinct().size >= MIN_PATTERN_POINTS

        fun encodePattern(points: List<Int>): String =
            points.filter { it in 0..8 }.distinct().joinToString("-")

        fun decodePattern(value: String): List<Int> =
            value.split('-').mapNotNull(String::toIntOrNull).filter { it in 0..8 }

        private fun prefix(targetId: String): String = KEY_NAMESPACE + targetId

        private fun serializeSecret(secret: String): String {
            val salt = AuthManager.generateSalt()
            return "$salt:${AuthManager.hashPasswordWithSalt(secret, salt)}"
        }
    }
}
