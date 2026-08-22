package com.focusguard.ui.compose.screens

import kotlin.OptIn
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.manager.BlockingSessionManager.BlockingProtectionUnavailableException
import com.focusguard.security.BlockDurationPolicy
import com.focusguard.security.DeactivationCredentialManager
import com.focusguard.security.TimedBlockProtectionController
import com.focusguard.ui.PermissionsActivity
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.DarkCard
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary
import com.focusguard.utils.FocusGuardLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TimeBlockSessionConfigScreen(
    appName: String,
    apps: List<String>,
    sites: List<String>,
    onBack: () -> Unit,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessionManager = remember(context) { BlockingSessionManager.getInstance(context) }
    val deviceOwnerManager = remember(context) { DeviceOwnerManager.getInstance(context) }
    val credentialManager = remember(context) { DeactivationCredentialManager(context) }
    val timedProtection = remember(context) {
        TimedBlockProtectionController.getInstance(context)
    }

    val availableUnits = remember(apps, sites) {
        BlockDurationPolicy.availableUnits(rules = sites, hasApps = apps.isNotEmpty())
    }
    var durationUnit by remember { mutableStateOf(BlockDurationPolicy.Unit.DAYS) }
    if (durationUnit !in availableUnits) {
        durationUnit = BlockDurationPolicy.Unit.DAYS
    }
    var amountText by remember { mutableStateOf("") }
    var termsAccepted by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var pendingProtectionReason by remember {
        mutableStateOf<BlockingProtectionUnavailableException.Reason?>(null)
    }
    var showMasterCredentialSetup by remember { mutableStateOf(false) }
    var showDeviceOwnerRequired by remember { mutableStateOf(false) }

    val duration = BlockDurationPolicy.resolve(durationUnit, amountText.toIntOrNull())
    val canSave = duration != null &&
        termsAccepted &&
        (apps.isNotEmpty() || sites.isNotEmpty())
    val protectionHealth = timedProtection.healthSnapshot()
    val masterCredentialReady = credentialManager.hasCredential()

    if (showMasterCredentialSetup) {
        DeactivationCredentialDialog(
            managementLocked = false,
            onDismiss = { showMasterCredentialSetup = false },
            onCredentialChanged = { showMasterCredentialSetup = false }
        )
    }

    if (showDeviceOwnerRequired) {
        AlertDialog(
            onDismissRequest = { showDeviceOwnerRequired = false },
            title = { Text(stringResource(R.string.time_block_device_owner_required_title)) },
            text = { Text(stringResource(R.string.time_block_device_owner_required_desc)) },
            confirmButton = {
                TextButton(onClick = { showDeviceOwnerRequired = false }) {
                    Text(stringResource(R.string.status_close))
                }
            }
        )
    }

    pendingProtectionReason?.let { reason ->
        val message = when (reason) {
            BlockingProtectionUnavailableException.Reason.PROTECTION_PERMISSIONS_REQUIRED -> {
                R.string.blocking_permissions_required_desc
            }
            BlockingProtectionUnavailableException.Reason.MASTER_CREDENTIAL_REQUIRED -> {
                R.string.master_credential_required_to_block
            }
        }
        val confirmLabel = when (reason) {
            BlockingProtectionUnavailableException.Reason.PROTECTION_PERMISSIONS_REQUIRED -> {
                R.string.dopamine_open_permissions
            }
            BlockingProtectionUnavailableException.Reason.MASTER_CREDENTIAL_REQUIRED -> {
                R.string.master_credential_create_action
            }
        }

        AlertDialog(
            onDismissRequest = { pendingProtectionReason = null },
            title = { Text(stringResource(R.string.dopamine_requirement_title)) },
            text = { Text(stringResource(message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingProtectionReason = null
                        when (reason) {
                            BlockingProtectionUnavailableException
                                .Reason.PROTECTION_PERMISSIONS_REQUIRED ->
                                context.startActivity(
                                    PermissionsActivity.createPendingProtectionIntent(context)
                                )
                            BlockingProtectionUnavailableException
                                .Reason.MASTER_CREDENTIAL_REQUIRED ->
                                showMasterCredentialSetup = true
                        }
                    }
                ) {
                    Text(stringResource(confirmLabel))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingProtectionReason = null }) {
                    Text(stringResource(R.string.status_close))
                }
            }
        )
    }

    Scaffold(
        containerColor = DarkBg,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dopamine_title), color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBg)
                .padding(innerPadding)
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Text(
                text = stringResource(R.string.dopamine_configure_for, appName),
                color = TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.dopamine_description),
                color = TextSecondary,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                color = AccentCyan.copy(alpha = 0.10f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(
                        text = stringResource(R.string.time_block_protection_title),
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.time_block_protection_warning),
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.time_block_health_title),
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                    Text(
                        text = stringResource(
                            if (protectionHealth.deviceOwnerReady) {
                                R.string.time_block_health_device_owner_ready
                            } else {
                                R.string.time_block_health_device_owner_missing
                            }
                        ),
                        color = if (protectionHealth.deviceOwnerReady) AccentCyan else DangerRed,
                        fontSize = 12.sp
                    )
                    Text(
                        text = stringResource(
                            if (masterCredentialReady) {
                                R.string.time_block_health_master_ready
                            } else {
                                R.string.time_block_health_master_missing
                            }
                        ),
                        color = if (masterCredentialReady) AccentCyan else DangerRed,
                        fontSize = 12.sp
                    )
                    Text(
                        text = stringResource(
                            if (protectionHealth.protectedSessionCount > 0 &&
                                protectionHealth.systemProtectionConfirmed
                            ) {
                                R.string.time_block_health_system_active
                            } else {
                                R.string.time_block_health_system_standby
                            }
                        ),
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = stringResource(R.string.dopamine_duration_question),
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    BlockDurationPicker(
                        unit = durationUnit,
                        amountText = amountText,
                        onUnitChange = { durationUnit = it },
                        onAmountChange = { amountText = it },
                        accent = DangerRed,
                        units = availableUnits
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(
                        color = DangerRed.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.time_block_protection_warning),
                            color = DangerRed,
                            modifier = Modifier.padding(12.dp),
                            textAlign = TextAlign.Start
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = stringResource(R.string.dopamine_terms_title),
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.time_block_revoke_desc),
                        color = TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                    Text(
                        text = stringResource(R.string.dopamine_terms_question),
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { termsAccepted = !termsAccepted },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = termsAccepted,
                            onCheckedChange = { termsAccepted = it },
                            colors = CheckboxDefaults.colors(checkedColor = DangerRed)
                        )
                        Spacer(modifier = Modifier.padding(horizontal = 2.dp))
                        Text(
                            text = stringResource(R.string.dopamine_terms_accept),
                            color = TextPrimary,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    if (isSaving) return@Button
                    if (!credentialManager.hasCredential()) {
                        pendingProtectionReason =
                            BlockingProtectionUnavailableException.Reason.MASTER_CREDENTIAL_REQUIRED
                        return@Button
                    }
                    if (!deviceOwnerManager.isDeviceOwnerActive()) {
                        showDeviceOwnerRequired = true
                        return@Button
                    }

                    isSaving = true
                    scope.launch {
                        var createdSessionId: Int? = null
                        try {
                            val resolved = duration ?: return@launch
                            check(timedProtection.prepareForTimeCreation()) {
                                "Android não confirmou a proteção de desinstalação"
                            }
                            val sessionId = sessionManager.startTimeSession(
                                days = 0,
                                hours = when (resolved) {
                                    is BlockDurationPolicy.Duration.Finite -> resolved.totalHours
                                    BlockDurationPolicy.Duration.Forever -> 0
                                },
                                openEnded = resolved is BlockDurationPolicy.Duration.Forever,
                                isFixed24h = true,
                                startHour = 0,
                                endHour = 24,
                                startMinute = 0,
                                endMinute = 0,
                                daysOfWeek = "",
                                apps = apps,
                                sites = sites
                            )
                            createdSessionId = sessionId
                            check(timedProtection.commitProtectedTimeSession(sessionId)) {
                                "Não foi possível vincular a proteção à sessão por tempo"
                            }
                            Toast.makeText(
                                context,
                                context.getString(R.string.bloqueio_por_tempo_ativado),
                                Toast.LENGTH_LONG
                            ).show()
                            onFinish()
                        } catch (cancelled: CancellationException) {
                            val createdId = createdSessionId
                            if (createdId != null) {
                                timedProtection.rollbackProtectedTimeSession(createdId)
                                sessionManager.checkAndEnforce()
                            } else {
                                timedProtection.cancelPendingCreation()
                            }
                            throw cancelled
                        } catch (
                            error: BlockingProtectionUnavailableException
                        ) {
                            val createdId = createdSessionId
                            if (createdId != null) {
                                timedProtection.rollbackProtectedTimeSession(createdId)
                                sessionManager.checkAndEnforce()
                            } else {
                                timedProtection.cancelPendingCreation()
                            }
                            pendingProtectionReason = error.reason
                        } catch (error: Exception) {
                            val createdId = createdSessionId
                            if (createdId != null) {
                                timedProtection.rollbackProtectedTimeSession(createdId)
                                sessionManager.checkAndEnforce()
                            } else {
                                timedProtection.cancelPendingCreation()
                            }
                            FocusGuardLogger.logError(
                                "TimeBlockConfig",
                                "Falha ao ativar bloqueio por tempo protegido",
                                error
                            )
                            Toast.makeText(
                                context,
                                context.getString(R.string.erro_ao_iniciar_sessao),
                                Toast.LENGTH_LONG
                            ).show()
                        } finally {
                            isSaving = false
                        }
                    }
                },
                enabled = canSave && !isSaving,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    stringResource(R.string.dopamine_activate),
                    color = DarkBg,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.common_back), color = TextSecondary)
            }
        }
    }
}
