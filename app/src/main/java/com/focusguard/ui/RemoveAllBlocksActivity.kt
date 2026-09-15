package com.focusguard.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
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
import com.focusguard.security.AppEntryAuthSession
import com.focusguard.security.DeactivationCredentialManager
import com.focusguard.security.PasswordAppUnlockStore
import com.focusguard.security.PasswordTargetAccessGrant
import com.focusguard.security.RemoveAllBlocksAuthorizationPolicy
import com.focusguard.ui.compose.theme.FocusGuardTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Operational surface that clears every configured blocking source.
 *
 * A TIME protection or daily usage limit requires the master credential unless
 * the current foreground visit already verified an active app-entry credential.
 * PASSWORD-only protection does not add another prompt here because app entry is
 * already guarded when that protection is active.
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
                var authorizationGate by remember {
                    mutableStateOf<RemoveAllBlocksAuthorizationPolicy.Gate?>(null)
                }
                var masterConfigured by remember {
                    mutableStateOf(credentialManager.hasCredential())
                }
                val masterSetupLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) {
                    masterConfigured = credentialManager.hasCredential()
                    credential = ""
                    error = null
                }

                suspend fun readAuthorizationGate(): RemoveAllBlocksAuthorizationPolicy.Gate =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val overview = blockingManager.getBlockOverview()
                            val generation = AppEntryAuthSession.foregroundGeneration.value
                            RemoveAllBlocksAuthorizationPolicy.evaluate(
                                hasActiveTimeProtection =
                                    overview.dopamineFastEntries.isNotEmpty(),
                                hasActiveUsageLimit = overview.dailyLimitEntries.isNotEmpty(),
                                appEntryCredentialAuthenticated =
                                    AppEntryAuthSession.isCredentialAuthenticated(generation)
                            )
                        }.getOrDefault(
                            RemoveAllBlocksAuthorizationPolicy.Gate.REQUIRE_MASTER_CREDENTIAL
                        )
                    }

                fun removeAllBlocks(masterAlreadyVerified: Boolean) {
                    if (working) return
                    working = true
                    error = null
                    scope.launch {
                        try {
                            if (!masterAlreadyVerified) {
                                val latestGate = readAuthorizationGate()
                                authorizationGate = latestGate
                                if (
                                    latestGate ==
                                    RemoveAllBlocksAuthorizationPolicy.Gate.REQUIRE_MASTER_CREDENTIAL
                                ) {
                                    credential = ""
                                    return@launch
                                }
                            }

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
                                error = getString(R.string.master_remove_all_blocks_failed)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            error = getString(R.string.master_remove_all_blocks_failed)
                        } finally {
                            working = false
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    authorizationGate = readAuthorizationGate()
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

                    when (authorizationGate) {
                        null -> {
                            CircularProgressIndicator()
                        }

                        RemoveAllBlocksAuthorizationPolicy.Gate.ALLOW -> {
                            Text(stringResource(R.string.master_remove_all_blocks_subtitle))
                            Spacer(Modifier.height(20.dp))
                            Button(
                                enabled = !working,
                                onClick = { removeAllBlocks(masterAlreadyVerified = false) }
                            ) {
                                if (working) {
                                    CircularProgressIndicator(modifier = Modifier.height(18.dp))
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

                        RemoveAllBlocksAuthorizationPolicy.Gate.REQUIRE_MASTER_CREDENTIAL -> {
                            Text(stringResource(R.string.master_remove_all_blocks_prompt))
                            Spacer(Modifier.height(20.dp))

                            if (!masterConfigured) {
                                Text(stringResource(R.string.master_credential_not_configured))
                                Spacer(Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        masterSetupLauncher.launch(
                                            MasterPasswordActivity.createIntent(
                                                this@RemoveAllBlocksActivity
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
                                    label = {
                                        Text(
                                            stringResource(
                                                R.string.master_password_settings_title
                                            )
                                        )
                                    }
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
                                            DeactivationCredentialManager.VerificationResult
                                                .PASSWORD_ACCEPTED,
                                            DeactivationCredentialManager.VerificationResult
                                                .RECOVERY_ACCEPTED -> {
                                                credential = ""
                                                removeAllBlocks(masterAlreadyVerified = true)
                                            }

                                            DeactivationCredentialManager.VerificationResult
                                                .REJECTED -> {
                                                error = getString(
                                                    R.string.master_credential_wrong
                                                )
                                            }

                                            DeactivationCredentialManager.VerificationResult
                                                .NOT_CONFIGURED -> {
                                                masterConfigured = false
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
                                    Text(
                                        stringResource(R.string.master_remove_all_blocks_title)
                                    )
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

                    error?.takeIf {
                        authorizationGate == RemoveAllBlocksAuthorizationPolicy.Gate.ALLOW
                    }?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it)
                    }
                }
            }
        }
    }
}
