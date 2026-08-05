package com.focusguard.manager

import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.room.withTransaction
import com.focusguard.R
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.database.AppDatabase
import com.focusguard.database.AppUsageLimit
import com.focusguard.database.BlockSession
import com.focusguard.database.SessionAppCrossRef
import com.focusguard.database.SessionWebsiteCrossRef
import com.focusguard.database.WebsiteUsageLimit
import com.focusguard.receiver.BlockingScheduleCalculator
import com.focusguard.receiver.BlockingScheduleReceiver
import com.focusguard.security.AuthManager
import com.focusguard.security.DeactivationCredentialManager
import com.focusguard.security.DopamineStartPolicy
import com.focusguard.security.MasterCredentialPolicy
import com.focusguard.service.BlockingAccessibilityService
import com.focusguard.service.PomodoroForegroundService
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.utils.PermissionUtils
import com.focusguard.utils.UsageLimitForegroundPolicy
import com.focusguard.utils.WebsiteBlocker
import com.focusguard.utils.WebsiteUsageLimitPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class BlockingSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deactivationCredentialManager: DeactivationCredentialManager
) {

    class BlockingProtectionUnavailableException(
        val reason: Reason
    ) : IllegalStateException(reason.name) {
        enum class Reason {
            ACCESSIBILITY_REQUIRED,

            /**
             * No master credential configured. Password blocks and dopamine fasts
             * cannot be armed without one — see [MasterCredentialPolicy].
             */
            MASTER_CREDENTIAL_REQUIRED
        }
    }

    /**
     * Refuses to arm a block until the master credential exists.
     *
     * Enforced here rather than only in the UI so no creation path — wizard,
     * protection setup, or a future caller — can arm a block that the user has no
     * authenticated way to manage afterwards.
     */
    private fun ensureMasterCredentialFor(sessionType: String) {
        val gate = MasterCredentialPolicy.evaluateCreation(
            sessionType = sessionType,
            hasMasterCredential = deactivationCredentialManager.hasCredential()
        )
        if (gate == MasterCredentialPolicy.CreationGate.MASTER_CREDENTIAL_REQUIRED) {
            throw BlockingProtectionUnavailableException(
                BlockingProtectionUnavailableException.Reason.MASTER_CREDENTIAL_REQUIRED
            )
        }
    }

    data class ConfiguredBlockedTargets(
        val passwordAppPackageNames: Set<String> = emptySet(),
        val passwordWebsiteRules: Set<String> = emptySet(),
        val limitedAppPackageNames: Set<String> = emptySet(),
        val limitedWebsiteRules: Set<String> = emptySet(),
        val exclusiveAppPackageNames: Set<String> = emptySet(),
        val exclusiveWebsiteRules: Set<String> = emptySet(),
        val unavailableAppPackageNames: Set<String> = emptySet(),
        val unavailableWebsiteRules: Set<String> = emptySet()
    ) {
        val allAppPackageNames: Set<String>
            get() = passwordAppPackageNames + limitedAppPackageNames + exclusiveAppPackageNames

        val allWebsiteRules: Set<String>
            get() = passwordWebsiteRules + limitedWebsiteRules + exclusiveWebsiteRules
    }

    data class DailyLimitAppTarget(
        val packageName: String,
        val appName: String
    )

    enum class EndSessionResult {
        ENDED,
        NOT_FOUND,
        POMODORO_NOT_REVOCABLE,
        TIME_NOT_REVOCABLE,
        FAILED
    }

    enum class LimitUnlockResult {
        UNLOCKED,
        WRONG_PASSWORD,
        NOT_FOUND,
        FAILED
    }

    companion object {
        private const val STATE_PREFERENCES = "blocking_session_manager_state"
        private const val PREVIOUS_DND_FILTER_KEY = "previous_dnd_filter"
        private const val POLICY_RECONCILIATION_INTERVAL_MILLIS = 15L * 60L * 1_000L

        @Volatile
        private var legacyInstance: BlockingSessionManager? = null

        fun getInstance(context: Context): BlockingSessionManager {
            return try {
                val entryPoint = dagger.hilt.android.EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    BlockingSessionManagerEntryPoint::class.java
                )
                entryPoint.blockingSessionManager()
            } catch (error: Exception) {
                FocusGuardLogger.logError(
                    "BlockingSessionManager",
                    "Hilt indisponível; usando singleton legado",
                    error
                )
                synchronized(this) {
                    legacyInstance ?: BlockingSessionManager(
                        context.applicationContext,
                        DeactivationCredentialManager(context.applicationContext)
                    ).also { legacyInstance = it }
                }
            }
        }

        internal fun combineConfiguredBlockedTargets(
            passwordSessionAppPackages: Collection<String>,
            passwordSessionWebsiteRules: Collection<String>,
            exclusiveSessionAppPackages: Collection<String>,
            exclusiveSessionWebsiteRules: Collection<String>,
            limitedAppPackages: Collection<String>,
            limitedWebsiteRules: Collection<String>
        ): ConfiguredBlockedTargets {
            val passwordWebsiteRules = WebsiteBlocker.normalizeRules(
                passwordSessionWebsiteRules +
                    WebsiteBlocker.domainRulesForAppPackages(passwordSessionAppPackages)
            )
            val normalizedLimitedWebsiteRules = WebsiteBlocker.normalizeRules(
                limitedWebsiteRules + WebsiteBlocker.domainRulesForAppPackages(limitedAppPackages)
            )
            val exclusiveWebsiteRules = WebsiteBlocker.normalizeRules(
                exclusiveSessionWebsiteRules +
                    WebsiteBlocker.domainRulesForAppPackages(exclusiveSessionAppPackages)
            )
            val passwordAppPackageNames = normalizeConfiguredAppPackages(
                passwordSessionAppPackages,
                passwordWebsiteRules
            )
            val normalizedLimitedAppPackages = normalizeConfiguredAppPackages(
                limitedAppPackages,
                normalizedLimitedWebsiteRules
            )
            val exclusiveAppPackageNames = normalizeConfiguredAppPackages(
                exclusiveSessionAppPackages,
                exclusiveWebsiteRules
            )
            val allWebsiteRules = WebsiteBlocker.normalizeRules(
                passwordWebsiteRules + normalizedLimitedWebsiteRules + exclusiveWebsiteRules
            )
            val unavailableWebsiteRules = allWebsiteRules.filterTo(linkedSetOf()) { candidate ->
                isWebsiteRuleCoveredBy(candidate, exclusiveWebsiteRules) ||
                    (
                        isWebsiteRuleCoveredBy(candidate, passwordWebsiteRules) &&
                            isWebsiteRuleCoveredBy(candidate, normalizedLimitedWebsiteRules)
                        )
            }
            val unavailableAppPackageNames = (
                exclusiveAppPackageNames +
                    passwordAppPackageNames.intersect(normalizedLimitedAppPackages) +
                    WebsiteBlocker.appPackageDomainsFor(unavailableWebsiteRules).keys
                ).filter(String::isNotBlank).toSet()

            return ConfiguredBlockedTargets(
                passwordAppPackageNames = passwordAppPackageNames,
                passwordWebsiteRules = passwordWebsiteRules,
                limitedAppPackageNames = normalizedLimitedAppPackages,
                limitedWebsiteRules = normalizedLimitedWebsiteRules,
                exclusiveAppPackageNames = exclusiveAppPackageNames,
                exclusiveWebsiteRules = exclusiveWebsiteRules,
                unavailableAppPackageNames = unavailableAppPackageNames,
                unavailableWebsiteRules = unavailableWebsiteRules
            )
        }

        private fun normalizeConfiguredAppPackages(
            packages: Collection<String>,
            websiteRules: Collection<String>
        ): Set<String> = (
            packages + WebsiteBlocker.appPackageDomainsFor(websiteRules).keys
            ).filter(String::isNotBlank).toSet()

        internal fun isWebsiteRuleCoveredBy(
            candidate: String,
            configuredRules: Collection<String>
        ): Boolean {
            val normalizedCandidate = WebsiteBlocker.normalizeRule(candidate)
            if (normalizedCandidate.isEmpty()) return false

            val normalizedConfigured = WebsiteBlocker.normalizeRules(configuredRules)
            if (normalizedCandidate in normalizedConfigured) return true

            return WebsiteBlocker.findMatchingRule(
                normalizedCandidate,
                normalizedConfigured
            ) != null
        }

        internal fun participatesInBlocking(session: BlockSession): Boolean {
            return session.sessionType != "POMODORO" || session.isBlockingEnabled
        }

        internal fun matchesBlockedTarget(
            blockedPackage: String?,
            blockedDomain: String?,
            sessionApps: Collection<String>,
            sessionSites: Collection<String>
        ): Boolean {
            return (!blockedPackage.isNullOrBlank() && blockedPackage in sessionApps) ||
                (!blockedDomain.isNullOrBlank() &&
                WebsiteBlocker.isUrlBlocked(blockedDomain, sessionSites))
        }

        internal fun shouldArmSelfProtection(
            hasEnforcingSessions: Boolean,
            hasBlockedApps: Boolean,
            hasBlockedSites: Boolean,
            adultFilterEnabled: Boolean
        ): Boolean = hasEnforcingSessions ||
            hasBlockedApps ||
            hasBlockedSites ||
            adultFilterEnabled
    }

    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface BlockingSessionManagerEntryPoint {
        fun blockingSessionManager(): BlockingSessionManager
    }

    private val database = AppDatabase.getDatabase(context)
    private val deviceOwnerManager = DeviceOwnerManager.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val enforcementMutex = Mutex()
    private val statePreferences = context.getSharedPreferences(
        STATE_PREFERENCES,
        Context.MODE_PRIVATE
    )

    val activeSessionsFlow: Flow<List<BlockSession>> =
        database.blockSessionDao().getAllActiveSessions()

    val isBlockingActiveFlow: Flow<Boolean> = activeSessionsFlow.map { sessions ->
        sessions.any { participatesInBlocking(it) && isCurrentlyInBlockingWindow(it) }
    }

    val hasRegisteredSessionFlow: Flow<Boolean> = activeSessionsFlow.map { it.isNotEmpty() }

    val sessionDetailsFlow: Flow<String> = activeSessionsFlow.map { sessions ->
        if (sessions.isEmpty()) return@map "Nenhuma sessão ativa"
        val formatter = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        buildString {
            appendLine("=== Sessões Ativas (${sessions.size}) ===")
            sessions.forEachIndexed { index, session ->
                appendLine("Sessão #${index + 1} (${session.sessionType})")
                appendLine(if (session.isFixed24h) "Modo: FIXO 24H" else "Modo: AGENDADO")
                session.endTime?.let { appendLine("Término: ${formatter.format(it)}") }
                appendLine("---")
            }
        }
    }

    /**
     * Returns targets grouped by protection mode.
     *
     * Password and daily-limit protection may coexist. Time/Pomodoro sessions are
     * exclusive, and targets that already have both compatible modes are exposed
     * as unavailable for an additional protection.
     */
    suspend fun getConfiguredBlockedTargets(): ConfiguredBlockedTargets =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val configuredSessions = database.blockSessionDao()
                .getAllActiveSessionsStatic()
                .filter { session -> session.endTime == null || session.endTime > now }
            val passwordSessionIds = configuredSessions
                .filter { it.sessionType == "PASSWORD" }
                .map { it.id }
            val exclusiveSessionIds = configuredSessions
                .filter { it.sessionType != "PASSWORD" }
                .map { it.id }

            combineConfiguredBlockedTargets(
                passwordSessionAppPackages = getAppsForSessions(passwordSessionIds),
                passwordSessionWebsiteRules = getSitesForSessions(passwordSessionIds),
                exclusiveSessionAppPackages = getAppsForSessions(exclusiveSessionIds),
                exclusiveSessionWebsiteRules = getSitesForSessions(exclusiveSessionIds),
                limitedAppPackages = database.appUsageLimitDao()
                    .getAllActiveLimitsStatic()
                    .map { it.packageName },
                limitedWebsiteRules = database.websiteUsageLimitDao()
                    .getAllStatic()
                    .filter { it.isEnabled }
                    .map { it.domain }
            )
        }

    /** Saves a daily limit and, optionally, an always-on password session together. */
    suspend fun configureDailyLimits(
        apps: List<DailyLimitAppTarget>,
        sites: List<String>,
        dailyLimitMinutes: Int,
        addPasswordProtection: Boolean
    ) = withContext(Dispatchers.IO) {
        require(dailyLimitMinutes in 1..24 * 60)
        // Checado antes da transação: um limite salvo sem a sessão de senha que o
        // usuário pediu deixaria a lista protegida pela metade.
        if (addPasswordProtection) {
            ensureMasterCredentialFor("PASSWORD")
        }
        val appTargets = apps
            .filter { it.packageName.isNotBlank() }
            .distinctBy { it.packageName }
        val normalizedSites = WebsiteBlocker.normalizeRules(sites)

        database.withTransaction {
            appTargets.forEach { app ->
                database.appUsageLimitDao().insert(
                    AppUsageLimit(
                        packageName = app.packageName,
                        appName = app.appName,
                        dailyLimitMinutes = dailyLimitMinutes,
                        isEnabled = true
                    )
                )
            }
            normalizedSites.forEach { rule ->
                database.websiteUsageLimitDao().insert(
                    WebsiteUsageLimit(
                        domain = rule,
                        dailyLimitMinutes = dailyLimitMinutes,
                        isEnabled = true
                    )
                )
            }

            if (addPasswordProtection) {
                val now = System.currentTimeMillis()
                val passwordSessions = database.blockSessionDao()
                    .getAllActiveSessionsStatic()
                    .filter {
                        it.sessionType == "PASSWORD" &&
                            (it.endTime == null || it.endTime > now)
                    }
                val passwordSessionIds = passwordSessions.map { it.id }
                val existingPasswordRules = WebsiteBlocker.normalizeRules(
                    getSitesForSessions(passwordSessionIds)
                )
                val existingPasswordApps = (
                    getAppsForSessions(passwordSessionIds) +
                        WebsiteBlocker.appPackageDomainsFor(existingPasswordRules).keys
                    ).toSet()
                val appsToProtect = appTargets
                    .map { it.packageName }
                    .filterNot { it in existingPasswordApps }
                val sitesToProtect = normalizedSites.filterNot {
                    isWebsiteRuleCoveredBy(it, existingPasswordRules)
                }

                if (appsToProtect.isNotEmpty() || sitesToProtect.isNotEmpty()) {
                    val session = BlockSession(
                        startTime = now,
                        isActive = true,
                        isRecurring = false,
                        blockedAppsCount = appsToProtect.size,
                        blockedWebsitesCount = sitesToProtect.size,
                        sessionType = "PASSWORD",
                        isFixed24h = true
                    )
                    val sessionId = database.blockSessionDao().insertNewSession(session).toInt()
                    appsToProtect.forEach {
                        database.sessionAppCrossRefDao().insert(
                            SessionAppCrossRef(sessionId, it)
                        )
                    }
                    sitesToProtect.forEach {
                        database.sessionWebsiteCrossRefDao().insert(
                            SessionWebsiteCrossRef(sessionId, it)
                        )
                    }
                }
            }
        }

        checkAndEnforceOrThrow()
    }

    suspend fun startPasswordSession(
        isFixed24h: Boolean,
        startHour: Int = 0,
        startMinute: Int = 0,
        endHour: Int = 0,
        endMinute: Int = 0,
        daysOfWeek: String = "",
        apps: List<String>,
        sites: List<String>
    ) = withContext(Dispatchers.IO) {
        try {
            ensureMasterCredentialFor("PASSWORD")
            val normalizedSites = WebsiteBlocker.normalizeRules(sites)
            database.withTransaction {
                val session = BlockSession(
                    startTime = System.currentTimeMillis(),
                    isActive = true,
                    isRecurring = !isFixed24h,
                    recurringStartHour = startHour,
                    recurringStartMinute = startMinute,
                    recurringEndHour = endHour,
                    recurringEndMinute = endMinute,
                    recurringDaysOfWeek = daysOfWeek,
                    blockedAppsCount = apps.distinct().size,
                    blockedWebsitesCount = normalizedSites.size,
                    sessionType = "PASSWORD",
                    isFixed24h = isFixed24h
                )
                val sessionId = database.blockSessionDao().insertNewSession(session).toInt()
                apps.distinct().forEach {
                    database.sessionAppCrossRefDao().insert(SessionAppCrossRef(sessionId, it))
                }
                normalizedSites.forEach {
                    database.sessionWebsiteCrossRefDao()
                        .insert(SessionWebsiteCrossRef(sessionId, it))
                }
            }
            checkAndEnforceOrThrow()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            FocusGuardLogger.logError("BlockingSessionManager", "Erro ao iniciar sessão", error)
            throw error
        }
    }

    suspend fun startTimeSession(
        days: Int,
        hours: Int,
        isFixed24h: Boolean,
        startHour: Int = 0,
        startMinute: Int = 0,
        endHour: Int = 0,
        endMinute: Int = 0,
        daysOfWeek: String = "",
        apps: List<String>,
        sites: List<String>
    ) = withContext(Dispatchers.IO) {
        val protectionWasAlreadyArmed = deviceOwnerManager.isBlockingProtectionArmed()
        var sessionCreated = false
        try {
            ensureMasterCredentialFor("TIME")
            val normalizedSites = WebsiteBlocker.normalizeRules(sites)
            val normalizedApps = apps.filter(String::isNotBlank).distinct()
            require(normalizedApps.isNotEmpty() || normalizedSites.isNotEmpty()) {
                "O Jejum de Dopamina exige pelo menos um app ou site"
            }
            val duration = TimeUnit.DAYS.toMillis(days.toLong()) +
                TimeUnit.HOURS.toMillis(hours.toLong())
            require(duration > 0L) { "A duração da sessão deve ser positiva" }
            ensureSimpleBlockingReady()
            database.withTransaction {
                val startMillis = System.currentTimeMillis()
                val session = BlockSession(
                    startTime = startMillis,
                    endTime = startMillis + duration,
                    isActive = true,
                    isRecurring = !isFixed24h,
                    recurringStartHour = startHour,
                    recurringStartMinute = startMinute,
                    recurringEndHour = endHour,
                    recurringEndMinute = endMinute,
                    recurringDaysOfWeek = daysOfWeek,
                    blockedAppsCount = normalizedApps.size,
                    blockedWebsitesCount = normalizedSites.size,
                    sessionType = "TIME",
                    isFixed24h = isFixed24h
                )
                val sessionId = database.blockSessionDao().insertNewSession(session).toInt()
                normalizedApps.forEach {
                    database.sessionAppCrossRefDao().insert(SessionAppCrossRef(sessionId, it))
                }
                normalizedSites.forEach {
                    database.sessionWebsiteCrossRefDao()
                        .insert(SessionWebsiteCrossRef(sessionId, it))
                }
            }
            sessionCreated = true
            checkAndEnforceOrThrow()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!sessionCreated && !protectionWasAlreadyArmed) {
                deviceOwnerManager.clearBlockingPolicies()
                deviceOwnerManager.applyNuclearShield()
            }
            FocusGuardLogger.logError(
                "BlockingSessionManager",
                "Erro ao iniciar sessão por tempo",
                error
            )
            throw error
        }
    }

    private fun ensureSimpleBlockingReady() {
        val protectionLevel = DopamineStartPolicy.protectionLevel(
            DopamineStartPolicy.Capabilities(
                accessibilityEnabled = PermissionUtils.isAccessibilityServiceEnabled(context),
                usageAccessEnabled = PermissionUtils.isUsageAccessEnabled(context),
                batteryOptimizationExempt = PermissionUtils.isBatteryOptimizationIgnored(context),
                deviceOwnerActive = deviceOwnerManager.isDeviceOwnerActive()
            )
        )
        if (protectionLevel == DopamineStartPolicy.ProtectionLevel.UNAVAILABLE) {
            throw BlockingProtectionUnavailableException(
                BlockingProtectionUnavailableException.Reason.ACCESSIBILITY_REQUIRED
            )
        }
        FocusGuardLogger.log(
            "BlockingSessionManager",
            "Jejum iniciado com proteção ${protectionLevel.name.lowercase(Locale.US)}"
        )
    }

    fun startPomodoroSession(durationMs: Long, isBlockingEnabled: Boolean = true) {
        scope.launch {
            runCatching {
                require(durationMs > 0L) { "A duração do Pomodoro deve ser positiva" }
                database.withTransaction {
                    database.blockSessionDao().deactivateActiveSessionsByType("POMODORO")
                    if (isBlockingEnabled) {
                        val startMillis = System.currentTimeMillis()
                        database.blockSessionDao().insertNewSession(
                            BlockSession(
                                startTime = startMillis,
                                endTime = startMillis + durationMs,
                                isActive = true,
                                sessionType = "POMODORO",
                                isFixed24h = true,
                                isBlockingEnabled = true
                            )
                        )
                    }
                }
                checkAndEnforce()
            }.onSuccess {
                showToast(R.string.modo_pomodoro_ativado_foco_total, Toast.LENGTH_LONG)
            }.onFailure {
                FocusGuardLogger.logError(
                    "BlockingSessionManager",
                    "Erro ao iniciar Pomodoro",
                    it
                )
            }
        }
    }

    fun endPomodoroSession() {
        scope.launch { endPomodoroSessionAndWait() }
    }

    suspend fun endPomodoroSessionAndWait(): Boolean {
        return runCatching {
            val changed = database.blockSessionDao()
                .deactivateActiveSessionsByType("POMODORO") > 0
            StrictPomodoroLock.clear(context)
            PomodoroForegroundService.stop(context)
            checkAndEnforce()
            changed
        }.onFailure {
            FocusGuardLogger.logError(
                "BlockingSessionManager",
                "Erro ao encerrar Pomodoro",
                it
            )
        }.getOrDefault(false)
    }

    suspend fun hasTimeSession(): Boolean {
        return database.blockSessionDao().getAllActiveSessionsStatic()
            .any { it.sessionType == "TIME" }
    }

    fun appendToTimeSession(
        addedDays: Int,
        addedHours: Int,
        additionalApps: List<String>,
        additionalSites: List<String>
    ) {
        scope.launch {
            runCatching {
                val session = database.blockSessionDao().getAllActiveSessionsStatic()
                    .filter { it.sessionType == "TIME" }
                    .maxByOrNull { it.startTime }
                    ?: return@runCatching
                val addedMillis = TimeUnit.DAYS.toMillis(addedDays.toLong()) +
                    TimeUnit.HOURS.toMillis(addedHours.toLong())
                require(addedMillis >= 0L) { "Extensão inválida" }
                val normalizedAdditionalSites = WebsiteBlocker.normalizeRules(additionalSites)
                database.withTransaction {
                    additionalApps.distinct().forEach {
                        database.sessionAppCrossRefDao().insert(SessionAppCrossRef(session.id, it))
                    }
                    normalizedAdditionalSites.forEach {
                        database.sessionWebsiteCrossRefDao()
                            .insert(SessionWebsiteCrossRef(session.id, it))
                    }
                    val apps = database.sessionAppCrossRefDao()
                        .getAppsForSessions(listOf(session.id))
                    val sites = database.sessionWebsiteCrossRefDao()
                        .getWebsitesForSessions(listOf(session.id))
                    database.blockSessionDao().updateBlockSession(
                        session.copy(
                            endTime = (session.endTime ?: System.currentTimeMillis()) + addedMillis,
                            blockedAppsCount = apps.distinct().size,
                            blockedWebsitesCount = sites.distinct().size
                        )
                    )
                }
                checkAndEnforce()
            }.onFailure {
                FocusGuardLogger.logError(
                    "BlockingSessionManager",
                    "Erro ao estender sessão",
                    it
                )
            }
        }
    }

    fun endPasswordSessions() {
        scope.launch {
            runCatching {
                database.blockSessionDao().deactivateActiveSessionsByType("PASSWORD")
                checkAndEnforce()
            }.onSuccess {
                showToast(R.string.bloqueios_por_senha_encerrados, Toast.LENGTH_SHORT)
            }.onFailure {
                FocusGuardLogger.logError(
                    "BlockingSessionManager",
                    "Erro ao encerrar sessões por senha",
                    it
                )
            }
        }
    }

    fun endSession(sessionId: Int) {
        scope.launch { endSessionAndWait(sessionId) }
    }

    suspend fun endSessionAndWait(sessionId: Int): EndSessionResult {
        return try {
            val session = database.blockSessionDao().getActiveSessionById(sessionId)
                ?: return EndSessionResult.NOT_FOUND
            when {
                session.sessionType == "POMODORO" -> EndSessionResult.POMODORO_NOT_REVOCABLE
                session.sessionType == "TIME" && isCurrentlyInBlockingWindow(session) ->
                    EndSessionResult.TIME_NOT_REVOCABLE
                database.blockSessionDao().deactivateSession(sessionId) == 0 ->
                    EndSessionResult.NOT_FOUND
                else -> {
                    checkAndEnforceOrThrow()
                    EndSessionResult.ENDED
                }
            }
        } catch (error: Exception) {
            FocusGuardLogger.logError(
                "BlockingSessionManager",
                "Erro ao encerrar sessão $sessionId",
                error
            )
            EndSessionResult.FAILED
        }
    }

    suspend fun findResponsibleSessionId(
        blockedPackage: String?,
        blockedDomain: String?
    ): Int? {
        val sessions = database.blockSessionDao().getAllActiveSessionsStatic()
            .filter { it.sessionType == "PASSWORD" && isCurrentlyInBlockingWindow(it) }
            .sortedByDescending { it.startTime }

        for (session in sessions) {
            val apps = if (blockedPackage.isNullOrBlank()) emptyList() else {
                database.sessionAppCrossRefDao().getAppsForSessions(listOf(session.id))
            }
            val sites = if (blockedDomain.isNullOrBlank()) emptyList() else {
                database.sessionWebsiteCrossRefDao().getWebsitesForSessions(listOf(session.id))
            }
            if (matchesBlockedTarget(blockedPackage, blockedDomain, apps, sites)) return session.id
        }

        return null
    }

    suspend fun unlockPasswordProtectedLimit(
        password: String,
        blockedPackage: String?,
        blockedDomain: String?
    ): LimitUnlockResult = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            val unlockUntil = BlockingScheduleCalculator.nextLocalMidnight(now)

            val appLimit = blockedPackage
                ?.takeIf(String::isNotBlank)
                ?.let { database.appUsageLimitDao().getLimitForPackage(it) }
                ?.takeIf { limit ->
                    limit.isEnabled &&
                        limit.lockMode.equals("PASSWORD", ignoreCase = true) &&
                        !limit.lockPasswordHash.isNullOrBlank() &&
                        WebsiteUsageLimitPolicy.isBlockingModeActive(
                            limit.lockMode,
                            limit.lockUntilTimestamp,
                            now
                        )
                }
            if (appLimit != null &&
                appLimit.packageName in getExceededAppLimits(listOf(appLimit), now)
            ) {
                if (!AuthManager.verifySerializedPassword(password, appLimit.lockPasswordHash!!)) {
                    return@withContext LimitUnlockResult.WRONG_PASSWORD
                }
                database.appUsageLimitDao().update(
                    appLimit.copy(lockUntilTimestamp = unlockUntil)
                )
                checkAndEnforceOrThrow()
                return@withContext LimitUnlockResult.UNLOCKED
            }

            val websiteLimits = database.websiteUsageLimitDao().getAllStatic()
                .filter { limit ->
                    limit.isEnabled &&
                        limit.lockMode.equals("PASSWORD", ignoreCase = true) &&
                        !limit.lockPasswordHash.isNullOrBlank() &&
                        WebsiteUsageLimitPolicy.isBlockingModeActive(
                            limit.lockMode,
                            limit.lockUntilTimestamp,
                            now
                        )
                }
            val websiteUsage = WebsiteUsageLimitPolicy.aggregateUsageByRule(
                usageByIdentifier = database.dailyUsageStatDao()
                    .getStatsForDateStatic(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now))
                    .map { it.identifier to it.timeSpentMs },
                configuredRules = websiteLimits.map { it.domain }
            )
            val blockingWebsiteLimits = websiteLimits.filter { limit ->
                WebsiteUsageLimitPolicy.shouldBlock(
                    usedMillis = websiteUsage[WebsiteBlocker.normalizeRule(limit.domain)] ?: 0L,
                    dailyLimitMinutes = limit.dailyLimitMinutes,
                    lockMode = limit.lockMode,
                    lockUntilTimestamp = limit.lockUntilTimestamp,
                    nowMillis = now
                )
            }
            val matchingRules = blockedDomain
                ?.takeIf(String::isNotBlank)
                ?.let {
                    WebsiteBlocker.findMatchingRules(
                        it,
                        WebsiteBlocker.normalizeRules(blockingWebsiteLimits.map { limit ->
                            limit.domain
                        })
                    )
                }
                .orEmpty()
            val matchingWebsiteLimits = matchingRules.mapNotNull { rule ->
                blockingWebsiteLimits.firstOrNull {
                    WebsiteBlocker.normalizeRule(it.domain) == rule
                }
            }
            if (matchingWebsiteLimits.isNotEmpty()) {
                val authenticatedLimits = matchingWebsiteLimits.filter { limit ->
                    AuthManager.verifySerializedPassword(password, limit.lockPasswordHash!!)
                }
                if (authenticatedLimits.isEmpty()) {
                    return@withContext LimitUnlockResult.WRONG_PASSWORD
                }
                authenticatedLimits.forEach { limit ->
                    database.websiteUsageLimitDao().insert(
                        limit.copy(lockUntilTimestamp = unlockUntil)
                    )
                }
                checkAndEnforceOrThrow()
                return@withContext LimitUnlockResult.UNLOCKED
            }

            LimitUnlockResult.NOT_FOUND
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            FocusGuardLogger.logError(
                "BlockingSessionManager",
                "Erro ao desbloquear limite protegido por senha",
                error
            )
            LimitUnlockResult.FAILED
        }
    }

    suspend fun checkAndEnforce() {
        try {
            checkAndEnforceOrThrow()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            FocusGuardLogger.logError(
                "BlockingSessionManager",
                "Erro ao reconciliar bloqueios",
                error
            )
        }
    }

    private suspend fun checkAndEnforceOrThrow() {
        enforcementMutex.withLock {
                val now = System.currentTimeMillis()
                val beforeExpiration = database.blockSessionDao().getAllActiveSessionsStatic()
                val expiredPomodoro = beforeExpiration.any {
                    it.sessionType == "POMODORO" && it.endTime != null && it.endTime <= now
                }
                database.blockSessionDao().deactivateExpiredSessions(now)
                if (expiredPomodoro) {
                    StrictPomodoroLock.clear(context)
                    PomodoroForegroundService.stop(context)
                }

                val activeSessions = database.blockSessionDao().getAllActiveSessionsStatic()
                val enforcingSessions = activeSessions.filter {
                    participatesInBlocking(it) && isCurrentlyInBlockingWindow(it)
                }
                val enforcingIds = enforcingSessions.map { it.id }
                val strictPomodoro = enforcingSessions.any {
                    it.sessionType == "POMODORO" && it.isBlockingEnabled
                }

                setDoNotDisturbMode(strictPomodoro)

                val sessionApps = if (strictPomodoro) {
                    getInstalledUserAppsExceptPhone()
                } else {
                    getAppsForSessions(enforcingIds)
                }
                val sessionSites = getSitesForSessions(enforcingIds)

                val activeAppLimits = database.appUsageLimitDao().getAllActiveLimitsStatic()
                val limitApps = getExceededAppLimits(activeAppLimits, now)

                val activeWebsiteLimits = database.websiteUsageLimitDao().getAllStatic()
                    .filter { it.isEnabled }
                val adultFilterEnabled = AuthManager.isAdultFilterConfigured(context)
                val policyExpirations = (
                    activeAppLimits.mapNotNull { limit ->
                        if (limit.lockMode.equals("TIME", ignoreCase = true)) {
                            limit.lockUntilTimestamp?.takeIf { it > now }
                        } else null
                    } + activeWebsiteLimits.mapNotNull { limit ->
                        if (limit.lockMode.equals("TIME", ignoreCase = true)) {
                            limit.lockUntilTimestamp?.takeIf { it > now }
                        } else null
                    }
                )
                val nextDailyReset = if (
                    activeAppLimits.isNotEmpty() || activeWebsiteLimits.isNotEmpty()
                ) {
                    BlockingScheduleCalculator.nextLocalMidnight(now)
                } else null
                val nextReconciliation = if (
                    activeSessions.isNotEmpty() ||
                    activeAppLimits.isNotEmpty() ||
                    activeWebsiteLimits.isNotEmpty() ||
                    adultFilterEnabled
                ) {
                    now + POLICY_RECONCILIATION_INTERVAL_MILLIS
                } else null
                BlockingScheduleReceiver.scheduleNext(
                    context = context,
                    sessions = activeSessions,
                    additionalBoundaries = policyExpirations +
                        listOfNotNull(nextDailyReset, nextReconciliation),
                    nowMillis = now
                )
                val activeWebsiteDomains = WebsiteBlocker.normalizeRules(
                    activeWebsiteLimits.map { it.domain }
                )
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
                val usageByWebsite = WebsiteUsageLimitPolicy.aggregateUsageByRule(
                    usageByIdentifier = database.dailyUsageStatDao()
                        .getStatsForDateStatic(today)
                        .map { it.identifier to it.timeSpentMs },
                    configuredRules = activeWebsiteDomains
                )
                val limitSites = activeWebsiteLimits.filter { limit ->
                    val normalizedDomain = WebsiteBlocker.normalizeRule(limit.domain)
                    WebsiteUsageLimitPolicy.shouldBlock(
                        usedMillis = usageByWebsite[normalizedDomain] ?: 0L,
                        dailyLimitMinutes = limit.dailyLimitMinutes,
                        lockMode = limit.lockMode,
                        lockUntilTimestamp = limit.lockUntilTimestamp,
                        nowMillis = now
                    )
                }.map { WebsiteBlocker.normalizeRule(it.domain) }

                val appFamilySites = WebsiteBlocker.domainRulesForAppPackages(
                    sessionApps + limitApps
                )
                val sitesToBlock = (sessionSites + limitSites + appFamilySites)
                    .map(WebsiteBlocker::normalizeRule)
                    .filter { it.isNotBlank() }
                    .distinct()
                val pornographyCategoryActive =
                    WebsiteBlocker.containsPornographyRule(sitesToBlock)
                deviceOwnerManager.setPornographyCategoryActive(pornographyCategoryActive)
                val websiteAppsToBlock = WebsiteBlocker.appPackageDomainsFor(sitesToBlock)
                    .keys
                    .filter(::isPackageInstalled)
                val appsToBlock = (sessionApps + limitApps + websiteAppsToBlock)
                    .filter { it.isNotBlank() }
                    .distinct()

                val allSessionApps = getAppsForSessions(activeSessions.map { it.id })
                val allSessionSites = getSitesForSessions(activeSessions.map { it.id })
                val allKnownWebsiteApps = WebsiteBlocker.appPackageDomainsFor(
                    allSessionSites + activeWebsiteLimits.map { it.domain }
                ).keys.filter(::isPackageInstalled)
                val allKnownApps = (
                    allSessionApps +
                        activeAppLimits.map { it.packageName } +
                        allKnownWebsiteApps
                ).distinct()

                deviceOwnerManager.syncSuspendedApps(
                    allAppsInSessions = allKnownApps,
                    appsToBlockNow = appsToBlock,
                    allowedSystemApps = appsToBlock.toSet()
                )

                if (sitesToBlock.isEmpty() && !adultFilterEnabled) {
                    deviceOwnerManager.clearWebsiteRestrictions()
                } else {
                    deviceOwnerManager.enforceWebsiteRestrictions(sitesToBlock)
                }

                val selfProtectionRequired = shouldArmSelfProtection(
                    hasEnforcingSessions = enforcingSessions.isNotEmpty(),
                    hasBlockedApps = appsToBlock.isNotEmpty(),
                    hasBlockedSites = sitesToBlock.isNotEmpty(),
                    adultFilterEnabled = adultFilterEnabled
                )
                if (selfProtectionRequired) {
                    deviceOwnerManager.enforceBlockingPolicies()
                } else {
                    deviceOwnerManager.clearBlockingPolicies()
                }
                deviceOwnerManager.applyNuclearShield()

                context.sendBroadcast(
                    BlockingAccessibilityService.createRefreshBlockingIntent(
                        context = context,
                        blockedApps = appsToBlock,
                        blockedSites = sitesToBlock,
                        blockingActive = selfProtectionRequired,
                        strictPomodoro = strictPomodoro
                    )
                )
        }
    }

    fun isCurrentlyInBlockingWindow(session: BlockSession?): Boolean {
        if (session == null || !session.isActive) return false
        val now = Calendar.getInstance()
        if (session.endTime != null && now.timeInMillis >= session.endTime) return false
        if (session.isFixed24h) return true

        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val startMinutes = session.recurringStartHour * 60 + session.recurringStartMinute
        val endMinutes = session.recurringEndHour * 60 + session.recurringEndMinute
        val overnight = startMinutes > endMinutes
        val afterMidnight = overnight && currentMinutes < endMinutes
        val logicalDay = now.clone() as Calendar
        if (afterMidnight) logicalDay.add(Calendar.DAY_OF_YEAR, -1)

        if (session.recurringDaysOfWeek.isNotBlank()) {
            val allowedDays = session.recurringDaysOfWeek.split(',')
                .map(String::trim)
                .filter(String::isNotBlank)
                .toSet()
            if (logicalDay.get(Calendar.DAY_OF_WEEK).toString() !in allowedDays) return false
        }

        return if (startMinutes <= endMinutes) {
            currentMinutes in startMinutes until endMinutes
        } else {
            currentMinutes >= startMinutes || currentMinutes < endMinutes
        }
    }

    private fun getExceededAppLimits(
        limits: List<com.focusguard.database.AppUsageLimit>,
        now: Long
    ): List<String> {
        if (limits.isEmpty()) return emptyList()
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as?
            UsageStatsManager
        if (usageStatsManager == null) return emptyList()
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val usage = usageStatsManager.queryAndAggregateUsageStats(startOfDay, now)

        return limits.filter { limit ->
            val usedMinutes = UsageLimitForegroundPolicy.usedMinutes(
                usage[limit.packageName]?.totalTimeInForeground ?: 0L
            )
            usedMinutes >= limit.dailyLimitMinutes &&
                limit.preventOpeningAfterLimit &&
                WebsiteUsageLimitPolicy.isBlockingModeActive(
                    limit.lockMode,
                    limit.lockUntilTimestamp,
                    now
                )
        }.map { it.packageName }
    }

    private fun getInstalledUserAppsExceptPhone(): List<String> {
        val phoneWhitelist = setOf(
            "com.android.dialer",
            "com.google.android.dialer",
            "com.android.phone",
            "com.android.server.telecom",
            "com.samsung.android.dialer",
            "com.samsung.android.incallui"
        )
        return try {
            context.packageManager
                .getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { app ->
                    app.packageName != context.packageName &&
                        app.packageName !in phoneWhitelist &&
                        app.flags and ApplicationInfo.FLAG_SYSTEM == 0
                }
                .map { it.packageName }
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError(
                "BlockingSessionManager",
                "Falha ao listar aplicativos instalados",
                error
            )
            emptyList()
        }
    }

    @Suppress("DEPRECATION")
    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0L)
                )
            } else {
                context.packageManager.getApplicationInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError(
                "BlockingSessionManager",
                "Falha ao verificar pacote associado a site",
                error
            )
            false
        }
    }

    private suspend fun getAppsForSessions(ids: List<Int>): List<String> {
        return if (ids.isEmpty()) emptyList()
        else database.sessionAppCrossRefDao().getAppsForSessions(ids)
    }

    private suspend fun getSitesForSessions(ids: List<Int>): List<String> {
        return if (ids.isEmpty()) emptyList()
        else database.sessionWebsiteCrossRefDao().getWebsitesForSessions(ids)
    }

    private fun setDoNotDisturbMode(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
            if (manager.isNotificationPolicyAccessGranted) {
                if (enabled) {
                    if (!statePreferences.contains(PREVIOUS_DND_FILTER_KEY)) {
                        statePreferences.edit()
                            .putInt(PREVIOUS_DND_FILTER_KEY, manager.currentInterruptionFilter)
                            .apply()
                    }
                    manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                } else if (statePreferences.contains(PREVIOUS_DND_FILTER_KEY)) {
                    val savedFilter = statePreferences.getInt(
                        PREVIOUS_DND_FILTER_KEY,
                        NotificationManager.INTERRUPTION_FILTER_ALL
                    )
                    val previousFilter = savedFilter.takeIf {
                        it == NotificationManager.INTERRUPTION_FILTER_ALL ||
                            it == NotificationManager.INTERRUPTION_FILTER_PRIORITY ||
                            it == NotificationManager.INTERRUPTION_FILTER_NONE ||
                            it == NotificationManager.INTERRUPTION_FILTER_ALARMS
                    } ?: NotificationManager.INTERRUPTION_FILTER_ALL
                    manager.setInterruptionFilter(previousFilter)
                    statePreferences.edit().remove(PREVIOUS_DND_FILTER_KEY).apply()
                }
            }
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError(
                "BlockingSessionManager",
                "Erro ao alterar Não Perturbe",
                error
            )
        }
    }

    private suspend fun showToast(resourceId: Int, duration: Int) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, context.getString(resourceId), duration).show()
        }
    }
}
