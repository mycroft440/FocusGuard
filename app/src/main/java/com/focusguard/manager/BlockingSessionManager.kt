package com.focusguard.manager

import com.focusguard.database.AppDatabase
import com.focusguard.database.BlockSession
import com.focusguard.domain.model.BlockSessionType
import com.focusguard.domain.port.BlockingEnforcementPort
import com.focusguard.security.BiometricAppUnlockPolicy
import com.focusguard.security.MasterCredentialPolicy
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.utils.WebsiteBlocker
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Stable façade for blocking use cases.
 *
 * Queries, creation, mutation, and Android policy reconciliation live in focused
 * collaborators so callers do not depend on those implementation details.
 */
@Singleton
class BlockingSessionManager @Inject constructor(
    private val database: AppDatabase,
    private val queries: BlockingSessionQueryService,
    private val creation: BlockingSessionCreationService,
    private val mutation: BlockingSessionMutationService,
    private val reconciler: BlockingPolicyReconciler
) : BlockingEnforcementPort {

    class BlockingProtectionUnavailableException(
        val reason: Reason
    ) : IllegalStateException(reason.name) {
        enum class Reason {
            PROTECTION_PERMISSIONS_REQUIRED,
            MASTER_CREDENTIAL_REQUIRED
        }
    }

    data class BlockOverview(
        val passwordEntries: List<Entry> = emptyList(),
        val dailyLimitEntries: List<Entry> = emptyList(),
        val dopamineFastEntries: List<Entry> = emptyList()
    ) {
        val isEmpty: Boolean
            get() = passwordEntries.isEmpty() &&
                dailyLimitEntries.isEmpty() &&
                dopamineFastEntries.isEmpty()

        data class Entry(
            val identifier: String,
            val isWebsite: Boolean,
            val dailyLimitMinutes: Int? = null,
            val unlockAtMillis: Long? = null
        )
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
            get() = passwordAppPackageNames + limitedAppPackageNames +
                exclusiveAppPackageNames

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

    val activeSessionsFlow: Flow<List<BlockSession>> =
        database.blockSessionDao().getAllActiveSessions()

    val isBlockingActiveFlow: Flow<Boolean> = activeSessionsFlow.map { sessions ->
        sessions.any {
            participatesInBlocking(it) && reconciler.isCurrentlyInBlockingWindow(it)
        }
    }

    val isUninstallBlockedByTimeFlow: Flow<Boolean> = combine(
        activeSessionsFlow,
        database.appUsageLimitDao().getAll(),
        database.websiteUsageLimitDao().getAll()
    ) { sessions, appLimits, websiteLimits ->
        val now = System.currentTimeMillis()
        sessions.any { session ->
            MasterCredentialPolicy.blocksUninstall(session.sessionType) &&
                participatesInBlocking(session) &&
                reconciler.isCurrentlyInBlockingWindow(session)
        } || appLimits.any { limit ->
            limit.isEnabled && MasterCredentialPolicy.isTimeHardened(
                lockMode = limit.lockMode,
                lockUntilTimestamp = limit.lockUntilTimestamp,
                nowMillis = now
            )
        } || websiteLimits.any { limit ->
            limit.isEnabled && MasterCredentialPolicy.isTimeHardened(
                lockMode = limit.lockMode,
                lockUntilTimestamp = limit.lockUntilTimestamp,
                nowMillis = now
            )
        }
    }

    val hasRegisteredSessionFlow: Flow<Boolean> = activeSessionsFlow.map { it.isNotEmpty() }

    val sessionDetailsFlow: Flow<String> = activeSessionsFlow.map { sessions ->
        if (sessions.isEmpty()) return@map "Nenhuma sessão ativa"
        val formatter = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        buildString {
            appendLine("=== Sessões Ativas (${sessions.size}) ===")
            sessions.forEachIndexed { index, session ->
                appendLine("Sessão #${index + 1} (${session.sessionType.name})")
                appendLine(if (session.isFixed24h) "Modo: FIXO 24H" else "Modo: AGENDADO")
                session.endTime?.let { appendLine("Término: ${formatter.format(it)}") }
                appendLine("---")
            }
        }
    }

    suspend fun getBlockOverview(): BlockOverview = queries.getBlockOverview()

    suspend fun getConfiguredBlockedTargets(): ConfiguredBlockedTargets =
        queries.getConfiguredBlockedTargets()

    suspend fun configureDailyLimits(
        apps: List<DailyLimitAppTarget>,
        sites: List<String>,
        dailyLimitMinutes: Int,
        addPasswordProtection: Boolean
    ) = creation.configureDailyLimits(
        apps,
        sites,
        dailyLimitMinutes,
        addPasswordProtection
    )

    suspend fun startPasswordSession(
        isFixed24h: Boolean,
        startHour: Int = 0,
        startMinute: Int = 0,
        endHour: Int = 0,
        endMinute: Int = 0,
        daysOfWeek: String = "",
        apps: List<String>,
        sites: List<String>
    ) = creation.startPasswordSession(
        isFixed24h,
        startHour,
        startMinute,
        endHour,
        endMinute,
        daysOfWeek,
        apps,
        sites
    )

    suspend fun startTimeSession(
        days: Int,
        hours: Int,
        isFixed24h: Boolean,
        openEnded: Boolean = false,
        startHour: Int = 0,
        startMinute: Int = 0,
        endHour: Int = 0,
        endMinute: Int = 0,
        daysOfWeek: String = "",
        apps: List<String>,
        sites: List<String>
    ) = creation.startTimeSession(
        days,
        hours,
        isFixed24h,
        openEnded,
        startHour,
        startMinute,
        endHour,
        endMinute,
        daysOfWeek,
        apps,
        sites
    )

    suspend fun startRecoveryProtectionPreset(typedConsent: String) =
        creation.startRecoveryProtectionPreset(typedConsent)

    override fun startPomodoroSession(durationMs: Long, isBlockingEnabled: Boolean) {
        creation.startPomodoroSession(durationMs, isBlockingEnabled)
    }

    fun endPomodoroSession() = mutation.endPomodoroSession()

    suspend fun endPomodoroSessionAndWait(): Boolean =
        mutation.endPomodoroSessionAndWait()

    suspend fun hasTimeSession(): Boolean = mutation.hasTimeSession()

    fun appendToTimeSession(
        addedDays: Int,
        addedHours: Int,
        additionalApps: List<String>,
        additionalSites: List<String>
    ) = mutation.appendToTimeSession(
        addedDays,
        addedHours,
        additionalApps,
        additionalSites
    )

    fun endPasswordSessions() = mutation.endPasswordSessions()

    fun endSession(sessionId: Int) = mutation.endSession(sessionId)

    suspend fun endSessionAndWait(sessionId: Int): EndSessionResult =
        mutation.endSessionAndWait(sessionId)

    suspend fun findResponsibleSessionId(
        blockedPackage: String?,
        blockedDomain: String?
    ): Int? = mutation.findResponsibleSessionId(blockedPackage, blockedDomain)

    suspend fun credentialUnlockOrigin(
        blockedPackage: String?,
        blockedDomain: String?,
        strictPomodoroActive: Boolean
    ): BiometricAppUnlockPolicy.BlockOrigin? = mutation.credentialUnlockOrigin(
        blockedPackage,
        blockedDomain,
        strictPomodoroActive
    )

    suspend fun unlockPasswordProtectedLimit(
        password: String,
        blockedPackage: String?,
        blockedDomain: String?
    ): LimitUnlockResult = mutation.unlockPasswordProtectedLimit(
        password,
        blockedPackage,
        blockedDomain
    )

    suspend fun unlockLimitWithVerifiedIdentity(
        blockedPackage: String?,
        blockedDomain: String?
    ): LimitUnlockResult = mutation.unlockLimitWithVerifiedIdentity(
        blockedPackage,
        blockedDomain
    )

    suspend fun checkAndEnforce() {
        try {
            reconciler.reconcile()
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

    override suspend fun checkAndEnforceStrict() {
        reconciler.reconcile()
    }

    fun isCurrentlyInBlockingWindow(session: BlockSession?): Boolean =
        reconciler.isCurrentlyInBlockingWindow(session)

    companion object {
        internal fun combineConfiguredBlockedTargets(
            passwordSessionAppPackages: Collection<String>,
            passwordSessionWebsiteRules: Collection<String>,
            exclusiveSessionAppPackages: Collection<String>,
            exclusiveSessionWebsiteRules: Collection<String>,
            limitedAppPackages: Collection<String>,
            limitedWebsiteRules: Collection<String>
        ): ConfiguredBlockedTargets {
            val passwordWebsiteRules =
                WebsiteBlocker.normalizeRules(passwordSessionWebsiteRules)
            val normalizedLimitedWebsiteRules =
                WebsiteBlocker.normalizeRules(limitedWebsiteRules)
            val exclusiveWebsiteRules =
                WebsiteBlocker.normalizeRules(exclusiveSessionWebsiteRules)
            val passwordAppPackageNames =
                normalizeConfiguredAppPackages(passwordSessionAppPackages)
            val normalizedLimitedAppPackages =
                normalizeConfiguredAppPackages(limitedAppPackages)
            val exclusiveAppPackageNames =
                normalizeConfiguredAppPackages(exclusiveSessionAppPackages)
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
                    passwordAppPackageNames.intersect(normalizedLimitedAppPackages)
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
            packages: Collection<String>
        ): Set<String> = packages.filter(String::isNotBlank).toSet()

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

        internal fun participatesInBlocking(session: BlockSession): Boolean =
            session.sessionType != BlockSessionType.POMODORO || session.isBlockingEnabled

        internal fun matchesBlockedTarget(
            blockedPackage: String?,
            blockedDomain: String?,
            sessionApps: Collection<String>,
            sessionSites: Collection<String>
        ): Boolean =
            (!blockedPackage.isNullOrBlank() && blockedPackage in sessionApps) ||
                (!blockedDomain.isNullOrBlank() &&
                    WebsiteBlocker.isUrlBlocked(blockedDomain, sessionSites))

        internal fun shouldArmSelfProtection(
            hasEnforcingSessions: Boolean,
            hasBlockedApps: Boolean,
            hasBlockedSites: Boolean,
            adultFilterEnabled: Boolean,
            focusModeActive: Boolean = false
        ): Boolean = hasEnforcingSessions ||
            hasBlockedApps ||
            hasBlockedSites ||
            adultFilterEnabled ||
            focusModeActive
    }
}
