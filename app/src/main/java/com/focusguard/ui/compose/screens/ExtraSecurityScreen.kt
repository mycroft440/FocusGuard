package com.focusguard.ui.compose.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.focusguard.utils.PermissionUtils
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.admin.UnknownSourcesSecurityManager
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.ui.compose.layout.FocusGuardScreenScaffold
import com.focusguard.ui.compose.layout.FocusGuardScrollableContent
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.CardBorder
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.FocusCard
import kotlinx.coroutines.launch

@Composable
fun ExtraSecurityScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val securityManager = remember(context.applicationContext) {
        UnknownSourcesSecurityManager.getInstance(context.applicationContext)
    }
    val blockingManager = remember(context.applicationContext) {
        BlockingSessionManager.getInstance(context.applicationContext)
    }
    val scope = rememberCoroutineScope()

    fun showMessage(messageRes: Int) {
        Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_LONG).show()
    }

    var blocked by remember { mutableStateOf(securityManager.isBlocked()) }
    var showPreActivationGuide by rememberSaveable { mutableStateOf(false) }
    var showDisableCredentialDialog by rememberSaveable { mutableStateOf(false) }
    // O fluxo precisa sobreviver à reconstrução da árvore de navegação quando o app
    // volta das Configurações. O estágio durável fica no UnknownSourcesSecurityManager.
    var awaitingReturn by rememberSaveable {
        mutableStateOf(
            securityManager.isActivationPending() && !securityManager.isReadyToEnable()
        )
    }
    var showReturnQuestion by rememberSaveable { mutableStateOf(false) }
    // O usuário confirmou que desativou tudo: o botão "Ativar bloqueio" aparece.
    var waitingForManualConfirmation by rememberSaveable {
        mutableStateOf(securityManager.isReadyToEnable())
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && awaitingReturn) {
                awaitingReturn = false
                showReturnQuestion = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun openUnknownSources() {
        if (!securityManager.markSettingsReviewStarted()) {
            showMessage(R.string.extra_security_policy_failed)
            return
        }

        awaitingReturn = true
        waitingForManualConfirmation = false
        if (!securityManager.openUnknownSourcesSettings(context)) {
            awaitingReturn = false
            securityManager.clearActivationFlow()
            showMessage(R.string.extra_security_settings_open_failed)
        }
    }

    fun disableBlock() {
        if (securityManager.setBlocked(false)) {
            blocked = false
            waitingForManualConfirmation = false
            showMessage(R.string.extra_security_unknown_sources_disabled)
        } else {
            blocked = securityManager.isBlocked()
            showMessage(R.string.extra_security_policy_failed)
        }
    }

    fun requestDisableBlock() {
        scope.launch {
            val overview = runCatching { blockingManager.getBlockOverview() }.getOrNull()
            if (overview == null) {
                showMessage(R.string.extra_security_policy_failed)
                return@launch
            }

            val hasNonPasswordBlock = overview.dailyLimitEntries.isNotEmpty() ||
                overview.scheduledTimeEntries.isNotEmpty() ||
                overview.dopamineFastEntries.isNotEmpty()

            if (hasNonPasswordBlock) {
                showDisableCredentialDialog = true
            } else {
                // Nenhum bloqueio sem saída por senha está ativo. Isso inclui o caso
                // em que existem somente bloqueios PASSWORD, que não prendem esta opção.
                disableBlock()
            }
        }
    }

    fun enableAfterManualConfirmation() {
        if (!PermissionUtils.isAccessibilityServiceEnabled(context)) {
            showMessage(R.string.extra_security_accessibility_required)
            return
        }

        if (securityManager.setBlocked(true)) {
            blocked = true
            waitingForManualConfirmation = false
            showMessage(R.string.extra_security_unknown_sources_enabled)
        } else {
            blocked = securityManager.isBlocked()
            showMessage(R.string.extra_security_policy_failed)
        }
    }

    FocusGuardScreenScaffold(
        title = stringResource(R.string.extra_security_title),
        onBack = onBack
    ) { paddingValues ->
        FocusGuardScrollableContent(paddingValues = paddingValues) {
            Text(
                text = stringResource(R.string.extra_security_intro),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )

            Spacer(Modifier.height(16.dp))

            FocusCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, CardBorder)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = AccentCyan
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(
                                    R.string.extra_security_unknown_sources_title
                                ),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(
                                    R.string.extra_security_unknown_sources_description
                                ),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = blocked,
                            onCheckedChange = { enable ->
                                if (enable) {
                                    showPreActivationGuide = true
                                } else {
                                    requestDisableBlock()
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = DarkBg,
                                checkedTrackColor = AccentCyan
                            )
                        )
                    }
                }
            }

            if (waitingForManualConfirmation && !blocked) {
                Spacer(Modifier.height(16.dp))
                FocusCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.7f))
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            text = stringResource(R.string.extra_security_confirmation_title),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.extra_security_confirmation_body),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = ::openUnknownSources
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.extra_security_open_settings_again))
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = ::enableAfterManualConfirmation
                        ) {
                            Text(stringResource(R.string.extra_security_confirm_revoked))
                        }
                    }
                }
            }
        }
    }

    if (showPreActivationGuide) {
        AlertDialog(
            onDismissRequest = { showPreActivationGuide = false },
            title = {
                Text(stringResource(R.string.extra_security_before_activation_title))
            },
            text = {
                Text(stringResource(R.string.extra_security_before_activation_body))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPreActivationGuide = false
                        openUnknownSources()
                    }
                ) {
                    Text(stringResource(R.string.extra_security_open_unknown_sources_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPreActivationGuide = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showDisableCredentialDialog) {
        ConfirmMasterCredentialDialog(
            promptRes = R.string.uninstall_app_subtitle,
            allowRecovery = false,
            onDismiss = { showDisableCredentialDialog = false },
            onConfirmed = {
                showDisableCredentialDialog = false
                disableBlock()
            }
        )
    }

    if (showReturnQuestion) {
        AlertDialog(
            onDismissRequest = { showReturnQuestion = false },
            title = { Text(stringResource(R.string.extra_security_confirmation_title)) },
            text = { Text(stringResource(R.string.extra_security_return_question)) },
            confirmButton = {
                TextButton(onClick = {
                    showReturnQuestion = false
                    if (securityManager.markReadyToEnable()) {
                        waitingForManualConfirmation = true
                    } else {
                        showMessage(R.string.extra_security_policy_failed)
                    }
                }) {
                    Text(stringResource(R.string.extra_security_return_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showReturnQuestion = false
                    openUnknownSources()
                }) {
                    Text(stringResource(R.string.extra_security_return_not_yet))
                }
            }
        )
    }
}
