package com.focusguard.manager

import android.content.Context
import androidx.room.withTransaction
import com.focusguard.database.AppDatabase
import com.focusguard.database.SessionAppCrossRef
import com.focusguard.database.SessionWebsiteCrossRef
import com.focusguard.domain.model.BlockSessionType
import com.focusguard.domain.model.UsageLimitLockMode
import com.focusguard.domain.port.BlockingRuntimePort
import com.focusguard.domain.port.BlockingUserMessage
import com.focusguard.scheduling.BlockingScheduleCalculator
import com.focusguard.security.BiometricAppUnlockPolicy
import com.focusguard.security.DeactivationCredentialManager
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.utils.WebsiteBlocker
import com.focusguard.utils.WebsiteUsageLimitPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
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

/** Owns completion, extension, and credential-authorized release of blocks. */
@Singleton
class BlockingSessionMutationService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deactivationCredentialManager: DeactivationCredentialManager,
    private val database: AppDatabase,
    private val blockingRuntime: BlockingRuntimePort,
    private val reconciler: BlockingPolicyReconciler
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun endPomodoroSession() {
        scope.launch { endPomodoroSessionAndWait() }
    }

    suspend fun endPomodoroSessionAndWait(): Boolean {
        return runCatching {
            val changed = database.blockSessionDao()
                .deactivateActiveSessionsByType(BlockSessionType.POMODORO) > 0
            StrictPomodoroLock.clear(context)
            blockingRuntime.stopPomodoroForeground()
            reconcileSafely()
            changed
        }.onFailure {
            FocusGuardLogger.logError(TAG, "Erro ao encerrar Pomodoro", it)
        }.getOrDefault(false)
    }

    suspend fun hasTimeSession(): Boolean =
        database.blockSessionDao().getAllActiveSessionsStatic()
            .any { it.sessionType == BlockSessionType.TIME }

    fun appendToTimeSession(
        addedDays: Int,
        addedHours: Int,
        additionalApps: List<String>,
        additionalSites: List<String>
    ) {
        scope.launch {
            runCatching {
                val session = database.blockSessionDao().getAllActiveSessionsStatic()
                    .filter { it.sessionType == BlockSessionType.TIME }
                    .maxByOrNull { it.startTime }
                    ?: return@runCatching
                val addedMillis = TimeUnit.DAYS.toMillis(addedDays.toLong()) +
                    TimeUnit.HOURS.toMillis(addedHours.toLong())
                require(addedMillis >= 0L) { "Extensão inválida" }
                val normalizedAdditionalSites = WebsiteBlocker.normalizeRules(additionalSites)
                database.withTransaction {
                    additionalApps.distinct().forEach {
                        database.sessionAppCrossRefDao().insert(
                            SessionAppCrossRef(session.id, it)
                        )
                    }
                    normalizedAdditionalSites.forEach {
                        database.sessionWebsiteCrossRefDao().insert(
                            SessionWebsiteCrossRef(session.id, it)
                        )
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
                reconcileSafely()
            }.onFailure {
                FocusGuardLogger.logError(TAG, "Erro ao estender sessão", it)
            }
        }
    }

    fun endPasswordSessions() {
        scope.launch {
            runCatching {
                database.blockSessionDao()
                    .deactivateActiveSessionsByType(BlockSessionType.PASSWORD)
                reconcileSafely()
            }.onSuccess {
                blockingRuntime.showUserMessage(BlockingUserMessage.PASSWORD_SESSIONS_ENDED)
            }.onFailure {
                FocusGuardLogger.logError(TAG, "Erro ao encerrar sessões por senha", it)
            }
        }
    }

    fun endSession(sessionId: Int) {
        scope.launch { endSessionAndWait(sessionId) }
    }

    suspend fun endSessionAndWait(
        sessionId: Int
    ): BlockingSessionManager.EndSessionResult {
        return try {
            val session = database.blockSessionDao().getActiveSessionById(sessionId)
                ?: return BlockingSessionManager.EndSessionResult.NOT_FOUND
            when {
                session.sessionType == BlockSessionType.POMODORO ->
                    BlockingSessionManager.EndSessionResult.POMODORO_NOT_REVOCABLE
                session.sessionType == BlockSessionType.TIME &&
                    reconciler.isCurrentlyInBlockingWindow(session) ->
                    BlockingSessionManager.EndSessionResult.TIME_NOT_REVOCABLE
                database.blockSessionDao().deactivateSession(sessionId) == 0 ->
                    BlockingSessionManager.EndSessionResult.NOT_FOUND
                else -> {
                    reconciler.reconcile()
                    BlockingSessionManager.EndSessionResult.ENDED
                }
            }
        } catch (error: Exception) {
            FocusGuardLogger.logError(TAG, "Erro ao encerrar sessão $sessionId", error)
            BlockingSessionManager.EndSessionResult.FAILED
        }
    }

    suspend fun findResponsibleSessionId(
        blockedPackage: String?,
        blockedDomain: String?
    ): Int? {
        val sessions = database.blockSessionDao().getAllActiveSessionsStatic()
            .filter {
                it.sessionType == BlockSessionType.PASSWORD &&
                    reconciler.isCurrentlyInBlockingWindow(it)
            }
            .sortedByDescending { it.startTime }

        for (session in sessions) {
            val apps = if (blockedPackage.isNullOrBlank()) {
                emptyList()
            } else {
                database.sessionAppCrossRefDao().getAppsForSessions(listOf(session.id))
            }
            val sites = if (blockedDomain.isNullOrBlank()) {
                emptyList()
            } else {
                database.sessionWebsiteCrossRefDao().getWebsitesForSessions(listOf(session.id))
            }
            if (
                BlockingSessionManager.matchesBlockedTarget(
                    blockedPackage,
                    blockedDomain,
                    apps,
                    sites
                )
            ) {
                return session.id
            }
        }

        return null
    }

    suspend fun credentialUnlockOrigin(
        blockedPackage: String?,
        blockedDomain: String?,
        strictPomodoroActive: Boolean
    ): BiometricAppUnlockPolicy.BlockOrigin? {
        if (strictPomodoroActive) {
            return BiometricAppUnlockPolicy.BlockOrigin.STRICT_POMODORO
        }
        if (hasCredentialUnlockableLimit(blockedPackage, blockedDomain)) {
            return BiometricAppUnlockPolicy.BlockOrigin.USAGE_LIMIT_PASSWORD_UNLOCK
        }
        if (findResponsibleSessionId(blockedPackage, blockedDomain) != null) {
            return BiometricAppUnlockPolicy.BlockOrigin.PASSWORD_SESSION
        }
        return null
    }

    suspend fun unlockPasswordProtectedLimit(
        password: String,
        blockedPackage: String?,
        blockedDomain: String?
    ): BlockingSessionManager.LimitUnlockResult = unlockCredentialProtectedLimit(
        blockedPackage = blockedPackage,
        blockedDomain = blockedDomain
    ) {
        when (deactivationCredentialManager.verify(password)) {
            DeactivationCredentialManager.VerificationResult.PASSWORD_ACCEPTED,
            DeactivationCredentialManager.VerificationResult.RECOVERY_ACCEPTED -> true
            DeactivationCredentialManager.VerificationResult.REJECTED,
            DeactivationCredentialManager.VerificationResult.NOT_CONFIGURED -> false
        }
    }

    suspend fun unlockLimitWithVerifiedIdentity(
        blockedPackage: String?,
        blockedDomain: String?
    ): BlockingSessionManager.LimitUnlockResult = unlockCredentialProtectedLimit(
        blockedPackage = blockedPackage,
        blockedDomain = blockedDomain
    ) { true }

    private suspend fun hasCredentialUnlockableLimit(
        blockedPackage: String?,
        blockedDomain: String?
    ): Boolean = unlockCredentialProtectedLimit(
        blockedPackage = blockedPackage,
        blockedDomain = blockedDomain
    ) { false } == BlockingSessionManager.LimitUnlockResult.WRONG_PASSWORD

    private suspend fun unlockCredentialProtectedLimit(
        blockedPackage: String?,
        blockedDomain: String?,
        verifyMasterCredential: () -> Boolean
    ): BlockingSessionManager.LimitUnlockResult = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            val unlockUntil = BlockingScheduleCalculator.nextLocalMidnight(now)

            val appLimit = blockedPackage
                ?.takeIf(String::isNotBlank)
                ?.let { database.appUsageLimitDao().getLimitForPackage(it) }
                ?.takeIf { limit ->
                    limit.isEnabled &&
                        limit.lockMode == UsageLimitLockMode.PASSWORD &&
                        WebsiteUsageLimitPolicy.isBlockingModeActive(
                            limit.lockMode,
                            limit.lockUntilTimestamp,
                            now
                        )
                }
            if (
                appLimit != null &&
                appLimit.packageName in reconciler.getExceededAppLimits(listOf(appLimit), now)
            ) {
                if (!verifyMasterCredential()) {
                    return@withContext BlockingSessionManager.LimitUnlockResult.WRONG_PASSWORD
                }
                database.appUsageLimitDao().update(
                    appLimit.copy(lockUntilTimestamp = unlockUntil)
                )
                reconciler.reconcile()
                return@withContext BlockingSessionManager.LimitUnlockResult.UNLOCKED
            }

            val websiteLimits = database.websiteUsageLimitDao().getAllStatic()
                .filter { limit ->
                    limit.isEnabled &&
                        limit.lockMode == UsageLimitLockMode.PASSWORD &&
                        WebsiteUsageLimitPolicy.isBlockingModeActive(
                            limit.lockMode,
                            limit.lockUntilTimestamp,
                            now
                        )
                }
            val websiteUsage = WebsiteUsageLimitPolicy.aggregateUsageByRule(
                usageByIdentifier = database.dailyUsageStatDao()
                    .getStatsForDateStatic(
                        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
                    )
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
                        WebsiteBlocker.normalizeRules(
                            blockingWebsiteLimits.map { limit -> limit.domain }
                        )
                    )
                }
                .orEmpty()
            val matchingWebsiteLimits = matchingRules.mapNotNull { rule ->
                blockingWebsiteLimits.firstOrNull {
                    WebsiteBlocker.normalizeRule(it.domain) == rule
                }
            }
            if (matchingWebsiteLimits.isNotEmpty()) {
                if (!verifyMasterCredential()) {
                    return@withContext BlockingSessionManager.LimitUnlockResult.WRONG_PASSWORD
                }
                matchingWebsiteLimits.forEach { limit ->
                    database.websiteUsageLimitDao().insert(
                        limit.copy(lockUntilTimestamp = unlockUntil)
                    )
                }
                reconciler.reconcile()
                return@withContext BlockingSessionManager.LimitUnlockResult.UNLOCKED
            }

            BlockingSessionManager.LimitUnlockResult.NOT_FOUND
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            FocusGuardLogger.logError(
                TAG,
                "Erro ao desbloquear limite protegido por credencial",
                error
            )
            BlockingSessionManager.LimitUnlockResult.FAILED
        }
    }

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
        const val TAG = "BlockingSessionMutation"
    }
}
