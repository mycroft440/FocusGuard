package com.focusguard.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.focusguard.R
import com.focusguard.focusmode.FocusModeManager
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.security.DeactivationCredentialManager
import com.focusguard.security.PasswordAppUnlockStore
import com.focusguard.security.PasswordTargetAccessGrant
import com.focusguard.ui.compose.theme.FocusGuardTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one operational surface authorized by the master password.
 *
 * Target credentials open protected apps/sites. The master password does not
 * participate in those flows; it is accepted here only to erase all configured
 * blocking sources in one explicit Settings action.
 */
class RemoveAllBlocksActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FocusGuardTheme {
                val scope = rememberCoroutineScope()
                val credentialManager = remember {
                    DeactivationCredentialManager(applicationContext)
                }
                val blockingManager = remember {
                    BlockingSessionManager.getInstance(applicationContext)
                }
                val focusModeManager = remember {
                    FocusModeManager.getInstance(applicationContext)
                }
                val targetCredentialStore = remember {
                    PasswordAppUnlockStore(applicationContext)
                }

                var credential by remember { mutableStateOf("") }
                var error by remember { mutableStateOf<String?>(null) }
                var working by remember { mutableStateOf(false) }
                var configuredRevision by remember { mutableStateOf(0) }
                val masterConfigured = remember(configuredRevision) {
                    credentialManager.hasCredential()
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(stringResource(R.string.master_remove_all_blocks_title))
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.master_remove_all_blocks_prompt))
                    Spacer(Modifier.height(20.dp))

                    if (!masterConfigured) {
                        Text(stringResource(R.string.master_credential_not_configured))
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                startActivity(
                                    Intent(
                                        this@RemoveAllBlocksActivity,
                                        MasterPasswordActivity::class.java
                                    )
                                )
                            }
                        ) {
                            Text(stringResource(R.string.master_credential_create_action))
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { finish() }) {
                            Text(stringResource(R.string.cancel))
                        }
                    } else {
                        OutlinedTextField(
                            value = credential,
                            onValueChange = {
                                credential = it
                                error = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !working,
                            visualTransformation = PasswordVisualTransformation(),
                            label = { Text(stringResource(R.string.deactivation_password_title)) }
                        )
                        error?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it)
                        }
                        Spacer(Modifier.height(16.dp))
                        Button(
                            enabled = credential.isNotBlank() && !working,
                            onClick = {
                                if (working) return@Button
                                when (credentialManager.verify(credential)) {
                                    DeactivationCredentialManager.VerificationResult.PASSWORD_ACCEPTED,
                                    DeactivationCredentialManager.VerificationResult.RECOVERY_ACCEPTED -> {
                                        working = true
                                        error = null
                                        scope.launch {
                                            try {
                                                val removed = withContext(Dispatchers.IO) {
                                                    focusModeManager.forceStopForDevelopmentExit()
                                                    PasswordTargetAccessGrant.clear()
                                                    val blocksRemoved = blockingManager
                                                        .removeAllBlocksForDevelopmentExit()
                                                    if (blocksRemoved) {
                                                        targetCredentialStore.clearAll()
                                                        blockingManager.checkAndEnforce()
                                                    }
                                                    blocksRemoved
                                                }
                                                if (removed) {
                                                    Toast.makeText(
                                                        this@RemoveAllBlocksActivity,
                                                        getString(R.string.master_remove_all_blocks_success),
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                    finish()
                                                } else {
                                                    error = getString(
                                                        R.string.master_remove_all_blocks_failed
                                                    )
                                                }
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (_: Exception) {
                                                error = getString(
                                                    R.string.master_remove_all_blocks_failed
                                                )
                                            } finally {
                                                working = false
                                            }
                                        }
                                    }
                                    DeactivationCredentialManager.VerificationResult.REJECTED -> {
                                        error = getString(R.string.master_credential_wrong)
                                    }
                                    DeactivationCredentialManager.VerificationResult.NOT_CONFIGURED -> {
                                        configuredRevision++
                                        error = getString(
                                            R.string.master_credential_not_configured
                                        )
                                    }
                                }
                            }
                        ) {
                            if (working) {
                                CircularProgressIndicator(
                                    modifier = Modifier.height(18.dp)
                                )
                                Spacer(Modifier.height(4.dp))
                            }
                            Text(stringResource(R.string.master_remove_all_blocks_title))
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            enabled = !working,
                            onClick = { finish() }
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Recreate Compose state after returning from master-password setup so the
        // newly configured credential is visible immediately.
        if (hasWindowFocus()) window.decorView.invalidate()
    }
}
