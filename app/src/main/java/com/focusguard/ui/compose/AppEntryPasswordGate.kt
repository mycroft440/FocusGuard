package com.focusguard.ui.compose

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.focusguard.R
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.security.AppEntryAuthSession
import com.focusguard.security.AppUnlockBiometricAuthenticator
import com.focusguard.security.AuthManager
import com.focusguard.security.PasswordAppUnlockConfig
import com.focusguard.security.PasswordAppUnlockMode
import com.focusguard.security.PasswordAppUnlockStore
import com.focusguard.ui.compose.components.PatternLockInput
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.DarkCard
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ActiveEntryCredential(
    val targetId: String,
    val config: PasswordAppUnlockConfig
)

private data class AppEntryLockSnapshot(
    val activeTargetCount: Int,
    val credentials: List<ActiveEntryCredential>
)

private sealed interface AppEntryGateState {
    val generation: Long

    data class Loading(override val generation: Long) : AppEntryGateState
    data class Unlocked(override val generation: Long) : AppEntryGateState
    data class Locked(
        override val generation: Long,
        val credentials: List<ActiveEntryCredential>
    ) : AppEntryGateState
}

/**
 * Opaque entry gate for the HardBlock management UI.
 *
 * The navigation tree is not composed until the active foreground generation
 * has been authenticated. The only database/crypto work performed here happens
 * when HardBlock itself enters the foreground or when the user explicitly
 * submits a credential; Accessibility blocking remains completely independent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppEntryPasswordGate(
    activity: FragmentActivity,
    authManager: AuthManager,
    foregroundGeneration: Long,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessionManager = remember(context) { BlockingSessionManager.getInstance(context) }
    val unlockStore = remember(context) { PasswordAppUnlockStore(context) }
    val passwordState = remember { TextFieldState() }

    var gateState by remember { mutableStateOf<AppEntryGateState>(AppEntryGateState.Loading(0L)) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var patternResetKey by remember { mutableIntStateOf(0) }

    val wrongCredentialMessage = stringResource(R.string.app_entry_lock_wrong_credential)
    val unavailableMessage = stringResource(R.string.app_entry_lock_unavailable)

    fun markUnlocked(generation: Long) {
        AppEntryAuthSession.markAuthenticated(generation)
        if (AppEntryAuthSession.isAuthenticated(generation)) {
            error = null
            gateState = AppEntryGateState.Unlocked(generation)
        }
    }

    LaunchedEffect(foregroundGeneration) {
        passwordState.clearText()
        error = null
        busy = false
        patternResetKey++

        if (foregroundGeneration <= 0L) {
            gateState = AppEntryGateState.Loading(foregroundGeneration)
            return@LaunchedEffect
        }
        if (AppEntryAuthSession.isAuthenticated(foregroundGeneration)) {
            gateState = AppEntryGateState.Unlocked(foregroundGeneration)
            return@LaunchedEffect
        }

        gateState = AppEntryGateState.Loading(foregroundGeneration)
        val snapshot = runCatching {
            loadAppEntryLockSnapshot(sessionManager, unlockStore)
        }.getOrElse {
            // Fail closed on a transient storage failure. Reopening the app starts
            // another foreground generation and retries without weakening the block.
            AppEntryLockSnapshot(activeTargetCount = 1, credentials = emptyList())
        }

        if (snapshot.activeTargetCount == 0) {
            markUnlocked(foregroundGeneration)
        } else {
            gateState = AppEntryGateState.Locked(
                generation = foregroundGeneration,
                credentials = snapshot.credentials
            )
        }
    }

    // A generation change invalidates the old UI synchronously, before the
    // LaunchedEffect above has a chance to run, preventing a one-frame flash of
    // the management interface when HardBlock returns from the background.
    val visibleState = if (gateState.generation == foregroundGeneration) {
        gateState
    } else {
        AppEntryGateState.Loading(foregroundGeneration)
    }

    when (val state = visibleState) {
        is AppEntryGateState.Unlocked -> content()
        is AppEntryGateState.Loading -> AppEntryLockLoading()
        is AppEntryGateState.Locked -> {
            val passwordCredentials = state.credentials.filter {
                it.config.mode == PasswordAppUnlockMode.PASSWORD
            }
            val patternCredentials = state.credentials.filter {
                it.config.mode == PasswordAppUnlockMode.PATTERN
            }
            val biometricAllowed = state.credentials.any {
                it.config.biometricEnabled || it.config.mode == PasswordAppUnlockMode.BIOMETRIC_ONLY
            } && authManager.isBiometricAppUnlockEnabled() &&
                AppUnlockBiometricAuthenticator.isAvailable(context)

            fun verifyCredential(
                mode: PasswordAppUnlockMode,
                credential: String
            ) {
                if (busy || credential.isBlank()) return
                val candidates = when (mode) {
                    PasswordAppUnlockMode.PASSWORD -> passwordCredentials
                    PasswordAppUnlockMode.PATTERN -> patternCredentials
                    PasswordAppUnlockMode.BIOMETRIC_ONLY -> emptyList()
                }
                if (candidates.isEmpty()) return

                scope.launch {
                    busy = true
                    error = null
                    val accepted = withContext(Dispatchers.Default) {
                        candidates.any { candidate ->
                            unlockStore.verifyTarget(candidate.targetId, credential)
                        }
                    }
                    if (accepted) {
                        markUnlocked(state.generation)
                    } else {
                        error = wrongCredentialMessage
                        if (mode == PasswordAppUnlockMode.PATTERN) patternResetKey++
                    }
                    busy = false
                }
            }

            fun launchBiometric() {
                if (!biometricAllowed || busy) return
                AppUnlockBiometricAuthenticator.authenticate(
                    activity = activity,
                    title = activity.getString(R.string.app_entry_lock_title),
                    subtitle = activity.getString(R.string.app_entry_lock_description),
                    cancelLabel = activity.getString(R.string.cancel),
                    onSuccess = {
                        scope.launch {
                            busy = true
                            error = null
                            val latest = runCatching {
                                loadAppEntryLockSnapshot(sessionManager, unlockStore)
                            }.getOrNull()
                            val stillProtected = latest?.activeTargetCount?.let { it > 0 } == true
                            val stillAllowed = latest?.credentials?.any {
                                it.config.biometricEnabled ||
                                    it.config.mode == PasswordAppUnlockMode.BIOMETRIC_ONLY
                            } == true && authManager.isBiometricAppUnlockEnabled() &&
                                AppUnlockBiometricAuthenticator.isAvailable(context)

                            when {
                                latest == null -> error = unavailableMessage
                                !stillProtected -> markUnlocked(state.generation)
                                stillAllowed -> markUnlocked(state.generation)
                                else -> error = unavailableMessage
                            }
                            busy = false
                        }
                    },
                    onError = { message ->
                        error = message.takeIf(String::isNotBlank) ?: unavailableMessage
                    },
                    onCancelled = { error = null }
                )
            }

            AppEntryLockScreen(
                passwordState = passwordState,
                showPassword = passwordCredentials.isNotEmpty(),
                showPattern = patternCredentials.isNotEmpty(),
                hidePatternTrace = patternCredentials.any { it.config.hidePatternTrace },
                patternResetKey = patternResetKey,
                showBiometric = biometricAllowed,
                unavailable = state.credentials.isEmpty() ||
                    (passwordCredentials.isEmpty() &&
                        patternCredentials.isEmpty() &&
                        !biometricAllowed),
                busy = busy,
                error = error,
                onPasswordSubmit = {
                    verifyCredential(
                        PasswordAppUnlockMode.PASSWORD,
                        passwordState.text.toString()
                    )
                },
                onPatternComplete = {
                    verifyCredential(PasswordAppUnlockMode.PATTERN, it)
                },
                onBiometric = ::launchBiometric
            )
        }
    }
}

private suspend fun loadAppEntryLockSnapshot(
    sessionManager: BlockingSessionManager,
    unlockStore: PasswordAppUnlockStore
): AppEntryLockSnapshot = withContext(Dispatchers.IO) {
    val entries = sessionManager.getBlockOverview().passwordEntries
    val credentials = entries.mapNotNull { entry ->
        val targetId = if (entry.isWebsite) {
            PasswordAppUnlockStore.targetIdForWebsite(entry.identifier)
        } else {
            PasswordAppUnlockStore.targetIdForPackage(entry.identifier)
        } ?: return@mapNotNull null
        val config = unlockStore.getTarget(targetId) ?: return@mapNotNull null
        ActiveEntryCredential(targetId = targetId, config = config)
    }.distinctBy(ActiveEntryCredential::targetId)

    AppEntryLockSnapshot(
        activeTargetCount = entries.size,
        credentials = credentials
    )
}

@Composable
private fun AppEntryLockLoading() {
    Surface(color = DarkBg, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(color = AccentCyan)
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.app_entry_lock_loading),
                color = TextSecondary,
                fontSize = 13.sp
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppEntryLockScreen(
    passwordState: TextFieldState,
    showPassword: Boolean,
    showPattern: Boolean,
    hidePatternTrace: Boolean,
    patternResetKey: Int,
    showBiometric: Boolean,
    unavailable: Boolean,
    busy: Boolean,
    error: String?,
    onPasswordSubmit: () -> Unit,
    onPatternComplete: (String) -> Unit,
    onBiometric: () -> Unit
) {
    Surface(color = DarkBg, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                tint = AccentCyan,
                modifier = Modifier.size(46.dp)
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.app_entry_lock_title),
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.app_entry_lock_description),
                color = TextSecondary,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(20.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkCard)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (showPassword) {
                        OutlinedSecureTextField(
                            state = passwordState,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.app_entry_lock_password_label)) },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            )
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = onPasswordSubmit,
                            enabled = !busy && passwordState.text.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.app_entry_lock_unlock))
                        }
                    }

                    if (showPattern) {
                        if (showPassword) Spacer(Modifier.height(18.dp))
                        Text(
                            text = stringResource(R.string.app_entry_lock_pattern_prompt),
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        PatternLockInput(
                            hideTrace = hidePatternTrace,
                            enabled = !busy,
                            resetKey = patternResetKey,
                            onPatternComplete = onPatternComplete
                        )
                    }

                    if (showBiometric) {
                        if (showPassword || showPattern) Spacer(Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = onBiometric,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Fingerprint, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.app_entry_lock_biometric))
                        }
                    }

                    if (unavailable) {
                        Text(
                            text = stringResource(R.string.app_entry_lock_unavailable),
                            color = DangerRed,
                            fontSize = 12.sp
                        )
                    }

                    if (busy) {
                        Spacer(Modifier.height(14.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = AccentCyan
                        )
                    }

                    error?.let { message ->
                        Spacer(Modifier.height(10.dp))
                        Text(message, color = DangerRed, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
