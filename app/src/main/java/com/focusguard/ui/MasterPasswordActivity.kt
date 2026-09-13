package com.focusguard.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.focusguard.security.MasterCredentialConfigurationManager
import com.focusguard.security.MasterCredentialPolicy
import com.focusguard.ui.compose.screens.DeactivationCredentialDialog
import com.focusguard.ui.compose.theme.FocusGuardTheme

/**
 * Creates or changes the master password used by "Remove all blocks".
 *
 * It is deliberately independent from target credentials. Creating an app/site
 * PASSWORD block never opens this Activity, and this credential is never offered
 * on a blocked-target screen. TIME blocks and enabled usage limits, however,
 * close this configuration path until they are no longer active.
 */
class MasterPasswordActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val configurationManager = remember {
                MasterCredentialConfigurationManager(this@MasterPasswordActivity)
            }
            var configurationGate by remember {
                mutableStateOf<MasterCredentialPolicy.ConfigurationGate?>(null)
            }

            LaunchedEffect(configurationManager) {
                configurationGate = configurationManager.getConfigurationGate()
            }

            FocusGuardTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(androidx.compose.material3.MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center
                ) {
                    val gate = configurationGate
                    if (gate == null) {
                        CircularProgressIndicator()
                    } else {
                        DeactivationCredentialDialog(
                            managementLocked = gate != MasterCredentialPolicy.ConfigurationGate.ALLOWED,
                            configureCredential = configurationManager::configure,
                            onDismiss = { finish() },
                            onCredentialChanged = {
                                setResult(Activity.RESULT_OK)
                            }
                        )
                    }
                }
            }
        }
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, MasterPasswordActivity::class.java)
    }
}
