package com.focusguard.manager

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.room.withTransaction
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.data.PredefinedWebsites
import com.focusguard.data.RecoveryProtectionPreset
import com.focusguard.database.AppDatabase
import com.focusguard.database.AppUsageLimit
import com.focusguard.database.BlockSession
import com.focusguard.database.SessionAppCrossRef
import com.focusguard.database.SessionWebsiteCrossRef
import com.focusguard.database.WebsiteUsageLimit
import com.focusguard.domain.model.BlockSessionType
import com.focusguard.domain.model.UsageLimitLockMode
import com.focusguard.domain.port.BlockingRuntimePort
import com.focusguard.domain.port.BlockingUserMessage
import com.focusguard.security.BlockTargetPolicy
import com.focusguard.security.DeactivationCredentialManager
import com.focusguard.security.DopamineStartPolicy
import com.focusguard.security.MasterCredentialPolicy
import com.focusguard.security.ProtectionPermissionGate
import com.focusguard.security.SelfProtectionStateStore
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.utils.WebsiteBlocker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns creation of sessions and limits, including their precondition gates. */
@Singleton
class BlockingSessionCreationService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deactivationCredentialManager: DeactivationCredentialManager,
    private val database: AppDatabase,
    private val deviceOwnerManager: DeviceOwnerManager,
    private val protectionPermissionGate: ProtectionPermissionGate,
    private val blockingRuntime: BlockingRuntimePort,
    private val reconciler: BlockingPolicyReconciler
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun configureDailyLimits(
        apps: List<BlockingSessionManager.DailyLimitAppTarget>,
        sites: List<String>,
        dailyLimitMinutes: Int,
        addPasswordProtection: Boolean
    ) = withContext(Dispatchers.IO) {
        ensureBlockingPermissionsReady()
        require(dailyLimitMinutes in 1..24 * 60)
        if (addPasswordProtection) ensureMasterCredentialFor(BlockSessionType.PASSWORD)

        val appTargets = apps
            .filter { it.packageName.isNotBlank() }
            .distinctBy { it.packageName }
        val normalizedSites = BlockTargetPolicy.acceptedRules(
            kinds = BlockTargetPolicy.DAILY_LIMIT,
            rules = sites
        )

        database.withTransaction {
            val existingAppLimits = database.appUsageLimitDao()
                .getAllStatic()
                .associateBy { it.packageName }
            val existingSiteLimits = database.websiteUsageLimitDao()
                .getAllStatic()
                .associateBy { WebsiteBlocker.normalizeRule(it.domain) }

            appTargets.forEach { app ->
                val existing = existingAppLimits[app.packageName]
                val lockMode = if (addPasswordProtection) {
                    UsageLimitLockMode.PASSWORD
                } else {
                    existing?.lockMode ?: UsageLimitLockMode.NONE
                }
                database.appUsageLimitDao().insert(
                    AppUsageLimit(
                        packageName = app.packageName,
                        appName = app.appName,
                        dailyLimitMinutes = dailyLimitMinutes,
                        isEnabled = true,
                        lockMode = lockMode,
                        lockPasswordHash = null,
                        lockUntilTimestamp = if (
                            lockMode == UsageLimitLockMode.PASSWORD
                        ) null else existing?.lockUntilTimestamp,
                        preventOpeningAfterLimit = true,
                        unlockWithPassword = lockMode == UsageLimitLockMode.PASSWORD
                    )
                )
            }
            normalizedSites.forEach { rule ->
                val existing = existingSiteLimits[rule]
                val lockMode = if (addPasswordProtection) {
                    UsageLimitLockMode.PASSWORD
                } else {
                    existing?.lockMode ?: UsageLimitLockMode.NONE
                }
                database.websiteUsageLimitDao().insert(
                    WebsiteUsageLimit(
                        domain = rule,
                        dailyLimitMinutes = dailyLimitMinutes,
                        isEnabled = true,
                        lockMode = lockMode,
                        lockPasswordHash = null,
                        lockUntilTimestamp = if (
                            lockMode == UsageLimitLockMode.PASSWORD
                        ) null else existing?.lockUntilTimestamp
                    )
                )
            }
        }

        reconciler.reconcile()
    }

    suspend fun startPasswordSession(
        isFixed24h: Boolean,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
        daysOfWeek: String,
        apps: List<String>,
        sites: List<String>
    ) = withContext(Dispatchers.IO) {
        try {
            ensureBlockingPermissionsReady()
            ensureMasterCredentialFor(BlockSessionType.PASSWORD)
            val normalizedSites = BlockTargetPolicy.acceptedRulesForSessionType(
                sessionType = BlockTargetPolicy.SESSION_TYPE_PASSWORD,
                rules = sites
            )
            if (sites.isNotEmpty()) {
                FocusGuardLogger.log(
                    TAG,
                    "Bloqueio por senha ignora ${sites.size} regra(s) de site/palavra"
                )
            }
            database.withTransaction {
                val session = BlockSession(
                    startTime = System.currentTimeMillis(),
                    isActive = true,
                    isRecurring = !isFixed24h,
                    recurringStartHour = startHour,
                    recurringStartMinute = startMinute,
                    recurringEndHour = endHour,
                    recurringEndMinute = endMinute,
                    recurringDaysOfWeek = parseRecurringDays(daysOfWeek),
                    blockedAppsCount = apps.distinct().size,
                    blockedWebsitesCount = normalizedSites.size,
                    sessionType = BlockSessionType.PASSWORD,
                    isFixed24h = isFixed24h
                )
                val sessionId = database.blockSessionDao().insertNewSession(session).toInt()
                apps.distinct().forEach {
                    database.sessionAppCrossRefDao().insert(SessionAppCrossRef(sessionId, it))
                }
                normalizedSites.forEach {
                    database.sessionWebsiteCrossRefDao().insert(
                        SessionWebsiteCrossRef(sessionId, it)
                    )
                }
            }
            armSelfProtectionBeforeFirstExposure()
            reconciler.reconcile()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            FocusGuardLogger.logError(TAG, "Erro ao iniciar sessão", error)
            throw error
        }
    }

    suspend fun startTimeSession(
        days: Int,
        hours: Int,
        isFixed24h: Boolean,
        openEnded: Boolean,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
        daysOfWeek: String,
        apps: List<String>,
        sites: List<String>
    ) = withContext(Dispatchers.IO) {
        val protectionWasAlreadyArmed = deviceOwnerManager.isBlockingProtectionArmed()
        var sessionCreated = false
        try {
            val normalizedSites = BlockTargetPolicy.acceptedRulesForSessionType(
                sessionType = BlockTargetPolicy.SESSION_TYPE_TIME,
                rules = sites
            )
            val normalizedApps = apps.filter(String::isNotBlank).distinct()
            require(normalizedApps.isNotEmpty() || normalizedSites.isNotEmpty()) {
                "O Jejum de Dopamina exige pelo menos um app ou site"
            }
            val duration = TimeUnit.DAYS.toMillis(days.toLong()) +
                TimeUnit.HOURS.toMillis(hours.toLong())
            require(openEnded || duration > 0L) { "A duração da sessão deve ser positiva" }
            ensureBlockingPermissionsReady()
            database.withTransaction {
                val startMillis = System.currentTimeMillis()
                val session = BlockSession(
                    startTime = startMillis,
                    endTime = if (openEnded) null else startMillis + duration,
                    isActive = true,
                    isRecurring = !isFixed24h,
                    recurringStartHour = startHour,
                    recurringStartMinute = startMinute,
                    recurringEndHour = endHour,
                    recurringEndMinute = endMinute,
                    recurringDaysOfWeek = parseRecurringDays(daysOfWeek),
                    blockedAppsCount = normalizedApps.size,
                    blockedWebsitesCount = normalizedSites.size,
                    sessionType = BlockSessionType.TIME,
                    isFixed24h = isFixed24h
                )
                val sessionId = database.blockSessionDao().insertNewSession(session).toInt()
                normalizedApps.forEach {
                    database.sessionAppCrossRefDao().insert(SessionAppCrossRef(sessionId, it))
                }
                normalizedSites.forEach {
                    database.sessionWebsiteCrossRefDao().insert(
                        SessionWebsiteCrossRef(sessionId, it)
                    )
                }
            }
            sessionCreated = true
            armSelfProtectionBeforeFirstExposure()
            reconciler.reconcile()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!sessionCreated && !protectionWasAlreadyArmed) {
                deviceOwnerManager.clearBlockingPolicies()
                deviceOwnerManager.applyNuclearShield()
            }
            FocusGuardLogger.logError(TAG, "Erro ao iniciar sessão por tempo", error)
            throw error
        }
    }

    suspend fun startRecoveryProtectionPreset(
        typedConsent: String
    ) = withContext(Dispatchers.IO) {
        require(RecoveryProtectionPreset.isConsentAccepted(typedConsent)) {
            "O termo de consentimento deve ser digitado exatamente"
        }

        val protectionWasAlreadyArmed = deviceOwnerManager.isBlockingProtectionArmed()
        var sessionsCreated = false
        try {
            val pornographySites = BlockTargetPolicy.acceptedRulesForSessionType(
                sessionType = BlockTargetPolicy.SESSION_TYPE_TIME,
                rules = listOf(PredefinedWebsites.PORNOGRAPHY_RULE)
            )
            val socialSites = BlockTargetPolicy.acceptedRulesForSessionType(
                sessionType = BlockTargetPolicy.SESSION_TYPE_TIME,
                rules = RecoveryProtectionPreset.SOCIAL_WEBSITE_RULES.toList()
            )
            val socialApps = getRecoverySocialApps()

            require(pornographySites.isNotEmpty()) {
                "A categoria de pornografia não pôde ser preparada"
            }
            require(socialApps.isNotEmpty() || socialSites.isNotEmpty()) {
                "Nenhuma rede social pôde ser preparada"
            }

            ensureBlockingPermissionsReady()
            database.withTransaction {
                val startMillis = System.currentTimeMillis()
                val pornographySessionId = database.blockSessionDao().insertNewSession(
                    BlockSession(
                        startTime = startMillis,
                        endTime = null,
                        isActive = true,
                        isRecurring = false,
                        blockedAppsCount = 0,
                        blockedWebsitesCount = pornographySites.size,
                        sessionType = BlockSessionType.TIME,
                        isFixed24h = true
                    )
                ).toInt()
                pornographySites.forEach { rule ->
                    database.sessionWebsiteCrossRefDao().insert(
                        SessionWebsiteCrossRef(pornographySessionId, rule)
                    )
                }

                val socialSessionId = database.blockSessionDao().insertNewSession(
                    BlockSession(
                        startTime = startMillis,
                        endTime = startMillis + TimeUnit.DAYS.toMillis(
                            RecoveryProtectionPreset.SOCIAL_BLOCK_DAYS.toLong()
                        ),
                        isActive = true,
                        isRecurring = false,
                        blockedAppsCount = socialApps.size,
                        blockedWebsitesCount = socialSites.size,
                        sessionType = BlockSessionType.TIME,
                        isFixed24h = true
                    )
                ).toInt()
                socialApps.forEach { packageName ->
                    database.sessionAppCrossRefDao().insert(
                        SessionAppCrossRef(socialSessionId, packageName)
                    )
                }
                socialSites.forEach { rule ->
                    database.sessionWebsiteCrossRefDao().insert(
                        SessionWebsiteCrossRef(socialSessionId, rule)
                    )
                }
            }
            sessionsCreated = true
            armSelfProtectionBeforeFirstExposure()
            reconciler.reconcile()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!sessionsCreated && !protectionWasAlreadyArmed) {
                deviceOwnerManager.clearBlockingPolicies()
                deviceOwnerManager.applyNuclearShield()
            }
            FocusGuardLogger.logError(
                TAG,
                "Erro ao ativar o atalho de proteção AntiPorn",
                error
            )
            throw error
        }
    }

    fun startPomodoroSession(durationMs: Long, isBlockingEnabled: Boolean) {
        scope.launch {
            runCatching {
                require(durationMs > 0L) { "A duração do Pomodoro deve ser positiva" }
                if (isBlockingEnabled) ensureBlockingPermissionsReady()
                database.withTransaction {
                    database.blockSessionDao()
                        .deactivateActiveSessionsByType(BlockSessionType.POMODORO)
                    if (isBlockingEnabled) {
                        val startMillis = System.currentTimeMillis()
                        database.blockSessionDao().insertNewSession(
                            BlockSession(
                                startTime = startMillis,
                                endTime = startMillis + durationMs,
                                isActive = true,
                                sessionType = BlockSessionType.POMODORO,
                                isFixed24h = true,
                                isBlockingEnabled = true
                            )
                        )
                    }
                }
                if (isBlockingEnabled) armSelfProtectionBeforeFirstExposure()
                reconcileSafely()
            }.onSuccess {
                blockingRuntime.showUserMessage(BlockingUserMessage.POMODORO_STARTED)
            }.onFailure {
                FocusGuardLogger.logError(TAG, "Erro ao iniciar Pomodoro", it)
            }
        }
    }

    private fun ensureMasterCredentialFor(sessionType: BlockSessionType) {
        val gate = MasterCredentialPolicy.evaluateCreation(
            sessionType = sessionType.name,
            hasMasterCredential = deactivationCredentialManager.hasCredential()
        )
        if (gate == MasterCredentialPolicy.CreationGate.MASTER_CREDENTIAL_REQUIRED) {
            throw BlockingSessionManager.BlockingProtectionUnavailableException(
                BlockingSessionManager.BlockingProtectionUnavailableException.Reason
                    .MASTER_CREDENTIAL_REQUIRED
            )
        }
    }

    private fun ensureBlockingPermissionsReady() {
        val permissionState = protectionPermissionGate.read()
        if (!permissionState.isReady) {
            throw BlockingSessionManager.BlockingProtectionUnavailableException(
                BlockingSessionManager.BlockingProtectionUnavailableException.Reason
                    .PROTECTION_PERMISSIONS_REQUIRED
            )
        }
        val protectionLevel = DopamineStartPolicy.protectionLevel(
            DopamineStartPolicy.Capabilities(
                accessibilityEnabled = permissionState.accessibility,
                usageAccessEnabled = permissionState.usageAccess,
                batteryOptimizationExempt = permissionState.batteryOptimization,
                deviceOwnerActive = deviceOwnerManager.isDeviceOwnerActive()
            )
        )
        FocusGuardLogger.log(
            TAG,
            "Bloqueio liberado com proteção ${protectionLevel.name.lowercase(Locale.US)}"
        )
    }

    private fun armSelfProtectionBeforeFirstExposure() {
        check(SelfProtectionStateStore.setArmed(context, true)) {
            "Não foi possível armar a proteção síncrona da nova sessão"
        }
    }

    private fun getRecoverySocialApps(): List<String> {
        val discoveredSocialApps = try {
            context.packageManager
                .getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { app ->
                    val isSystemApp = app.flags and (
                        ApplicationInfo.FLAG_SYSTEM or
                            ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                        ) != 0
                    val declaredSocialCategory =
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                            app.category == ApplicationInfo.CATEGORY_SOCIAL

                    app.packageName != context.packageName &&
                        RecoveryProtectionPreset.shouldBlockApp(
                            packageName = app.packageName,
                            declaredSocialCategory = declaredSocialCategory,
                            isSystemApp = isSystemApp
                        )
                }
                .map { it.packageName }
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError(
                TAG,
                "Falha ao identificar redes sociais instaladas",
                error
            )
            emptyList()
        }

        return (RecoveryProtectionPreset.KNOWN_SOCIAL_APP_PACKAGES + discoveredSocialApps)
            .asSequence()
            .filterNot { it == context.packageName }
            .filterNot(RecoveryProtectionPreset::isMessengerPackage)
            .distinct()
            .toList()
    }

    private fun parseRecurringDays(value: String): Set<Int> = value
        .split(',')
        .mapNotNull { it.trim().toIntOrNull() }
        .filterTo(linkedSetOf()) { it in Calendar.SUNDAY..Calendar.SATURDAY }

    private suspend fun reconcileSafely() {
        try {
            reconciler.reconcile()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            FocusGuardLogger.logError(TAG, "Erro ao reconciliar bloqueios", error)
        }
    }

    private companion object {
        const val TAG = "BlockingSessionCreation"
    }
}
