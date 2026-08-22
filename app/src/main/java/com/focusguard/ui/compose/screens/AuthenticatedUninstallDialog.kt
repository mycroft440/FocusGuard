package com.focusguard.ui.compose.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.focusguard.R
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.security.AppUnlockBiometricAuthenticator
import com.focusguard.uninstall.AuthenticatedUninstallCoordinator
import com.focusguard.security.BiometricAppUnlockPolicy
import com.focusguard.security.DeactivationCredentialManager
import com.focusguard.security.PasswordAppUnlockMode
import com.focusguard.security.PasswordAppUnlockStore
import com.focusguard.ui.compose.components.PatternLockInput
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.TextSecondary
import kotlinx.coroutines.launch

private data class PendingUninstallSessionAuth(
    val sessionId: Int,
    val packageNames: List<String>
)

private data class UninstallSessionPackageConfig(
    val sessionId: Int,
    val packageName: String,
    val mode: PasswordAppUnlockMode,
    val biometricEnabled: Boolean,
    val hidePatternTrace: Boolean
)

/**
 * User-facing uninstall authorization.
 *
 * Product rules:
 *  - no active block -> uninstall is immediately available, regardless of granted permissions;
 *  - time-hardened block -> uninstall stays sealed until expiry/monthly maintenance;
 *  - reversible password block -> reuse the same password, pattern or biometric that can open it;
 *  - legacy/site-only reversible blocks fall back to the master deactivation credential.
 *
 * Authorization is tracked per active session, not per app. That mirrors the existing unlock
 * behavior: authenticating one protected app ends the responsible PASSWORD session, including
 * any sites that belong to that same session. Independent sessions still need independent
 * authorization, so one weak block cannot be used to uninstall around a stronger one.
 */
@Composable
internal fun AuthenticatedUninstallDialog(
    credentialManager: DeactivationCredentialManager,
    sessionManager: BlockingSessionManager,
    deviceOwnerManager: DeviceOwnerManager,
    uninstallCoordinator: AuthenticatedUninstallCoordinator,
    appUnlockStore: PasswordAppUnlockStore,
    hasActiveBlock: Boolean,
    hasActiveIrreversibleBlock: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()
    var remainingSessions by remember {
        mutableStateOf<List<PendingUninstallSessionAuth>>(emptyList())
    }
    var needsMasterFallback by remember { mutableStateOf(false) }
    var authorizationLoaded by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var masterCredential by remember { mutableStateOf("") }
    var patternResetKey by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    val maintenanceActive = deviceOwnerManager.isMaintenanceActive()
    val biometricAvailable = activity != null &&
        AppUnlockBiometricAuthenticator.isAvailable(context)
    val masterCredentialConfigured = remember { credentialManager.hasCredential() }

    val blockedMessage = stringResource(R.string.uninstall_app_blocked_by_active_block)
    val failedMessage = stringResource(R.string.uninstall_app_failed)
    val authorizationRequiredMessage = stringResource(R.string.uninstall_app_authorization_required)
    val wrongCredentialMessage = stringResource(R.string.uninstall_app_wrong_block_credential)
    val invalidMasterMessage = stringResource(R.string.device_owner_maintenance_invalid_credential)
    val masterNotConfiguredMessage = stringResource(R.string.master_credential_not_configured)
    val biometricPromptTitle = stringResource(R.string.uninstall_app_biometric_prompt_title)
    val biometricPromptSubtitle = stringResource(R.string.uninstall_app_biometric_prompt_subtitle)
    val cancelLabel = stringResource(R.string.cancel)

    LaunchedEffect(
        hasActiveBlock,
        hasActiveIrreversibleBlock,
        maintenanceActive
    ) {
        remainingSessions = emptyList()
        needsMasterFallback = false
        authorizationLoaded = false
        password = ""
        masterCredential = ""
        errorMessage = null

        if (
            hasActiveBlock &&
            !hasActiveIrreversibleBlock &&
            !maintenanceActive
        ) {
            val overview = runCatching { sessionManager.getBlockOverview() }.getOrNull()
            if (overview == null) {
                // Fail closed: if the live block inventory could not be read, require the
                // established master credential rather than silently treating it as empty.
                needsMasterFallback = true
            } else {
                // getBlockOverview() also knows about scheduled sessions that still exist in
                // Room. Re-check each entry against the live blocking classifier so a session
                // outside its current recurring window never asks for authorization here.
                val activePasswordEntries = overview.passwordEntries.filter { entry ->
                    val origin = if (entry.isWebsite) {
                        sessionManager.credentialUnlockOrigin(
                            blockedPackage = null,
                            blockedDomain = entry.identifier,
                            strictPomodoroActive = false
                        )
                    } else {
                        sessionManager.credentialUnlockOrigin(
                            blockedPackage = entry.identifier,
                            blockedDomain = null,
                            strictPomodoroActive = false
                        )
                    }
                    origin == BiometricAppUnlockPolicy.BlockOrigin.PASSWORD_SESSION
                }

                val entriesBySession = linkedMapOf<
                    Int,
                    MutableList<BlockingSessionManager.BlockOverview.Entry>
                >()
                var unresolvedEntryExists = false
                activePasswordEntries.forEach { entry ->
                    val sessionId = sessionManager.findResponsibleSessionId(
                        blockedPackage = entry.identifier.takeUnless { entry.isWebsite },
                        blockedDomain = entry.identifier.takeIf { entry.isWebsite }
                    )
                    if (sessionId == null) {
                        unresolvedEntryExists = true
                    } else {
                        entriesBySession.getOrPut(sessionId) { mutableListOf() }.add(entry)
                    }
                }

                val pending = mutableListOf<PendingUninstallSessionAuth>()
                var fallbackNeeded = unresolvedEntryExists
                entriesBySession.forEach { (sessionId, entries) ->
                    val configuredApps = entries
                        .filterNot { it.isWebsite }
                        .map { it.identifier }
                        .filter(String::isNotBlank)
                        .distinct()
                        .filter { appUnlockStore.get(it) != null }

                    if (configuredApps.isEmpty()) {
                        // Website-only and legacy sessions do not have a per-app unlock store.
                        fallbackNeeded = true
                    } else {
                        pending += PendingUninstallSessionAuth(
                            sessionId = sessionId,
                            packageNames = configuredApps
                        )
                    }
                }

                remainingSessions = pending
                // A currently active block with no PASSWORD-session entries is another reversible
                // surface (for example the focus timer) and retains the established master exit.
                needsMasterFallback = fallbackNeeded || activePasswordEntries.isEmpty()
            }
        }
        authorizationLoaded = true
    }

    val sessionPackageConfigs = remainingSessions.flatMap { session ->
        session.packageNames.mapNotNull { packageName ->
            appUnlockStore.get(packageName)?.let { config ->
                UninstallSessionPackageConfig(
                    sessionId = session.sessionId,
                    packageName = packageName,
                    mode = config.mode,
                    biometricEnabled = config.biometricEnabled,
                    hidePatternTrace = config.hidePatternTrace
                )
            }
        }
    }
    val biometricSessionIds = sessionPackageConfigs
        .filter { it.biometricEnabled }
        .map { it.sessionId }
        .toSet()
    val passwordSessionIds = sessionPackageConfigs
        .filter { it.mode == PasswordAppUnlockMode.PASSWORD }
        .map { it.sessionId }
        .toSet()
    val patternSessionIds = sessionPackageConfigs
        .filter { it.mode == PasswordAppUnlockMode.PATTERN }
        .map { it.sessionId }
        .toSet()
    val sessionWithoutAvailableMethod = remainingSessions.any { session ->
        val configs = sessionPackageConfigs.filter { it.sessionId == session.sessionId }
        val hasTypedCredential = configs.any { it.mode != PasswordAppUnlockMode.BIOMETRIC_ONLY }
        val hasUsableBiometric = biometricAvailable && configs.any { it.biometricEnabled }
        !hasTypedCredential && !hasUsableBiometric
    }

    val reversibleAuthorizationComplete =
        authorizationLoaded && remainingSessions.isEmpty() && !needsMasterFallback
    val canProceed = when {
        hasActiveIrreversibleBlock && !maintenanceActive -> false
        maintenanceActive -> true
        !hasActiveBlock -> true
        else -> reversibleAuthorizationComplete
    }

    fun removeAuthorizedSessions(sessionIds: Collection<Int>) {
        if (sessionIds.isEmpty()) return
        remainingSessions = remainingSessions.filterNot { it.sessionId in sessionIds }
        errorMessage = null
    }

    fun authorizePassword() {
        if (password.isBlank() || working) return
        val unlockedSessionIds = remainingSessions.filter { session ->
            session.packageNames.any { packageName ->
                val config = appUnlockStore.get(packageName)
                config?.mode == PasswordAppUnlockMode.PASSWORD &&
                    appUnlockStore.verify(packageName, password)
            }
        }.map { it.sessionId }

        if (unlockedSessionIds.isEmpty()) {
            errorMessage = wrongCredentialMessage
        } else {
            removeAuthorizedSessions(unlockedSessionIds)
            password = ""
        }
    }

    fun authorizePattern(pattern: String) {
        if (working) return
        val unlockedSessionIds = remainingSessions.filter { session ->
            session.packageNames.any { packageName ->
                val config = appUnlockStore.get(packageName)
                config?.mode == PasswordAppUnlockMode.PATTERN &&
                    appUnlockStore.verify(packageName, pattern)
            }
        }.map { it.sessionId }

        if (unlockedSessionIds.isEmpty()) {
            errorMessage = wrongCredentialMessage
            patternResetKey++
        } else {
            removeAuthorizedSessions(unlockedSessionIds)
            patternResetKey++
        }
    }

    fun authorizeMasterFallback() {
        if (masterCredential.isBlank() || working) return
        val verification = credentialManager.verify(masterCredential)
        val verified = verification ==
            DeactivationCredentialManager.VerificationResult.PASSWORD_ACCEPTED ||
            verification ==
            DeactivationCredentialManager.VerificationResult.RECOVERY_ACCEPTED
        if (verified) {
            needsMasterFallback = false
            masterCredential = ""
            errorMessage = null
        } else {
            errorMessage = invalidMasterMessage
        }
    }

    fun launchBiometric() {
        val host = activity ?: run {
            errorMessage = context.getString(R.string.password_app_unlock_biometric_required)
            return
        }
        if (!biometricAvailable || biometricSessionIds.isEmpty() || working) return

        AppUnlockBiometricAuthenticator.authenticate(
            activity = host,
            title = biometricPromptTitle,
            subtitle = biometricPromptSubtitle,
            cancelLabel = cancelLabel,
            onSuccess = {
                // Re-read every session after Android returns so a biometric preference changed
                // while the prompt was open cannot authorize a session that no longer allows it.
                val stillBiometricSessionIds = remainingSessions.filter { session ->
                    session.packageNames.any { packageName ->
                        appUnlockStore.get(packageName)?.biometricEnabled == true
                    }
                }.map { it.sessionId }
                removeAuthorizedSessions(stillBiometricSessionIds)
            },
            onError = { message ->
                if (message.isNotBlank()) errorMessage = message
            }
        )
    }

    fun releaseAndHandOff() {
        if (!canProceed || working) return
        working = true
        errorMessage = null
        val authorization = if (
            hasActiveBlock && !maintenanceActive
        ) {
            AuthenticatedUninstallCoordinator.Authorization.REVERSIBLE_BLOCK_AUTHENTICATED
        } else {
            AuthenticatedUninstallCoordinator.Authorization.NONE
        }

        scope.launch {
            when (
                uninstallCoordinator.releaseAndOpen(authorization)
            ) {
                AuthenticatedUninstallCoordinator.Outcome.STARTED -> onDismiss()
                AuthenticatedUninstallCoordinator.Outcome.AUTHORIZATION_REQUIRED ->
                    errorMessage = authorizationRequiredMessage
                AuthenticatedUninstallCoordinator.Outcome.BLOCKED_BY_IRREVERSIBLE_BLOCK ->
                    errorMessage = blockedMessage
                AuthenticatedUninstallCoordinator.Outcome.RELEASE_FAILED,
                AuthenticatedUninstallCoordinator.Outcome.UNINSTALL_UI_FAILED ->
                    errorMessage = failedMessage
            }
            working = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(stringResource(R.string.uninstall_app_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                when {
                    hasActiveIrreversibleBlock && !maintenanceActive -> {
                        Text(blockedMessage, color = DangerRed)
                    }

                    maintenanceActive -> {
                        Text(
                            stringResource(R.string.uninstall_app_maintenance_description),
                            color = TextSecondary
                        )
                    }

                    !hasActiveBlock -> {
                        Text(
                            stringResource(R.string.uninstall_app_free_description),
                            color = TextSecondary
                        )
                    }

                    !authorizationLoaded -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator()
                            Spacer(Modifier.width(12.dp))
                            Text(
                                stringResource(R.string.uninstall_app_loading_authorization),
                                color = TextSecondary
                            )
                        }
                    }

                    else -> {
                        Text(
                            stringResource(R.string.uninstall_app_block_auth_description),
                            color = TextSecondary
                        )

                        val pendingCount = remainingSessions.size +
                            if (needsMasterFallback) 1 else 0
                        if (pendingCount > 0) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                stringResource(
                                    R.string.uninstall_app_pending_authorizations,
                                    pendingCount
                                ),
                                color = TextSecondary
                            )
                        }

                        if (biometricSessionIds.isNotEmpty() && biometricAvailable) {
                            Spacer(Modifier.height(14.dp))
                            Button(
                                onClick = { launchBiometric() },
                                enabled = !working,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Fingerprint, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.uninstall_app_use_biometric))
                            }
                        }

                        if (passwordSessionIds.isNotEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            OutlinedTextField(
                                value = password,
                                onValueChange = {
                                    password = it
                                    errorMessage = null
                                },
                                label = { Text(stringResource(R.string.uninstall_app_block_password)) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                modifier = Modifier.fillMaxWidth()
                            )
                            TextButton(
                                onClick = { authorizePassword() },
                                enabled = password.isNotBlank() && !working
                            ) {
                                Text(stringResource(R.string.uninstall_app_validate_password))
                            }
                        }

                        if (patternSessionIds.isNotEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            Text(
                                stringResource(R.string.uninstall_app_draw_pattern),
                                color = TextSecondary
                            )
                            PatternLockInput(
                                hideTrace = sessionPackageConfigs
                                    .filter { it.sessionId in patternSessionIds }
                                    .all { it.hidePatternTrace },
                                enabled = !working,
                                resetKey = patternResetKey,
                                onPatternComplete = ::authorizePattern
                            )
                        }

                        if (sessionWithoutAvailableMethod) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                stringResource(R.string.password_app_unlock_biometric_required),
                                color = DangerRed
                            )
                        }

                        if (needsMasterFallback) {
                            Spacer(Modifier.height(14.dp))
                            Text(
                                stringResource(R.string.uninstall_app_master_fallback_description),
                                color = TextSecondary
                            )
                            if (masterCredentialConfigured) {
                                OutlinedTextField(
                                    value = masterCredential,
                                    onValueChange = {
                                        masterCredential = it
                                        errorMessage = null
                                    },
                                    label = {
                                        Text(
                                            stringResource(
                                                R.string.device_owner_maintenance_password_label
                                            )
                                        )
                                    },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Password
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                TextButton(
                                    onClick = { authorizeMasterFallback() },
                                    enabled = masterCredential.isNotBlank() && !working
                                ) {
                                    Text(stringResource(R.string.uninstall_app_validate_password))
                                }
                            } else {
                                Text(masterNotConfiguredMessage, color = DangerRed)
                            }
                        }

                        if (reversibleAuthorizationComplete) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                stringResource(R.string.uninstall_app_authorization_complete),
                                color = TextSecondary
                            )
                        }
                    }
                }

                errorMessage?.let { message ->
                    Spacer(Modifier.height(10.dp))
                    Text(message, color = DangerRed)
                }
            }
        },
        confirmButton = {
            if (canProceed) {
                TextButton(
                    onClick = { releaseAndHandOff() },
                    enabled = !working
                ) {
                    Text(stringResource(R.string.uninstall_app_confirm), color = DangerRed)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (!working) onDismiss() }) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
