package com.focusguard.ui.compose.screens

import kotlin.OptIn
import androidx.compose.foundation.clickable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Alignment
import com.focusguard.security.BlockDurationPolicy
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.focusguard.R
import android.widget.Toast
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.manager.BlockingSessionManager.BlockingProtectionUnavailableException
import com.focusguard.security.DeactivationCredentialManager
import com.focusguard.ui.PermissionsActivity
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
fun TimeBlockSessionConfigScreen(
    sessionManager: BlockingSessionManager,
    credentialManager: DeactivationCredentialManager,
    appName: String,
    apps: List<String>,
    sites: List<String>,
    onBack: () -> Unit,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // "Para sempre" só é ofertado quando o alvo é exclusivamente pornografia:
    // é o único bloqueio sem volta que o usuário pede querendo não voltar.
    val availableUnits = remember(apps, sites) {
        BlockDurationPolicy.availableUnits(rules = sites, hasApps = apps.isNotEmpty())
    }
    var durationUnit by rememberSaveable { mutableStateOf(BlockDurationPolicy.Unit.DAYS) }
    // Se o alvo mudar e a unidade escolhida deixar de existir, cai para dias em
    // vez de continuar armando um prazo que a tela não oferece mais.
    if (durationUnit !in availableUnits) {
        durationUnit = BlockDurationPolicy.Unit.DAYS
    }
    var amountText by rememberSaveable { mutableStateOf("") }
    var termsAccepted by rememberSaveable { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var pendingProtectionReason by remember {
        mutableStateOf<BlockingProtectionUnavailableException.Reason?>(null)
    }
    var showMasterCredentialSetup by rememberSaveable { mutableStateOf(false) }

    val duration = BlockDurationPolicy.resolve(durationUnit, amountText.toIntOrNull())
    // O aceite e obrigatorio: sem senha mestre, o consentimento informado e a
    // unica coisa que autoriza armar um bloqueio irrevogavel.
    val canSave = duration != null &&
        termsAccepted &&
        (apps.isNotEmpty() || sites.isNotEmpty())

    if (showMasterCredentialSetup) {
        // Configurar a senha mestre aqui mesmo: o usuário está tentando armar um
        // bloqueio, mandá-lo navegar até Configurações perderia o contexto.
        DeactivationCredentialDialog(
            manager = credentialManager,
            managementLocked = false,
            onDismiss = { showMasterCredentialSetup = false },
            onCredentialChanged = { showMasterCredentialSetup = false }
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = TextPrimary)
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
                Text(
                    text = stringResource(R.string.dopamine_simple_mode_info),
                    color = TextSecondary,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    fontSize = 13.sp
                )
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
                            text = if (durationUnit == BlockDurationPolicy.Unit.FOREVER) {
                                stringResource(R.string.dopamine_duration_forever_warning)
                            } else {
                                stringResource(R.string.dopamine_warning)
                            },
                            color = DangerRed,
                            modifier = Modifier.padding(12.dp),
                            textAlign = TextAlign.Start
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Termo de aceite: o jejum nao pede senha mestre, entao o que autoriza
            // arma-lo e o consentimento informado. O texto explica o mecanismo e a
            // janela do dia 15 antes de o usuario poder marcar a caixa.
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
                    listOf(
                        R.string.dopamine_terms_intro,
                        R.string.dopamine_terms_how,
                        R.string.dopamine_terms_escape
                    ).forEach { paragraph ->
                        Text(
                            text = stringResource(paragraph),
                            color = TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                    }
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
                    isSaving = true
                    scope.launch {
                        try {
                            val resolved = duration ?: return@launch
                            sessionManager.startTimeSession(
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
                            isSaving = false
                            Toast.makeText(
                                context,
                                context.getString(R.string.bloqueio_por_tempo_ativado),
                                Toast.LENGTH_LONG
                            ).show()
                            onFinish()
                        } catch (cancelled: CancellationException) {
                            isSaving = false
                            throw cancelled
                        } catch (
                            error: BlockingProtectionUnavailableException
                        ) {
                            isSaving = false
                            pendingProtectionReason = error.reason
                        } catch (error: Exception) {
                            isSaving = false
                            FocusGuardLogger.logError(
                                "TimeBlockConfig",
                                "Falha ao ativar bloqueio por tempo",
                                error
                            )
                            Toast.makeText(
                                context,
                                context.getString(R.string.erro_ao_iniciar_sessao),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                },
                enabled = canSave && !isSaving,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(stringResource(R.string.dopamine_activate), color = DarkBg, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(10.dp))

            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.common_back), color = TextSecondary)
            }
        }
    }
}
