package com.focusguard.ui.compose.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.admin.UnknownSourcesSecurityManager
import com.focusguard.ui.compose.layout.FocusGuardScreenScaffold
import com.focusguard.ui.compose.layout.FocusGuardScrollableContent
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.CardBorder
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.FocusCard

@Composable
fun ExtraSecurityScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val securityManager = remember(context.applicationContext) {
        UnknownSourcesSecurityManager.getInstance(context.applicationContext)
    }

    var blocked by remember { mutableStateOf(securityManager.isBlocked()) }
    var showPreActivationGuide by rememberSaveable { mutableStateOf(false) }
    var waitingForManualConfirmation by rememberSaveable { mutableStateOf(false) }

    fun showMessage(messageRes: Int) {
        Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_LONG).show()
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

    fun enableAfterManualConfirmation() {
        if (!securityManager.isDeviceOwnerActive()) {
            showMessage(R.string.extra_security_device_owner_required)
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
                                    if (!securityManager.isDeviceOwnerActive()) {
                                        showMessage(R.string.extra_security_device_owner_required)
                                    } else {
                                        showPreActivationGuide = true
                                    }
                                } else {
                                    disableBlock()
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    if (!securityManager.openUnknownSourcesSettings(context)) {
                                        showMessage(R.string.extra_security_settings_open_failed)
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.extra_security_open_settings_again))
                            }
                            Button(
                                modifier = Modifier.weight(1f),
                                onClick = ::enableAfterManualConfirmation
                            ) {
                                Text(stringResource(R.string.extra_security_confirm_revoked))
                            }
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
                        if (securityManager.openUnknownSourcesSettings(context)) {
                            waitingForManualConfirmation = true
                            showPreActivationGuide = false
                        } else {
                            showMessage(R.string.extra_security_settings_open_failed)
                        }
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
}
