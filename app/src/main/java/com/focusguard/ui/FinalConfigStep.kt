package com.focusguard.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.security.AppUnlockBiometricAuthenticator
import com.focusguard.security.DeactivationCredentialManager
import com.focusguard.security.PasswordAppUnlockMode
import com.focusguard.security.PasswordAppUnlockStore
import com.focusguard.ui.compose.components.PatternLockInput
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.DarkCard
import com.focusguard.ui.compose.theme.TextHint
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary
import com.focusguard.utils.FocusGuardLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FinalConfigStep(
    sessionType: String,
    authManager: com.focusguard.security.AuthManager,
    sites: List<String>,
    apps: List<String>,
    onFinish: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessionManager = remember(context) { BlockingSessionManager.getInstance(context) }
    val credentialManager = remember(context) { DeactivationCredentialManager(context) }
    val appUnlockStore = remember(context) { PasswordAppUnlockStore(context) }
    val biometricAvailable = remember(context) {
        AppUnlockBiometricAuthenticator.isAvailable(context)
    }

    var hasPassword by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var unlockModeName by rememberSaveable {
        mutableStateOf(PasswordAppUnlockMode.PASSWORD.name)
    }
    val unlockMode = runCatching { PasswordAppUnlockMode.valueOf(unlockModeName) }
        .getOrDefault(PasswordAppUnlockMode.PASSWORD)
    var unlockPassword by rememberSaveable { mutableStateOf("") }
    var unlockPasswordConfirmation by rememberSaveable { mutableStateOf("") }
    var patternCredential by rememberSaveable { mutableStateOf("") }
    var hidePatternTrace by rememberSaveable { mutableStateOf(false) }
    var biometricEnabled by rememberSaveable { mutableStateOf(false) }
    var showPatternDialog by remember { mutableStateOf(false) }
    var configError by remember { mutableStateOf<String?>(null) }
    var securityOptionsExpanded by rememberSaveable { mutableStateOf(false) }

    val masterPasswordLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        hasPassword = credentialManager.hasCredential()
        if (hasPassword) {
            securityOptionsExpanded = true
        }
    }

    LaunchedEffect(Unit) {
        hasPassword = credentialManager.hasCredential()
    }

    if (showPatternDialog) {
        PatternSetupDialog(
            hideTrace = hidePatternTrace,
            onDismiss = { showPatternDialog = false },
            onPatternSet = {
                patternCredential = it
                configError = null
                showPatternDialog = false
            }
        )
    }

    val passwordValid = PasswordAppUnlockStore.isPasswordValid(unlockPassword) &&
        unlockPassword == unlockPasswordConfirmation
    val patternValid = PasswordAppUnlockStore.isPatternValid(patternCredential)
    val selectedUnlockLabel = when (unlockMode) {
        PasswordAppUnlockMode.PASSWORD ->
            stringResource(R.string.password_app_unlock_mode_password)
        PasswordAppUnlockMode.PATTERN ->
            stringResource(R.string.password_app_unlock_mode_pattern)
        PasswordAppUnlockMode.BIOMETRIC_ONLY ->
            stringResource(R.string.password_app_unlock_mode_biometric_only)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.final_config_title), color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.common_back),
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg)
            )
        },
        containerColor = DarkBg
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.final_config_password_block),
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (hasPassword) {
                            stringResource(R.string.final_config_existing_password)
                        } else {
                            stringResource(R.string.final_config_no_password)
                        },
                        color = if (hasPassword) AccentCyan else DangerRed
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.password_app_unlock_master_note),
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    if (!hasPassword) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                masterPasswordLauncher.launch(
                                    MasterPasswordActivity.createIntent(context)
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(
                                stringResource(R.string.final_config_create_password),
                                color = DarkBg
                            )
                        }
                    }
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                securityOptionsExpanded = !securityOptionsExpanded
                            }
                            .padding(horizontal = 18.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.password_app_unlock_config_title),
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Spacer(modifier = Modifier.height(5.dp))
                            Text(
                                text = if (hasPassword) {
                                    selectedUnlockLabel
                                } else {
                                    stringResource(R.string.final_config_create_password)
                                },
                                color = if (hasPassword) AccentCyan else TextHint,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Icon(
                            imageVector = if (securityOptionsExpanded) {
                                Icons.Default.KeyboardArrowUp
                            } else {
                                Icons.Default.KeyboardArrowDown
                            },
                            contentDescription = null,
                            tint = TextSecondary
                        )
                    }

                    AnimatedVisibility(visible = securityOptionsExpanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            HorizontalDivider(color = TextHint.copy(alpha = 0.32f))
                            Spacer(modifier = Modifier.height(2.dp))

                            if (!hasPassword) {
                                Text(
                                    stringResource(R.string.final_config_no_password),
                                    color = DangerRed,
                                    fontSize = 13.sp
                                )
                            }

                            UnlockModeRow(
                                selected = hasPassword &&
                                    unlockMode == PasswordAppUnlockMode.PASSWORD,
                                label = stringResource(R.string.password_app_unlock_mode_password),
                                enabled = hasPassword,
                                onClick = {
                                    unlockModeName = PasswordAppUnlockMode.PASSWORD.name
                                    configError = null
                                }
                            )
                            UnlockModeRow(
                                selected = hasPassword &&
                                    unlockMode == PasswordAppUnlockMode.PATTERN,
                                label = stringResource(R.string.password_app_unlock_mode_pattern),
                                enabled = hasPassword,
                                onClick = {
                                    unlockModeName = PasswordAppUnlockMode.PATTERN.name
                                    configError = null
                                }
                            )
                            UnlockModeRow(
                                selected = hasPassword &&
                                    unlockMode == PasswordAppUnlockMode.BIOMETRIC_ONLY,
                                label = stringResource(
                                    R.string.password_app_unlock_mode_biometric_only
                                ),
                                enabled = hasPassword && biometricAvailable,
                                onClick = {
                                    unlockModeName = PasswordAppUnlockMode.BIOMETRIC_ONLY.name
                                    biometricEnabled = true
                                    configError = null
                                }
                            )

                            if (hasPassword && !biometricAvailable) {
                                Text(
                                    stringResource(
                                        R.string.password_app_unlock_biometric_unavailable
                                    ),
                                    color = TextHint,
                                    fontSize = 12.sp
                                )
                            }

                            if (hasPassword) {
                                when (unlockMode) {
                                    PasswordAppUnlockMode.PASSWORD -> {
                                        OutlinedTextField(
                                            value = unlockPassword,
                                            onValueChange = {
                                                unlockPassword = it
                                                configError = null
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            label = {
                                                Text(
                                                    stringResource(
                                                        R.string.password_app_unlock_password
                                                    )
                                                )
                                            },
                                            singleLine = true,
                                            visualTransformation = PasswordVisualTransformation(),
                                            keyboardOptions = KeyboardOptions(
                                                keyboardType = KeyboardType.Password
                                            )
                                        )
                                        OutlinedTextField(
                                            value = unlockPasswordConfirmation,
                                            onValueChange = {
                                                unlockPasswordConfirmation = it
                                                configError = null
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            label = {
                                                Text(
                                                    stringResource(
                                                        R.string.password_app_unlock_password_confirm
                                                    )
                                                )
                                            },
                                            singleLine = true,
                                            visualTransformation = PasswordVisualTransformation(),
                                            keyboardOptions = KeyboardOptions(
                                                keyboardType = KeyboardType.Password
                                            )
                                        )
                                        Text(
                                            stringResource(
                                                R.string.password_app_unlock_password_requirement,
                                                PasswordAppUnlockStore.MIN_PASSWORD_LENGTH
                                            ),
                                            color = TextHint,
                                            fontSize = 11.sp
                                        )
                                    }

                                    PasswordAppUnlockMode.PATTERN -> {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                stringResource(
                                                    R.string.password_app_unlock_hide_pattern
                                                ),
                                                modifier = Modifier.weight(1f),
                                                color = TextPrimary,
                                                fontSize = 13.sp
                                            )
                                            Switch(
                                                checked = hidePatternTrace,
                                                onCheckedChange = { hidePatternTrace = it }
                                            )
                                        }
                                        OutlinedButton(
                                            onClick = { showPatternDialog = true },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(16.dp)
                                        ) {
                                            Text(
                                                stringResource(
                                                    if (patternValid) {
                                                        R.string.password_app_unlock_change_pattern
                                                    } else {
                                                        R.string.password_app_unlock_create_pattern
                                                    }
                                                )
                                            )
                                        }
                                        if (patternValid) {
                                            Text(
                                                stringResource(
                                                    R.string.password_app_unlock_pattern_ready
                                                ),
                                                color = AccentCyan,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }

                                    PasswordAppUnlockMode.BIOMETRIC_ONLY -> {
                                        Text(
                                            stringResource(
                                                if (biometricAvailable) {
                                                    R.string.password_app_unlock_mode_biometric_only
                                                } else {
                                                    R.string.password_app_unlock_biometric_required
                                                }
                                            ),
                                            color = if (biometricAvailable) {
                                                AccentCyan
                                            } else {
                                                DangerRed
                                            },
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }

                                if (unlockMode != PasswordAppUnlockMode.BIOMETRIC_ONLY) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            stringResource(
                                                R.string.password_app_unlock_allow_biometric
                                            ),
                                            modifier = Modifier.weight(1f),
                                            color = if (biometricAvailable) {
                                                TextPrimary
                                            } else {
                                                TextHint
                                            },
                                            fontSize = 13.sp
                                        )
                                        Switch(
                                            checked = biometricEnabled && biometricAvailable,
                                            onCheckedChange = { enabled ->
                                                biometricEnabled = enabled && biometricAvailable
                                            },
                                            enabled = biometricAvailable
                                        )
                                    }
                                }
                            }

                            configError?.let { message ->
                                Text(message, color = DangerRed, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            Button(
                onClick = {
                    if (!hasPassword) {
                        masterPasswordLauncher.launch(
                            MasterPasswordActivity.createIntent(context)
                        )
                    } else if (!isSaving) {
                        val validationError = when (unlockMode) {
                            PasswordAppUnlockMode.PASSWORD -> when {
                                !PasswordAppUnlockStore.isPasswordValid(unlockPassword) ->
                                    context.getString(
                                        R.string.password_app_unlock_password_invalid,
                                        PasswordAppUnlockStore.MIN_PASSWORD_LENGTH
                                    )

                                unlockPassword != unlockPasswordConfirmation ->
                                    context.getString(
                                        R.string.password_app_unlock_password_mismatch
                                    )

                                else -> null
                            }

                            PasswordAppUnlockMode.PATTERN -> if (!patternValid) {
                                context.getString(
                                    R.string.password_app_unlock_pattern_too_short,
                                    PasswordAppUnlockStore.MIN_PATTERN_POINTS
                                )
                            } else null

                            PasswordAppUnlockMode.BIOMETRIC_ONLY -> if (!biometricAvailable) {
                                context.getString(R.string.password_app_unlock_biometric_required)
                            } else null
                        }
                        if (validationError != null) {
                            configError = validationError
                            securityOptionsExpanded = true
                            return@Button
                        }

                        val appCredential = when (unlockMode) {
                            PasswordAppUnlockMode.PASSWORD -> unlockPassword
                            PasswordAppUnlockMode.PATTERN -> patternCredential
                            PasswordAppUnlockMode.BIOMETRIC_ONLY -> null
                        }
                        val effectiveBiometricEnabled =
                            unlockMode == PasswordAppUnlockMode.BIOMETRIC_ONLY ||
                                (biometricEnabled && biometricAvailable)

                        isSaving = true
                        scope.launch {
                            try {
                                check(
                                    appUnlockStore.saveForPackages(
                                        packageNames = apps,
                                        mode = unlockMode,
                                        credential = appCredential,
                                        biometricEnabled = effectiveBiometricEnabled,
                                        hidePatternTrace = hidePatternTrace
                                    )
                                ) { "Não foi possível salvar o método de desbloqueio" }

                                try {
                                    sessionManager.startPasswordSession(
                                        isFixed24h = true,
                                        startHour = 0,
                                        endHour = 24,
                                        startMinute = 0,
                                        endMinute = 0,
                                        daysOfWeek = "",
                                        apps = apps,
                                        sites = sites
                                    )
                                } catch (error: Exception) {
                                    appUnlockStore.clearPackages(apps)
                                    throw error
                                }

                                isSaving = false
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.bloqueio_ativado_com_sucesso),
                                    Toast.LENGTH_LONG
                                ).show()
                                onFinish()
                            } catch (cancelled: CancellationException) {
                                isSaving = false
                                throw cancelled
                            } catch (error: Exception) {
                                isSaving = false
                                FocusGuardLogger.logError(
                                    "FinalConfig",
                                    "Falha ao ativar bloqueio por senha",
                                    error
                                )
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.erro_ao_iniciar_sessao),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                },
                enabled = !isSaving && apps.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    stringResource(R.string.final_config_activate_block),
                    color = DarkBg,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun UnlockModeRow(
    selected: Boolean,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Text(
            label,
            color = if (enabled) TextPrimary else TextHint,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun PatternSetupDialog(
    hideTrace: Boolean,
    onDismiss: () -> Unit,
    onPatternSet: (String) -> Unit
) {
    var firstPattern by remember { mutableStateOf<String?>(null) }
    var resetKey by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val tooShort = stringResource(
        R.string.password_app_unlock_pattern_too_short,
        PasswordAppUnlockStore.MIN_PATTERN_POINTS
    )
    val mismatch = stringResource(R.string.password_app_unlock_pattern_mismatch)
    val instruction = if (firstPattern == null) {
        stringResource(
            R.string.password_app_unlock_pattern_first,
            PasswordAppUnlockStore.MIN_PATTERN_POINTS
        )
    } else {
        stringResource(R.string.password_app_unlock_pattern_confirm)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.password_app_unlock_pattern_dialog_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    instruction,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))
                PatternLockInput(
                    modifier = Modifier.fillMaxWidth(),
                    hideTrace = hideTrace,
                    resetKey = resetKey,
                    onPatternComplete = { pattern ->
                        when {
                            !PasswordAppUnlockStore.isPatternValid(pattern) -> {
                                error = tooShort
                                resetKey++
                            }

                            firstPattern == null -> {
                                firstPattern = pattern
                                error = null
                                resetKey++
                            }

                            firstPattern == pattern -> onPatternSet(pattern)

                            else -> {
                                firstPattern = null
                                error = mismatch
                                resetKey++
                            }
                        }
                    }
                )
                error?.let {
                    Text(it, color = DangerRed, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
