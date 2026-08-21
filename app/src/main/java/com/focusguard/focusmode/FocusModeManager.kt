package com.focusguard.focusmode

import android.content.Context
import android.content.Intent
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.domain.port.BlockingEnforcementPort
import com.focusguard.domain.port.FocusModeRuntimePort
import com.focusguard.domain.port.FocusModeSystemPort
import com.focusguard.pomodoro.StrictPomodoroLock
import com.focusguard.state.FocusModeStore
import com.focusguard.utils.FocusGuardLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class FocusModeManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceOwnerManager: DeviceOwnerManager,
    private val blockingEnforcement: BlockingEnforcementPort,
    private val systemPort: FocusModeSystemPort,
    private val runtimePort: FocusModeRuntimePort
) {
    enum class StartOutcome {
        STARTED,
        INVALID_DURATION,
        ACCESSIBILITY_REQUIRED,
        NOTIFICATION_ACCESS_REQUIRED,
        STRICT_POMODORO_ACTIVE,
        ENFORCEMENT_FAILED
    }

    data class StartResult(
        val outcome: StartOutcome,
        val session: FocusModeSession? = null
    )

    private val mutationMutex = Mutex()
    private val _session = MutableStateFlow(
        FocusModeStore.readSession(context)?.takeIf { it.isActive() }
    )
    val session: StateFlow<FocusModeSession?> = _session.asStateFlow()

    fun isActive(): Boolean = FocusModeStore.isActive(context)

    fun isAccessibilityServiceEnabled(): Boolean =
        runtimePort.isAccessibilityEnabled()

    fun isNotificationAccessEnabled(): Boolean =
        runtimePort.isNotificationAccessEnabled()

    suspend fun loadSelectableApps(): List<FocusModeSelectableApp> =
        withContext(Dispatchers.IO) { FocusModeAppCatalog.loadLaunchableApps(context) }

    fun initialSelectedPackages(apps: Collection<FocusModeSelectableApp>): Set<String> {
        val installed = apps.mapTo(mutableSetOf()) { it.packageName }
        return FocusModeStore.readDraftPackages(context)
            ?.intersect(installed)
            ?: FocusModeAppCatalog.defaultDraftPackages(context, installed)
    }

    fun saveDraftPackages(packageNames: Set<String>) {
        if (!FocusModeStore.saveDraftPackages(context, packageNames)) {
            FocusGuardLogger.log("FocusMode", "Não foi possível salvar a seleção de apps")
        }
    }

    suspend fun start(
        durationMillis: Long,
        selectedPackages: Set<String>,
        grayscaleEnabled: Boolean
    ): StartResult = mutationMutex.withLock {
        if (durationMillis !in 1L..FocusModePolicy.MAX_DURATION_MILLIS) {
            return@withLock StartResult(StartOutcome.INVALID_DURATION)
        }
        if (!isAccessibilityServiceEnabled()) {
            return@withLock StartResult(StartOutcome.ACCESSIBILITY_REQUIRED)
        }
        if (!isNotificationAccessEnabled()) {
            return@withLock StartResult(StartOutcome.NOTIFICATION_ACCESS_REQUIRED)
        }
        if (StrictPomodoroLock.isActive(context)) {
            return@withLock StartResult(StartOutcome.STRICT_POMODORO_ACTIVE)
        }

        val launchableApps = withContext(Dispatchers.IO) {
            FocusModeAppCatalog.loadLaunchableApps(context)
        }
        val launchablePackages = launchableApps.mapTo(mutableSetOf()) { it.packageName }
        val selectedInstalled = selectedPackages.intersect(launchablePackages)
        val mandatoryPackages = FocusModeAppCatalog.mandatoryPackages(context)
        val allowedPackages = FocusModePolicy.buildAllowedPackages(
            focusGuardPackage = context.packageName,
            mandatoryPackages = mandatoryPackages,
            selectedPackages = selectedInstalled
        )
        val blockedPackages = FocusModePolicy.packagesToBlock(
            launchablePackages = launchablePackages,
            allowedPackages = allowedPackages
        )
        val now = System.currentTimeMillis()
        val session = FocusModeSession(
            startedAtMillis = now,
            endTimeMillis = now + durationMillis,
            durationMillis = durationMillis,
            allowedPackages = allowedPackages,
            blockedPackages = blockedPackages,
            grayscaleEnabled = grayscaleEnabled
        )

        if (!FocusModeStore.saveSession(context, session)) {
            return@withLock StartResult(StartOutcome.ENFORCEMENT_FAILED)
        }

        try {
            val nativeFocusLockdownActive = FocusModePolicy.usesNativeFocusLockdown(
                deviceOwnerActive = deviceOwnerManager.isDeviceOwnerActive(),
                systemLockdownSupported =
                    deviceOwnerManager.isFocusModeSystemLockdownSupported()
            )
            if (nativeFocusLockdownActive) {
                check(deviceOwnerManager.prepareFocusModeLockTaskPackages(allowedPackages)) {
                    "O Android não confirmou a lista de apps do quiosque"
                }
            }
            check(systemPort.reconcileSystemRestrictions()) {
                "O Android não confirmou a proteção de janelas do quiosque"
            }
            blockingEnforcement.checkAndEnforceStrict()
            if (nativeFocusLockdownActive) {
                check(deviceOwnerManager.isFocusModeSystemLockdownConfirmed()) {
                    "O Android não confirmou o quiosque e o bloqueio de modo seguro"
                }
            }
            val nonSuspendable = if (nativeFocusLockdownActive) {
                blockedPackages.filterNotTo(mutableSetOf()) {
                    deviceOwnerManager.isPackageSuspendedByFocusMode(it)
                }
            } else {
                emptySet()
            }
            val verifiedSession = FocusModeStore.updateNonSuspendablePackages(
                context,
                nonSuspendable
            ) ?: session.copy(nonSuspendablePackages = nonSuspendable)
            saveDraftPackages(selectedInstalled)
            runtimePort.activate(verifiedSession.endTimeMillis)
            _session.value = verifiedSession
            StartResult(StartOutcome.STARTED, verifiedSession)
        } catch (cancelled: CancellationException) {
            rollbackFailedStart()
            throw cancelled
        } catch (error: Exception) {
            FocusGuardLogger.logError(
                "FocusMode",
                "Falha ao ativar o Modo Foco",
                error
            )
            rollbackFailedStart()
            StartResult(StartOutcome.ENFORCEMENT_FAILED)
        }
    }

    suspend fun ensureEnforced(): Boolean = mutationMutex.withLock {
        val stored = FocusModeStore.readSession(context)
        if (stored == null) {
            _session.value = null
            systemPort.reconcileSystemRestrictions()
            return@withLock false
        }
        if (!stored.isActive()) {
            finishSessionLocked()
            return@withLock false
        }
        return@withLock try {
            val nativeFocusLockdownActive = FocusModePolicy.usesNativeFocusLockdown(
                deviceOwnerActive = deviceOwnerManager.isDeviceOwnerActive(),
                systemLockdownSupported =
                    deviceOwnerManager.isFocusModeSystemLockdownSupported()
            )
            if (nativeFocusLockdownActive) {
                check(deviceOwnerManager.prepareFocusModeLockTaskPackages(stored.allowedPackages))
            }
            check(systemPort.reconcileSystemRestrictions())
            blockingEnforcement.checkAndEnforceStrict()
            if (nativeFocusLockdownActive) {
                check(deviceOwnerManager.isFocusModeSystemLockdownConfirmed())
            }
            runtimePort.activate(stored.endTimeMillis)
            _session.value = stored
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            FocusGuardLogger.logError(
                "FocusMode",
                "Falha ao reconciliar o Modo Foco",
                error
            )
            false
        }
    }

    suspend fun finishExpiredSession() = mutationMutex.withLock {
        val stored = FocusModeStore.readSession(context)
        if (stored?.isActive() == true) return@withLock
        if (stored == null && _session.value == null) {
            systemPort.reconcileSystemRestrictions()
            return@withLock
        }
        finishSessionLocked()
    }

    private suspend fun finishSessionLocked() = withContext(NonCancellable) {
        val hadState = FocusModeStore.readSession(context) != null || _session.value != null
        FocusModeStore.clearSession(context)
        systemPort.reconcileSystemRestrictions()
        if (hadState) {
            runCatching { blockingEnforcement.checkAndEnforceStrict() }
                .onFailure { error ->
                    FocusGuardLogger.logError(
                        "FocusMode",
                        "Falha ao restaurar os bloqueios anteriores",
                        error
                    )
                }
        }
        runtimePort.deactivate()
        deviceOwnerManager.clearFocusModeLockTaskPackages()
        _session.value = null
    }

    private suspend fun rollbackFailedStart() = withContext(NonCancellable) {
        FocusModeStore.clearSession(context)
        systemPort.reconcileSystemRestrictions()
        runCatching { blockingEnforcement.checkAndEnforceStrict() }
        runtimePort.deactivate()
        deviceOwnerManager.clearFocusModeLockTaskPackages()
        _session.value = null
    }

    fun createOpenAppIntent(packageName: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
