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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.ui.compose.screens.DeactivationCredentialDialog
import com.focusguard.ui.compose.theme.FocusGuardTheme

/**
 * Destino único para criar ou alterar a senha mestra.
 *
 * Configurações, Proteger apps e Limites de uso abrem esta mesma Activity, de
 * modo que nunca existam formulários ou credenciais concorrentes.
 */
class MasterPasswordActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FocusGuardTheme {
                val blockingManager = remember {
                    BlockingSessionManager.getInstance(applicationContext)
                }
                val deviceOwnerManager = remember {
                    DeviceOwnerManager.getInstance(applicationContext)
                }
                val blockingActive by blockingManager.isBlockingActiveFlow.collectAsState(
                    initial = true
                )
                val managementLocked = blockingActive ||
                    deviceOwnerManager.isArmoredProtectionArmed()

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(androidx.compose.material3.MaterialTheme.colorScheme.background)
                ) {
                    DeactivationCredentialDialog(
                        managementLocked = managementLocked,
                        onDismiss = { finish() },
                        onCredentialChanged = {
                            setResult(Activity.RESULT_OK)
                        }
                    )
                }
            }
        }
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, MasterPasswordActivity::class.java)
    }
}
