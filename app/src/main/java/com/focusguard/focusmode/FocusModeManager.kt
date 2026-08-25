package com.focusguard.focusmode

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.manager.StrictPomodoroLock
import com.focusguard.receiver.FocusModeReceiver
import com.focusguard.service.FocusModeForegroundService
import com.focusguard.service.FocusModeNotificationService
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.utils.PermissionUtils
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
    @ApplicationContext private val context: Context
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

    companion object {
        @Volatile
        private var legacyInstance: FocusModeManager? = null

        fun getInstance(context: Context): FocusModeManager {
            val appContext = context.applicationContext
            return try {
                val entryPoint = dagger.hilt.android.EntryPointAccessors.fromApplication(
                    appContext,
                    FocusModeManagerEntryPoint::class.java
                )
                entryPoint.focusModeManager()
            } catch (error: Exception) {
                FocusGuardLogger.logError(
                    "FocusMode",
                    "Hilt indisponível; usando singleton legado",
                    error
                )
                synchronized(this) {
                    legacyInstance ?: FocusModeManager(appContext).also {
                        legacyInstance = it
                    }
                }
            }
        }
    }

    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface FocusModeManagerEntryPoint {
        fun focusModeManager(): FocusModeManager
    }

    private val deviceOwnerManager = DeviceOwnerManager.getInstance(context)
    private val blockingSessionManager = BlockingSessionManager.getInstance(context)
    private val mutationMutex = Mutex()
    private val _session = MutableStateFlow(
        FocusModeStore.readSession(context)?.takeIf { it.isActive() }
    )
    val session: StateFlow<FocusModeSession?> = _session.asStateFlow()

    fun isActive(): Boolean = FocusModeStore.isActive(context)

    fun isAccessibilityServiceEnabled(): Boolean =
        PermissionUtils.isAccessibilityServiceEnabled(context)

    fun isNotificationAccessEnabled(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)

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
            check(FocusModeHomeController.reconcile(context)) {
                "O Android não confirmou o retorno do botão Home ao HardBlock"
            }
            check(FocusModeKioskController.reconcileSystemRestrictions(context)) {
                "O Android não confirmou a proteção de janelas do quiosque"
            }
            blockingSessionManager.checkAndEnforceStrict()
            if (nativeFocusLockdownActive) {
                check(FocusModeHomeController.isNativeHomeConfigured(context)) {
                    "O Android não confirmou Home + menu de energia no quiosque"
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
            FocusModeForegroundService.start(context)
            FocusModeReceiver.scheduleExpiration(context, verifiedSession.endTimeMillis)
            FocusModeNotificationService.requestRefresh(context)
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
            FocusModeHomeController.clear(context)
            _session.value = null
            FocusModeKioskController.reconcileSystemRestrictions(context)
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
            check(FocusModeHomeController.reconcile(context))
            check(FocusModeKioskController.reconcileSystemRestrictions(context))
            blockingSessionManager.checkAndEnforceStrict()
            if (nativeFocusLockdownActive) {
                check(FocusModeHomeController.isNativeHomeConfigured(context))
            }
            FocusModeForegroundService.start(context)
            FocusModeReceiver.scheduleExpiration(context, stored.endTimeMillis)
            FocusModeNotificationService.requestRefresh(context)
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
            FocusModeHomeController.clear(context)
            FocusModeKioskController.reconcileSystemRestrictions(context)
            return@withLock
        }
        finishSessionLocked()
    }

    suspend fun forceStopForDevelopmentExit() = mutationMutex.withLock {
        FocusModeStore.clearSession(context)
        FocusModeHomeController.clear(context)
        FocusModeKioskController.reconcileSystemRestrictions(context)
        FocusModeReceiver.cancelExpiration(context)
        FocusModeForegroundService.stop(context)
        FocusModeNotificationService.requestRefresh(context)
        // Keep Lock Task package allowlisting in place until the development
        // coordinator has removed every block and Device Owner policy. The Home
        // override itself is already safe to restore here.
        _session.value = null
    }

    private suspend fun finishSessionLocked() = withContext(NonCancellable) {
        val hadState = FocusModeStore.readSession(context) != null || _session.value != null
        FocusModeStore.clearSession(context)
        FocusModeHomeController.clear(context)
        FocusModeKioskController.reconcileSystemRestrictions(context)
        if (hadState) {
            runCatching { blockingSessionManager.checkAndEnforceStrict() }
                .onFailure { error ->
                    FocusGuardLogger.logError(
                        "FocusMode",
                        "Falha ao restaurar os bloqueios anteriores",
                        error
                    )
                }
        }
        FocusModeReceiver.cancelExpiration(context)
        FocusModeForegroundService.stop(context)
        FocusModeNotificationService.requestRefresh(context)
        deviceOwnerManager.clearFocusModeLockTaskPackages()
        _session.value = null
    }

    private suspend fun rollbackFailedStart() = withContext(NonCancellable) {
        FocusModeStore.clearSession(context)
        FocusModeHomeController.clear(context)
        FocusModeKioskController.reconcileSystemRestrictions(context)
        runCatching { blockingSessionManager.checkAndEnforceStrict() }
        FocusModeReceiver.cancelExpiration(context)
        FocusModeForegroundService.stop(context)
        FocusModeNotificationService.requestRefresh(context)
        deviceOwnerManager.clearFocusModeLockTaskPackages()
        _session.value = null
    }

    fun createOpenAppIntent(packageName: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
