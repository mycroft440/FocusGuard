from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)


def write(path: str, content: str) -> None:
    Path(path).write_text(content)


# -----------------------------------------------------------------------------
# MasterCredentialPolicy: a TIME commitment remains irreversible for its whole
# lifetime, even outside a recurring daily blocking window.
# -----------------------------------------------------------------------------
p = Path("app/src/main/java/com/focusguard/security/MasterCredentialPolicy.kt")
s = p.read_text()
old = '''    fun isIrreversibleSessionType(sessionType: String): Boolean {
        return when (sessionType.uppercase()) {
            SESSION_TYPE_TIME, SESSION_TYPE_POMODORO -> true
            else -> false
        }
    }

    /**
     * Only the explicit passwordless time block prevents uninstall. A PASSWORD
'''
new = '''    fun isIrreversibleSessionType(sessionType: String): Boolean {
        return when (sessionType.uppercase()) {
            SESSION_TYPE_TIME, SESSION_TYPE_POMODORO -> true
            else -> false
        }
    }

    /**
     * A TIME commitment stays armed from creation until its absolute end, even
     * when a recurring schedule is currently outside its daily blocking window.
     * The schedule decides when targets are inaccessible; it must never become an
     * escape hatch for deleting the session or uninstalling FocusGuard.
     */
    fun isTimeCommitmentActive(
        sessionType: String,
        isActive: Boolean,
        endTime: Long?,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (!isActive || !sessionType.equals(SESSION_TYPE_TIME, ignoreCase = true)) {
            return false
        }
        return endTime == null || endTime > nowMillis
    }

    /**
     * Only the explicit passwordless time block prevents uninstall. A PASSWORD
'''
s = replace_once(s, old, new, "MasterCredentialPolicy time commitment")
p.write_text(s)


# -----------------------------------------------------------------------------
# BlockingSessionManager: live password state, target-specific password unlock,
# and lifetime hardening for TIME commitments.
# -----------------------------------------------------------------------------
p = Path("app/src/main/java/com/focusguard/manager/BlockingSessionManager.kt")
s = p.read_text()
s = replace_once(
    s,
    "import com.focusguard.security.ProtectionPermissionGate\n",
    "import com.focusguard.security.ProtectionPermissionGate\nimport com.focusguard.security.PasswordAppUnlockStore\n",
    "BlockingSessionManager PasswordAppUnlockStore import"
)
old = '''        sessions.any { session ->
            MasterCredentialPolicy.blocksUninstall(session.sessionType) &&
                participatesInBlocking(session) &&
                isCurrentlyInBlockingWindow(session)
        } || appLimits.any { limit ->
'''
new = '''        sessions.any { session ->
            MasterCredentialPolicy.blocksUninstall(session.sessionType) &&
                MasterCredentialPolicy.isTimeCommitmentActive(
                    sessionType = session.sessionType,
                    isActive = session.isActive,
                    endTime = session.endTime,
                    nowMillis = now
                )
        } || appLimits.any { limit ->
'''
s = replace_once(s, old, new, "uninstall TIME commitment")

marker = '''    suspend fun hasTimeSession(): Boolean {
        return database.blockSessionDao().getAllActiveSessionsStatic()
            .any { it.sessionType == "TIME" }
    }
'''
addition = '''    /** True only while a password-backed rule is actually blocking now. */
    suspend fun hasPasswordProtectionBlockingNow(): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val passwordSessionActive = database.blockSessionDao()
            .getAllActiveSessionsStatic()
            .any { session ->
                session.sessionType.equals("PASSWORD", ignoreCase = true) &&
                    isCurrentlyInBlockingWindow(session)
            }
        if (passwordSessionActive) return@withContext true

        val passwordAppLimits = database.appUsageLimitDao()
            .getAllActiveLimitsStatic()
            .filter { it.lockMode.equals("PASSWORD", ignoreCase = true) }
        if (getExceededAppLimits(passwordAppLimits, now).isNotEmpty()) {
            return@withContext true
        }

        val passwordWebsiteLimits = database.websiteUsageLimitDao()
            .getAllStatic()
            .filter {
                it.isEnabled && it.lockMode.equals("PASSWORD", ignoreCase = true)
            }
        if (passwordWebsiteLimits.isEmpty()) return@withContext false

        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
        val usageByWebsite = WebsiteUsageLimitPolicy.aggregateUsageByRule(
            usageByIdentifier = database.dailyUsageStatDao()
                .getStatsForDateStatic(today)
                .map { it.identifier to it.timeSpentMs },
            configuredRules = passwordWebsiteLimits.map { it.domain }
        )
        passwordWebsiteLimits.any { limit ->
            WebsiteUsageLimitPolicy.shouldBlock(
                usedMillis = usageByWebsite[WebsiteBlocker.normalizeRule(limit.domain)] ?: 0L,
                dailyLimitMinutes = limit.dailyLimitMinutes,
                lockMode = limit.lockMode,
                lockUntilTimestamp = limit.lockUntilTimestamp,
                nowMillis = now
            )
        }
    }

''' + marker
s = replace_once(s, marker, addition, "live password blocking helper")

old = '''                session.sessionType == "POMODORO" -> EndSessionResult.POMODORO_NOT_REVOCABLE
                session.sessionType == "TIME" && isCurrentlyInBlockingWindow(session) ->
                    EndSessionResult.TIME_NOT_REVOCABLE
'''
new = '''                session.sessionType == "POMODORO" -> EndSessionResult.POMODORO_NOT_REVOCABLE
                MasterCredentialPolicy.isTimeCommitmentActive(
                    sessionType = session.sessionType,
                    isActive = session.isActive,
                    endTime = session.endTime
                ) -> EndSessionResult.TIME_NOT_REVOCABLE
'''
s = replace_once(s, old, new, "endSession TIME lifetime")

marker = '''    /**
     * What kind of protection is holding this target closed, expressed only in
     * terms a credential can act on.
'''
method = '''    /**
     * Releases only the target whose password was successfully authenticated.
     * A PASSWORD session can contain several apps; opening one must never end the
     * protection of all siblings in that session.
     */
    suspend fun unlockPasswordSessionTarget(
        blockedPackage: String?,
        blockedDomain: String?
    ): EndSessionResult = withContext(Dispatchers.IO) {
        try {
            val sessionId = findResponsibleSessionId(blockedPackage, blockedDomain)
                ?: return@withContext EndSessionResult.NOT_FOUND
            val session = database.blockSessionDao().getActiveSessionById(sessionId)
                ?: return@withContext EndSessionResult.NOT_FOUND
            if (!session.sessionType.equals("PASSWORD", ignoreCase = true)) {
                return@withContext EndSessionResult.NOT_FOUND
            }

            database.withTransaction {
                blockedPackage?.takeIf(String::isNotBlank)?.let { packageName ->
                    database.sessionAppCrossRefDao().deleteSpecificApp(sessionId, packageName)
                }
                blockedDomain?.takeIf(String::isNotBlank)?.let { domain ->
                    val sessionSites = database.sessionWebsiteCrossRefDao()
                        .getWebsitesForSessions(listOf(sessionId))
                    sessionSites.filter { configuredRule ->
                        WebsiteBlocker.isUrlBlocked(domain, listOf(configuredRule))
                    }.forEach { configuredRule ->
                        database.sessionWebsiteCrossRefDao()
                            .deleteSpecificWebsite(sessionId, configuredRule)
                    }
                }

                val remainingApps = database.sessionAppCrossRefDao()
                    .getAppsForSessions(listOf(sessionId))
                val remainingSites = database.sessionWebsiteCrossRefDao()
                    .getWebsitesForSessions(listOf(sessionId))
                if (remainingApps.isEmpty() && remainingSites.isEmpty()) {
                    database.blockSessionDao().deactivateSession(sessionId)
                } else {
                    database.blockSessionDao().updateBlockSession(
                        session.copy(
                            blockedAppsCount = remainingApps.distinct().size,
                            blockedWebsitesCount = remainingSites.distinct().size
                        )
                    )
                }
            }

            blockedPackage?.takeIf(String::isNotBlank)?.let { packageName ->
                PasswordAppUnlockStore(context).clearPackages(listOf(packageName))
            }
            checkAndEnforceOrThrow()
            EndSessionResult.ENDED
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            FocusGuardLogger.logError(
                "BlockingSessionManager",
                "Erro ao liberar alvo de sessão PASSWORD",
                error
            )
            EndSessionResult.FAILED
        }
    }

''' + marker
s = replace_once(s, marker, method, "target-specific password unlock")

old = '''                val activeSessions = database.blockSessionDao().getAllActiveSessionsStatic()
                val enforcingSessions = activeSessions.filter {
                    participatesInBlocking(it) && isCurrentlyInBlockingWindow(it)
                }
'''
new = '''                val activeSessions = database.blockSessionDao().getAllActiveSessionsStatic()
                val activeTimeCommitment = activeSessions.any { session ->
                    MasterCredentialPolicy.isTimeCommitmentActive(
                        sessionType = session.sessionType,
                        isActive = session.isActive,
                        endTime = session.endTime,
                        nowMillis = now
                    )
                }
                val enforcingSessions = activeSessions.filter {
                    participatesInBlocking(it) && isCurrentlyInBlockingWindow(it)
                }
'''
s = replace_once(s, old, new, "active TIME commitment in enforcement")
old = '''                val selfProtectionRequired = shouldArmSelfProtection(
                    hasEnforcingSessions = enforcingSessions.isNotEmpty(),
                    hasBlockedApps = appsToBlock.isNotEmpty(),
                    hasBlockedSites = sitesToBlock.isNotEmpty(),
                    adultFilterEnabled = adultFilterEnabled,
                    focusModeActive = focusModeSession != null
                )
'''
new = '''                val selfProtectionRequired = shouldArmSelfProtection(
                    hasEnforcingSessions = enforcingSessions.isNotEmpty(),
                    hasBlockedApps = appsToBlock.isNotEmpty(),
                    hasBlockedSites = sitesToBlock.isNotEmpty(),
                    adultFilterEnabled = adultFilterEnabled,
                    focusModeActive = focusModeSession != null
                ) || activeTimeCommitment
'''
s = replace_once(s, old, new, "self protection TIME lifetime")
p.write_text(s)


# -----------------------------------------------------------------------------
# AuthManager: password gate only when password protection is blocking NOW.
# -----------------------------------------------------------------------------
p = Path("app/src/main/java/com/focusguard/security/AuthManager.kt")
s = p.read_text()
s = replace_once(
    s,
    "import com.focusguard.database.AppPassword\n",
    "import com.focusguard.database.AppPassword\nimport com.focusguard.manager.BlockingSessionManager\n",
    "AuthManager manager import"
)
start = s.index("    suspend fun isAppLocked(): Boolean {")
end = s.index("\n    suspend fun hasPasswordSet(): Boolean", start)
replacement = '''    suspend fun isAppLocked(): Boolean {
        ensureMigrationDone()
        if (!DeactivationCredentialManager(appContext).hasCredential()) return false
        return BlockingSessionManager.getInstance(appContext)
            .hasPasswordProtectionBlockingNow()
    }
'''
s = s[:start] + replacement + s[end:]
p.write_text(s)


# -----------------------------------------------------------------------------
# Password unlock UI: release only the authenticated target, never the whole
# PASSWORD session.
# -----------------------------------------------------------------------------
p = Path("app/src/main/java/com/focusguard/ui/compose/screens/PasswordProtectedAppUnlockPanel.kt")
s = p.read_text()
old = '''                val sessionId = sessionManager.findResponsibleSessionId(
                    blockedPackage = blockedPackage,
                    blockedDomain = null
                )
                if (sessionId == null) {
                    error = failureMessage
                    onInvalid?.invoke()
                    return@launch
                }
                when (sessionManager.endSessionAndWait(sessionId)) {
'''
new = '''                when (
                    sessionManager.unlockPasswordSessionTarget(
                        blockedPackage = blockedPackage,
                        blockedDomain = null
                    )
                ) {
'''
s = replace_once(s, old, new, "custom app target unlock")
p.write_text(s)

p = Path("app/src/main/java/com/focusguard/ui/BlockNoticeActivity.kt")
s = p.read_text()
old = '''        val sessionId = sessionManager.findResponsibleSessionId(blockedPackage, blockedDomain)
            ?: return UnlockResult.NO_REVOCABLE_SESSION
        when (sessionManager.endSessionAndWait(sessionId)) {
'''
new = '''        when (
            sessionManager.unlockPasswordSessionTarget(
                blockedPackage = blockedPackage,
                blockedDomain = blockedDomain
            )
        ) {
'''
s = replace_once(s, old, new, "master password target unlock")
p.write_text(s)


# -----------------------------------------------------------------------------
# Legacy usage-limit flow: use the canonical deactivation credential, matching
# the actual unlock path.
# -----------------------------------------------------------------------------
p = Path("app/src/main/java/com/focusguard/ui/compose/screens/TimeSessionConfigScreen.kt")
s = p.read_text()
s = replace_once(
    s,
    "import com.focusguard.security.AuthManager\n",
    "import com.focusguard.security.AuthManager\nimport com.focusguard.security.DeactivationCredentialManager\n",
    "TimeSessionConfig credential import"
)
s = replace_once(
    s,
    "        hasPassword = authManager.hasPasswordSet()\n",
    "        hasPassword = DeactivationCredentialManager(context).hasCredential()\n",
    "legacy limit canonical password"
)
p.write_text(s)


# -----------------------------------------------------------------------------
# Usage-limits list: retain configured absent packages and expose preventive apps
# before installation so they can be limited ahead of time.
# -----------------------------------------------------------------------------
p = Path("app/src/main/java/com/focusguard/ui/compose/screens/UsageLimitsScreen.kt")
s = p.read_text()
s = replace_once(
    s,
    "import com.focusguard.database.WebsiteUsageLimit\n",
    "import com.focusguard.database.WebsiteUsageLimit\nimport com.focusguard.data.PredefinedApps\n",
    "UsageLimits PredefinedApps import"
)
old = '''            val loadedApps = resolveInfos.mapNotNull { info ->
                val packageName = info.activityInfo.packageName
                if (packageName == context.packageName) return@mapNotNull null
                val appName = info.loadLabel(pm).toString()
                val limit = existingLimits[packageName]
                UsageLimitAppUi(
                    packageName = packageName,
                    appName = appName,
                    currentLimitMinutes = limit?.dailyLimitMinutes,
                    isEnabled = limit?.isEnabled ?: false,
                    usageMs = stats[packageName]?.totalTimeInForeground ?: 0L,
                    lockMode = limit?.lockMode ?: "NONE",
                    lockPasswordHash = limit?.lockPasswordHash,
                    lockUntilTimestamp = limit?.lockUntilTimestamp
                )
            }.sortedBy { it.appName }
'''
new = '''            val installedApps = resolveInfos.mapNotNull { info ->
                val packageName = info.activityInfo.packageName
                if (packageName == context.packageName) return@mapNotNull null
                val appName = info.loadLabel(pm).toString()
                val limit = existingLimits[packageName]
                UsageLimitAppUi(
                    packageName = packageName,
                    appName = appName,
                    currentLimitMinutes = limit?.dailyLimitMinutes,
                    isEnabled = limit?.isEnabled ?: false,
                    usageMs = stats[packageName]?.totalTimeInForeground ?: 0L,
                    lockMode = limit?.lockMode ?: "NONE",
                    lockPasswordHash = limit?.lockPasswordHash,
                    lockUntilTimestamp = limit?.lockUntilTimestamp
                )
            }
            val installedPackages = installedApps.mapTo(mutableSetOf()) { it.packageName }
            val absentKnownApps = PredefinedApps.PREVENTIVE_APPS
                .asSequence()
                .filter { it.packageName !in installedPackages }
                .map { predefined ->
                    val limit = existingLimits[predefined.packageName]
                    UsageLimitAppUi(
                        packageName = predefined.packageName,
                        appName = predefined.name,
                        currentLimitMinutes = limit?.dailyLimitMinutes,
                        isEnabled = limit?.isEnabled ?: false,
                        usageMs = 0L,
                        lockMode = limit?.lockMode ?: "NONE",
                        lockPasswordHash = limit?.lockPasswordHash,
                        lockUntilTimestamp = limit?.lockUntilTimestamp
                    )
                }
                .toList()
            val absentConfiguredApps = existingLimits.values
                .asSequence()
                .filter { it.packageName !in installedPackages }
                .filter { limit -> absentKnownApps.none { it.packageName == limit.packageName } }
                .map { limit ->
                    UsageLimitAppUi(
                        packageName = limit.packageName,
                        appName = limit.appName.ifBlank { limit.packageName },
                        currentLimitMinutes = limit.dailyLimitMinutes,
                        isEnabled = limit.isEnabled,
                        usageMs = 0L,
                        lockMode = limit.lockMode,
                        lockPasswordHash = limit.lockPasswordHash,
                        lockUntilTimestamp = limit.lockUntilTimestamp
                    )
                }
                .toList()
            val loadedApps = (installedApps + absentKnownApps + absentConfiguredApps)
                .distinctBy { it.packageName }
                .sortedBy { it.appName }
'''
s = replace_once(s, old, new, "usage list absent apps")
p.write_text(s)


# -----------------------------------------------------------------------------
# WebsiteUsageLimitPolicy: identify limits that require an observable URL without
# triggering pause/block notifications as a side effect.
# -----------------------------------------------------------------------------
p = Path("app/src/main/java/com/focusguard/utils/WebsiteUsageLimitPolicy.kt")
s = p.read_text()
marker = '''    fun isBlockingModeActive(
        lockMode: String,
        lockUntilTimestamp: Long?,
        nowMillis: Long
    ): Boolean {
'''
addition = '''    fun requiresUrlObservationForHardLimit(
        lockMode: String,
        lockUntilTimestamp: Long?,
        nowMillis: Long
    ): Boolean {
        return when {
            UsageLimitBehaviorPolicy.isPauseMode(lockMode) ||
                UsageLimitBehaviorPolicy.isBlockUntilTomorrowMode(lockMode) ->
                UsageLimitBehaviorPolicy.isRuleActive(lockUntilTimestamp, nowMillis)
            else -> when (lockMode.uppercase(Locale.ROOT)) {
                "WARNING" -> false
                "TIME" -> lockUntilTimestamp?.let { it > nowMillis } == true
                "PASSWORD" -> lockUntilTimestamp?.let { nowMillis >= it } ?: true
                else -> true
            }
        }
    }

''' + marker
s = replace_once(s, marker, addition, "website hard observation helper")
p.write_text(s)


# -----------------------------------------------------------------------------
# WebsiteBlocker: recognize an address-bar node even before it contains text and
# detect pornography queries on major search engines, not Google only.
# -----------------------------------------------------------------------------
p = Path("app/src/main/java/com/focusguard/utils/WebsiteBlocker.kt")
s = p.read_text()
s = s.replace(
    "isPornographyGoogleSearchUrl(urlOrDomain) ||\n                isGoogleImagesUrl(urlOrDomain)",
    "isPornographySearchUrl(urlOrDomain) ||\n                isGoogleImagesUrl(urlOrDomain)"
)
s = replace_once(
    s,
    "(isPornographyGoogleSearchUrl(candidateUrl) || isGoogleImagesUrl(candidateUrl))",
    "(isPornographySearchUrl(candidateUrl) || isGoogleImagesUrl(candidateUrl))",
    "porn input generic search url"
)
marker = '''    /** Detecta os termos da categoria em `q=` de qualquer domínio regional do Google. */
    fun isPornographyGoogleSearchUrl(url: String): Boolean {
        if (!isGoogleUrl(url)) return false
        return searchQueryValues(url).any(::containsPornographySearchTerm)
    }
'''
addition = marker + '''
    /** Detecta a mesma consulta também em Bing, DuckDuckGo, Brave, Yahoo e afins. */
    fun isPornographySearchUrl(url: String): Boolean {
        val domain = extractDomain(url)
        if (domain.isEmpty() || !isKnownSearchEngineDomain(domain)) return false
        return searchQueryValues(url).any(::containsPornographySearchTerm)
    }

'''
s = replace_once(s, marker, addition, "generic pornography search function")
marker = '''    /** Fallback para texto ainda digitado quando o evento não veio da barra. */
    fun extractAddressBarTextFromRoot(
'''
# Insert hasAddressBarNode before the existing fallback doc.
addition = '''    /** True even while a genuine address bar is still empty (e.g. a new tab). */
    fun hasAddressBarNode(
        root: AccessibilityNodeInfo?,
        browserPackageName: String
    ): Boolean {
        if (root == null || browserPackageName.isBlank()) return false
        return findAddressBarNode(root, browserPackageName, 0, intArrayOf(0))
    }

''' + marker
s = replace_once(s, marker, addition, "has address bar node API")
marker = '''    private fun findAddressBarValue(
        node: AccessibilityNodeInfo?,
'''
addition = '''    private fun findAddressBarNode(
        node: AccessibilityNodeInfo?,
        browserPackageName: String,
        depth: Int,
        visitedNodes: IntArray
    ): Boolean {
        if (node == null || depth > MAX_TREE_DEPTH) return false
        visitedNodes[0] += 1
        if (visitedNodes[0] > MAX_TREE_NODES) return false
        return try {
            if (isAddressBarNode(node, browserPackageName)) return true
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try {
                    if (findAddressBarNode(child, browserPackageName, depth + 1, visitedNodes)) {
                        return true
                    }
                } finally {
                    recycleSafely(child)
                }
            }
            false
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError(TAG, "Falha ao localizar barra de endereço", error)
            false
        }
    }

''' + marker
s = replace_once(s, marker, addition, "address bar recursive finder")
# Insert search-engine helper before authorityHost.
marker = '''    private fun authorityHost(authority: String?): String? {
'''
addition = '''    private fun isKnownSearchEngineDomain(domain: String): Boolean {
        if (GOOGLE_HOST_REGEX.matches(domain)) return true
        return SEARCH_ENGINE_DOMAIN_SUFFIXES.any { suffix ->
            domain == suffix || domain.endsWith(".$suffix")
        }
    }

''' + marker
s = replace_once(s, marker, addition, "search engine domain helper")
s = replace_once(
    s,
    '    private val SEARCH_QUERY_KEYS = setOf("q", "query", "oq")\n',
    '    private val SEARCH_QUERY_KEYS = setOf("q", "query", "oq", "p", "text", "search", "keyword", "wd")\n'
    '    private val SEARCH_ENGINE_DOMAIN_SUFFIXES = setOf(\n'
    '        "bing.com", "duckduckgo.com", "search.brave.com", "ecosia.org",\n'
    '        "startpage.com", "qwant.com", "yahoo.com", "yandex.com", "yandex.ru",\n'
    '        "baidu.com", "aol.com", "ask.com", "naver.com"\n'
    '    )\n',
    "search query keys and engines"
)
p.write_text(s)


# -----------------------------------------------------------------------------
# Pure fail-closed decision used by the AccessibilityService and JVM tests.
# -----------------------------------------------------------------------------
write(
    "app/src/main/java/com/focusguard/utils/WebsiteObservabilityPolicy.kt",
    '''package com.focusguard.utils

/** Fail-closed policy for browsers that hide their address bar from accessibility. */
object WebsiteObservabilityPolicy {
    const val OPAQUE_BROWSER_GRACE_MILLIS = 800L

    fun shouldBlockOpaqueBrowser(
        websiteProtectionRequiresObservation: Boolean,
        browserStillForeground: Boolean,
        addressBarObservable: Boolean,
        firstUnobservableElapsed: Long?,
        nowElapsed: Long,
        graceMillis: Long = OPAQUE_BROWSER_GRACE_MILLIS
    ): Boolean {
        if (!websiteProtectionRequiresObservation || !browserStillForeground) return false
        if (addressBarObservable) return false
        val firstSeen = firstUnobservableElapsed ?: return false
        return nowElapsed - firstSeen >= graceMillis.coerceAtLeast(0L)
    }
}
'''
)


# -----------------------------------------------------------------------------
# Accessibility service: faster app-limit pulse and fail-closed browser
# observability without relying on DNS/VPN routing.
# -----------------------------------------------------------------------------
p = Path("app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt")
s = p.read_text()
s = replace_once(
    s,
    "import com.focusguard.utils.WebsiteBlocker\n",
    "import com.focusguard.utils.WebsiteBlocker\nimport com.focusguard.utils.WebsiteObservabilityPolicy\n",
    "A11y observability import"
)
s = replace_once(
    s,
    "    @Volatile private var limitedWebsiteDomains: Set<String> = emptySet()\n",
    "    @Volatile private var limitedWebsiteDomains: Set<String> = emptySet()\n"
    "    @Volatile private var hardLimitedWebsiteDomains: Set<String> = emptySet()\n",
    "A11y hard limited domains"
)
s = replace_once(
    s,
    "    private var appLimitMonitoringJob: Job? = null\n",
    "    private var appLimitMonitoringJob: Job? = null\n"
    "    private val opaqueBrowserFirstSeenElapsed = mutableMapOf<String, Long>()\n"
    "    private val opaqueBrowserVerificationScheduled = mutableSetOf<String>()\n",
    "A11y opaque browser state"
)
s = replace_once(s, "    private val appLimitPulseMillis = 5_000L\n", "    private val appLimitPulseMillis = 1_000L\n", "app limit pulse")
s = replace_once(
    s,
    "            limitedWebsiteDomains = emptySet()\n            limitedWebsiteAppDomains = emptyMap()\n",
    "            limitedWebsiteDomains = emptySet()\n"
    "            hardLimitedWebsiteDomains = emptySet()\n"
    "            limitedWebsiteAppDomains = emptyMap()\n"
    "            opaqueBrowserFirstSeenElapsed.clear()\n"
    "            opaqueBrowserVerificationScheduled.clear()\n",
    "dev relinquish opaque state"
)
# Calculate and publish hard limits during refresh.
old = '''                        val configuredWebsiteDomains = WebsiteBlocker.normalizeRules(
                            websiteLimits.map { it.domain }
                        )
                        val exceededWebsiteDomains = calculateExceededWebsiteLimits(websiteLimits)
'''
new = '''                        val configuredWebsiteDomains = WebsiteBlocker.normalizeRules(
                            websiteLimits.map { it.domain }
                        )
                        val hardConfiguredWebsiteDomains = WebsiteBlocker.normalizeRules(
                            websiteLimits.filter { limit ->
                                WebsiteUsageLimitPolicy.requiresUrlObservationForHardLimit(
                                    lockMode = limit.lockMode,
                                    lockUntilTimestamp = limit.lockUntilTimestamp,
                                    nowMillis = System.currentTimeMillis()
                                )
                            }.map { it.domain }
                        )
                        val exceededWebsiteDomains = calculateExceededWebsiteLimits(websiteLimits)
'''
s = replace_once(s, old, new, "hard website domains calculation")
old = '''                            limitedWebsiteDomains = configuredWebsiteDomains
                            limitedWebsiteAppDomains = limitedWebsiteApps
                            hasActiveAppLimits = activeAppLimits.isNotEmpty()
'''
new = '''                            limitedWebsiteDomains = configuredWebsiteDomains
                            hardLimitedWebsiteDomains = hardConfiguredWebsiteDomains
                            limitedWebsiteAppDomains = limitedWebsiteApps
                            if (
                                blockedWebsiteDomains.isEmpty() &&
                                hardConfiguredWebsiteDomains.isEmpty()
                            ) {
                                opaqueBrowserFirstSeenElapsed.clear()
                                opaqueBrowserVerificationScheduled.clear()
                            }
                            hasActiveAppLimits = activeAppLimits.isNotEmpty()
'''
s = replace_once(s, old, new, "publish hard website domains")
# Recognize a browser even when the address bar is currently empty.
s = replace_once(
    s,
    "            WebsiteBlocker.extractAddressBarTextFromRoot(root, packageName) != null\n",
    "            WebsiteBlocker.hasAddressBarNode(root, packageName)\n",
    "recognize blank address bar"
)
# Insert observability handling after URL/address extraction.
old = '''        val addressText = fastAddressText
            ?: WebsiteBlocker.extractAddressBarTextFromRoot(root, packageName)
        val now = System.currentTimeMillis()

        if (pornographyCategoryActive) {
'''
new = '''        val addressText = fastAddressText
            ?: WebsiteBlocker.extractAddressBarTextFromRoot(root, packageName)
        val addressBarObservable = fastAddressText != null ||
            url != null || WebsiteBlocker.hasAddressBarNode(root, packageName)
        if (handleBrowserObservability(packageName, addressBarObservable)) {
            recycleSafely(root)
            return
        }
        val now = System.currentTimeMillis()

        if (pornographyCategoryActive) {
'''
s = replace_once(s, old, new, "handle browser observability")
# Generic porn searches in tracking.
s = replace_once(
    s,
    "            WebsiteBlocker.isPornographyGoogleSearchUrl(urlOrDomain) ||\n                WebsiteBlocker.isGoogleImagesUrl(urlOrDomain)\n",
    "            WebsiteBlocker.isPornographySearchUrl(urlOrDomain) ||\n                WebsiteBlocker.isGoogleImagesUrl(urlOrDomain)\n",
    "generic porn tracking surface"
)
# Insert browser observability helpers before updateWebsiteTracking.
marker = '''    private fun updateWebsiteTracking(urlOrDomain: String, packageName: String, now: Long) {
'''
helpers = '''    private fun websiteObservationRequired(): Boolean =
        blockedWebsitesDomainSet.isNotEmpty() || hardLimitedWebsiteDomains.isNotEmpty()

    private fun handleBrowserObservability(
        packageName: String,
        addressBarObservable: Boolean
    ): Boolean {
        if (!websiteObservationRequired() || addressBarObservable) {
            opaqueBrowserFirstSeenElapsed.remove(packageName)
            opaqueBrowserVerificationScheduled.remove(packageName)
            return false
        }

        val nowElapsed = SystemClock.elapsedRealtime()
        val firstSeen = opaqueBrowserFirstSeenElapsed.getOrPut(packageName) { nowElapsed }
        if (WebsiteObservabilityPolicy.shouldBlockOpaqueBrowser(
                websiteProtectionRequiresObservation = true,
                browserStillForeground = foregroundPackageName == packageName,
                addressBarObservable = false,
                firstUnobservableElapsed = firstSeen,
                nowElapsed = nowElapsed
            )
        ) {
            blockOpaqueBrowser(packageName)
            return true
        }

        if (opaqueBrowserVerificationScheduled.add(packageName)) {
            val delayMillis = (
                WebsiteObservabilityPolicy.OPAQUE_BROWSER_GRACE_MILLIS -
                    (nowElapsed - firstSeen)
                ).coerceAtLeast(1L)
            mainHandler.postDelayed({ verifyOpaqueBrowser(packageName, firstSeen) }, delayMillis)
        }
        return false
    }

    private fun verifyOpaqueBrowser(packageName: String, expectedFirstSeen: Long) {
        opaqueBrowserVerificationScheduled.remove(packageName)
        if (opaqueBrowserFirstSeenElapsed[packageName] != expectedFirstSeen) return
        if (!websiteObservationRequired() || foregroundPackageName != packageName) {
            opaqueBrowserFirstSeenElapsed.remove(packageName)
            return
        }

        val root = rootInActiveWindow
        val observable = try {
            WebsiteBlocker.hasAddressBarNode(root, packageName)
        } finally {
            recycleSafely(root)
        }
        if (observable) {
            opaqueBrowserFirstSeenElapsed.remove(packageName)
            return
        }

        val nowElapsed = SystemClock.elapsedRealtime()
        if (WebsiteObservabilityPolicy.shouldBlockOpaqueBrowser(
                websiteProtectionRequiresObservation = true,
                browserStillForeground = foregroundPackageName == packageName,
                addressBarObservable = false,
                firstUnobservableElapsed = expectedFirstSeen,
                nowElapsed = nowElapsed
            )
        ) {
            blockOpaqueBrowser(packageName)
        }
    }

    private fun blockOpaqueBrowser(packageName: String) {
        opaqueBrowserFirstSeenElapsed.remove(packageName)
        opaqueBrowserVerificationScheduled.remove(packageName)
        stopWebsiteTracking()
        val noticeLaunched = launchBlockNotice(
            blockedPackage = null,
            blockedDomain = "Navegador sem URL verificável",
            redirectBrowserPackage = packageName
        )
        if (!noticeLaunched && !redirectBrowserToSafePage(packageName)) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

''' + marker
s = replace_once(s, marker, helpers, "opaque browser helper methods")
p.write_text(s)


# -----------------------------------------------------------------------------
# Static package receiver: reapply stored package-name rules even if the
# AccessibilityService was not alive when a previously absent app was installed.
# -----------------------------------------------------------------------------
write(
    "app/src/main/java/com/focusguard/receiver/PackageChangeReceiver.kt",
    '''package com.focusguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.utils.FocusGuardLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PackageChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_ADDED &&
            intent.action != Intent.ACTION_PACKAGE_REPLACED &&
            intent.action != Intent.ACTION_PACKAGE_CHANGED
        ) return

        val changedPackage = intent.data?.schemeSpecificPart.orEmpty()
        if (changedPackage.isBlank() || changedPackage == context.packageName) return

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                BlockingSessionManager.getInstance(context.applicationContext).checkAndEnforce()
            } catch (error: Exception) {
                FocusGuardLogger.logError(
                    "PackageChangeReceiver",
                    "Falha ao reaplicar bloqueio após mudança de pacote: $changedPackage",
                    error
                )
            } finally {
                pending.finish()
            }
        }
    }
}
'''
)

p = Path("app/src/main/AndroidManifest.xml")
s = p.read_text()
marker = '''        <receiver
            android:name=".receiver.PomodoroWatchdogReceiver"
            android:exported="false" />

'''
addition = marker + '''        <receiver
            android:name=".receiver.PackageChangeReceiver"
            android:exported="false">
            <intent-filter>
                <action android:name="android.intent.action.PACKAGE_ADDED" />
                <action android:name="android.intent.action.PACKAGE_REPLACED" />
                <action android:name="android.intent.action.PACKAGE_CHANGED" />
                <data android:scheme="package" />
            </intent-filter>
        </receiver>

'''
s = replace_once(s, marker, addition, "manifest package receiver")
p.write_text(s)


# -----------------------------------------------------------------------------
# Tests.
# -----------------------------------------------------------------------------
p = Path("app/src/test/java/com/focusguard/security/MasterCredentialPolicyTest.kt")
s = p.read_text()
marker = '''    @Test
    fun `only explicit time block prevents uninstall`() {
'''
tests = '''    @Test
    fun `recurring time commitment remains active outside its daily window`() {
        assertThat(
            MasterCredentialPolicy.isTimeCommitmentActive(
                sessionType = "TIME",
                isActive = true,
                endTime = 10_000L,
                nowMillis = 5_000L
            )
        ).isTrue()
    }

    @Test
    fun `expired or inactive time commitment is no longer armed`() {
        assertThat(
            MasterCredentialPolicy.isTimeCommitmentActive(
                sessionType = "TIME",
                isActive = true,
                endTime = 5_000L,
                nowMillis = 5_000L
            )
        ).isFalse()
        assertThat(
            MasterCredentialPolicy.isTimeCommitmentActive(
                sessionType = "TIME",
                isActive = false,
                endTime = null,
                nowMillis = 5_000L
            )
        ).isFalse()
        assertThat(
            MasterCredentialPolicy.isTimeCommitmentActive(
                sessionType = "PASSWORD",
                isActive = true,
                endTime = null,
                nowMillis = 5_000L
            )
        ).isFalse()
    }

''' + marker
s = replace_once(s, marker, tests, "MasterCredentialPolicy commitment tests")
p.write_text(s)

p = Path("app/src/test/java/com/focusguard/utils/WebsiteBlockerTest.kt")
s = p.read_text()
marker = '''    @Test
    fun `pornography category maps effective keyword identifiers back to one rule`() {
'''
tests = '''    @Test
    fun `pornography category blocks adult searches on major non Google engines`() {
        val category = listOf(PredefinedWebsites.PORNOGRAPHY_RULE)

        assertThat(
            WebsiteBlocker.isUrlBlocked("https://www.bing.com/search?q=free+porn", category)
        ).isTrue()
        assertThat(
            WebsiteBlocker.isUrlBlocked("https://duckduckgo.com/?q=hentai+videos", category)
        ).isTrue()
        assertThat(
            WebsiteBlocker.isUrlBlocked("https://search.yahoo.com/search?p=xxx", category)
        ).isTrue()
        assertThat(
            WebsiteBlocker.isUrlBlocked("https://yandex.com/search/?text=conteudo+sexual", category)
        ).isTrue()
        assertThat(
            WebsiteBlocker.isUrlBlocked("https://safe-example.com/search?q=porn", category)
        ).isFalse()
    }

''' + marker
s = replace_once(s, marker, tests, "porn search engine tests")
p.write_text(s)

write(
    "app/src/test/java/com/focusguard/utils/WebsiteObservabilityPolicyTest.kt",
    '''package com.focusguard.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebsiteObservabilityPolicyTest {
    @Test
    fun `opaque browser fails closed after grace while protection is active`() {
        assertThat(
            WebsiteObservabilityPolicy.shouldBlockOpaqueBrowser(
                websiteProtectionRequiresObservation = true,
                browserStillForeground = true,
                addressBarObservable = false,
                firstUnobservableElapsed = 1_000L,
                nowElapsed = 1_800L,
                graceMillis = 800L
            )
        ).isTrue()
    }

    @Test
    fun `observable browser and inactive protection never fail closed`() {
        assertThat(
            WebsiteObservabilityPolicy.shouldBlockOpaqueBrowser(
                websiteProtectionRequiresObservation = true,
                browserStillForeground = true,
                addressBarObservable = true,
                firstUnobservableElapsed = 1_000L,
                nowElapsed = 9_000L
            )
        ).isFalse()
        assertThat(
            WebsiteObservabilityPolicy.shouldBlockOpaqueBrowser(
                websiteProtectionRequiresObservation = false,
                browserStillForeground = true,
                addressBarObservable = false,
                firstUnobservableElapsed = 1_000L,
                nowElapsed = 9_000L
            )
        ).isFalse()
    }

    @Test
    fun `opaque browser gets its grace window before blocking`() {
        assertThat(
            WebsiteObservabilityPolicy.shouldBlockOpaqueBrowser(
                websiteProtectionRequiresObservation = true,
                browserStillForeground = true,
                addressBarObservable = false,
                firstUnobservableElapsed = 1_000L,
                nowElapsed = 1_799L,
                graceMillis = 800L
            )
        ).isFalse()
    }
}
'''
)

print("Blocking review hardening patch applied")
