package com.focusguard.ui.compose.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.repository.AppLimitChange
import com.focusguard.repository.UsageLimitsRepository
import com.focusguard.repository.WebsiteLimitChange
import com.focusguard.security.AuthManager
import com.focusguard.security.DeactivationCredentialManager
import com.focusguard.security.MasterCredentialPolicy
import com.focusguard.permissions.ProtectionPermissionGate
import com.focusguard.ui.compose.components.limits.UsageLimitAppUi
import com.focusguard.ui.compose.components.limits.WebsiteLimitUi
import com.focusguard.utils.FocusGuardLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class PermissionReadiness {
    CHECKING,
    READY,
    MISSING
}

data class UsageLimitsUiState(
    val permissionReadiness: PermissionReadiness = PermissionReadiness.CHECKING,
    val apps: List<UsageLimitAppUi> = emptyList(),
    val websites: List<WebsiteLimitUi> = emptyList(),
    val isLoading: Boolean = true,
    val hasMasterCredential: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class UsageLimitsViewModel @Inject constructor(
    private val repository: UsageLimitsRepository,
    private val blockingSessionManager: BlockingSessionManager,
    private val authManager: AuthManager,
    private val credentialManager: DeactivationCredentialManager,
    private val protectionPermissionGate: ProtectionPermissionGate
) : ViewModel() {
    private val permissionReadiness = MutableStateFlow(PermissionReadiness.CHECKING)
    private val hasMasterCredential = MutableStateFlow(credentialManager.hasCredential())
    private val error = MutableStateFlow<String?>(null)

    private val apps = repository.observeInstalledApps()
        .catch { throwable ->
            reportFailure("carregar limites de aplicativos", throwable)
            emit(emptyList())
        }
    private val websites = repository.observeWebsiteLimits()
        .catch { throwable ->
            reportFailure("carregar limites de sites", throwable)
            emit(emptyList())
        }

    val uiState: StateFlow<UsageLimitsUiState> = combine(
        apps,
        websites,
        permissionReadiness,
        hasMasterCredential,
        error
    ) { appLimits, websiteLimits, permission, credentialConfigured, currentError ->
        UsageLimitsUiState(
            permissionReadiness = permission,
            apps = appLimits.map { limit ->
                UsageLimitAppUi(
                    packageName = limit.packageName,
                    appName = limit.appName,
                    currentLimitMinutes = limit.dailyLimitMinutes,
                    isEnabled = limit.isEnabled,
                    usageMs = limit.usageMillis,
                    lockMode = limit.lockMode,
                    lockPasswordHash = null,
                    lockUntilTimestamp = limit.lockUntilTimestamp
                )
            },
            websites = websiteLimits.map { limit ->
                WebsiteLimitUi(
                    domain = limit.domain,
                    dailyLimitMinutes = limit.dailyLimitMinutes,
                    isEnabled = limit.isEnabled,
                    usageMs = limit.usageMillis,
                    lockMode = limit.lockMode,
                    lockPasswordHash = null,
                    lockUntilTimestamp = limit.lockUntilTimestamp
                )
            },
            isLoading = permission == PermissionReadiness.CHECKING,
            hasMasterCredential = credentialConfigured,
            error = currentError
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = UsageLimitsUiState()
    )

    init {
        refresh()
    }

    fun refresh() {
        repository.refreshPlatformSnapshot()
        viewModelScope.launch {
            permissionReadiness.value = if (protectionPermissionGate.read().isReady) {
                PermissionReadiness.READY
            } else {
                PermissionReadiness.MISSING
            }
            hasMasterCredential.value = credentialManager.hasCredential()
        }
    }

    fun evaluateMutation(
        lockMode: String,
        lockUntilTimestamp: Long?
    ): MasterCredentialPolicy.MutationGate = MasterCredentialPolicy.evaluateLimitMutation(
        lockMode = lockMode,
        lockUntilTimestamp = lockUntilTimestamp,
        safetyModeEnabled = authManager.isSafetyModeEnabled(),
        hasMasterCredential = credentialManager.hasCredential(),
        masterCredentialVerified = false
    )

    fun verifyMasterCredential(
        credential: String
    ): DeactivationCredentialManager.VerificationResult = credentialManager.verify(credential)

    fun saveAppLimit(
        app: UsageLimitAppUi,
        minutes: Int?,
        enabled: Boolean,
        lockMode: String,
        lockUntilTimestamp: Long?
    ) {
        runProtectedMutation("salvar limite de aplicativo") {
            repository.saveAppLimit(
                AppLimitChange(
                    packageName = app.packageName,
                    appName = app.appName,
                    dailyLimitMinutes = minutes,
                    isEnabled = enabled,
                    lockMode = lockMode,
                    lockUntilTimestamp = lockUntilTimestamp
                )
            )
        }
    }

    fun saveWebsiteLimit(
        previousDomain: String?,
        domain: String,
        minutes: Int,
        enabled: Boolean,
        lockMode: String,
        lockUntilTimestamp: Long?
    ) {
        runProtectedMutation("salvar limite de site") {
            repository.saveWebsiteLimit(
                WebsiteLimitChange(
                    previousDomain = previousDomain,
                    domain = domain,
                    dailyLimitMinutes = minutes,
                    isEnabled = enabled,
                    lockMode = lockMode,
                    lockUntilTimestamp = lockUntilTimestamp
                )
            )
        }
    }

    fun deleteWebsiteLimit(domain: String) {
        runProtectedMutation("remover limite de site") {
            repository.deleteWebsiteLimit(domain)
        }
    }

    fun consumeError() {
        error.value = null
    }

    private fun runProtectedMutation(
        label: String,
        mutation: suspend () -> Unit
    ) {
        viewModelScope.launch {
            if (!protectionPermissionGate.read().isReady) {
                permissionReadiness.value = PermissionReadiness.MISSING
                return@launch
            }
            try {
                mutation()
                blockingSessionManager.checkAndEnforceStrict()
                repository.refreshPlatformSnapshot()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                reportFailure(label, throwable)
            }
        }
    }

    private fun reportFailure(operation: String, throwable: Throwable) {
        FocusGuardLogger.logError("UsageLimits", "Falha ao $operation", throwable)
        error.value = throwable.localizedMessage ?: "Falha ao $operation"
    }
}
