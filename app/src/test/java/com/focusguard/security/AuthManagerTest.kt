package com.focusguard.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Testes unitários para [AuthManager] — foco nas funções puras (sem dependência
 * de Android Context) do companion object: hash de senha, geração de salt, etc.
 *
 * Estas funções são críticas para a segurança do app. Antes da Fase 3, este era
 * código de segurança sem NENHUM teste automatizado.
 */
class AuthManagerTest {

    @Test
    fun `generateSalt returns 32-char hex string (16 bytes)`() {
        val salt = AuthManager.generateSalt()
        assertThat(salt).hasLength(32) // 16 bytes * 2 chars/byte
        assertThat(salt).matches("[0-9a-f]{32}")
    }

    @Test
    fun `generateSalt returns different values on each call`() {
        val salt1 = AuthManager.generateSalt()
        val salt2 = AuthManager.generateSalt()
        assertThat(salt1).isNotEqualTo(salt2)
    }

    @Test
    fun `hashPasswordWithSalt is deterministic for same password and salt`() {
        val password = "minhaSenha123"
        val salt = AuthManager.generateSalt()
        val hash1 = AuthManager.hashPasswordWithSalt(password, salt)
        val hash2 = AuthManager.hashPasswordWithSalt(password, salt)
        assertThat(hash1).isEqualTo(hash2)
    }

    @Test
    fun `hashPasswordWithSalt produces different hashes for different salts`() {
        val password = "minhaSenha123"
        val salt1 = AuthManager.generateSalt()
        val salt2 = AuthManager.generateSalt()
        val hash1 = AuthManager.hashPasswordWithSalt(password, salt1)
        val hash2 = AuthManager.hashPasswordWithSalt(password, salt2)
        assertThat(hash1).isNotEqualTo(hash2)
    }

    @Test
    fun `hashPasswordWithSalt produces different hashes for different passwords`() {
        val salt = AuthManager.generateSalt()
        val hash1 = AuthManager.hashPasswordWithSalt("password1", salt)
        val hash2 = AuthManager.hashPasswordWithSalt("password2", salt)
        assertThat(hash1).isNotEqualTo(hash2)
    }

    @Test
    fun `hashPasswordWithSalt returns 64-char hex string (SHA-256 = 32 bytes)`() {
        val hash = AuthManager.hashPasswordWithSalt("test", "00000000000000000000000000000000")
        // PBKDF2 scheme v2: prefix "v2:" + 64 hex chars (32 bytes SHA-256)
        assertThat(hash).hasLength(67) // 3 (prefix) + 64 (hash)
        assertThat(hash).matches("v2:[0-9a-f]{64}")
    }

    @Test
    fun `hashPasswordLegacy is deterministic for same password`() {
        val password = "minhaSenha123"
        val hash1 = AuthManager.hashPasswordLegacy(password)
        val hash2 = AuthManager.hashPasswordLegacy(password)
        assertThat(hash1).isEqualTo(hash2)
    }

    @Test
    fun `hashPasswordLegacy returns 64-char hex string (SHA-256)`() {
        val hash = AuthManager.hashPasswordLegacy("test")
        assertThat(hash).hasLength(64)
        assertThat(hash).matches("[0-9a-f]{64}")
    }

    @Test
    fun `hashPasswordLegacy is NOT equal to hashPasswordWithSalt for same password`() {
        val password = "minhaSenha123"
        val salt = AuthManager.generateSalt()
        val legacyHash = AuthManager.hashPasswordLegacy(password)
        val saltedHash = AuthManager.hashPasswordWithSalt(password, salt)
        // Legacy é SHA-256 puro; salted é SHA-256 com 5000 iterações + salt
        assertThat(legacyHash).isNotEqualTo(saltedHash)
    }

    @Test
    fun `hashPasswordWithSalt handles empty password`() {
        val salt = AuthManager.generateSalt()
        val hash = AuthManager.hashPasswordWithSalt("", salt)
        // Não deve lançar exceção e deve produzir hash válido
        assertThat(hash).hasLength(67) // v2: prefix + 64 hex
        assertThat(hash).matches("v2:[0-9a-f]{64}")
    }

    @Test
    fun `hashPasswordWithSalt handles unicode password`() {
        val salt = AuthManager.generateSalt()
        val hash = AuthManager.hashPasswordWithSalt("senhação☕", salt)
        assertThat(hash).hasLength(67) // v2: prefix + 64 hex
        assertThat(hash).matches("v2:[0-9a-f]{64}")
    }

    @Test
    fun `hashPasswordWithSalt handles very long password`() {
        val salt = AuthManager.generateSalt()
        val longPassword = "a".repeat(10_000)
        val hash = AuthManager.hashPasswordWithSalt(longPassword, salt)
        assertThat(hash).hasLength(67) // v2: prefix + 64 hex
    }

    @Test
    fun `verifyPassword via hash comparison — same salt reproduces same hash`() {
        // Cenário: armazenar senha com salt, depois verificar
        val password = "senhaTeste123"
        val salt = AuthManager.generateSalt()
        val storedHash = AuthManager.hashPasswordWithSalt(password, salt)

        // Simulação de verificação: re-computa o hash com o salt armazenado
        val attemptHash = AuthManager.hashPasswordWithSalt(password, salt)
        assertThat(attemptHash).isEqualTo(storedHash)
    }

    @Test
    fun `verifyPassword via hash comparison — wrong password produces different hash`() {
        val correctPassword = "senhaCorreta"
        val wrongPassword = "senhaErrada"
        val salt = AuthManager.generateSalt()
        val storedHash = AuthManager.hashPasswordWithSalt(correctPassword, salt)

        val wrongAttemptHash = AuthManager.hashPasswordWithSalt(wrongPassword, salt)
        assertThat(wrongAttemptHash).isNotEqualTo(storedHash)
    }

    @Test
    fun `serialized limit password verifies only the configured password`() {
        val password = "limite123"
        val salt = AuthManager.generateSalt()
        val stored = "$salt:${AuthManager.hashPasswordWithSalt(password, salt)}"

        assertThat(AuthManager.verifySerializedPassword(password, stored)).isTrue()
        assertThat(AuthManager.verifySerializedPassword("incorreta", stored)).isFalse()
        assertThat(AuthManager.verifySerializedPassword(password, "inválido")).isFalse()
    }
}
