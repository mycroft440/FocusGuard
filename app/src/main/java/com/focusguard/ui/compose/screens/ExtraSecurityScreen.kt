package com.focusguard.ui.compose.screens

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.focusguard.R
import com.focusguard.admin.UnknownSourcesProtection
import com.focusguard.security.DeactivationCredentialManager
import com.focusguard.ui.compose.layout.FocusGuardScreenScaffold
import com.focusguard.ui.compose.layout.FocusGuardScrollableContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ExtraSecurityScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val protection = remember(context) { UnknownSourcesProtection(context) }
    val coroutineScope = rememberCoroutineScope()
    var status by remember { mutableStateOf(protection.inspect()) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    var showSetup by remember { mutableStateOf(false) }
    var confirmDisable by remember { mutableStateOf(false) }
    var confirmCredential by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, protection) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) status = protection.inspect()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    BackHandler { if (!working) onBack() }

    fun changeProtection(enabled: Boolean) {
        if (working) return
        working = true
        message = null
        coroutineScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { protection.setEnabled(enabled) }
                status = protection.inspect()
                message = when (result) {
                    UnknownSourcesProtection.Result.APPLIED -> if (enabled) {
                        R.string.extra_security_enabled
                    } else {
                        R.string.extra_security_disabled
                    }
                    UnknownSourcesProtection.Result.OWNER_REQUIRED -> R.string.extra_security_owner_required
                    UnknownSourcesProtection.Result.FAILED -> R.string.extra_security_failed
                }
            } finally {
                working = false
            }
        }
    }

    FocusGuardScreenScaffold(
        title = stringResource(R.string.extra_security_title),
        onBack = { if (!working) onBack() }
    ) { padding ->
        FocusGuardScrollableContent(paddingValues = padding) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.extra_security_unknown_sources), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.extra_security_explanation))
                Text(
                    stringResource(when {
                        !status.verified -> R.string.extra_security_failed
                        !status.ownerActive -> R.string.extra_security_owner_required
                        status.scope == UnknownSourcesProtection.Scope.ALL_USERS -> R.string.extra_security_scope_device
                        status.scope == UnknownSourcesProtection.Scope.CURRENT_USER -> R.string.extra_security_scope_user
                        else -> R.string.extra_security_disabled
                    }),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(stringResource(R.string.extra_security_review_hint))
                if (!status.enabled) {
                    OutlinedButton(
                        enabled = !working,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            // No package URI: open the list of source apps, not FocusGuard details.
                            val opened = runCatching {
                                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
                            }.isSuccess
                            if (!opened) message = R.string.extra_security_settings_unavailable
                        }
                    ) { Text(stringResource(R.string.extra_security_review)) }
                }
                if (status.ownerActive && status.verified) {
                    Button(
                        enabled = !working,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (status.enabled) {
                                if (DeactivationCredentialManager(context).hasCredential()) {
                                    confirmCredential = true
                                } else {
                                    confirmDisable = true
                                }
                            } else {
                                changeProtection(true)
                            }
                        }
                    ) {
                        Text(stringResource(if (status.enabled) R.string.extra_security_disable else R.string.extra_security_enable))
                    }
                } else if (status.verified) {
                    Button(onClick = { showSetup = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.device_owner_setup_title))
                    }
                }
                if (!status.verified) {
                    OutlinedButton(onClick = { status = protection.inspect() }, enabled = !working) {
                        Text(stringResource(R.string.dashboard_try_again))
                    }
                }
                message?.let { Text(stringResource(it)) }
            }
        }
    }
    if (showSetup) {
        DeviceOwnerSetupGuideDialog(
            onDismiss = { showSetup = false; status = protection.inspect() },
            onStateChanged = { status = protection.inspect() }
        )
    }
    if (confirmDisable) {
        AlertDialog(
            onDismissRequest = { confirmDisable = false },
            title = { Text(stringResource(R.string.extra_security_disable)) },
            text = { Text(stringResource(R.string.extra_security_disable_warning)) },
            confirmButton = {
                TextButton(onClick = { confirmDisable = false; changeProtection(false) }) {
                    Text(stringResource(R.string.extra_security_disable))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisable = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
    if (confirmCredential) {
        ConfirmMasterCredentialDialog(
            promptRes = R.string.extra_security_disable_warning,
            onDismiss = { confirmCredential = false },
            onConfirmed = { confirmCredential = false; changeProtection(false) }
        )
    }
}
