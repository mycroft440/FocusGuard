package com.focusguard.ui.compose.screens

import com.focusguard.monetization.RewardedGateCoordinator
import com.focusguard.monetization.MonetizationPolicy
import androidx.compose.runtime.*
import android.widget.Toast
import com.focusguard.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.focusguard.database.AppUsageLimit
import com.focusguard.database.WebsiteUsageLimit
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.security.AuthManager
import com.focusguard.security.DeactivationCredentialManager
import com.focusguard.security.ProtectionPermissionGate
import com.focusguard.ui.compose.rememberAppDatabase
import com.focusguard.utils.WebsiteBlocker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TimeSessionConfigScreen(
    authManager: AuthManager,
    appName: String,
    apps: List<String>,
    sites: List<String>,
    onBack: () -> Unit,
    onFinish: () -> Unit,
    onCreateOrManagePassword: () -> Unit,
    passwordRefreshKey: Int = 0
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasPassword by remember { mutableStateOf(false) }
    // P2-3: captura AppDatabase via Hilt EntryPoint antes das coroutines
    val database = rememberAppDatabase()

    LaunchedEffect(passwordRefreshKey) {
        hasPassword = DeactivationCredentialManager(context).hasCredential()
    }

    UsageBlockConfigScreen(
        appName = appName,
        hasExistingPassword = hasPassword,
        onCreateOrManagePassword = onCreateOrManagePassword,
        onCancel = onBack,
        onSave = { config ->
            if (config.limitType == LimitType.HARD_BLOCK_WITH_PASSWORD && !hasPassword) {
                onCreateOrManagePassword()
                return@UsageBlockConfigScreen
            }
            val monetizedAction: () -> Unit = {

            scope.launch(Dispatchers.IO) {
                if (!ProtectionPermissionGate.read(context).isReady) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.blocking_permissions_required_desc),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }
                val dailyLimitMinutes = (
                    config.dailyLimitHours.coerceIn(0, 24) * 60 +
                        config.dailyLimitMinutes.coerceIn(0, 59)
                    ).coerceIn(1, 24 * 60)
                val now = System.currentTimeMillis()
                val lockMode = when (config.limitType) {
                    LimitType.HARD_BLOCK_NO_PASSWORD -> "TIME"
                    LimitType.WARNING_ONLY -> "WARNING"
                    LimitType.HARD_BLOCK_WITH_PASSWORD -> "PASSWORD"
                }
                val durationDays = when (config.limitType) {
                    LimitType.HARD_BLOCK_NO_PASSWORD -> config.daysLimit.coerceIn(1, 120)
                    else -> 1
                }
                val lockUntilTimestamp = if (
                    config.limitType == LimitType.HARD_BLOCK_NO_PASSWORD
                ) {
                    now + durationDays * 24L * 60L * 60L * 1000L
                } else {
                    null
                }

                apps.forEach { packageName ->
                    database.appUsageLimitDao().insert(
                        AppUsageLimit(
                            packageName = packageName,
                            appName = packageName,
                            dailyLimitMinutes = dailyLimitMinutes,
                            isEnabled = true,
                            lockMode = lockMode,
                            lockUntilTimestamp = lockUntilTimestamp,
                            createdAt = now,
                            lastResetDate = now,
                            preventOpeningAfterLimit = config.limitType != LimitType.WARNING_ONLY,
                            durationDays = durationDays,
                            unlockWithPassword = config.limitType == LimitType.HARD_BLOCK_WITH_PASSWORD
                        )
                    )
                }

                val websiteDao = database.websiteUsageLimitDao()
                val existingWebsiteLimits = websiteDao.getAllStatic()
                WebsiteBlocker.normalizeRules(sites).forEach { domain ->
                    existingWebsiteLimits
                        .filter { existing ->
                            existing.domain != domain &&
                                WebsiteBlocker.normalizeRule(existing.domain) == domain
                        }
                        .forEach { websiteDao.delete(it) }
                    websiteDao.insert(
                        WebsiteUsageLimit(
                            domain = domain,
                            dailyLimitMinutes = dailyLimitMinutes,
                            isEnabled = true,
                            lockMode = lockMode,
                            lockUntilTimestamp = lockUntilTimestamp,
                            createdAt = now
                        )
                    )
                }

                BlockingSessionManager.getInstance(context).checkAndEnforce()

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, context.getString(R.string.limite_diario_ativado_com_sucesso), Toast.LENGTH_LONG).show()
                    onFinish()
                }
            }

            }
            if (config.limitType == LimitType.HARD_BLOCK_NO_PASSWORD) {
                RewardedGateCoordinator.launch(
                    context = context,
                    requiredAds = MonetizationPolicy.TIME_BLOCK_REWARDED_ADS,
                    title = "Ativar bloqueio sem senha",
                    description = "Assista a 3 anúncios para criar este bloqueio por tempo.",
                    action = monetizedAction
                )
            } else {
                monetizedAction()
            }
        }
    )
}
